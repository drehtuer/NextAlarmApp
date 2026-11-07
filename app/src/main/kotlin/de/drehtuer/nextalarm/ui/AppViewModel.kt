package de.drehtuer.nextalarm.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.drehtuer.nextalarm.data.SoundFiles
import de.drehtuer.nextalarm.data.SoundImportResult
import de.drehtuer.nextalarm.domain.AlarmEngine
import de.drehtuer.nextalarm.domain.AlarmRepository
import de.drehtuer.nextalarm.domain.AlarmSettingsModel
import de.drehtuer.nextalarm.domain.CalendarId
import de.drehtuer.nextalarm.domain.CalendarRepository
import de.drehtuer.nextalarm.domain.DeviceCalendar
import de.drehtuer.nextalarm.domain.DiagnosticsModel
import de.drehtuer.nextalarm.domain.HomeModel
import de.drehtuer.nextalarm.domain.LOG_FAILED_NOTE
import de.drehtuer.nextalarm.domain.LOG_SAVED_NOTE
import de.drehtuer.nextalarm.domain.NewsDraft
import de.drehtuer.nextalarm.domain.NewsModel
import de.drehtuer.nextalarm.domain.PlannerEngine
import de.drehtuer.nextalarm.domain.PlannerRecord
import de.drehtuer.nextalarm.domain.PlannerRepository
import de.drehtuer.nextalarm.domain.PlannerState
import de.drehtuer.nextalarm.domain.Settings
import de.drehtuer.nextalarm.domain.SettingsRepository
import de.drehtuer.nextalarm.domain.SoundRef
import de.drehtuer.nextalarm.domain.TEST_ALARM_DELAY
import de.drehtuer.nextalarm.domain.buildAlarmSettings
import de.drehtuer.nextalarm.domain.buildDiagnostics
import de.drehtuer.nextalarm.domain.buildHome
import de.drehtuer.nextalarm.domain.buildLogExport
import de.drehtuer.nextalarm.domain.buildNews
import de.drehtuer.nextalarm.domain.calendarFact
import de.drehtuer.nextalarm.domain.calendarSummary
import de.drehtuer.nextalarm.domain.diagnosticsSummary
import de.drehtuer.nextalarm.domain.soundRejectionText
import de.drehtuer.nextalarm.domain.testAlarmEntry
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What the screens need from the rest of the app. */
class AppPorts(
    val settings: SettingsRepository,
    val planner: PlannerRepository,
    val alarms: AlarmRepository,
    val calendars: CalendarRepository,
    val engine: PlannerEngine,
    /** Rings the test alarm. */
    val alarmEngine: AlarmEngine,
    /** Emits whenever a planning job changes state, so that home shows its result. */
    val planningChanges: Flow<*>,
    val phone: PhonePorts,
    val news: NewsPorts,
    /** For the calls below that would block the screen. */
    val io: CoroutineDispatcher,
    val now: () -> Instant = Instant::now,
    val zone: () -> ZoneId = ZoneId::systemDefault,
)

/** The parts of the phone the settings and diagnostics screens report on or write to. */
class PhonePorts(
    val audio: PhoneAudio,
    val sounds: SoundFiles,
    val logs: LogDocuments,
    val notificationsAllowed: () -> Boolean = { true },
    val fullScreenAllowed: () -> Boolean,
)

data class AppState(
    /** False until the first load, so that nothing wrong flashes up. */
    val loaded: Boolean = false,
    val setupDone: Boolean = false,
    val home: HomeModel = HomeModel(null, emptyList(), null, null, null),
    val calendars: List<DeviceCalendar> = emptyList(),
    val selectedCalendar: CalendarId? = null,
    val alarmSettings: AlarmSettingsModel = buildAlarmSettings(Settings.DEFAULT, null, null, null),
    val news: NewsModel = buildNews(NewsDraft(), null, Instant.EPOCH, ZoneOffset.UTC),
    val diagnosticsSummary: String = diagnosticsSummary(PlannerRecord.INITIAL, ZoneOffset.UTC),
    val diagnostics: DiagnosticsModel = buildDiagnostics(
        PlannerRecord.INITIAL,
        emptyList(),
        Instant.EPOCH,
        ZoneOffset.UTC,
        calendarSummary(null),
        false,
    ),
    /** True from asking for a test alarm until it rings. */
    val testAlarmPending: Boolean = false,
    /** How the last log export went. */
    val logNote: String? = null,
)

/** What screen 5 can ask for. */
interface AlarmSettingsActions {
    fun importSound(uri: Uri)
    fun useDefaultSound()
    fun setVolumeRamp(seconds: Int)
    fun setMaxVolume(volume: Float?)
    fun setVibrate(vibrate: Boolean)
    fun setRingFor(minutes: Int)
    fun setSnoozeLength(minutes: Int)
    fun setSpeakTitle(speak: Boolean)
}

/** What screen 7 can ask for. */
interface DiagnosticsActions {
    fun ringTestAlarm()
    fun exportLog(uri: Uri)
}

/** What screen 6 can ask for, and what leaving it means. */
interface NewsActions {
    fun openNews()
    fun closeNews()
    fun setNewsAddress(address: String)
    fun shortenNews()
    fun lengthenNews()
    fun toggleNewsTest()
    fun stopNewsTest()
    fun turnOffNews()
}

interface AppActions : AlarmSettingsActions, NewsActions, DiagnosticsActions

@HiltViewModel
class AppViewModel @Inject constructor(private val ports: AppPorts) : ViewModel(), AppActions {
    private val mutableState = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = mutableState.asStateFlow()

    private var watching: List<Job> = emptyList()

    /** One refresh at a time, so that an older one that takes longer cannot overwrite a newer one. */
    private val refreshLock = Mutex()

    /** One change at a time, so that two quick changes cannot each load the settings and lose the other. */
    private val settingsLock = Mutex()

    /** Why the last picked file was not taken. It stays until the user picks again or gives up on the file. */
    private var soundRejection: String? = null

    private val news = NewsController(
        viewModelScope,
        ports.news,
        ports.settings,
        ports.now,
        ports.zone,
        store = { source -> changeSettings { it.copy(news = source) } },
        publish = { model -> mutableState.update { it.copy(news = model) } },
    )

    /** While the screen is visible: keeps relative times current, and picks up finished planning runs. */
    fun startWatching() {
        stopWatching()
        watching = listOf(
            viewModelScope.launch {
                while (true) {
                    refresh()
                    delay(REFRESH_MILLIS)
                }
            },
            viewModelScope.launch { ports.planningChanges.collect { refresh() } },
        )
    }

    fun stopWatching() {
        watching.forEach { it.cancel() }
        watching = emptyList()
    }

    private suspend fun refresh() = refreshLock.withLock {
        val settings = ports.settings.load()
        val record: PlannerRecord = ports.planner.load()
        val calendars = readCalendars()
        val alarms = ports.alarms.all()
        val selected = settings.alarmCalendar
        val calendar = calendars.find { it.id == selected }
        val now = ports.now()
        val zone = ports.zone()
        val phone = ports.phone
        val volume = withContext(ports.io) { phone.audio.alarmVolume() }
        val soundName = withContext(ports.io) { phone.audio.defaultSoundName() }
        val notificationsAllowed = phone.notificationsAllowed()
        mutableState.update {
            it.copy(
                loaded = true,
                setupDone = record.state != PlannerState.Unconfigured,
                home = buildHome(now, zone, alarms, record, calendar, notificationsAllowed),
                calendars = calendars,
                selectedCalendar = selected,
                alarmSettings = buildAlarmSettings(settings, soundName, volume, soundRejection),
                diagnosticsSummary = diagnosticsSummary(record, zone),
                diagnostics = buildDiagnostics(
                    record,
                    alarms,
                    now,
                    zone,
                    calendarFact(selected, calendar, record.lastRun?.failure),
                    phone.fullScreenAllowed(),
                    notificationsAllowed,
                ),
            )
        }
    }

    /** Choosing a calendar counts at once. Before setup is finished, the planner ignores the request. */
    fun chooseCalendar(calendar: CalendarId) = changeSettings { it.copy(alarmCalendar = calendar) }

    fun completeSetup() {
        viewModelScope.launch {
            ports.engine.setupCompleted()
            refresh()
        }
    }

    override fun importSound(uri: Uri) {
        viewModelScope.launch {
            when (val result = ports.phone.sounds.import(uri)) {
                is SoundImportResult.Imported -> replaceSound(result.sound)
                is SoundImportResult.Rejected -> {
                    soundRejection = soundRejectionText(result.reason, result.fileName)
                    refresh()
                }
            }
        }
    }

    override fun useDefaultSound() {
        viewModelScope.launch { replaceSound(SoundRef.SystemDefault) }
    }

    override fun setVolumeRamp(seconds: Int) = changeSettings { it.copy(volumeRampSeconds = seconds) }

    override fun setMaxVolume(volume: Float?) = changeSettings { it.copy(maxVolume = volume) }

    override fun setVibrate(vibrate: Boolean) = changeSettings { it.copy(vibrate = vibrate) }

    override fun setRingFor(minutes: Int) = changeSettings { it.copy(ringTimeoutMinutes = minutes) }

    override fun setSnoozeLength(minutes: Int) = changeSettings { it.copy(snoozeMinutes = minutes) }

    override fun setSpeakTitle(speak: Boolean) = changeSettings { it.copy(speakReason = speak) }

    override fun openNews() = news.open()

    override fun closeNews() = news.close()

    override fun setNewsAddress(address: String) = news.setAddress(address)

    override fun shortenNews() = news.shorten()

    override fun lengthenNews() = news.lengthen()

    override fun toggleNewsTest() = news.toggleTest()

    override fun stopNewsTest() = news.stopTest()

    override fun turnOffNews() = news.turnOff()

    /** A test alarm goes the normal way. Asking again while one is on its way does not add another. */
    override fun ringTestAlarm() {
        if (mutableState.value.testAlarmPending) return
        mutableState.update { it.copy(testAlarmPending = true) }
        viewModelScope.launch {
            ports.alarmEngine.create(testAlarmEntry(ports.now(), ports.settings.load()))
            refresh()
            delay(TEST_ALARM_DELAY.toMillis())
            refresh()
            mutableState.update { it.copy(testAlarmPending = false) }
        }
    }

    override fun exportLog(uri: Uri) {
        viewModelScope.launch {
            val text = buildLogExport(ports.now(), ports.zone(), ports.planner.load(), ports.alarms.all())
            val saved = withContext(ports.io) { ports.phone.logs.write(uri, text) }
            mutableState.update { it.copy(logNote = if (saved) LOG_SAVED_NOTE else LOG_FAILED_NOTE) }
        }
    }

    private fun changeSettings(change: (Settings) -> Settings) {
        viewModelScope.launch {
            settingsLock.withLock { ports.settings.save(change(ports.settings.load())) }
            ports.engine.replanRequested()
            refresh()
        }
    }

    /** The settings are saved first, so that they never name a copy that has been deleted. */
    private suspend fun replaceSound(sound: SoundRef) {
        soundRejection = null
        val old = settingsLock.withLock {
            val current = ports.settings.load()
            ports.settings.save(current.copy(sound = sound))
            current.sound
        }
        withContext(ports.io) { ports.phone.sounds.delete(old) }
        ports.engine.replanRequested()
        refresh()
    }

    /** Without the calendar permission there is simply nothing to list yet. */
    private suspend fun readCalendars(): List<DeviceCalendar> = try {
        ports.calendars.calendars()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        emptyList()
    }

    private companion object {
        const val REFRESH_MILLIS = 60_000L
    }
}
