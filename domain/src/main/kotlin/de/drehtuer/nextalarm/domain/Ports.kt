package de.drehtuer.nextalarm.domain

import java.time.Instant

/** A calendar on the device that can be chosen as the alarm calendar. */
data class DeviceCalendar(
    val id: CalendarId,
    val name: String,
    val accountName: String?,
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

/** The persisted state of the planner state machine. */
data class PlannerRecord(
    val state: PlannerState,
    val generation: Long,
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
