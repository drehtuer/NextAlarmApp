package de.drehtuer.nextalarm.domain

import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.coroutines.cancellation.CancellationException

/** The parts of an alarm that need Android services and notifications. They run while the engine holds its lock. */
interface AlarmEffects {
    fun perform(instance: AlarmInstance, action: AlarmAction)

    /** Shows the next alarm, or removes the notification if there is none. */
    fun showNextAlarm(alarm: AlarmInstance?)

    /** The alarms that ring in this process right now. This is known without the database. */
    fun ringing(): List<AlarmInstance> = emptyList()
}

/** Lets each step of a loop fail on its own: the others still run, and the first failure comes out at the end. */
private class Failures {
    private var first: Exception? = null

    val any: Boolean
        get() = first != null

    suspend fun attempt(step: suspend () -> Unit) {
        try {
            step()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val earlier = first
            if (earlier == null) first = failure else earlier.addSuppressed(failure)
        }
    }

    fun rethrow() {
        first?.let { throw it }
    }
}

private suspend fun <T> eachOnItsOwn(items: Iterable<T>, step: suspend (T) -> Unit) {
    val failures = Failures()
    items.forEach { item -> failures.attempt { step(item) } }
    failures.rethrow()
}

/**
 * Runs the alarm state machine against stored alarms. docs/state_machines.md is the specification.
 *
 * Alarms get events from many sides at once: the system, the user, the ring timeout, the recovery sweep and the planner.
 * The [lock] keeps each read and write of a state in one piece, so that, say, a `Fire` that read `Ringing` cannot
 * overwrite the `Done` of a `Dismiss` that came in between.
 *
 * A failure of one alarm does not get in the way of the others. Each call handles every alarm it can, and then throws
 * the first failure, for the caller to report. See docs/architecture.md#when-the-database-fails.
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

    /**
     * Sends [event] to one alarm. The new state is stored first, so that a crash cannot lose it. If that fails, whatever
     * stops the sound still happens: see [runsWithoutStoring].
     */
    suspend fun handle(instanceId: Long, event: AlarmEvent) = lock.withLock { send(instanceId, event) }

    /** The system delivered this alarm: sends `Fire` with how late it is. */
    suspend fun fire(instanceId: Long) = lock.withLock {
        val instance = alarms.find(instanceId) ?: return@withLock
        send(instanceId, AlarmEvent.Fire(lateBy(instance.triggerAt, now())))
    }

    /**
     * Snooze and Dismiss on the alarm screen and its notification apply to all ringing alarms: those stored as ringing,
     * and those that ring in this process, whatever the database says or if it cannot say anything.
     */
    suspend fun sendToRinging(event: AlarmEvent) = lock.withLock {
        val failures = Failures()
        var stored = emptyList<Long>()
        failures.attempt { stored = alarms.all().filter { it.state == AlarmState.Ringing }.map { it.id } }
        (effects.ringing().map { it.id } + stored).distinct().forEach { id -> failures.attempt { send(id, event) } }
        failures.rethrow()
    }

    /** The ring timeout of these alarms ran out. */
    suspend fun ringTimedOut(instanceIds: List<Long>) = lock.withLock {
        eachOnItsOwn(instanceIds) { send(it, AlarmEvent.RingTimeout) }
    }

    /** The user stopped the wake-up automation, which all the alarms in it share. */
    suspend fun stopWakeUp() = lock.withLock {
        eachOnItsOwn(alarms.all().filter { it.state == AlarmState.Announcing || it.state == AlarmState.NewsPlaying }) {
            send(it.id, AlarmEvent.StopWakeUp)
        }
    }

    /**
     * Registers every waiting alarm with the system again. Registering twice replaces the first one, and this also
     * repairs an alarm whose registration was interrupted by a cancelled planning run.
     */
    suspend fun registerWaiting() = lock.withLock {
        val time = now()
        eachOnItsOwn(
            alarms.all().filter { (it.state == AlarmState.Scheduled || it.state == AlarmState.Snoozed) && it.triggerAt > time },
        ) { scheduler.schedule(it.id, it.triggerAt) }
    }

    /**
     * The recovery sweep, see docs/architecture.md#recovery. A reboot also clears the notifications, so a snoozed
     * alarm gets its own back: it is the only way to dismiss the alarm before it rings again.
     */
    suspend fun recover() = lock.withLock {
        val failures = Failures()
        recoverySteps(alarms.all(), now()).forEach { step -> failures.attempt { recoverStep(step) } }
        failures.attempt { showNextAlarm() }
        failures.rethrow()
    }

    private suspend fun recoverStep(step: RecoveryStep) {
        when (step) {
            is RecoveryStep.Register -> {
                scheduler.schedule(step.alarm.id, step.alarm.triggerAt)
                if (step.alarm.state == AlarmState.Snoozed) perform(step.alarm, AlarmAction.PostSnoozeNotification)
            }
            is RecoveryStep.Send -> send(step.instanceId, step.event)
        }
    }

    /** Deletes the alarms the history no longer keeps. See [expiredAlarms]. */
    suspend fun removeExpired() = lock.withLock {
        expiredAlarms(alarms.all(), now()).forEach { alarms.delete(it.id) }
    }

    private suspend fun send(instanceId: Long, event: AlarmEvent) {
        // What rings here is known without the database, and is right even if an earlier write failed.
        val instance = effects.ringing().firstOrNull { it.id == instanceId } ?: alarms.find(instanceId) ?: return
        val context = AlarmContext(instance.autoSnoozed, instance.snapshot)
        val result = transition(instance.state, event, context)
        if (result.actions.isEmpty()) return

        val updated = result.actions
            .fold(instance.copy(state = result.state), ::applyToInstance)
            .recordLateness(event, result.actions)
        val failures = Failures()
        if (!updated.key.isEmergency) failures.attempt { alarms.update(updated) }
        val actions = if (failures.any) result.actions.filter { runsWithoutStoring(event, it) } else result.actions
        actions.forEach { action -> failures.attempt { perform(updated, action) } }
        failures.attempt { showNextAlarm() }
        failures.rethrow()
    }

    /**
     * What still happens if the new state could not be stored. The sound has to start and stop whatever the database does,
     * and a snooze has to ring again. The rest would not survive the stored state being behind:
     * - A planning run that removes an alarm must keep it (fail-safe): the run fails, and the next one tries again.
     * - A wake-up automation that starts now could not be stopped, as `stopWakeUp` goes by the stored state.
     */
    private fun runsWithoutStoring(event: AlarmEvent, action: AlarmAction): Boolean = when {
        event == AlarmEvent.Remove -> false
        event == AlarmEvent.Dismiss -> action != AlarmAction.StartAnnouncement && action != AlarmAction.StartNews
        else -> true
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
