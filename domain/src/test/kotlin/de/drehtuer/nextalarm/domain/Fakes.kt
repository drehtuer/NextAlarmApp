package de.drehtuer.nextalarm.domain

import java.time.Duration
import java.time.Instant

class FakeAlarmRepository : AlarmRepository {
    val stored = linkedMapOf<Long, AlarmInstance>()
    var failOnCreate = false
    var failOnDelete = false
    private var nextId = 1L

    override suspend fun all() = stored.values.toList()
    override suspend fun find(id: Long) = stored[id]

    override suspend fun create(key: AlarmKey, triggerAt: Instant, snapshot: AlarmSnapshot): AlarmInstance {
        check(!failOnCreate) { "storage failed" }
        val instance = AlarmInstance(nextId++, key, triggerAt, AlarmState.Scheduled, 0, false, snapshot)
        stored[instance.id] = instance
        return instance
    }

    override suspend fun update(instance: AlarmInstance) {
        stored[instance.id] = instance
    }

    override suspend fun delete(id: Long) {
        check(!failOnDelete) { "storage failed" }
        stored.remove(id)
    }

    fun add(instance: AlarmInstance): AlarmInstance {
        stored[instance.id] = instance
        nextId = maxOf(nextId, instance.id + 1)
        return instance
    }
}

object NoAlarmEffects : AlarmEffects {
    override fun perform(instance: AlarmInstance, action: AlarmAction) = Unit
    override fun showNextAlarm(alarm: AlarmInstance?) = Unit
}

class FakeScheduler : AlarmScheduler {
    val scheduled = linkedMapOf<Long, Instant>()
    val cancelled = mutableListOf<Long>()

    override fun schedule(instanceId: Long, at: Instant) {
        scheduled[instanceId] = at
    }

    override fun cancel(instanceId: Long) {
        scheduled.remove(instanceId)
        cancelled += instanceId
    }
}

class FakeClock(var time: Instant = NOW) {
    fun now() = time
}

class FakePlannerRepository(var record: PlannerRecord = PlannerRecord.INITIAL) : PlannerRepository {
    override suspend fun load() = record
    override suspend fun save(record: PlannerRecord) {
        this.record = record
    }
}

class FakeSettingsRepository(var settings: Settings = settings()) : SettingsRepository {
    override suspend fun load() = settings
    override suspend fun save(settings: Settings) {
        this.settings = settings
    }
}

class FakeCalendarRepository : CalendarRepository {
    var events = listOf<CalendarEvent>()
    var failure: Exception? = null
    var readRange: Pair<Instant, Instant>? = null
    var readCalendar: CalendarId? = null

    override suspend fun calendars() = emptyList<DeviceCalendar>()

    override suspend fun events(calendar: CalendarId, from: Instant, to: Instant): List<CalendarEvent> {
        readCalendar = calendar
        readRange = from to to
        failure?.let { throw it }
        return events
    }
}

class FakePlannerEffects : PlannerEffects {
    val calls = mutableListOf<String>()

    override fun cancelPlanningJob() {
        calls += "cancel"
    }

    override fun startPlanningJob(generation: Long) {
        calls += "start:$generation"
    }

    override fun scheduleRetry(delay: Duration) {
        calls += "retry:${delay.toMinutes()}"
    }

    override fun showFailSafeWarning() {
        calls += "showWarning"
    }

    override fun removeFailSafeWarning() {
        calls += "removeWarning"
    }

    override fun showNextAlarm(alarm: AlarmInstance?) {
        calls += "nextAlarm:${alarm?.id}"
    }
}

/** Counts how the lock is used. Tests run on one coroutine, so it does not have to exclude anyone. */
class FakeLock : EngineLock {
    var uses = 0

    override suspend fun <T> withLock(block: suspend () -> T): T {
        uses++
        return block()
    }
}
