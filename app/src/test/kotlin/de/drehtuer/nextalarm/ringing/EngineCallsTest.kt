package de.drehtuer.nextalarm.ringing

import android.app.Application
import dagger.Lazy
import de.drehtuer.nextalarm.domain.AlarmAction
import de.drehtuer.nextalarm.domain.AlarmEffects
import de.drehtuer.nextalarm.domain.AlarmEngine
import de.drehtuer.nextalarm.domain.AlarmEvent
import de.drehtuer.nextalarm.domain.AlarmInstance
import de.drehtuer.nextalarm.domain.AlarmState
import de.drehtuer.nextalarm.planning.MutexEngineLock
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class EngineCallsTest {
    private object NoEffects : AlarmEffects {
        override fun perform(instance: AlarmInstance, action: AlarmAction) = Unit
        override fun showNextAlarm(alarm: AlarmInstance?) = Unit
    }

    private val alarms = FakeAlarms(
        alarm(id = 1, state = AlarmState.Ringing),
        alarm(id = 2, state = AlarmState.Announcing),
    )
    private val lock = MutexEngineLock()
    private val engine = AlarmEngine(alarms, FakeScheduler(), NoEffects, { AT }, lock)

    // Unconfined runs each call on this thread until it has to wait, so it is done when the call returns unless it waits.
    private val calls = EngineCalls(alarmScope(Dispatchers.Unconfined), Lazy { engine })

    private fun state(id: Long) = alarms.stored.getValue(id).state

    @Test
    fun `a ring timeout reaches the engine`() {
        calls.ringTimedOut(listOf(1))
        assertEquals(AlarmState.Snoozed, state(1))
    }

    @Test
    fun `an event reaches the engine`() {
        calls.handle(1, AlarmEvent.Dismiss)
        assertEquals(AlarmState.Done, state(1))
    }

    @Test
    fun `stopping the wake-up automation reaches the engine`() {
        calls.stopWakeUp()
        assertEquals(AlarmState.Done, state(2))
    }

    @Test
    fun `a call made under the engine's lock returns at once and is handled when the lock is free`(): Unit = runBlocking {
        lock.withLock {
            calls.handle(1, AlarmEvent.Dismiss)
            assertEquals(AlarmState.Ringing, state(1))
        }
        awaitUntil { state(1) == AlarmState.Done }
    }

    @Test
    fun `the engine is only asked for when a call is made`() {
        EngineCalls(alarmScope(Dispatchers.Unconfined), Lazy { error("The engine is not built yet") })
    }
}
