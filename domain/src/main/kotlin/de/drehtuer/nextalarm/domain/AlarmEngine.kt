package de.drehtuer.nextalarm.domain

import java.time.Instant

/** The parts of an alarm that need Android services and notifications. */
fun interface AlarmEffects {
    fun perform(instance: AlarmInstance, action: AlarmAction)
}

/** Runs the alarm state machine against stored alarms. docs/state_machines.md is the specification. */
class AlarmEngine(
    private val alarms: AlarmRepository,
    private val scheduler: AlarmScheduler,
    private val effects: AlarmEffects,
    private val now: () -> Instant,
) {
    suspend fun create(entry: PlanEntry) {
        val instance = alarms.create(entry.key, entry.triggerAt, entry.snapshot)
        createAlarm().actions.forEach { perform(instance, it) }
    }

    /** Sends [event] to one alarm. The new state is stored first, so that a crash cannot lose it. */
    suspend fun handle(instanceId: Long, event: AlarmEvent) {
        val instance = alarms.find(instanceId) ?: return
        val context = AlarmContext(instance.autoSnoozed, instance.snapshot)
        val result = transition(instance.state, event, context)
        if (result.actions.isEmpty()) return

        val updated = result.actions.fold(instance.copy(state = result.state), ::applyToInstance)
        alarms.update(updated)
        result.actions.forEach { perform(updated, it) }
    }

    /**
     * Registers every waiting alarm with the system again. Registering twice replaces the first one, and this also
     * repairs an alarm whose registration was interrupted by a cancelled planning run.
     */
    suspend fun registerWaiting() {
        val time = now()
        alarms.all()
            .filter { (it.state == AlarmState.Scheduled || it.state == AlarmState.Snoozed) && it.triggerAt > time }
            .forEach { scheduler.schedule(it.id, it.triggerAt) }
    }

    private fun applyToInstance(instance: AlarmInstance, action: AlarmAction): AlarmInstance = when (action) {
        is AlarmAction.ReplaceSnapshot -> instance.copy(snapshot = action.snapshot)
        is AlarmAction.SetAutoSnoozed -> instance.copy(autoSnoozed = action.value)
        AlarmAction.IncrementSnoozeCount -> instance.copy(snoozeCount = instance.snoozeCount + 1)
        is AlarmAction.RegisterSnoozeAlarm ->
            instance.copy(triggerAt = now().plusSeconds(action.minutes * SECONDS_PER_MINUTE))
        else -> instance
    }

    private fun perform(instance: AlarmInstance, action: AlarmAction) {
        when (action) {
            AlarmAction.RegisterAlarm, is AlarmAction.RegisterSnoozeAlarm ->
                scheduler.schedule(instance.id, instance.triggerAt)
            AlarmAction.CancelAlarm -> scheduler.cancel(instance.id)
            is AlarmAction.ReplaceSnapshot, is AlarmAction.SetAutoSnoozed, AlarmAction.IncrementSnoozeCount -> Unit
            AlarmAction.JoinAlarmService, AlarmAction.LeaveAlarmService, AlarmAction.LeaveAllServices,
            AlarmAction.StartAnnouncement, AlarmAction.StartNews, AlarmAction.PostSnoozeNotification,
            AlarmAction.PostLateNotification, AlarmAction.PostMissedNotification,
            AlarmAction.PostUnansweredNotification, AlarmAction.RemoveNotifications,
            -> effects.perform(instance, action)
        }
    }

    private companion object {
        const val SECONDS_PER_MINUTE = 60L
    }
}
