package de.drehtuer.nextalarm.domain

import java.time.Duration
import java.time.Instant

class FakeAlarmRepository : AlarmRepository {
    val stored = linkedMapOf<Long, AlarmInstance>()
    var failOnCreate = false
    var failOnDelete = false
    var failOnRead = false

    /** The alarms whose new state cannot be stored. */
    var failOnUpdate: (Long) -> Boolean = { false }
    private var nextId = 1L

    override suspend fun all(): List<AlarmInstance> {
        check(!failOnRead) { "storage failed" }
        return stored.values.toList()
    }

    override suspend fun find(id: Long): AlarmInstance? {
        check(!failOnRead) { "storage failed" }
        return stored[id]
    }

    override suspend fun create(key: AlarmKey, triggerAt: Instant, snapshot: AlarmSnapshot): AlarmInstance {
        check(!failOnCreate) { "storage failed" }
        val instance = AlarmInstance(nextId++, key, triggerAt, AlarmState.Scheduled, 0, false, snapshot)
        stored[instance.id] = instance
        return instance
    }

    override suspend fun update(instance: AlarmInstance) {
        check(!failOnUpdate(instance.id)) { "storage failed" }
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

class FakeCalls(var isActive: Boolean = false) : PhoneCalls {
    var failure: RuntimeException? = null
    var failToWatch: RuntimeException? = null
    val watching = mutableListOf<(Boolean) -> Unit>()

    override fun active(): Boolean {
        failure?.let { throw it }
        return isActive
    }

    override fun watch(onChange: (Boolean) -> Unit): () -> Unit {
        failToWatch?.let { throw it }
        watching += onChange
        return { watching -= onChange }
    }

    /** A call starts or ends, and Android says so. */
    fun change(active: Boolean) {
        isActive = active
        watching.toList().forEach { it(active) }
    }
}

class FakeFocus : AudioFocus {
    var requests = 0
    var abandons = 0
    var answer = true
    var failure: RuntimeException? = null
    var onLost: () -> Unit = {}

    override fun request(onLostForGood: () -> Unit): Boolean {
        requests++
        onLost = onLostForGood
        failure?.let { throw it }
        return answer
    }

    override fun abandon() {
        abandons++
        failure?.let { throw it }
    }
}

object NoAlarmEffects : AlarmEffects {
    override fun perform(instance: AlarmInstance, action: AlarmAction) = Unit
    override fun showNextAlarm(alarm: AlarmInstance?) = Unit
}

class FakeScheduler : AlarmScheduler {
    val scheduled = linkedMapOf<Long, Instant>()
    val cancelled = mutableListOf<Long>()

    /** The alarms the system refuses to register. */
    var failFor: Set<Long> = emptySet()

    override fun schedule(instanceId: Long, at: Instant) {
        check(instanceId !in failFor) { "not registered" }
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
    var readCalendars: Set<CalendarId>? = null

    /** If set, what each calendar on the phone holds: a run gets what the calendars it asks for hold, and others are gone. */
    var onThePhone: Map<CalendarId, List<CalendarEvent>>? = null

    override suspend fun calendars() = emptyList<DeviceCalendar>()

    override suspend fun events(calendars: Set<CalendarId>, from: Instant, to: Instant): List<CalendarEvent> {
        readCalendars = calendars
        readRange = from to to
        failure?.let { throw it }
        val phone = onThePhone ?: return events
        val gone = calendars - phone.keys
        if (gone.isNotEmpty()) throw CalendarGoneException(gone)
        return calendars.flatMap { phone.getValue(it) }
    }
}

class FakePlannerEffects : PlannerEffects {
    val calls = mutableListOf<String>()
    var warnedAbout: PlanningFailure? = null

    override fun cancelPlanningJob() {
        calls += "cancel"
    }

    override fun startPlanningJob(generation: Long) {
        calls += "start:$generation"
    }

    override fun scheduleRetry(delay: Duration) {
        calls += "retry:${delay.toMinutes()}"
    }

    override fun showFailSafeWarning(failure: PlanningFailure?) {
        calls += "showWarning"
        warnedAbout = failure
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
