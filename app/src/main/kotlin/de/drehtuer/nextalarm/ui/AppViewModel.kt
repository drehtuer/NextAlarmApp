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
import de.drehtuer.nextalarm.domain.CalendarAccount
import de.drehtuer.nextalarm.domain.CalendarId
import de.drehtuer.nextalarm.domain.CalendarRepository
import de.drehtuer.nextalarm.domain.CalendarSync
import de.drehtuer.nextalarm.domain.DeviceCalendar
import de.drehtuer.nextalarm.domain.DiagnosticsModel
import de.drehtuer.nextalarm.domain.HomeModel
import de.drehtuer.nextalarm.domain.LOG_FAILED_NOTE
import de.drehtuer.nextalarm.domain.LOG_SAVED_NOTE
import de.drehtuer.nextalarm.domain.NewsDraft
import de.drehtuer.nextalarm.domain.NewsModel
import de.drehtuer.nextalarm.domain.PhoneSoundPick
import de.drehtuer.nextalarm.domain.PlannerEngine
import de.drehtuer.nextalarm.domain.PlannerRecord
import de.drehtuer.nextalarm.domain.PlannerRepository
import de.drehtuer.nextalarm.domain.PlannerState
import de.drehtuer.nextalarm.domain.Settings
import de.drehtuer.nextalarm.domain.SettingsRepository
import de.drehtuer.nextalarm.domain.SoundPreview
import de.drehtuer.nextalarm.domain.SoundPreviewStatus
import de.drehtuer.nextalarm.domain.SoundRef
import de.drehtuer.nextalarm.domain.TEST_ALARM_DELAY
import de.drehtuer.nextalarm.domain.WeatherDraft
import de.drehtuer.nextalarm.domain.WeatherModel
import de.drehtuer.nextalarm.domain.accountToGrant
import de.drehtuer.nextalarm.domain.buildAlarmSettings
import de.drehtuer.nextalarm.domain.buildDiagnostics
import de.drehtuer.nextalarm.domain.buildHome
import de.drehtuer.nextalarm.domain.buildLogExport
import de.drehtuer.nextalarm.domain.buildNews
import de.drehtuer.nextalarm.domain.buildWeather
import de.drehtuer.nextalarm.domain.calendarFact
import de.drehtuer.nextalarm.domain.calendarSummary
import de.drehtuer.nextalarm.domain.chosenCalendars
import de.drehtuer.nextalarm.domain.diagnosticsSummary
import de.drehtuer.nextalarm.domain.soundRejectionText
import de.drehtuer.nextalarm.domain.syncAccounts
import de.drehtuer.nextalarm.domain.testAlarmEntry
import de.drehtuer.nextalarm.domain.withAlarmCalendar
import de.drehtuer.nextalarm.domain.withPreview
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
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
    /** For "Sync now" on home. */
    val calendarSync: CalendarSync,
    val engine: PlannerEngine,
    /** Rings the test alarm. */
    val alarmEngine: AlarmEngine,
    /** Emits whenever a planning job changes state, so that home shows its result. */
    val planningChanges: Flow<*>,
    val phone: PhonePorts,
    val news: NewsPorts,
    val weather: WeatherPorts,
    /** Plays the alarm sound on screen 5. */
    val preview: SoundPreview,
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
    val selectedCalendars: Set<CalendarId> = emptySet(),
    val alarmSettings: AlarmSettingsModel = buildAlarmSettings(Settings.DEFAULT, null, null, null),
    val news: NewsModel = buildNews(NewsDraft(), null, Instant.EPOCH, ZoneOffset.UTC),
    val weather: WeatherModel = buildWeather(WeatherDraft()),
    val diagnosticsSummary: String = diagnosticsSummary(PlannerRecord.INITIAL, ZoneOffset.UTC),
    val diagnostics: DiagnosticsModel = buildDiagnostics(
        PlannerRecord.INITIAL,
        emptyList(),
        Instant.EPOCH,
        ZoneOffset.UTC,
        calendarSummary(emptyList()),
        false,
    ),
    /** True from asking for a test alarm until it rings. */
    val testAlarmPending: Boolean = false,
    /** How the last log export went. */
    val logNote: String? = null,
    /** True from tapping "Sync now" until the re-plan it asked for has been requested. */
    val syncPending: Boolean = false,
    /**
     * An account of the alarm calendars that "Sync now" cannot ask yet: home shows Android's account chooser for it,
     * once, and says so with [AppActions.accountAccessShown].
     */
    val accountToGrant: CalendarAccount? = null,
)

/** What screen 2 can ask for. */
interface HomeActions {
    /** Asks the accounts of the alarm calendars to sync, and plans again from what the phone has now. */
    fun syncNow()

    /** The account chooser for [AppState.accountToGrant] is up, so that it is not shown a second time. */
    fun accountAccessShown()

    /** The user picked the account in the chooser ([granted]), or left it. */
    fun accountAccessAnswered(granted: Boolean)
}

/** What screen 5 can ask for. */
interface AlarmSettingsActions {
    fun importSound(uri: Uri)
    fun pickPhoneSound(pick: PhoneSoundPick)
    fun useDefaultSound()
    fun setVolumeRamp(seconds: Int)
    fun setMaxVolume(volume: Float?)
    fun setVibrate(vibrate: Boolean)
    fun setRingFor(minutes: Int)
    fun setSnoozeLength(minutes: Int)
    fun setSpeakTitle(speak: Boolean)

    /** Plays the selected sound, or stops it if it plays. */
    fun toggleSoundPreview()

    /** Also when the screen is left or hidden. Does nothing if nothing plays. */
    fun stopSoundPreview()
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
    fun setNewsPause(millis: Int)
    fun setNewsLanguage(language: String?)
    fun toggleNewsTest()
    fun stopNewsTest()
    fun turnOffNews()
}

interface WeatherActions {
    fun openWeather()
    fun closeWeather()
    fun setWeatherQuery(query: String)
    fun chooseWeatherPlace(index: Int)
    fun setWeatherUseCurrentLocation(use: Boolean)
    fun setWeatherLanguage(language: String?)
    fun previewWeather()
    fun turnOffWeather()
}

interface AppActions : HomeActions, AlarmSettingsActions, NewsActions, WeatherActions, DiagnosticsActions

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

    /** What the preview of the sound does, which a refresh keeps. Its end can be told on any thread. */
    @Volatile
    private var preview = SoundPreviewStatus.IDLE

    /** Told apart from each other, because the end of an earlier preview, or the sound it waits for, can still arrive. */
    private val previewRun = AtomicInteger()

    private val news = NewsController(
        viewModelScope,
        ports.news,
        ports.settings,
        ports.now,
        ports.zone,
        store = { source -> changeSettings { it.copy(news = source) } },
        publish = { model -> mutableState.update { it.copy(news = model) } },
    )

    private val weather = WeatherController(
        viewModelScope,
        ports.weather,
        ports.settings,
        store = { source -> changeSettings { it.copy(weather = source) } },
        publish = { model -> mutableState.update { it.copy(weather = model) } },
    )

    /** While the screen is visible: starts a planning run, keeps relative times current, and picks up finished runs. */
    fun startWatching() {
        stopWatching()
        watching = listOf(
            // A run is cheap, and home then shows the real state, for instance right after the permission was granted.
            viewModelScope.launch { ports.engine.replanRequested() },
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
        val selected = settings.alarmCalendars
        val chosen = chosenCalendars(selected, calendars)
        val now = ports.now()
        val zone = ports.zone()
        val phone = ports.phone
        val volume = withContext(ports.io) { phone.audio.alarmVolume() }
        val soundName = withContext(ports.io) { phone.audio.defaultSoundName() }
        // The app can be seen, so the phone has been unlocked, and its default sound can be read.
        val defaultCopied = phone.sounds.keepDefaultCopy()
        val notificationsAllowed = phone.notificationsAllowed()
        mutableState.update {
            it.copy(
                loaded = true,
                setupDone = record.state != PlannerState.Unconfigured,
                home = buildHome(now, zone, alarms, record, chosen, notificationsAllowed),
                calendars = calendars,
                selectedCalendars = selected,
                alarmSettings = buildAlarmSettings(settings, soundName, volume, soundRejection, preview, defaultCopied),
                diagnosticsSummary = diagnosticsSummary(record, zone),
                diagnostics = buildDiagnostics(
                    record,
                    alarms,
                    now,
                    zone,
                    calendarFact(selected, chosen, record.lastRun?.failure),
                    phone.fullScreenAllowed(),
                    notificationsAllowed,
                ),
            )
        }
    }

    /** The accounts the user was asked about since the last tap of "Sync now", which are not asked about again. */
    private var askedForAccess = emptySet<CalendarAccount>()

    /** The account whose chooser is up, until the user answers. */
    private var askingForAccess: CalendarAccount? = null

    /**
     * The re-plan runs at once, from what the phone has now. What the sync fetches later reaches the planner through the
     * calendar trigger. Tapping again while a request is on its way does not add another.
     *
     * Only the accounts the app can see are asked: Android drops any other request without a word. The first one it
     * can't see is offered for access, and once the user has picked it, the sync is asked for again.
     */
    override fun syncNow() {
        askedForAccess = emptySet()
        sync()
    }

    private fun sync() {
        if (mutableState.value.syncPending) return
        mutableState.update { it.copy(syncPending = true) }
        viewModelScope.launch {
            try {
                val accounts = syncAccounts(ports.settings.load().alarmCalendars, readCalendars())
                val toGrant = withContext(ports.io) {
                    val accessible = ports.calendarSync.accessible(accounts)
                    ports.calendarSync.requestSync(accessible)
                    accountToGrant(accounts, accessible + askedForAccess)
                }
                if (toGrant != null) {
                    askedForAccess = askedForAccess + toGrant
                    mutableState.update { it.copy(accountToGrant = toGrant) }
                }
                ports.engine.replanRequested()
                refresh()
            } finally {
                mutableState.update { it.copy(syncPending = false) }
            }
        }
    }

    override fun accountAccessShown() {
        askingForAccess = mutableState.value.accountToGrant
        mutableState.update { it.copy(accountToGrant = null) }
    }

    /**
     * A pick makes the account visible, so the sync is asked for again, which also offers the next account, if any. An
     * account already asked about is never offered again, so a pick that did not take cannot bring the chooser back.
     * Leaving the chooser asks nothing more.
     */
    override fun accountAccessAnswered(granted: Boolean) {
        if (askingForAccess == null) return
        askingForAccess = null
        if (granted) sync()
    }

    /** Ticking or unticking a calendar counts at once. Before setup is finished, the planner ignores the request. */
    fun setCalendarTicked(calendar: CalendarId, ticked: Boolean) =
        changeSettings { it.withAlarmCalendar(calendar, ticked) }

    fun completeSetup() {
        viewModelScope.launch {
            ports.engine.setupCompleted()
            refresh()
        }
    }

    override fun importSound(uri: Uri) = takeSound { ports.phone.sounds.import(uri) }

    /** A phone sound is copied like a picked file. The picker's "Default" entry is the phone's default sound. */
    override fun pickPhoneSound(pick: PhoneSoundPick) = when (pick) {
        PhoneSoundPick.Cancelled -> Unit
        PhoneSoundPick.PhoneDefault -> useDefaultSound()
        is PhoneSoundPick.Sound -> takeSound { ports.phone.sounds.importPhoneSound(Uri.parse(pick.address)) }
    }

    private fun takeSound(import: suspend () -> SoundImportResult) {
        viewModelScope.launch {
            when (val result = import()) {
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

    /** The sound is read from what is stored, as a sound that was just picked may not be on the screen yet. */
    override fun toggleSoundPreview() {
        if (preview == SoundPreviewStatus.PLAYING) {
            stopSoundPreview()
            return
        }
        val run = previewRun.incrementAndGet()
        showPreview(SoundPreviewStatus.PLAYING)
        viewModelScope.launch {
            val sound = ports.settings.load().sound
            if (run != previewRun.get()) return@launch
            val status = ports.preview.play(sound) { previewEnded(run) }
            if (status != SoundPreviewStatus.PLAYING) showPreview(status)
        }
    }

    override fun stopSoundPreview() {
        previewRun.incrementAndGet()
        ports.preview.stop()
        showPreview(SoundPreviewStatus.IDLE)
    }

    private fun previewEnded(run: Int) {
        if (run == previewRun.get()) showPreview(SoundPreviewStatus.IDLE)
    }

    private fun showPreview(status: SoundPreviewStatus) {
        preview = status
        mutableState.update { it.copy(alarmSettings = it.alarmSettings.withPreview(status)) }
    }

    override fun openNews() = news.open()

    override fun closeNews() = news.close()

    override fun setNewsAddress(address: String) = news.setAddress(address)

    override fun shortenNews() = news.shorten()

    override fun lengthenNews() = news.lengthen()

    override fun setNewsPause(millis: Int) = news.setPause(millis)

    override fun setNewsLanguage(language: String?) = news.setLanguage(language)

    override fun toggleNewsTest() = news.toggleTest()

    override fun stopNewsTest() = news.stopTest()

    override fun turnOffNews() = news.turnOff()

    override fun openWeather() = weather.open()

    override fun closeWeather() = weather.close()

    override fun setWeatherQuery(query: String) = weather.setQuery(query)

    override fun chooseWeatherPlace(index: Int) = weather.choose(index)

    override fun setWeatherUseCurrentLocation(use: Boolean) = weather.setUseCurrentLocation(use)

    override fun setWeatherLanguage(language: String?) = weather.setLanguage(language)

    override fun previewWeather() = weather.showPreview()

    override fun turnOffWeather() = weather.turnOff()

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
        // What plays would no longer be the sound that is selected.
        stopSoundPreview()
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
