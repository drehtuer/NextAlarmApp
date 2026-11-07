package de.drehtuer.nextalarm.domain

import java.time.Instant
import java.time.temporal.ChronoUnit

/** The parts of an alarm that need Android services and notifications. They run while the engine holds its lock. */
interface AlarmEffects {
    fun perform(instance: AlarmInstance, action: AlarmAction)

    /** Shows the next alarm, or removes the notification if there is none. */
    fun showNextAlarm(alarm: AlarmInstance?)
}

/**
 * Runs the alarm state machine against stored alarms. docs/state_machines.md is the specification.
 *
 * Alarms get events from many sides at once: the system, the user, the ring timeout, the recovery sweep and the planner.
 * The [lock] keeps each read and write of a state in one piece, so that, say, a `Fire` that read `Ringing` cannot
 * overwrite the `Done` of a `Dismiss` that came in between.
 */
class AlarmEngine(
    private val alarms: AlarmRepository,
    private val scheduler: AlarmScheduler,
    private val effects: AlarmEffects,
    private val now: () -> Instant,
    private val lock: EngineLock,
) {
    suspend fun create(entry: PlanEntry) = lock.withLock {
        val instance = alarms.create(entry.key, entry.triggerAt, entry.snapshot)
        createAlarm().actions.forEach { perform(instance, it) }
    }

    /** Sends [event] to one alarm. The new state is stored first, so that a crash cannot lose it. */
    suspend fun handle(instanceId: Long, event: AlarmEvent) = lock.withLock { send(instanceId, event) }

    /** The system delivered this alarm: sends `Fire` with how late it is. */
    suspend fun fire(instanceId: Long) = lock.withLock {
        val instance = alarms.find(instanceId) ?: return@withLock
        send(instanceId, AlarmEvent.Fire(lateBy(instance.triggerAt, now())))
    }

    /** Snooze and Dismiss on the alarm screen and its notification apply to all ringing alarms. */
    suspend fun sendToRinging(event: AlarmEvent) = lock.withLock {
        alarms.all().filter { it.state == AlarmState.Ringing }.forEach { send(it.id, event) }
    }

    /**
     * Registers every waiting alarm with the system again. Registering twice replaces the first one, and this also
     * repairs an alarm whose registration was interrupted by a cancelled planning run.
     */
    suspend fun registerWaiting() = lock.withLock {
        val time = now()
        alarms.all()
            .filter { (it.state == AlarmState.Scheduled || it.state == AlarmState.Snoozed) && it.triggerAt > time }
            .forEach { scheduler.schedule(it.id, it.triggerAt) }
    }

    /**
     * The recovery sweep, see docs/architecture.md#recovery. A reboot also clears the notifications, so a snoozed
     * alarm gets its own back: it is the only way to dismiss the alarm before it rings again.
     */
    suspend fun recover() = lock.withLock {
        recoverySteps(alarms.all(), now()).forEach { step ->
            when (step) {
                is RecoveryStep.Register -> {
                    scheduler.schedule(step.alarm.id, step.alarm.triggerAt)
                    if (step.alarm.state == AlarmState.Snoozed) perform(step.alarm, AlarmAction.PostSnoozeNotification)
                }
                is RecoveryStep.Send -> send(step.instanceId, step.event)
            }
        }
        showNextAlarm()
    }

    private suspend fun send(instanceId: Long, event: AlarmEvent) {
        val instance = alarms.find(instanceId) ?: return
        val context = AlarmContext(instance.autoSnoozed, instance.snapshot)
        val result = transition(instance.state, event, context)
        if (result.actions.isEmpty()) return

        val updated = result.actions
            .fold(instance.copy(state = result.state), ::applyToInstance)
            .recordLateness(event, result.actions)
        alarms.update(updated)
        result.actions.forEach { perform(updated, it) }
        showNextAlarm()
    }

    private suspend fun showNextAlarm() = effects.showNextAlarm(nextAlarm(alarms.all(), now()))

    private fun applyToInstance(instance: AlarmInstance, action: AlarmAction): AlarmInstance = when (action) {
        is AlarmAction.ReplaceSnapshot -> instance.copy(snapshot = action.snapshot)
        is AlarmAction.SetAutoSnoozed -> instance.copy(autoSnoozed = action.value)
        AlarmAction.IncrementSnoozeCount -> instance.copy(snoozeCount = instance.snoozeCount + 1)
        is AlarmAction.RegisterSnoozeAlarm ->
            instance.copy(triggerAt = now().truncatedTo(ChronoUnit.MINUTES).plusSeconds(action.minutes * SECONDS_PER_MINUTE))
        else -> instance
    }

    /**
     * Only a start that gets the "late alarm" notification counts, so an alarm that rang late keeps that through an
     * on-time snooze, and an alarm that is rung again after a crash does not seem late because it has been ringing.
     */
    private fun AlarmInstance.recordLateness(event: AlarmEvent, actions: List<AlarmAction>): AlarmInstance =
        if (event is AlarmEvent.Fire && AlarmAction.PostLateNotification in actions) copy(lateBy = event.lateBy) else this

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
