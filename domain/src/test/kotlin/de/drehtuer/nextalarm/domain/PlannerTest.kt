package de.drehtuer.nextalarm.domain

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

class PlannerTest {
    private fun event(id: Long, offset: Duration, title: String? = "Title", allDay: Boolean = false, cancelled: Boolean = false) =
        CalendarEvent(id, NOW.plus(offset), title, allDay, cancelled)

    private fun keptIds(vararg events: CalendarEvent) =
        plan(NOW, events.toList(), settings()).entries.map { it.key.value.split(":")[1].toLong() }

    @Test
    fun `an event becomes an entry at its start time, with its title and the settings`() {
        val event = event(1, Duration.ofHours(2), title = "Flight")
        val entry = plan(NOW, listOf(event), settings()).entries.single()
        assertEquals(AlarmKey.forEvent(1, event.instanceBegin), entry.key)
        assertEquals(event.instanceBegin, entry.triggerAt)
        assertEquals(settings().snapshotFor("Flight"), entry.snapshot)
    }

    @Test
    fun `no events give an empty plan`() {
        assertEquals(Plan(emptyList()), plan(NOW, emptyList(), settings()))
    }

    @Test
    fun `the horizon includes now and excludes 48 hours ahead`() {
        val ids = keptIds(
            event(1, Duration.ofMillis(-1)),
            event(2, Duration.ZERO),
            event(3, Duration.ofHours(48).minusMillis(1)),
            event(4, Duration.ofHours(48)),
        )
        assertEquals(listOf(2L, 3L), ids)
    }

    @Test
    fun `an event that started earlier and is still running is not planned`() {
        assertEquals(emptyList(), keptIds(event(1, Duration.ofMinutes(-2))))
    }

    @Test
    fun `cancelled and all-day events are dropped`() {
        val ids = keptIds(
            event(1, Duration.ofHours(1), cancelled = true),
            event(2, Duration.ofHours(1), allDay = true),
            event(3, Duration.ofHours(1)),
        )
        assertEquals(listOf(3L), ids)
    }

    @Test
    fun `close events are not merged, even at the same time`() {
        val ids = keptIds(
            event(1, Duration.ofHours(1)),
            event(2, Duration.ofHours(1)),
            event(3, Duration.ofHours(1).plusSeconds(1)),
        )
        assertEquals(listOf(1L, 2L, 3L), ids)
    }

    @Test
    fun `entries are ordered by trigger time`() {
        val ids = keptIds(event(1, Duration.ofHours(3)), event(2, Duration.ofHours(1)), event(3, Duration.ofHours(2)))
        assertEquals(listOf(2L, 3L, 1L), ids)
    }
}

class DiffTest {
    private val future = NOW.plus(Duration.ofHours(2))
    private val key = AlarmKey.forEvent(1, future)
    private val entry = PlanEntry(key, future, snapshot())
    private val planWithEntry = Plan(listOf(entry))
    private val emptyPlan = Plan(emptyList())

    private fun instance(state: AlarmState, id: Long = 10, snapshot: AlarmSnapshot = entry.snapshot, triggerAt: java.time.Instant = future, key: AlarmKey = this.key) =
        AlarmInstance(id, key, triggerAt, state, snoozeCount = 0, autoSnoozed = false, snapshot = snapshot)

    private val startedStates = listOf(AlarmState.Ringing, AlarmState.Snoozed, AlarmState.Announcing, AlarmState.NewsPlaying)
    private val finishedStates = listOf(AlarmState.Done, AlarmState.Unanswered, AlarmState.Missed)

    @Test
    fun `a new entry is created`() {
        assertEquals(listOf(PlanCommand.Create(entry)), diff(planWithEntry, emptyList(), NOW))
    }

    @Test
    fun `an unchanged scheduled alarm needs nothing`() {
        assertEquals(emptyList(), diff(planWithEntry, listOf(instance(AlarmState.Scheduled)), NOW))
    }

    @Test
    fun `a scheduled alarm with a different snapshot is refreshed`() {
        val old = snapshot(reason = "Old")
        assertEquals(
            listOf(PlanCommand.Refresh(10, entry.snapshot)),
            diff(planWithEntry, listOf(instance(AlarmState.Scheduled, snapshot = old)), NOW),
        )
    }

    @Test
    fun `a scheduled alarm no longer in the plan is removed`() {
        assertEquals(listOf(PlanCommand.Remove(10)), diff(emptyPlan, listOf(instance(AlarmState.Scheduled)), NOW))
    }

    @Test
    fun `a moved event is one remove and one create`() {
        val moved = PlanEntry(AlarmKey.forEvent(1, future.plusSeconds(600)), future.plusSeconds(600), snapshot())
        assertEquals(
            listOf(PlanCommand.Create(moved), PlanCommand.Remove(10)),
            diff(Plan(listOf(moved)), listOf(instance(AlarmState.Scheduled)), NOW),
        )
    }

    @Test
    fun `an overdue scheduled alarm is not removed`() {
        val overdue = instance(AlarmState.Scheduled, triggerAt = NOW.minusSeconds(60))
        assertEquals(emptyList(), diff(emptyPlan, listOf(overdue), NOW))
    }

    @Test
    fun `a scheduled alarm whose trigger is exactly now is not removed`() {
        assertEquals(emptyList(), diff(emptyPlan, listOf(instance(AlarmState.Scheduled, triggerAt = NOW)), NOW))
    }

    @Test
    fun `started alarms are not changed by the plan`() {
        val old = snapshot(reason = "Old")
        startedStates.forEach { state ->
            assertEquals(emptyList(), diff(planWithEntry, listOf(instance(state, snapshot = old)), NOW), "in plan: $state")
            assertEquals(emptyList(), diff(emptyPlan, listOf(instance(state)), NOW), "not in plan: $state")
        }
    }

    @Test
    fun `finished alarms never ring twice`() {
        finishedStates.forEach { state ->
            assertEquals(emptyList(), diff(planWithEntry, listOf(instance(state)), NOW), "in plan: $state")
            assertEquals(emptyList(), diff(emptyPlan, listOf(instance(state)), NOW), "not in plan: $state")
        }
    }

    @Test
    fun `a restored event is created again`() {
        assertEquals(listOf(PlanCommand.Create(entry)), diff(planWithEntry, listOf(instance(AlarmState.Cancelled)), NOW))
    }

    @Test
    fun `a cancelled alarm not in the plan needs nothing`() {
        assertEquals(emptyList(), diff(emptyPlan, listOf(instance(AlarmState.Cancelled)), NOW))
    }

    @Test
    fun `a live instance takes precedence over a cancelled one with the same key`() {
        val instances = listOf(instance(AlarmState.Cancelled, id = 10), instance(AlarmState.Scheduled, id = 11))
        assertEquals(emptyList(), diff(planWithEntry, instances, NOW))
        assertEquals(emptyList(), diff(planWithEntry, instances.reversed(), NOW))
    }

    @Test
    fun `the test alarm is left out of the diff`() {
        val testAlarm = instance(AlarmState.Scheduled, key = AlarmKey.forTestAlarm(1))
        assertEquals(emptyList(), diff(emptyPlan, listOf(testAlarm), NOW))
    }
}
