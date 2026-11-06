package de.drehtuer.nextalarm.domain

import de.drehtuer.nextalarm.domain.AlarmAction.CancelAlarm
import de.drehtuer.nextalarm.domain.AlarmAction.IncrementSnoozeCount
import de.drehtuer.nextalarm.domain.AlarmAction.JoinAlarmService
import de.drehtuer.nextalarm.domain.AlarmAction.LeaveAlarmService
import de.drehtuer.nextalarm.domain.AlarmAction.LeaveAllServices
import de.drehtuer.nextalarm.domain.AlarmAction.PostLateNotification
import de.drehtuer.nextalarm.domain.AlarmAction.PostMissedNotification
import de.drehtuer.nextalarm.domain.AlarmAction.PostSnoozeNotification
import de.drehtuer.nextalarm.domain.AlarmAction.PostUnansweredNotification
import de.drehtuer.nextalarm.domain.AlarmAction.RegisterAlarm
import de.drehtuer.nextalarm.domain.AlarmAction.RegisterSnoozeAlarm
import de.drehtuer.nextalarm.domain.AlarmAction.RemoveNotifications
import de.drehtuer.nextalarm.domain.AlarmAction.ReplaceSnapshot
import de.drehtuer.nextalarm.domain.AlarmAction.SetAutoSnoozed
import de.drehtuer.nextalarm.domain.AlarmAction.StartAnnouncement
import de.drehtuer.nextalarm.domain.AlarmAction.StartNews
import de.drehtuer.nextalarm.domain.AlarmEvent.AnnouncementDone
import de.drehtuer.nextalarm.domain.AlarmEvent.Create
import de.drehtuer.nextalarm.domain.AlarmEvent.Dismiss
import de.drehtuer.nextalarm.domain.AlarmEvent.Fire
import de.drehtuer.nextalarm.domain.AlarmEvent.NewsDone
import de.drehtuer.nextalarm.domain.AlarmEvent.Refresh
import de.drehtuer.nextalarm.domain.AlarmEvent.Remove
import de.drehtuer.nextalarm.domain.AlarmEvent.RingTimeout
import de.drehtuer.nextalarm.domain.AlarmEvent.Snooze
import de.drehtuer.nextalarm.domain.AlarmEvent.StopWakeUp
import de.drehtuer.nextalarm.domain.AlarmState.Announcing
import de.drehtuer.nextalarm.domain.AlarmState.Cancelled
import de.drehtuer.nextalarm.domain.AlarmState.Done
import de.drehtuer.nextalarm.domain.AlarmState.Missed
import de.drehtuer.nextalarm.domain.AlarmState.NewsPlaying
import de.drehtuer.nextalarm.domain.AlarmState.Ringing
import de.drehtuer.nextalarm.domain.AlarmState.Scheduled
import de.drehtuer.nextalarm.domain.AlarmState.Snoozed
import de.drehtuer.nextalarm.domain.AlarmState.Unanswered
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * Checks every (state, event) pair against the transition table in docs/state_machines.md, in every guard variant.
 * A pair without an expectation in [expected] must be ignored.
 */
class AlarmStateMachineTest {
    private val onTime = Fire(Duration.ZERO)
    private val atLateNoticeLimit = Fire(Duration.ofMinutes(1))
    private val late = Fire(Duration.ofMinutes(1).plusSeconds(1))
    private val atLatenessLimit = Fire(Duration.ofMinutes(60))
    private val tooLate = Fire(Duration.ofMinutes(60).plusSeconds(1))
    private val refresh = Refresh(snapshot(reason = "New"))

    private val states = listOf(Scheduled, Ringing, Snoozed, Announcing, NewsPlaying, Done, Unanswered, Cancelled, Missed)
    private val events = listOf(
        Create, onTime, atLateNoticeLimit, late, atLatenessLimit, tooLate,
        Snooze, Dismiss, RingTimeout, refresh, Remove, AnnouncementDone, NewsDone, StopWakeUp,
    )

    /** The guards depend on these: G2 on autoSnoozed, G3 and G4 on the snapshot. */
    private val contexts = listOf(false, true).flatMap { autoSnoozed ->
        listOf(
            snapshot(reason = "Flight", speakReason = true, news = NEWS),
            snapshot(reason = "Flight", speakReason = true, news = null),
            snapshot(reason = "Flight", speakReason = false, news = NEWS),
            snapshot(reason = "Flight", speakReason = false, news = null),
            snapshot(reason = null, speakReason = true, news = NEWS),
            snapshot(reason = null, speakReason = true, news = null),
        ).map { AlarmContext(autoSnoozed, it) }
    }

    private val toRinging = Transition<AlarmState, AlarmAction>(Ringing, listOf(JoinAlarmService))
    private val toRingingLate = Transition<AlarmState, AlarmAction>(Ringing, listOf(JoinAlarmService, PostLateNotification))
    private val toMissed = Transition<AlarmState, AlarmAction>(Missed, listOf(LeaveAlarmService, RemoveNotifications, PostMissedNotification))
    private val toCancelled = Transition<AlarmState, AlarmAction>(Cancelled, listOf(CancelAlarm, RemoveNotifications))
    private val toUnanswered = Transition<AlarmState, AlarmAction>(Unanswered, listOf(LeaveAlarmService, CancelAlarm, PostUnansweredNotification))
    private val toAnnouncing = Transition<AlarmState, AlarmAction>(Announcing, listOf(LeaveAlarmService, CancelAlarm, StartAnnouncement))
    private val toNewsPlaying = Transition<AlarmState, AlarmAction>(NewsPlaying, listOf(LeaveAlarmService, CancelAlarm, StartNews))
    private val toDone = Transition<AlarmState, AlarmAction>(Done, listOf(LeaveAllServices, CancelAlarm, RemoveNotifications))

    private fun toSnoozed(context: AlarmContext, auto: Boolean) = Transition<AlarmState, AlarmAction>(
        Snoozed,
        listOf(
            SetAutoSnoozed(auto),
            LeaveAlarmService,
            IncrementSnoozeCount,
            RegisterSnoozeAlarm(context.snapshot.snoozeMinutes),
            PostSnoozeNotification,
        ),
    )

    private fun g3(c: AlarmContext) = when {
        c.snapshot.speakReason && c.snapshot.reason != null -> toAnnouncing
        c.snapshot.news != null -> toNewsPlaying
        else -> toDone
    }

    private fun g4(c: AlarmContext) = if (c.snapshot.news != null) toNewsPlaying else toDone

    private fun fireFromWaiting(event: AlarmEvent) = when (event) {
        onTime, atLateNoticeLimit -> toRinging
        late, atLatenessLimit -> toRingingLate
        else -> toMissed
    }

    private fun expected(state: AlarmState, event: AlarmEvent, c: AlarmContext): Transition<AlarmState, AlarmAction>? = when (state) {
        Scheduled -> when (event) {
            is Fire -> fireFromWaiting(event)
            refresh -> Transition(Scheduled, listOf(ReplaceSnapshot(refresh.snapshot)))
            Remove -> toCancelled
            else -> null
        }
        Ringing -> when (event) {
            tooLate -> toMissed
            is Fire -> toRinging
            Snooze -> toSnoozed(c, auto = false)
            Dismiss -> g3(c)
            RingTimeout -> if (c.autoSnoozed) toUnanswered else toSnoozed(c, auto = true)
            else -> null
        }
        Snoozed -> when (event) {
            is Fire -> fireFromWaiting(event)
            Dismiss -> g3(c)
            else -> null
        }
        Announcing -> when (event) {
            AnnouncementDone -> g4(c)
            StopWakeUp -> toDone
            else -> null
        }
        NewsPlaying -> when (event) {
            NewsDone, StopWakeUp -> toDone
            else -> null
        }
        else -> null
    }

    @TestFactory
    fun `every state and event pair matches the table`(): List<DynamicTest> =
        states.flatMap { state ->
            events.flatMap { event ->
                contexts.map { context ->
                    dynamicTest("$state + $event, auto=${context.autoSnoozed}, ${context.snapshot.reason}/${context.snapshot.speakReason}/${context.snapshot.news != null}") {
                        val want = expected(state, event, context) ?: Transition(state)
                        assertEquals(want, transition(state, event, context))
                    }
                }
            }
        }

    @Test
    fun `the test covers every state and event type`() {
        assertEquals(AlarmState::class.sealedSubclasses.toSet(), states.map { it::class }.toSet())
        assertEquals(AlarmEvent::class.sealedSubclasses.toSet(), events.map { it::class }.toSet())
    }

    @Test
    fun `a new alarm starts scheduled and registered`() {
        assertEquals(Transition<AlarmState, AlarmAction>(Scheduled, listOf(RegisterAlarm)), createAlarm())
    }

    @Test
    fun `a recovered ringing alarm posts no late notification`() {
        val context = contexts.first()
        assertEquals(toRinging, transition(Ringing, late, context))
    }

    @Test
    fun `an alarm unanswered after a manual snooze still gets its automatic snooze`() {
        val first = AlarmContext(autoSnoozed = false, snapshot())
        val afterManual = transition(Ringing, Snooze, first)
        assertEquals(SetAutoSnoozed(false), afterManual.actions.first())
        val afterTimeout = transition(Ringing, RingTimeout, first)
        assertEquals(SetAutoSnoozed(true), afterTimeout.actions.first())
        assertEquals(Unanswered, transition(Ringing, RingTimeout, first.copy(autoSnoozed = true)).state)
    }
}
