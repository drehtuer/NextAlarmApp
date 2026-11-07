package de.drehtuer.nextalarm.domain

import java.time.Duration
import java.time.Instant

/** A calendar on the device that can be chosen as an alarm calendar. */
data class DeviceCalendar(
    val id: CalendarId,
    val name: String,
    val accountName: String?,
    val accountType: String?,
    /** The calendar's own colour, as an ARGB value. */
    val color: Int?,
)

/** At least one of the chosen alarm calendars is no longer on the phone. [calendars] are the ones that are gone. */
class CalendarGoneException(val calendars: Set<CalendarId>) :
    Exception("Calendars ${calendars.map { it.value }} are gone")

/** Reads the calendar provider. A failed read throws, so that it can never be mistaken for "no alarms". */
interface CalendarRepository {
    suspend fun calendars(): List<DeviceCalendar>

    /**
     * The event instances of all of [calendars] in this range, whether or not a calendar app shows those calendars.
     * They can include events that started before [from]. Throws [CalendarGoneException] if any of the calendars does
     * not exist, and reads nothing then. A calendar that exists and has no events simply adds nothing. Asking for no
     * calendars is an error: a planning run knows that none are chosen, and it must not look like "no alarms".
     */
    suspend fun events(calendars: Set<CalendarId>, from: Instant, to: Instant): List<CalendarEvent>
}

interface SettingsRepository {
    /** The stored settings, or [Settings.DEFAULT] if none were saved yet. */
    suspend fun load(): Settings
    suspend fun save(settings: Settings)
}

interface AlarmRepository {
    suspend fun all(): List<AlarmInstance>
    suspend fun find(id: Long): AlarmInstance?

    /** Stores a new alarm in [AlarmState.Scheduled]. */
    suspend fun create(key: AlarmKey, triggerAt: Instant, snapshot: AlarmSnapshot): AlarmInstance
    suspend fun update(instance: AlarmInstance)
    suspend fun delete(id: Long)
}

enum class PlanningFailure { NO_CALENDAR, CALENDAR_GONE, NO_PERMISSION, READ_FAILED, APPLY_FAILED }

/** What the last finished planning run did, for the diagnostics screen. */
data class PlanningRun(
    val startedAt: Instant,
    val duration: Duration,
    /** How many alarms the run found. Empty if it failed. */
    val alarmCount: Int?,
    /** Why the run failed. Empty if it succeeded. */
    val failure: PlanningFailure?,
)

/** The persisted state of the planner state machine. */
data class PlannerRecord(
    val state: PlannerState,
    val generation: Long,
    val consecutiveFailures: Int = 0,
    val lastRun: PlanningRun? = null,
) {
    companion object {
        val INITIAL = PlannerRecord(PlannerState.Unconfigured, generation = 0)
    }
}

interface PlannerRepository {
    /** The stored record, or [PlannerRecord.INITIAL] if none was saved yet. */
    suspend fun load(): PlannerRecord
    suspend fun save(record: PlannerRecord)
}

/** Registers alarms with the system. */
interface AlarmScheduler {
    fun schedule(instanceId: Long, at: Instant)
    fun cancel(instanceId: Long)
}
