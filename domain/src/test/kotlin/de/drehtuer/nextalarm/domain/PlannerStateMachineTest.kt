package de.drehtuer.nextalarm.domain

import de.drehtuer.nextalarm.domain.PlannerAction.ApplyPlan
import de.drehtuer.nextalarm.domain.PlannerAction.CancelPlanningJob
import de.drehtuer.nextalarm.domain.PlannerAction.RemoveFailSafeWarning
import de.drehtuer.nextalarm.domain.PlannerAction.ScheduleRetry
import de.drehtuer.nextalarm.domain.PlannerAction.ShowFailSafeWarning
import de.drehtuer.nextalarm.domain.PlannerAction.StartPlanningJob
import de.drehtuer.nextalarm.domain.PlannerEvent.PlanFailed
import de.drehtuer.nextalarm.domain.PlannerEvent.PlanSucceeded
import de.drehtuer.nextalarm.domain.PlannerEvent.ReplanRequested
import de.drehtuer.nextalarm.domain.PlannerEvent.SetupCompleted
import de.drehtuer.nextalarm.domain.PlannerState.FailSafe
import de.drehtuer.nextalarm.domain.PlannerState.Planned
import de.drehtuer.nextalarm.domain.PlannerState.Planning
import de.drehtuer.nextalarm.domain.PlannerState.Unconfigured
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory

/** Checks every (state, event) pair against the transition table in docs/state_machines.md. */
class PlannerStateMachineTest {
    private val context = PlannerContext(generation = 5)
    private val current = 5L
    private val stale = 4L

    private val states = listOf(Unconfigured, Planning, Planned, FailSafe)
    private val events = listOf(
        SetupCompleted,
        ReplanRequested,
        PlanSucceeded(current),
        PlanSucceeded(stale),
        PlanFailed(current),
        PlanFailed(stale),
    )

    private val toPlanning = Transition<PlannerState, PlannerAction>(Planning, listOf(CancelPlanningJob, StartPlanningJob(6)))
    private val toPlanned = Transition<PlannerState, PlannerAction>(Planned, listOf(ApplyPlan, RemoveFailSafeWarning))
    private val toFailSafe = Transition<PlannerState, PlannerAction>(FailSafe, listOf(ShowFailSafeWarning, ScheduleRetry))

    /** Every pair that is not listed here must be ignored. */
    private val table: Map<Pair<PlannerState, PlannerEvent>, Transition<PlannerState, PlannerAction>> = mapOf(
        (Unconfigured to SetupCompleted) to toPlanning,
        (Planning to SetupCompleted) to toPlanning,
        (Planning to ReplanRequested) to toPlanning,
        (Planning to PlanSucceeded(current)) to toPlanned,
        (Planning to PlanFailed(current)) to toFailSafe,
        (Planned to SetupCompleted) to toPlanning,
        (Planned to ReplanRequested) to toPlanning,
        (FailSafe to SetupCompleted) to toPlanning,
        (FailSafe to ReplanRequested) to toPlanning,
    )

    @TestFactory
    fun `every state and event pair matches the table`(): List<DynamicTest> =
        states.flatMap { state ->
            events.map { event ->
                dynamicTest("$state + $event") {
                    val expected = table[state to event] ?: Transition(state)
                    assertEquals(expected, transition(state, event, context))
                }
            }
        }

    @Test
    fun `the test covers every state and event type`() {
        assertEquals(PlannerState::class.sealedSubclasses.toSet(), states.map { it::class }.toSet())
        assertEquals(PlannerEvent::class.sealedSubclasses.toSet(), events.map { it::class }.toSet())
    }
}
