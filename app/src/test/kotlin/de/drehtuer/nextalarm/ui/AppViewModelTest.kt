package de.drehtuer.nextalarm.ui

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import de.drehtuer.nextalarm.data.AlarmIntents
import de.drehtuer.nextalarm.data.DataComponents
import de.drehtuer.nextalarm.domain.AlarmAction
import de.drehtuer.nextalarm.domain.AlarmEffects
import de.drehtuer.nextalarm.domain.AlarmEngine
import de.drehtuer.nextalarm.domain.AlarmInstance
import de.drehtuer.nextalarm.domain.AlarmKey
import de.drehtuer.nextalarm.domain.AlarmState
import de.drehtuer.nextalarm.domain.CalendarEvent
import de.drehtuer.nextalarm.domain.CalendarId
import de.drehtuer.nextalarm.domain.CalendarRepository
import de.drehtuer.nextalarm.domain.DeviceCalendar
import de.drehtuer.nextalarm.domain.PlannerEffects
import de.drehtuer.nextalarm.domain.PlannerEngine
import de.drehtuer.nextalarm.domain.EngineLock
import de.drehtuer.nextalarm.domain.PlannerState
import de.drehtuer.nextalarm.domain.Settings
import de.drehtuer.nextalarm.domain.snapshotFor
import de.drehtuer.nextalarm.planning.AppNotifications
import de.drehtuer.nextalarm.planning.AppPlannerEffects
import de.drehtuer.nextalarm.planning.PlanningModule
import de.drehtuer.nextalarm.planning.PlanningWork
import androidx.work.WorkManager
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

private val NOW: Instant = Instant.parse("2026-11-06T19:00:00Z")
private val CALENDAR = DeviceCalendar(CalendarId(1), "NextAlarm", "me", "bitfire.at.davdroid", null)

private class FakeCalendars(var result: () -> List<DeviceCalendar> = { listOf(CALENDAR) }) : CalendarRepository {
    var reads = 0

    override suspend fun calendars(): List<DeviceCalendar> {
        reads++
        return result()
    }

    override suspend fun events(calendar: CalendarId, from: Instant, to: Instant) = emptyList<CalendarEvent>()
}

private class RecordingEffects : PlannerEffects {
    val started = mutableListOf<Long>()
    override fun cancelPlanningJob() = Unit
    override fun startPlanningJob(generation: Long) {
        started += generation
    }

    override fun scheduleRetry(delay: Duration) = Unit
    override fun showFailSafeWarning() = Unit
    override fun removeFailSafeWarning() = Unit
    override fun showNextAlarm(alarm: AlarmInstance?) = Unit
}

private object NoAlarmEffects : AlarmEffects {
    override fun perform(instance: AlarmInstance, action: AlarmAction) = Unit
    override fun showNextAlarm(alarm: AlarmInstance?) = Unit
}

private object OpenLock : EngineLock {
    override suspend fun <T> withLock(block: suspend () -> T): T = block()
}

/** Uses the real database with a fake calendar provider, so no real calendar is read. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class AppViewModelTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val data = DataComponents(context)
    private val calendars = FakeCalendars()
    private val effects = RecordingEffects()
    private val changes = MutableSharedFlow<Unit>()
    private val alarmEngine = AlarmEngine(data.alarms, data.scheduler(NO_INTENTS), NoAlarmEffects, { NOW }, OpenLock)
    private val engine = PlannerEngine(data.planner, data.settings, calendars, data.alarms, alarmEngine, effects, OpenLock) { NOW }
    private val viewModel = AppViewModel(
        AppPorts(data.settings, data.planner, data.alarms, calendars, engine, changes, { NOW }, { ZoneId.of("Europe/Berlin") }),
    )

    private fun settle() = shadowOf(android.os.Looper.getMainLooper()).idle()

    /** Lets work that was started on the main thread and continues on background threads finish. */
    private fun awaitState(check: (AppState) -> Boolean): AppState = runBlocking {
        withTimeout(WAIT_MILLIS) {
            var state = viewModel.state.value
            while (!check(state)) {
                settle()
                Thread.sleep(POLL_MILLIS)
                state = viewModel.state.value
            }
            state
        }
    }

    private fun load(): AppState {
        viewModel.startWatching()
        val state = awaitState { it.loaded }
        viewModel.stopWatching()
        return state
    }

    @Test
    fun `the state starts unloaded`() {
        assertFalse(viewModel.state.value.loaded)
    }

    @Test
    fun `a new install is not set up, and has no selected calendar`() {
        val state = load()
        assertTrue(state.loaded)
        assertFalse(state.setupDone)
        assertNull(state.selectedCalendar)
        assertEquals(listOf(CALENDAR), state.calendars)
    }

    @Test
    fun `home shows the waiting alarms and the chosen calendar`() = runBlocking {
        val at = NOW.plus(Duration.ofHours(10))
        data.settings.save(Settings.DEFAULT.copy(alarmCalendar = CALENDAR.id))
        data.alarms.create(AlarmKey.forEvent(1, at), at, Settings.DEFAULT.snapshotFor("Flight"))
        val state = load()
        assertEquals(CALENDAR.id, state.selectedCalendar)
        assertEquals("Flight", state.home.hero?.title)
        assertEquals("From the calendar “NextAlarm” (DAVx⁵).", state.home.footer)
    }

    @Test
    fun `without calendar access there is nothing to list`() {
        calendars.result = { throw SecurityException("no permission") }
        assertEquals(emptyList(), load().calendars)
    }

    @Test
    fun `a cancelled read does not count as an empty list`() {
        calendars.result = { throw CancellationException("gone") }
        viewModel.startWatching()
        runBlocking {
            withTimeout(WAIT_MILLIS) {
                while (calendars.reads == 0) {
                    settle()
                    Thread.sleep(POLL_MILLIS)
                }
            }
        }
        settle()
        viewModel.stopWatching()
        assertFalse(viewModel.state.value.loaded)
    }

    @Test
    fun `completing setup starts planning`() {
        viewModel.completeSetup()
        val state = awaitState { it.setupDone }
        assertTrue(state.loaded)
        assertEquals(listOf(1L), effects.started)
    }

    @Test
    fun `choosing a calendar saves it, and re-plans once setup is done`() = runBlocking {
        viewModel.chooseCalendar(CALENDAR.id)
        awaitState { it.selectedCalendar == CALENDAR.id }
        assertTrue(effects.started.isEmpty())

        engine.setupCompleted()
        viewModel.chooseCalendar(CalendarId(2))
        awaitState { it.selectedCalendar == CalendarId(2) }
        assertEquals(listOf(1L, 2L), effects.started)
        assertEquals(PlannerState.Planning, data.planner.load().state)
    }

    @Test
    fun `watching loads the state at once and again when planning changes`() = runBlocking {
        viewModel.startWatching()
        awaitState { it.loaded && it.calendars.size == 1 }

        calendars.result = { emptyList() }
        withTimeout(WAIT_MILLIS) { changes.subscriptionCount.first { it > 0 } }
        changes.emit(Unit)
        awaitState { it.calendars.isEmpty() }
        viewModel.stopWatching()
    }

    @Test
    fun `after watching stops, changes are not picked up`() = runBlocking {
        viewModel.startWatching()
        awaitState { it.loaded }
        viewModel.stopWatching()
        assertEquals(0, changes.subscriptionCount.value)
    }

    @Test
    fun `watching again replaces the earlier watch`() = runBlocking {
        viewModel.startWatching()
        viewModel.startWatching()
        settle()
        withTimeout(WAIT_MILLIS) { changes.subscriptionCount.first { it == 1 } }
        viewModel.stopWatching()
        Unit
    }

    private companion object {
        const val WAIT_MILLIS = 10_000L
        const val POLL_MILLIS = 10L

        val NO_INTENTS = object : AlarmIntents {
            override fun fire(instanceId: Long) = throw UnsupportedOperationException()
            override fun show(instanceId: Long) = throw UnsupportedOperationException()
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AppNotificationsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val notifications = AppNotifications(context) { NOW }

    private fun alarm(title: String?) = AlarmInstance(
        id = 1,
        key = AlarmKey.forTestAlarm(1),
        triggerAt = NOW.plus(Duration.ofDays(1)),
        state = AlarmState.Scheduled,
        snoozeCount = 0,
        autoSnoozed = false,
        snapshot = Settings.DEFAULT.snapshotFor(title),
    )

    private fun shown(id: Int) = shadowOf(manager).activeNotifications.find { it.id == id }?.notification

    @Test
    fun `the next alarm is shown silently and stays`() {
        notifications.showNextAlarm(alarm("Flight"))
        val notification = assertNotNull(shown(AppNotifications.NEXT_ALARM_ID))
        assertEquals("Flight", notification.extras.getString(android.app.Notification.EXTRA_TEXT))
        assertTrue(notification.extras.getString(android.app.Notification.EXTRA_TITLE)!!.startsWith("Next alarm: "))
        assertTrue(notification.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(NotificationManager.IMPORTANCE_LOW, manager.getNotificationChannel(AppNotifications.NEXT_ALARM_CHANNEL).importance)
        assertNotNull(notification.contentIntent)
    }

    @Test
    fun `no next alarm removes the notification`() {
        notifications.showNextAlarm(alarm("Flight"))
        notifications.showNextAlarm(null)
        assertNull(shown(AppNotifications.NEXT_ALARM_ID))
    }

    @Test
    fun `the fail-safe warning can be shown and removed`() {
        notifications.showFailSafeWarning()
        val notification = assertNotNull(shown(AppNotifications.FAIL_SAFE_ID))
        assertEquals("New alarms aren't being added", notification.extras.getString(android.app.Notification.EXTRA_TITLE))
        notifications.removeFailSafeWarning()
        assertNull(shown(AppNotifications.FAIL_SAFE_ID))
    }

    @Test
    fun `the default clock can be used`() {
        AppNotifications(context).showNextAlarm(alarm(null))
        assertEquals("No title", shown(AppNotifications.NEXT_ALARM_ID)?.extras?.getString(android.app.Notification.EXTRA_TEXT))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AppPlannerEffectsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var workManager: WorkManager
    private lateinit var effects: AppPlannerEffects

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        workManager = WorkManager.getInstance(context)
        effects = AppPlannerEffects(PlanningWork(workManager), AppNotifications(context))
    }

    private fun exists(name: String) = workManager.getWorkInfosForUniqueWork(name).get().isNotEmpty()

    @Test
    fun `jobs go to WorkManager`() {
        effects.startPlanningJob(1)
        effects.cancelPlanningJob()
        effects.scheduleRetry(Duration.ofMinutes(1))
        assertTrue(exists(PlanningWork.PLAN_WORK))
        assertTrue(exists(PlanningWork.RETRY_WORK))
    }

    @Test
    fun `notifications go to the notification manager`() {
        val manager = shadowOf(context.getSystemService(NotificationManager::class.java))
        effects.showFailSafeWarning()
        assertEquals(1, manager.activeNotifications.size)
        effects.removeFailSafeWarning()
        effects.showNextAlarm(null)
        assertEquals(0, manager.activeNotifications.size)
    }

    @Test
    fun `the module provides what the screens need`() = runBlocking {
        val data = PlanningModule.dataComponents(context)
        val engine = PlanningModule.plannerEngine(context, data, PlanningModule.planningWork(context), AlarmEngine(data.alarms, data.scheduler(de.drehtuer.nextalarm.planning.AppAlarmIntents(context)), NoAlarmEffects, { NOW }, OpenLock))
        val ports = PlanningModule.appPorts(context, data, engine)
        assertEquals(PlannerState.Unconfigured, ports.planner.load().state)
        assertTrue(ports.alarms.all().isEmpty())
        assertEquals(Settings.DEFAULT, ports.settings.load())
    }
}
