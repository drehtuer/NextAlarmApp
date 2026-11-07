package de.drehtuer.nextalarm.ringing

import android.app.Application
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.work.impl.WorkManagerImpl
import de.drehtuer.nextalarm.data.DataComponents
import de.drehtuer.nextalarm.domain.AlarmAction
import de.drehtuer.nextalarm.domain.AlarmEffects
import de.drehtuer.nextalarm.domain.AlarmEngine
import de.drehtuer.nextalarm.domain.AlarmInstance
import de.drehtuer.nextalarm.domain.AlarmKey
import de.drehtuer.nextalarm.domain.AlarmState
import de.drehtuer.nextalarm.domain.Settings
import de.drehtuer.nextalarm.domain.WakeUpPlaying
import de.drehtuer.nextalarm.domain.WakeUpStatus
import de.drehtuer.nextalarm.domain.snapshotFor
import de.drehtuer.nextalarm.planning.MutexEngineLock
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class WakeUpChangesTest {
    @Test
    fun `it starts idle, and holds what was reported last`() {
        val changes = WakeUpChanges()
        assertEquals(WakeUpStatus.IDLE, changes.status.value)
        val status = WakeUpStatus(listOf("Gym"), null, null)
        changes.report(status)
        assertEquals(status, changes.status.value)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class WakeUpScreenControllerTest {
    private object NoEffects : AlarmEffects {
        override fun perform(instance: AlarmInstance, action: AlarmAction) = Unit
        override fun showNextAlarm(alarm: AlarmInstance?) = Unit
    }

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val alarms = FakeAlarms(
        alarm(id = 1, state = AlarmState.Announcing),
        alarm(id = 2, state = AlarmState.NewsPlaying),
        alarm(id = 3, state = AlarmState.Ringing),
    )
    private val engine = AlarmEngine(alarms, FakeScheduler(), NoEffects, { AT }, MutexEngineLock())
    private val changes = WakeUpChanges()
    private var starts = 0
    private val foreground = WakeUpForeground(WakeUpNotifications(context), WakeUpMedia(context) {}) { starts++ }
    private val screenScope = CoroutineScope(Job() + Dispatchers.Unconfined)
    private var time = AT
    private val running = WakeUpStatus(listOf("Gym"), WakeUpPlaying.Speaking("Gym", 1, 1, AT), null)
    private var current = running
    private val controller = WakeUpScreenController(
        alarms,
        engine,
        { current },
        changes,
        foreground,
        alarmScope(Dispatchers.Unconfined),
        { time },
        Duration.ofMillis(10),
    )

    @After
    fun tearDown() = screenScope.cancel()

    @Test
    fun `it shows what the automation says, and is not finished while it runs`() {
        controller.start(screenScope)
        awaitUntil { controller.status.value == running }
        assertFalse(controller.finished.value)
    }

    @Test
    fun `it follows the clock, and how far things have got`() {
        controller.start(screenScope)
        time = AT.plusSeconds(60)
        val later = running.copy(titles = listOf("Later"))
        current = later
        awaitUntil { controller.now.value == AT.plusSeconds(60) && controller.status.value == later }
    }

    @Test
    fun `it hears of changes at once`() {
        val stillClock = WakeUpScreenController(alarms, engine, { current }, changes, foreground, alarmScope(Dispatchers.Unconfined), { time }, Duration.ofHours(1))
        stillClock.start(screenScope)
        val later = running.copy(titles = listOf("Later"))
        current = later
        changes.report(later)
        awaitUntil { stillClock.status.value == later }
    }

    @Test
    fun `an alarm that is stored as in the automation keeps it open, before the automation has heard of it`() {
        current = WakeUpStatus.IDLE
        controller.start(screenScope)
        Thread.sleep(50)
        assertFalse(controller.finished.value)
    }

    @Test
    fun `an alarm stored as playing the news keeps it open too`() {
        current = WakeUpStatus.IDLE
        alarms.stored.remove(1)
        controller.start(screenScope)
        Thread.sleep(50)
        assertFalse(controller.finished.value)
    }

    @Test
    fun `an automation that runs keeps it open, whatever is stored`() {
        alarms.stored.clear()
        controller.start(screenScope)
        Thread.sleep(50)
        assertFalse(controller.finished.value)
    }

    @Test
    fun `it is finished when neither is left`() {
        controller.start(screenScope)
        current = WakeUpStatus.IDLE
        alarms.stored.clear()
        awaitUntil { controller.finished.value }
    }

    @Test
    fun `Stop ends every alarm in the automation, and closes the screen`() {
        current = WakeUpStatus.IDLE
        controller.stop()
        awaitUntil { controller.finished.value }
        assertEquals(AlarmState.Done, alarms.stored[1]?.state)
        assertEquals(AlarmState.Done, alarms.stored[2]?.state)
        assertEquals(AlarmState.Ringing, alarms.stored[3]?.state)
    }

    @Test
    fun `when the screen comes up it starts a service that was refused`() {
        foreground.changed(running)
        assertEquals(1, starts)
        controller.onResume()
        assertEquals(2, starts)
    }

    @Test
    fun `by default it goes by the real time`() {
        val defaults = WakeUpScreenController(alarms, engine, { current }, changes, foreground, alarmScope(Dispatchers.Unconfined))
        assertTrue(defaults.now.value.isAfter(AT.minus(Duration.ofDays(365))))
    }
}

/** The activities on the real app: what the database says, and what the real wake-up automation does with it. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h923dp")
class WakeUpActivityTest {
    @get:Rule
    val rule = createEmptyComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val data = DataComponents(context)

    @Before
    fun setUp() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        // WorkManager keeps itself in static fields, which outlive the tests that set it up.
        listOf("sDelegatedInstance", "sDefaultInstance").forEach { name ->
            WorkManagerImpl::class.java.getDeclaredField(name).apply { isAccessible = true }.set(null, null)
        }
    }

    private fun ringing(title: String, speak: Boolean): AlarmInstance = runBlocking {
        val at = Instant.now()
        val snapshot = Settings.DEFAULT.copy(speakReason = speak).snapshotFor(title)
        val created = data.alarms.create(AlarmKey.forEvent(1, at), at, snapshot)
        created.copy(state = AlarmState.Ringing).also { data.alarms.update(it) }
    }

    private fun state(id: Long) = runBlocking { data.alarms.find(id)?.state }

    private fun waitForText(text: String) =
        rule.waitUntil(WAIT_MILLIS) { rule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }

    private fun nextStarted(): Intent? = shadowOf(context as Application).nextStartedActivity

    @Test
    fun `Dismiss leads to the wake-up screen, and Stop there ends it all and closes it`() {
        val alarm = ringing("Flight to Hamburg", speak = true)
        ActivityScenario.launch(AlarmActivity::class.java).use {
            waitForText("Dismiss")
            rule.onNodeWithText("Dismiss").performClick()
            rule.waitUntil(WAIT_MILLIS) { state(alarm.id) == AlarmState.Announcing }
            rule.waitUntil(WAIT_MILLIS) {
                var finishing = false
                it.onActivity { activity ->
                    val next = shadowOf(activity).peekNextStartedActivity()
                    finishing = activity.isFinishing && next?.component?.className == WakeUpActivity::class.java.name
                }
                finishing
            }
        }
        ActivityScenario.launch(WakeUpActivity::class.java).use { scenario ->
            waitForText("Stop")
            assertEquals(2, rule.onAllNodes(hasText("Flight to Hamburg")).fetchSemanticsNodes().size, "the title and what plays")
            rule.onNodeWithText("Speaking the title").assertIsDisplayed()
            scenario.onActivity { assertEquals(AudioManager.STREAM_MUSIC, it.volumeControlStream) }
            rule.onNodeWithText("Unlocking the phone won't stop it.").assertIsDisplayed()
            rule.onNodeWithText("Stop").performClick()
            rule.waitUntil(WAIT_MILLIS) { state(alarm.id) == AlarmState.Done }
            rule.waitUntil(WAIT_MILLIS) { scenario.isClosing() }
        }
    }

    @Test
    fun `without anything to do after it, Dismiss leads to nothing`() {
        val alarm = ringing("Gym", speak = false)
        ActivityScenario.launch(AlarmActivity::class.java).use { scenario ->
            waitForText("Dismiss")
            rule.onNodeWithText("Dismiss").performClick()
            rule.waitUntil(WAIT_MILLIS) { state(alarm.id) == AlarmState.Done }
            rule.waitUntil(WAIT_MILLIS) {
                var finishing = false
                scenario.onActivity { finishing = it.isFinishing }
                finishing
            }
            scenario.onActivity { assertNull(shadowOf(it).peekNextStartedActivity()) }
        }
    }

    @Test
    fun `with no automation running, the screen closes by itself`() {
        ActivityScenario.launch(WakeUpActivity::class.java).use { scenario ->
            rule.waitUntil(WAIT_MILLIS) { scenario.isClosing() }
        }
    }

    private companion object {
        const val WAIT_MILLIS = 10_000L
    }
}

/** On the real application with its real Hilt graph. */
@RunWith(RobolectricTestRunner::class)
class WakeUpServiceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun service() = Robolectric.buildService(WakeUpService::class.java).create().get()

    @Test
    fun `it is not sticky, as an interrupted automation is not resumed, and cannot be bound`() {
        val service = service()
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 1))
        assertNull(service.onBind(Intent()))
    }

    @Test
    fun `with nothing running it goes into the foreground as demanded, and stops`() {
        val service = service()
        service.onStartCommand(Intent(context, WakeUpService::class.java), 0, 1)
        assertEquals(WakeUpNotifications.ID, shadowOf(service).lastForegroundNotificationId)
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertTrue(shadowOf(service).isForegroundStopped)
    }

    @Test
    fun `with something running it shows that in the foreground, and stays`() {
        val service = service()
        service.foreground.changed(WakeUpStatus(listOf("Gym"), WakeUpPlaying.Speaking("Gym", 1, 1, AT), null))
        service.onStartCommand(null, 0, 1)
        assertEquals(WakeUpNotifications.ID, shadowOf(service).lastForegroundNotificationId)
        assertFalse(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun `it stops when the automation has ended`() {
        val service = service()
        service.foreground.changed(WakeUpStatus(listOf("Gym"), WakeUpPlaying.Speaking("Gym", 1, 1, AT), null))
        service.onStartCommand(null, 0, 1)
        service.foreground.changed(WakeUpStatus.IDLE)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun `once it is gone, a running automation starts it again`() {
        val service = service()
        service.foreground.changed(WakeUpStatus(listOf("Gym"), WakeUpPlaying.Speaking("Gym", 1, 1, AT), null))
        service.onStartCommand(null, 0, 1)
        val application = shadowOf(context as Application)
        while (application.nextStartedService != null) {
            // Only what happens after this point counts.
        }
        service.onDestroy()
        service.foreground.sync()
        assertEquals(WakeUpService::class.java.name, application.nextStartedService?.component?.className)
    }
}
