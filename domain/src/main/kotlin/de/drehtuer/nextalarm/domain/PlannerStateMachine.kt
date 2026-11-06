package de.drehtuer.nextalarm.domain

/** The planner state machine. docs/state_machines.md is the specification. */
sealed interface PlannerState {
    data object Unconfigured : PlannerState
    data object Planning : PlannerState
    data object Planned : PlannerState
    data object FailSafe : PlannerState
}

sealed interface PlannerEvent {
    data object SetupCompleted : PlannerEvent
    data object ReplanRequested : PlannerEvent
    data class PlanSucceeded(val generation: Long) : PlannerEvent
    data class PlanFailed(val generation: Long) : PlannerEvent
}

sealed interface PlannerAction {
    data object CancelPlanningJob : PlannerAction

    /** Starts a planning job. The caller stores [generation] as the current one. */
    data class StartPlanningJob(val generation: Long) : PlannerAction
    data object ApplyPlan : PlannerAction
    data object RemoveFailSafeWarning : PlannerAction
    data object ShowFailSafeWarning : PlannerAction
    data object ScheduleRetry : PlannerAction
}

/** [generation] is the current planning run. A result from any other run is stale. */
data class PlannerContext(val generation: Long)

fun transition(
    state: PlannerState,
    event: PlannerEvent,
    context: PlannerContext,
): Transition<PlannerState, PlannerAction> {
    val ignore = Transition<PlannerState, PlannerAction>(state)
    return when (state) {
        PlannerState.Unconfigured -> when (event) {
            PlannerEvent.SetupCompleted -> enterPlanning(context)
            PlannerEvent.ReplanRequested, is PlannerEvent.PlanSucceeded, is PlannerEvent.PlanFailed -> ignore
        }
        PlannerState.Planning -> when (event) {
            PlannerEvent.SetupCompleted, PlannerEvent.ReplanRequested -> enterPlanning(context)
            is PlannerEvent.PlanSucceeded -> if (event.generation == context.generation) enterPlanned() else ignore
            is PlannerEvent.PlanFailed -> if (event.generation == context.generation) enterFailSafe() else ignore
        }
        PlannerState.Planned, PlannerState.FailSafe -> when (event) {
            PlannerEvent.SetupCompleted, PlannerEvent.ReplanRequested -> enterPlanning(context)
            is PlannerEvent.PlanSucceeded, is PlannerEvent.PlanFailed -> ignore
        }
    }
}

private fun enterPlanning(context: PlannerContext) = Transition<PlannerState, PlannerAction>(
    PlannerState.Planning,
    listOf(PlannerAction.CancelPlanningJob, PlannerAction.StartPlanningJob(context.generation + 1)),
)

private fun enterPlanned() = Transition<PlannerState, PlannerAction>(
    PlannerState.Planned,
    listOf(PlannerAction.ApplyPlan, PlannerAction.RemoveFailSafeWarning),
)

private fun enterFailSafe() = Transition<PlannerState, PlannerAction>(
    PlannerState.FailSafe,
    listOf(PlannerAction.ShowFailSafeWarning, PlannerAction.ScheduleRetry),
)
