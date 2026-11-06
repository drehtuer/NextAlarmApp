package de.drehtuer.nextalarm.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.drehtuer.nextalarm.domain.AlarmRepository
import de.drehtuer.nextalarm.domain.CalendarId
import de.drehtuer.nextalarm.domain.CalendarRepository
import de.drehtuer.nextalarm.domain.DeviceCalendar
import de.drehtuer.nextalarm.domain.HomeModel
import de.drehtuer.nextalarm.domain.PlannerEngine
import de.drehtuer.nextalarm.domain.PlannerRecord
import de.drehtuer.nextalarm.domain.PlannerRepository
import de.drehtuer.nextalarm.domain.PlannerState
import de.drehtuer.nextalarm.domain.SettingsRepository
import de.drehtuer.nextalarm.domain.buildHome
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the screens need from the rest of the app. */
class AppPorts(
    val settings: SettingsRepository,
    val planner: PlannerRepository,
    val alarms: AlarmRepository,
    val calendars: CalendarRepository,
    val engine: PlannerEngine,
    /** Emits whenever a planning job changes state, so that home shows its result. */
    val planningChanges: Flow<*>,
    val now: () -> Instant = Instant::now,
    val zone: () -> ZoneId = ZoneId::systemDefault,
)

data class AppState(
    /** False until the first load, so that nothing wrong flashes up. */
    val loaded: Boolean = false,
    val setupDone: Boolean = false,
    val home: HomeModel = HomeModel(null, emptyList(), null, null, null),
    val calendars: List<DeviceCalendar> = emptyList(),
    val selectedCalendar: CalendarId? = null,
)

@HiltViewModel
class AppViewModel @Inject constructor(private val ports: AppPorts) : ViewModel() {
    private val mutableState = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = mutableState.asStateFlow()

    private var watching: List<Job> = emptyList()

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

    private suspend fun refresh() {
        val settings = ports.settings.load()
        val record: PlannerRecord = ports.planner.load()
        val calendars = readCalendars()
        val selected = settings.alarmCalendar
        mutableState.value = AppState(
            loaded = true,
            setupDone = record.state != PlannerState.Unconfigured,
            home = buildHome(ports.now(), ports.zone(), ports.alarms.all(), record, calendars.find { it.id == selected }),
            calendars = calendars,
            selectedCalendar = selected,
        )
    }

    /** Choosing a calendar counts at once. Before setup is finished, the planner ignores the request. */
    fun chooseCalendar(calendar: CalendarId) {
        viewModelScope.launch {
            ports.settings.save(ports.settings.load().copy(alarmCalendar = calendar))
            ports.engine.replanRequested()
            refresh()
        }
    }

    fun completeSetup() {
        viewModelScope.launch {
            ports.engine.setupCompleted()
            refresh()
        }
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
