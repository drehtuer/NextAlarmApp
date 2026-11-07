package de.drehtuer.nextalarm.domain

import java.time.Duration
import java.time.Instant

/** How late an alarm is. An alarm that arrives early counts as on time: ringing beats not ringing. */
fun lateBy(triggerAt: Instant, now: Instant): Duration = maxOf(Duration.between(triggerAt, now), Duration.ZERO)

/** One thing the recovery sweep has to do. */
sealed interface RecoveryStep {
    /** Register this waiting alarm with the system again. */
    data class Register(val alarm: AlarmInstance) : RecoveryStep

    data class Send(val instanceId: Long, val event: AlarmEvent) : RecoveryStep
}

/**
 * What the recovery sweep does, after a reboot or crash. At process start nothing is playing yet, and the persisted
 * state says what should be. See docs/architecture.md#recovery. Every event is defined in every state, so running the
 * sweep twice is harmless.
 */
fun recoverySteps(alarms: List<AlarmInstance>, now: Instant): List<RecoveryStep> = alarms.mapNotNull { alarm ->
    when (alarm.state) {
        AlarmState.Scheduled, AlarmState.Snoozed ->
            if (alarm.triggerAt > now) RecoveryStep.Register(alarm) else fireStep(alarm, now)
        AlarmState.Ringing -> fireStep(alarm, now)
        AlarmState.Announcing, AlarmState.NewsPlaying -> RecoveryStep.Send(alarm.id, AlarmEvent.StopWakeUp)
        AlarmState.Done, AlarmState.Unanswered, AlarmState.Cancelled, AlarmState.Missed -> null
    }
}

private fun fireStep(alarm: AlarmInstance, now: Instant) =
    RecoveryStep.Send(alarm.id, AlarmEvent.Fire(lateBy(alarm.triggerAt, now)))
