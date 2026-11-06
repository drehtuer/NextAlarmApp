package de.drehtuer.nextalarm.domain

import java.time.Duration
import java.time.Instant

/** A calendar on the device that can be chosen as the alarm calendar. */
data class DeviceCalendar(
    val id: CalendarId,
    val name: String,
    val accountName: String?,
    val accountType: String?,
    /** The calendar's own colour, as an ARGB value. */
    val color: Int?,
)

/** Reads the calendar provider. A failed read throws, so that it can never be mistaken for "no alarms". */
interface CalendarRepository {
    suspend fun calendars(): List<DeviceCalendar>

    /** The event instances of [calendar] in this range. They can include events that started before [from]. */
    suspend fun events(calendar: CalendarId, from: Instant, to: Instant): List<CalendarEvent>
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
}

enum class PlanningFailure { NO_CALENDAR, NO_PERMISSION, READ_FAILED, APPLY_FAILED }

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
