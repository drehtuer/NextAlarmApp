package de.drehtuer.nextalarm.domain

import java.time.Duration
import java.time.Instant

/** The rolling planning window. See docs/architecture.md#planning. */
val PLANNING_HORIZON: Duration = Duration.ofHours(48)

data class PlanEntry(
    val key: AlarmKey,
    val triggerAt: Instant,
    val snapshot: AlarmSnapshot,
)

data class Plan(val entries: List<PlanEntry>)

/**
 * Turns event instances into a plan, with one entry per instance that starts in `now <= start < now + 48 h`.
 * Cancelled and all-day events are dropped. Close events are not merged. Entries are ordered by trigger time.
 */
fun plan(now: Instant, events: List<CalendarEvent>, settings: Settings): Plan {
    val horizonEnd = now.plus(PLANNING_HORIZON)
    val entries = events
        .filter { !it.cancelled && !it.allDay }
        .filter { it.instanceBegin >= now && it.instanceBegin < horizonEnd }
        .map {
            PlanEntry(
                key = AlarmKey.forEvent(it.eventId, it.instanceBegin),
                triggerAt = it.instanceBegin,
                snapshot = settings.snapshotFor(it.title),
            )
        }
        .sortedWith(compareBy({ it.triggerAt }, { it.key.value }))
    return Plan(entries)
}

/** What a planning run asks of the alarm state machines. */
sealed interface PlanCommand {
    data class Create(val entry: PlanEntry) : PlanCommand
    data class Refresh(val instanceId: Long, val snapshot: AlarmSnapshot) : PlanCommand
    data class Remove(val instanceId: Long) : PlanCommand
}

/**
 * Compares a plan with the existing alarm instances, following the table in docs/architecture.md#planning.
 * Instances that do not come from the calendar are left out.
 */
fun diff(plan: Plan, existing: List<AlarmInstance>, now: Instant): List<PlanCommand> {
    val calendarInstances = existing.filter { it.key.isFromCalendar }

    // A restored event leaves an old instance behind next to the new one, so a live instance takes precedence.
    val liveByKey = calendarInstances
        .filter { it.state !is AlarmState.Cancelled && !it.canRingAgain(now) }
        .associateBy { it.key }

    val forPlanEntries = plan.entries.mapNotNull { entry ->
        val instance = liveByKey[entry.key]
        when {
            instance == null -> PlanCommand.Create(entry)
            instance.state is AlarmState.Scheduled && instance.snapshot != entry.snapshot ->
                PlanCommand.Refresh(instance.id, entry.snapshot)
            else -> null
        }
    }

    val plannedKeys = plan.entries.map { it.key }.toSet()
    val forRemovedEntries = liveByKey.values
        .filter { it.key !in plannedKeys && it.state is AlarmState.Scheduled && it.triggerAt > now }
        .map { PlanCommand.Remove(it.id) }

    return forPlanEntries + forRemovedEntries
}

/**
 * A forward jump of the clock makes an alarm [AlarmState.Missed] without it having rung. If the clock is corrected, its
 * time is still to come, and it has to ring then. A snoozed alarm has rung, and never rings twice, so its count rules it out.
 */
private fun AlarmInstance.canRingAgain(now: Instant): Boolean =
    state is AlarmState.Missed && snoozeCount == 0 && triggerAt > now
