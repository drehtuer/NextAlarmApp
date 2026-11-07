package de.drehtuer.nextalarm.domain

import java.time.Duration
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException

/** The parts of planning that need Android: background jobs and the warning notification. */
interface PlannerEffects {
    fun cancelPlanningJob()
    fun startPlanningJob(generation: Long)
    fun scheduleRetry(delay: Duration)
    fun showFailSafeWarning()
    fun removeFailSafeWarning()

    /** Shows the next alarm, or removes the notification if there is none. */
    fun showNextAlarm(alarm: AlarmInstance?)
}

/**
 * Lets only one caller at a time work on the state of an engine. The planner and the alarm engine each have their own,
 * and the planner calls into the alarm engine while it holds its lock, so never the other way round.
 */
interface EngineLock {
    suspend fun <T> withLock(block: suspend () -> T): T
}

/**
 * Runs the planner state machine: it decides when a planning run starts, and applies its result.
 * A failure of any kind keeps the alarms that are already planned. See docs/architecture.md#planning.
 */
class PlannerEngine(
    private val planner: PlannerRepository,
    private val settings: SettingsRepository,
    private val calendars: CalendarRepository,
    private val alarms: AlarmRepository,
    private val alarmEngine: AlarmEngine,
    private val effects: PlannerEffects,
    private val lock: EngineLock,
    private val now: () -> Instant,
) {
    suspend fun setupCompleted() = lock.withLock { request(PlannerEvent.SetupCompleted) }

    suspend fun replanRequested() = lock.withLock { request(PlannerEvent.ReplanRequested) }

    /** The body of a planning job. Reading happens outside the lock, so that it cannot hold up other callers. */
    suspend fun runPlanning(generation: Long) {
        val startedAt = now()
        val result = try {
            Result.success(readPlan(startedAt))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        }
        lock.withLock {
            val plan = result.getOrNull()
            if (plan != null) {
                finishSucceeded(generation, plan, startedAt)
            } else {
                finishFailed(generation, classify(result.exceptionOrNull()), startedAt)
            }
        }
    }

    private suspend fun readPlan(at: Instant): Plan {
        val current = settings.load()
        val calendar = current.alarmCalendar ?: throw NoCalendarChosen()
        return plan(at, calendars.events(calendar, at, at.plus(PLANNING_HORIZON)), current)
    }

    private fun classify(failure: Throwable?): PlanningFailure = when (failure) {
        is NoCalendarChosen -> PlanningFailure.NO_CALENDAR
        is SecurityException -> PlanningFailure.NO_PERMISSION
        else -> PlanningFailure.READ_FAILED
    }

    private suspend fun request(event: PlannerEvent) {
        val record = planner.load()
        val result = transition(record.state, event, PlannerContext(record.generation))
        val generation = result.actions.filterIsInstance<PlannerAction.StartPlanningJob>()
            .firstOrNull()?.generation ?: record.generation
        // Stored before the job starts, so that the job's result is never taken for a stale one.
        val updated = record.copy(state = result.state, generation = generation)
        planner.save(updated)
        perform(result, updated)
    }

    private suspend fun finishSucceeded(generation: Long, plan: Plan, startedAt: Instant) {
        val record = planner.load()
        val event = PlannerEvent.PlanSucceeded(generation)
        val result = transition(record.state, event, PlannerContext(record.generation))
        if (result.actions.isEmpty()) return

        try {
            applyPlan(plan)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            finishFailed(generation, PlanningFailure.APPLY_FAILED, startedAt)
            return
        }
        val updated = record.copy(
            state = result.state,
            consecutiveFailures = 0,
            lastRun = PlanningRun(startedAt, Duration.between(startedAt, now()), plan.entries.size, null),
        )
        planner.save(updated)
        perform(result, updated)
        effects.showNextAlarm(nextAlarm(alarms.all(), now()))
        removeExpiredAlarms()
    }

    /** Only tidies the history, so it cannot make a run fail. What is left behind goes in the next run. */
    private suspend fun removeExpiredAlarms() {
        try {
            alarmEngine.removeExpired()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The plan has been applied by now, and nothing depends on old alarms being gone.
        }
    }

    private suspend fun finishFailed(generation: Long, failure: PlanningFailure, startedAt: Instant) {
        val record = planner.load()
        val event = PlannerEvent.PlanFailed(generation)
        val result = transition(record.state, event, PlannerContext(record.generation))
        if (result.actions.isEmpty()) return

        val updated = record.copy(
            state = result.state,
            consecutiveFailures = record.consecutiveFailures + 1,
            lastRun = PlanningRun(startedAt, Duration.between(startedAt, now()), null, failure),
        )
        planner.save(updated)
        perform(result, updated)
    }

    private suspend fun applyPlan(plan: Plan) {
        diff(plan, alarms.all(), now()).forEach { command ->
            when (command) {
                is PlanCommand.Create -> alarmEngine.create(command.entry)
                is PlanCommand.Refresh -> alarmEngine.handle(command.instanceId, AlarmEvent.Refresh(command.snapshot))
                is PlanCommand.Remove -> alarmEngine.handle(command.instanceId, AlarmEvent.Remove)
            }
        }
        alarmEngine.registerWaiting()
    }

    private fun perform(result: Transition<PlannerState, PlannerAction>, record: PlannerRecord) {
        result.actions.forEach { action ->
            when (action) {
                PlannerAction.CancelPlanningJob -> effects.cancelPlanningJob()
                is PlannerAction.StartPlanningJob -> effects.startPlanningJob(action.generation)
                PlannerAction.ApplyPlan -> Unit // Already done, because its outcome decides the next state.
                PlannerAction.RemoveFailSafeWarning -> effects.removeFailSafeWarning()
                PlannerAction.ShowFailSafeWarning -> effects.showFailSafeWarning()
                PlannerAction.ScheduleRetry -> effects.scheduleRetry(retryDelay(record.consecutiveFailures))
            }
        }
    }

    private class NoCalendarChosen : Exception()
}
