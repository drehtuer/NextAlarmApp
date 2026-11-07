package de.drehtuer.nextalarm.domain

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class RecoveryTest {
    private val future = NOW.plusSeconds(60)
    private val late = Duration.ofMinutes(5)
    private val past = NOW.minus(late)

    private fun alarm(state: AlarmState, triggerAt: Instant, id: Long = 1) =
        AlarmInstance(id, AlarmKey.forTestAlarm(id), triggerAt, state, 0, false, snapshot())

    private fun steps(state: AlarmState, triggerAt: Instant) = recoverySteps(listOf(alarm(state, triggerAt)), NOW)

    @Test
    fun `waiting alarms with a trigger in the future are registered again`() {
        listOf(AlarmState.Scheduled, AlarmState.Snoozed).forEach { state ->
            assertEquals(listOf<RecoveryStep>(RecoveryStep.Register(alarm(state, future))), steps(state, future))
        }
    }

    @Test
    fun `waiting alarms whose trigger was reached fire, with how late they are`() {
        listOf(AlarmState.Scheduled, AlarmState.Snoozed).forEach { state ->
            assertEquals(listOf<RecoveryStep>(RecoveryStep.Send(1, AlarmEvent.Fire(late))), steps(state, past))
            assertEquals(listOf<RecoveryStep>(RecoveryStep.Send(1, AlarmEvent.Fire(Duration.ZERO))), steps(state, NOW))
        }
    }

    @Test
    fun `a ringing alarm fires again, whatever its trigger says`() {
        assertEquals(listOf<RecoveryStep>(RecoveryStep.Send(1, AlarmEvent.Fire(late))), steps(AlarmState.Ringing, past))
        assertEquals(listOf<RecoveryStep>(RecoveryStep.Send(1, AlarmEvent.Fire(Duration.ZERO))), steps(AlarmState.Ringing, future))
    }

    @Test
    fun `an interrupted wake-up automation is not resumed`() {
        listOf(AlarmState.Announcing, AlarmState.NewsPlaying).forEach { state ->
            assertEquals(listOf<RecoveryStep>(RecoveryStep.Send(1, AlarmEvent.StopWakeUp)), steps(state, past))
        }
    }

    @Test
    fun `terminal alarms are left alone`() {
        listOf(AlarmState.Done, AlarmState.Unanswered, AlarmState.Cancelled, AlarmState.Missed).forEach { state ->
            assertEquals(emptyList(), steps(state, past))
        }
    }

    @Test
    fun `all alarms are looked at`() {
        val alarms = listOf(alarm(AlarmState.Scheduled, future, 1), alarm(AlarmState.Done, past, 2), alarm(AlarmState.Ringing, past, 3))
        assertEquals(
            listOf(RecoveryStep.Register(alarms[0]), RecoveryStep.Send(3, AlarmEvent.Fire(late))),
            recoverySteps(alarms, NOW),
        )
    }

    @Test
    fun `an alarm that arrives early counts as on time`() {
        assertEquals(Duration.ZERO, lateBy(future, NOW))
        assertEquals(late, lateBy(past, NOW))
    }
}
