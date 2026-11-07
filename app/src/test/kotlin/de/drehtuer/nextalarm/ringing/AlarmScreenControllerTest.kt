package de.drehtuer.nextalarm.ringing

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import de.drehtuer.nextalarm.domain.AlarmAction
import de.drehtuer.nextalarm.domain.AlarmEffects
import de.drehtuer.nextalarm.domain.AlarmEngine
import de.drehtuer.nextalarm.domain.AlarmInstance
import de.drehtuer.nextalarm.domain.AlarmState
import de.drehtuer.nextalarm.planning.MutexEngineLock
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AlarmScreenControllerTest {
    private object NoEffects : AlarmEffects {
        override fun perform(instance: AlarmInstance, action: AlarmAction) = Unit
        override fun showNextAlarm(alarm: AlarmInstance?) = Unit
    }

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val alarms = FakeAlarms(alarm(id = 1), alarm(id = 2), alarm(id = 3, state = AlarmState.Snoozed))
    private val engine = AlarmEngine(alarms, FakeScheduler(), NoEffects, { AT }, MutexEngineLock())
    private val changes = RingingChanges()
    private var starts = 0
    private val foreground = AlarmForeground(AlarmNotifications(context)) { starts++ }
    private val appScope = alarmScope(Dispatchers.Unconfined)
    private val screenScope = CoroutineScope(Job() + Dispatchers.Unconfined)
    private var time = AT
    private val controller = AlarmScreenController(
        alarms,
        engine,
        changes,
        foreground,
        appScope,
        { time },
        Duration.ofMillis(1),
    )

    @After
    fun tearDown() = screenScope.cancel()

    @Test
    fun `nothing is known before it starts`() {
        assertTrue(controller.ringing.value.isEmpty())
        assertFalse(controller.finished.value)
        assertEquals(AT, controller.now.value)
    }

    @Test
    fun `it shows the stored ringing alarms, and only those`() {
        controller.start(screenScope)
        assertEquals(listOf(1L, 2L), controller.ringing.value.map { it.id })
        assertFalse(controller.finished.value)
    }

    @Test
    fun `it ends if nothing rings`() {
        alarms.stored.clear()
        controller.start(screenScope)
        assertTrue(controller.finished.value)
    }

    @Test
    fun `a change in the ringing alarms is picked up`() {
        controller.start(screenScope)
        alarms.stored[4] = alarm(id = 4)
        changes.report(listOf(alarm(id = 4)))
        assertEquals(listOf(1L, 2L, 4L), controller.ringing.value.map { it.id })
    }

    @Test
    fun `it ends when the alarms have ended in another way`() {
        controller.start(screenScope)
        alarms.stored.clear()
        changes.report(listOf(alarm(id = 9)))
        assertTrue(controller.finished.value)
    }

    @Test
    fun `Snooze goes to all ringing alarms and ends the screen`() {
        controller.start(screenScope)
        controller.snooze()
        assertEquals(AlarmState.Snoozed, alarms.stored[1]?.state)
        assertEquals(AlarmState.Snoozed, alarms.stored[2]?.state)
        assertTrue(controller.finished.value)
    }

    @Test
    fun `Dismiss goes to all ringing alarms and ends the screen`() {
        controller.start(screenScope)
        controller.dismiss()
        assertEquals(AlarmState.Done, alarms.stored[1]?.state)
        assertEquals(AlarmState.Done, alarms.stored[2]?.state)
        assertEquals(AlarmState.Snoozed, alarms.stored[3]?.state)
        assertTrue(controller.finished.value)
    }

    @Test
    fun `Snooze and Dismiss still take effect if the screen is gone by then`() {
        controller.start(screenScope)
        screenScope.cancel()
        controller.dismiss()
        assertEquals(AlarmState.Done, alarms.stored[1]?.state)
    }

    @Test
    fun `the clock keeps running`() {
        controller.start(screenScope)
        time = AT.plusSeconds(60)
        awaitUntil { controller.now.value == AT.plusSeconds(60) }
    }

    @Test
    fun `coming to the front gives the service another chance to start`() {
        foreground.ringingChanged(listOf(alarm(id = 1)))
        controller.onResume()
        assertEquals(2, starts)
    }

    @Test
    fun `the default clock can be used`() {
        val before = Instant.now()
        val plain = AlarmScreenController(alarms, engine, changes, foreground, appScope)
        assertFalse(plain.now.value.isBefore(before))
    }

    @Test
    fun `the changes start out empty and keep the latest report`() {
        assertTrue(changes.alarms.value.isEmpty())
        changes.report(listOf(alarm(id = 7)))
        assertEquals(listOf(7L), changes.alarms.value.map { it.id })
    }
}
