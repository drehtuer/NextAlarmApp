package de.drehtuer.nextalarm.domain

import java.time.Duration

/** An alarm later than this does not ring and becomes [AlarmState.Missed] (guard G1). */
val MAX_LATENESS: Duration = Duration.ofMinutes(60)

/** An alarm later than this gets a "late alarm" notification when it starts ringing. */
val LATE_NOTICE_THRESHOLD: Duration = Duration.ofMinutes(1)

/** The alarm state machine. docs/state_machines.md is the specification. */
sealed interface AlarmState {
    data object Scheduled : AlarmState
    data object Ringing : AlarmState
    data object Snoozed : AlarmState
    data object Announcing : AlarmState
    data object NewsPlaying : AlarmState
    data object Done : AlarmState
    data object Unanswered : AlarmState
    data object Cancelled : AlarmState
    data object Missed : AlarmState
}

sealed interface AlarmEvent {
    /** Only creates an instance, see [createAlarm]. It is ignored on an existing one. */
    data object Create : AlarmEvent
    data class Fire(val lateBy: Duration) : AlarmEvent
    data object Snooze : AlarmEvent
    data object Dismiss : AlarmEvent
    data object RingTimeout : AlarmEvent
    data class Refresh(val snapshot: AlarmSnapshot) : AlarmEvent
    data object Remove : AlarmEvent
    data object AnnouncementDone : AlarmEvent
    data object NewsDone : AlarmEvent
    data object StopWakeUp : AlarmEvent
}

sealed interface AlarmAction {
    data object RegisterAlarm : AlarmAction
    data object CancelAlarm : AlarmAction
    data class ReplaceSnapshot(val snapshot: AlarmSnapshot) : AlarmAction
    data object JoinAlarmService : AlarmAction
    data object LeaveAlarmService : AlarmAction
    data object LeaveAllServices : AlarmAction
    data class SetAutoSnoozed(val value: Boolean) : AlarmAction
    data object IncrementSnoozeCount : AlarmAction

    /** Sets the trigger time to now plus [minutes] and registers the alarm there. */
    data class RegisterSnoozeAlarm(val minutes: Int) : AlarmAction
    data object StartAnnouncement : AlarmAction
    data object StartNews : AlarmAction
    data object PostSnoozeNotification : AlarmAction
    data object PostLateNotification : AlarmAction
    data object PostMissedNotification : AlarmAction
    data object PostUnansweredNotification : AlarmAction
    data object RemoveNotifications : AlarmAction
}

/** The instance data the guards use. */
data class AlarmContext(
    val autoSnoozed: Boolean,
    val snapshot: AlarmSnapshot,
)

/** The initial transition of a new instance. */
fun createAlarm(): Transition<AlarmState, AlarmAction> =
    Transition(AlarmState.Scheduled, listOf(AlarmAction.RegisterAlarm))

fun transition(
    state: AlarmState,
    event: AlarmEvent,
    context: AlarmContext,
): Transition<AlarmState, AlarmAction> = when (state) {
    AlarmState.Scheduled -> fromScheduled(event)
    AlarmState.Ringing -> fromRinging(event, context)
    AlarmState.Snoozed -> fromSnoozed(event, context)
    AlarmState.Announcing -> fromAnnouncing(event, context)
    AlarmState.NewsPlaying -> fromNewsPlaying(event)
    AlarmState.Done, AlarmState.Unanswered, AlarmState.Cancelled, AlarmState.Missed -> fromTerminal(state, event)
}

private fun ignore(state: AlarmState) = Transition<AlarmState, AlarmAction>(state)

private fun fromScheduled(event: AlarmEvent): Transition<AlarmState, AlarmAction> = when (event) {
    is AlarmEvent.Fire -> fire(event, wasRinging = false)
    is AlarmEvent.Refresh -> Transition(AlarmState.Scheduled, listOf(AlarmAction.ReplaceSnapshot(event.snapshot)))
    AlarmEvent.Remove -> enterCancelled()
    AlarmEvent.Create, AlarmEvent.Snooze, AlarmEvent.Dismiss, AlarmEvent.RingTimeout,
    AlarmEvent.AnnouncementDone, AlarmEvent.NewsDone, AlarmEvent.StopWakeUp,
    -> ignore(AlarmState.Scheduled)
}

private fun fromRinging(event: AlarmEvent, context: AlarmContext): Transition<AlarmState, AlarmAction> = when (event) {
    is AlarmEvent.Fire -> fire(event, wasRinging = true)
    AlarmEvent.Snooze -> enterSnoozed(context, autoSnoozed = false)
    AlarmEvent.Dismiss -> startWakeUp(context.snapshot)
    AlarmEvent.RingTimeout ->
        if (!context.autoSnoozed) enterSnoozed(context, autoSnoozed = true) else enterUnanswered()
    AlarmEvent.Create, is AlarmEvent.Refresh, AlarmEvent.Remove,
    AlarmEvent.AnnouncementDone, AlarmEvent.NewsDone, AlarmEvent.StopWakeUp,
    -> ignore(AlarmState.Ringing)
}

private fun fromSnoozed(event: AlarmEvent, context: AlarmContext): Transition<AlarmState, AlarmAction> = when (event) {
    is AlarmEvent.Fire -> fire(event, wasRinging = false)
    AlarmEvent.Dismiss -> startWakeUp(context.snapshot)
    AlarmEvent.Create, AlarmEvent.Snooze, AlarmEvent.RingTimeout, is AlarmEvent.Refresh, AlarmEvent.Remove,
    AlarmEvent.AnnouncementDone, AlarmEvent.NewsDone, AlarmEvent.StopWakeUp,
    -> ignore(AlarmState.Snoozed)
}

private fun fromAnnouncing(event: AlarmEvent, context: AlarmContext): Transition<AlarmState, AlarmAction> = when (event) {
    AlarmEvent.AnnouncementDone -> if (context.snapshot.news != null) enterNewsPlaying() else enterDone()
    AlarmEvent.StopWakeUp -> enterDone()
    AlarmEvent.Create, is AlarmEvent.Fire, AlarmEvent.Snooze, AlarmEvent.Dismiss, AlarmEvent.RingTimeout,
    is AlarmEvent.Refresh, AlarmEvent.Remove, AlarmEvent.NewsDone,
    -> ignore(AlarmState.Announcing)
}

private fun fromNewsPlaying(event: AlarmEvent): Transition<AlarmState, AlarmAction> = when (event) {
    AlarmEvent.NewsDone, AlarmEvent.StopWakeUp -> enterDone()
    AlarmEvent.Create, is AlarmEvent.Fire, AlarmEvent.Snooze, AlarmEvent.Dismiss, AlarmEvent.RingTimeout,
    is AlarmEvent.Refresh, AlarmEvent.Remove, AlarmEvent.AnnouncementDone,
    -> ignore(AlarmState.NewsPlaying)
}

private fun fromTerminal(state: AlarmState, event: AlarmEvent): Transition<AlarmState, AlarmAction> = when (event) {
    AlarmEvent.Create, is AlarmEvent.Fire, AlarmEvent.Snooze, AlarmEvent.Dismiss, AlarmEvent.RingTimeout,
    is AlarmEvent.Refresh, AlarmEvent.Remove, AlarmEvent.AnnouncementDone, AlarmEvent.NewsDone,
    AlarmEvent.StopWakeUp,
    -> ignore(state)
}

/** Guard G1, and the late notification for an alarm that was not already ringing. */
private fun fire(event: AlarmEvent.Fire, wasRinging: Boolean): Transition<AlarmState, AlarmAction> = when {
    event.lateBy > MAX_LATENESS -> enterMissed()
    !wasRinging && event.lateBy > LATE_NOTICE_THRESHOLD -> Transition(
        AlarmState.Ringing,
        listOf(AlarmAction.JoinAlarmService, AlarmAction.PostLateNotification),
    )
    else -> Transition(AlarmState.Ringing, listOf(AlarmAction.JoinAlarmService))
}

/** Guard G3: where dismissing an alarm leads. */
private fun startWakeUp(snapshot: AlarmSnapshot): Transition<AlarmState, AlarmAction> = when {
    snapshot.speakReason && snapshot.reason != null -> Transition(
        AlarmState.Announcing,
        listOf(AlarmAction.LeaveAlarmService, AlarmAction.CancelAlarm, AlarmAction.StartAnnouncement),
    )
    snapshot.news != null -> enterNewsPlaying()
    else -> enterDone()
}

private fun enterSnoozed(context: AlarmContext, autoSnoozed: Boolean) = Transition<AlarmState, AlarmAction>(
    AlarmState.Snoozed,
    listOf(
        AlarmAction.SetAutoSnoozed(autoSnoozed),
        AlarmAction.LeaveAlarmService,
        AlarmAction.IncrementSnoozeCount,
        AlarmAction.RegisterSnoozeAlarm(context.snapshot.snoozeMinutes),
        AlarmAction.PostSnoozeNotification,
    ),
)

private fun enterNewsPlaying() = Transition<AlarmState, AlarmAction>(
    AlarmState.NewsPlaying,
    listOf(AlarmAction.LeaveAlarmService, AlarmAction.CancelAlarm, AlarmAction.StartNews),
)

private fun enterDone() = Transition<AlarmState, AlarmAction>(
    AlarmState.Done,
    listOf(AlarmAction.LeaveAllServices, AlarmAction.CancelAlarm, AlarmAction.RemoveNotifications),
)

private fun enterUnanswered() = Transition<AlarmState, AlarmAction>(
    AlarmState.Unanswered,
    listOf(AlarmAction.LeaveAlarmService, AlarmAction.CancelAlarm, AlarmAction.PostUnansweredNotification),
)

private fun enterCancelled() = Transition<AlarmState, AlarmAction>(
    AlarmState.Cancelled,
    listOf(AlarmAction.CancelAlarm, AlarmAction.RemoveNotifications),
)

private fun enterMissed() = Transition<AlarmState, AlarmAction>(
    AlarmState.Missed,
    listOf(AlarmAction.LeaveAlarmService, AlarmAction.RemoveNotifications, AlarmAction.PostMissedNotification),
)
