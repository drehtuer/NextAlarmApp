package de.drehtuer.nextalarm.domain

import java.io.IOException
import java.time.Duration
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class PlannerEngineTest {
    private val planner = FakePlannerRepository()
    private val settingsRepo = FakeSettingsRepository()
    private val calendars = FakeCalendarRepository()
    private val alarms = FakeAlarmRepository()
    private val scheduler = FakeScheduler()
    private val clock = FakeClock()
    private val effects = FakePlannerEffects()
    private val lock = FakeLock()
    private val alarmEngine = AlarmEngine(alarms, scheduler, NoAlarmEffects, clock::now, FakeLock())
    private val engine = PlannerEngine(planner, settingsRepo, calendars, alarms, alarmEngine, effects, lock, clock::now)

    private val future = NOW.plus(Duration.ofHours(2))
    private val event = CalendarEvent(1, future, "Flight")

    private fun planning(generation: Long = 1, failures: Int = 0) {
        planner.record = PlannerRecord(PlannerState.Planning, generation, failures)
    }

    private fun failFrom(exception: Exception) {
        calendars.failure = exception
    }

    @Test
    fun `a calendar that is gone is a failed run that keeps every alarm, and the warning says why`() = runBlocking {
        settingsRepo.settings = settings()
        alarms.add(AlarmInstance(1, AlarmKey.forEvent(1, future), future, AlarmState.Scheduled, 0, false, snapshot()))
        planning()
        failFrom(CalendarGoneException(setOf(CalendarId(1))))
        engine.runPlanning(1)
        assertEquals(PlannerState.FailSafe, planner.record.state)
        assertEquals(PlanningFailure.CALENDAR_GONE, planner.record.lastRun?.failure)
        assertEquals(PlanningFailure.CALENDAR_GONE, effects.warnedAbout)
        assertEquals(AlarmState.Scheduled, alarms.find(1)?.state)
    }

    @Test
    fun `a calendar that exists and has no events is a valid empty result, which removes what it no longer holds`() = runBlocking {
        settingsRepo.settings = settings()
        alarms.add(AlarmInstance(1, AlarmKey.forEvent(1, future), future, AlarmState.Scheduled, 0, false, snapshot()))
        planning()
        engine.runPlanning(1)
        assertEquals(PlannerState.Planned, planner.record.state)
        assertEquals(AlarmState.Cancelled, alarms.find(1)?.state)
    }

    @Test
    fun `setup starts the first planning run`() = runBlocking {
        engine.setupCompleted()
        assertEquals(PlannerRecord(PlannerState.Planning, 1), planner.record)
        assertEquals(listOf("cancel", "start:1"), effects.calls)
        assertEquals(1, lock.uses)
    }

    @Test
    fun `a re-plan request before setup does nothing`() = runBlocking {
        engine.replanRequested()
        assertEquals(PlannerRecord.INITIAL, planner.record)
        assertTrue(effects.calls.isEmpty())
    }

    @Test
    fun `a re-plan request starts a newer run and keeps the last run's details`() = runBlocking {
        val lastRun = PlanningRun(NOW, Duration.ZERO, 2, null)
        planner.record = PlannerRecord(PlannerState.Planned, 4, lastRun = lastRun)
        engine.replanRequested()
        assertEquals(PlannerRecord(PlannerState.Planning, 5, lastRun = lastRun), planner.record)
        assertEquals(listOf("cancel", "start:5"), effects.calls)
    }

    private val first = CalendarId(1)
    private val second = CalendarId(2)
    private val laterEvent = CalendarEvent(2, future.plusSeconds(60), "Special case")

    private fun chosen(calendars: Set<CalendarId>) {
        settingsRepo.settings = settings().copy(alarmCalendars = calendars)
    }

    private suspend fun run(generation: Long = 1) {
        planning(generation)
        engine.runPlanning(generation)
    }

    @Test
    fun `a run reads all the chosen calendars at once, and plans the events of all of them`() = runBlocking {
        chosen(setOf(first, second))
        calendars.onThePhone = mapOf(first to listOf(event), second to listOf(laterEvent))
        run()
        assertEquals(setOf(first, second), calendars.readCalendars)
        assertEquals(PlannerState.Planned, planner.record.state)
        assertEquals(2, planner.record.lastRun?.alarmCount)
        assertEquals(listOf("Flight", "Special case"), alarms.all().map { it.snapshot.reason })
    }

    @Test
    fun `an empty calendar among several adds nothing and is no failure`() = runBlocking {
        chosen(setOf(first, second))
        calendars.onThePhone = mapOf(first to listOf(event), second to emptyList())
        run()
        assertEquals(PlannerState.Planned, planner.record.state)
        assertEquals(listOf("Flight"), alarms.all().map { it.snapshot.reason })
    }

    @Test
    fun `one missing calendar among several fails the run and keeps all planned alarms, of every calendar`() = runBlocking {
        chosen(setOf(first, second))
        calendars.onThePhone = mapOf(first to listOf(event), second to listOf(laterEvent))
        run()
        val planned = alarms.all()

        calendars.onThePhone = mapOf(first to emptyList())
        run(generation = 2)
        assertEquals(PlannerState.FailSafe, planner.record.state)
        assertEquals(PlanningFailure.CALENDAR_GONE, planner.record.lastRun?.failure)
        assertEquals(PlanningFailure.CALENDAR_GONE, effects.warnedAbout)
        assertEquals(planned, alarms.all())
    }

    @Test
    fun `unticking the missing calendar lets planning go on`() = runBlocking {
        chosen(setOf(first, second))
        calendars.onThePhone = mapOf(first to listOf(event))
        run()
        assertEquals(PlannerState.FailSafe, planner.record.state)

        chosen(setOf(first))
        run(generation = 2)
        assertEquals(PlannerState.Planned, planner.record.state)
        assertEquals(listOf("Flight"), alarms.all().map { it.snapshot.reason })
    }

    @Test
    fun `unticking a calendar removes its alarms that have not started, and keeps those of the others`() = runBlocking {
        chosen(setOf(first, second))
        calendars.onThePhone = mapOf(first to listOf(event), second to listOf(laterEvent))
        run()

        chosen(setOf(first))
        run(generation = 2)
        assertEquals(PlannerState.Planned, planner.record.state)
        assertEquals(
            listOf("Flight" to AlarmState.Scheduled, "Special case" to AlarmState.Cancelled),
            alarms.all().map { it.snapshot.reason to it.state },
        )
    }

    @Test
    fun `unticking a calendar does not stop its alarm that has already started`() = runBlocking {
        chosen(setOf(first, second))
        calendars.onThePhone = mapOf(first to emptyList(), second to emptyList())
        alarms.add(AlarmInstance(5, AlarmKey.forEvent(2, future), future, AlarmState.Snoozed, 1, false, snapshot()))

        chosen(setOf(first))
        run()
        assertEquals(AlarmState.Snoozed, alarms.find(5)?.state)
    }

    @Test
    fun `ticking a calendar again brings its alarms back`() = runBlocking {
        chosen(setOf(first, second))
        calendars.onThePhone = mapOf(first to emptyList(), second to listOf(laterEvent))
        run()
        chosen(setOf(first))
        run(generation = 2)
        chosen(setOf(first, second))
        run(generation = 3)
        assertEquals(1, alarms.all().count { it.state == AlarmState.Scheduled })
    }

    @Test
    fun `an alarm planned from one calendar is not created again now that several can be chosen`() = runBlocking {
        // The key as version 2 of the database stored it. It holds the event and its start, and no calendar.
        val stored = AlarmKey("event:1:${future.toEpochMilli()}")
        alarms.add(AlarmInstance(5, stored, future, AlarmState.Scheduled, 0, false, settings().snapshotFor("Flight")))
        chosen(setOf(first, second))
        calendars.onThePhone = mapOf(first to listOf(event), second to emptyList())
        run()
        assertEquals(PlannerState.Planned, planner.record.state)
        val alarm = alarms.all().single()
        assertEquals(5, alarm.id)
        assertEquals(AlarmState.Scheduled, alarm.state)
        assertEquals(stored, alarm.key)
    }

    @Test
    fun `unticking every calendar is a failed run that keeps the planned alarms, and the warning says why`() = runBlocking {
        alarms.add(AlarmInstance(1, AlarmKey.forEvent(1, future), future, AlarmState.Scheduled, 0, false, snapshot()))
        chosen(emptySet())
        run()
        assertEquals(PlannerState.FailSafe, planner.record.state)
        assertEquals(PlanningFailure.NO_CALENDAR, planner.record.lastRun?.failure)
        assertEquals(PlanningFailure.NO_CALENDAR, effects.warnedAbout)
        assertEquals(AlarmState.Scheduled, alarms.find(1)?.state)
    }

    @Test
    fun `a run reads the 48 hours of the chosen calendar`() = runBlocking {
        planning()
        engine.runPlanning(1)
        assertEquals(setOf(CalendarId(1)), calendars.readCalendars)
        assertEquals(NOW to NOW.plus(Duration.ofHours(48)), calendars.readRange)
    }

    @Test
    fun `a successful run creates and registers the alarms and records its details`() = runBlocking {
        planning(failures = 3)
        calendars.events = listOf(event, event.copy(eventId = 2))
        clock.time = NOW
        engine.runPlanning(1)

        assertEquals(2, alarms.all().size)
        assertEquals(setOf(future), scheduler.scheduled.values.toSet())
        assertEquals(PlannerState.Planned, planner.record.state)
        assertEquals(0, planner.record.consecutiveFailures)
        assertEquals(PlanningRun(NOW, Duration.ZERO, 2, null), planner.record.lastRun)
        assertEquals(listOf("removeWarning", "nextAlarm:1"), effects.calls)
    }

    @Test
    fun `a run that finds nothing removes the next alarm notification`() = runBlocking {
        planning()
        engine.runPlanning(1)
        assertEquals(listOf("removeWarning", "nextAlarm:null"), effects.calls)
    }

    @Test
    fun `the duration of a run is recorded`() = runBlocking {
        planning()
        var ticks = 0L
        val ticking = PlannerEngine(planner, settingsRepo, calendars, alarms, alarmEngine, effects, lock) {
            NOW.plusSeconds(ticks++)
        }
        ticking.runPlanning(1)
        assertEquals(NOW, planner.record.lastRun?.startedAt)
        assertEquals(Duration.ofSeconds(2), planner.record.lastRun?.duration)
    }

    @Test
    fun `an empty calendar is a valid result and removes alarms that have not started`() = runBlocking {
        val first = alarms.create(AlarmKey.forEvent(1, future), future, snapshot())
        planning()
        engine.runPlanning(1)
        assertEquals(AlarmState.Cancelled, alarms.find(first.id)?.state)
        assertEquals(PlannerState.Planned, planner.record.state)
        assertEquals(0, planner.record.lastRun?.alarmCount)
    }

    @Test
    fun `a changed title refreshes the alarm`() = runBlocking {
        val first = alarms.create(AlarmKey.forEvent(1, future), future, settings().snapshotFor("Old"))
        calendars.events = listOf(event)
        planning()
        engine.runPlanning(1)
        assertEquals("Flight", alarms.find(first.id)?.snapshot?.reason)
    }

    @Test
    fun `a run registers waiting alarms again, which repairs an interrupted run`() = runBlocking {
        val first = alarms.create(AlarmKey.forEvent(1, future), future, settings().snapshotFor("Flight"))
        calendars.events = listOf(event)
        planning()
        engine.runPlanning(1)
        assertEquals(future, scheduler.scheduled[first.id])
    }

    @Test
    fun `a failed read keeps the planned alarms, warns and retries`() = runBlocking {
        val first = alarms.create(AlarmKey.forEvent(1, future), future, snapshot())
        failFrom(IOException("provider gone"))
        planning()
        engine.runPlanning(1)

        assertEquals(AlarmState.Scheduled, alarms.find(first.id)?.state)
        assertEquals(PlannerState.FailSafe, planner.record.state)
        assertEquals(1, planner.record.consecutiveFailures)
        assertEquals(PlanningFailure.READ_FAILED, planner.record.lastRun?.failure)
        assertNull(planner.record.lastRun?.alarmCount)
        assertEquals(listOf("showWarning", "retry:1"), effects.calls)
    }

    @Test
    fun `repeated failures wait longer`() = runBlocking {
        failFrom(IOException("provider gone"))
        planning(failures = 3)
        engine.runPlanning(1)
        assertEquals(4, planner.record.consecutiveFailures)
        assertEquals(listOf("showWarning", "retry:8"), effects.calls)
    }

    @Test
    fun `a revoked permission is reported as such`() = runBlocking {
        failFrom(SecurityException("no permission"))
        planning()
        engine.runPlanning(1)
        assertEquals(PlanningFailure.NO_PERMISSION, planner.record.lastRun?.failure)
    }

    @Test
    fun `no chosen calendar is reported as such, without reading any`() = runBlocking {
        settingsRepo.settings = settings().copy(alarmCalendars = emptySet())
        planning()
        engine.runPlanning(1)
        assertEquals(PlanningFailure.NO_CALENDAR, planner.record.lastRun?.failure)
        assertNull(calendars.readCalendars)
    }

    @Test
    fun `a failure while applying the plan leads to fail-safe`() = runBlocking {
        alarms.failOnCreate = true
        calendars.events = listOf(event)
        planning()
        engine.runPlanning(1)
        assertEquals(PlannerState.FailSafe, planner.record.state)
        assertEquals(PlanningFailure.APPLY_FAILED, planner.record.lastRun?.failure)
    }

    @Test
    fun `a stale success changes nothing and creates no alarms`() = runBlocking {
        planning(generation = 2)
        calendars.events = listOf(event)
        engine.runPlanning(1)
        assertEquals(PlannerRecord(PlannerState.Planning, 2), planner.record)
        assertTrue(alarms.all().isEmpty())
        assertTrue(effects.calls.isEmpty())
    }

    @Test
    fun `a stale failure changes nothing`() = runBlocking {
        planning(generation = 2)
        failFrom(IOException("provider gone"))
        engine.runPlanning(1)
        assertEquals(PlannerRecord(PlannerState.Planning, 2), planner.record)
        assertTrue(effects.calls.isEmpty())
    }

    @Test
    fun `a cancelled run while reading is not a failure`() = runBlocking {
        failFrom(CancellationException("replaced"))
        planning()
        assertFailsWith<CancellationException> { engine.runPlanning(1) }
        assertEquals(PlannerState.Planning, planner.record.state)
        assertEquals(0, planner.record.consecutiveFailures)
    }

    private fun ended(id: Long, state: AlarmState = AlarmState.Done, days: Long = 30) = alarms.add(
        AlarmInstance(id, AlarmKey.forTestAlarm(id), NOW.minus(Duration.ofDays(days)), state, 0, false, snapshot()),
    )

    @Test
    fun `a successful run deletes old ended alarms and keeps the rest`() = runBlocking {
        ended(101)
        ended(102, days = 1)
        ended(103, AlarmState.Ringing)
        planning()
        engine.runPlanning(1)
        assertEquals(PlannerState.Planned, planner.record.state)
        assertEquals(listOf(102L, 103L), alarms.all().map { it.id })
    }

    @Test
    fun `a failed run deletes nothing`() = runBlocking {
        ended(101)
        failFrom(IllegalStateException("broken"))
        planning()
        engine.runPlanning(1)
        assertEquals(PlannerState.FailSafe, planner.record.state)
        assertEquals(listOf(101L), alarms.all().map { it.id })
    }

    @Test
    fun `a stale run deletes nothing`() = runBlocking {
        ended(101)
        planning(generation = 2)
        engine.runPlanning(1)
        assertEquals(listOf(101L), alarms.all().map { it.id })
    }

    @Test
    fun `a failure to delete does not make a successful run fail`() = runBlocking {
        ended(101)
        alarms.failOnDelete = true
        planning()
        engine.runPlanning(1)
        assertEquals(PlannerState.Planned, planner.record.state)
        assertEquals(0, planner.record.consecutiveFailures)
        assertEquals(listOf(101L), alarms.all().map { it.id })

        alarms.failOnDelete = false
        engine.replanRequested()
        engine.runPlanning(2)
        assertTrue(alarms.all().isEmpty())
    }

    @Test
    fun `a cancelled deletion stays cancelled`() = runBlocking {
        val cancelling = object : AlarmRepository by alarms {
            override suspend fun delete(id: Long): Unit = throw CancellationException("replaced")
        }
        val alarmEngine = AlarmEngine(cancelling, scheduler, NoAlarmEffects, clock::now, FakeLock())
        val engine = PlannerEngine(planner, settingsRepo, calendars, cancelling, alarmEngine, effects, lock, clock::now)
        ended(101)
        planning()
        assertFailsWith<CancellationException> { engine.runPlanning(1) }
        assertEquals(PlannerState.Planned, planner.record.state)
    }

    @Test
    fun `a cancelled run while applying is not a failure`() = runBlocking {
        val cancelling = object : AlarmRepository by alarms {
            override suspend fun all(): List<AlarmInstance> = throw CancellationException("replaced")
        }
        val engine = PlannerEngine(planner, settingsRepo, calendars, cancelling, alarmEngine, effects, lock, clock::now)
        planning()
        assertFailsWith<CancellationException> { engine.runPlanning(1) }
        assertEquals(PlannerState.Planning, planner.record.state)
    }
}

class PlanningScheduleTest {
    @Test
    fun `retries double from one minute up to one hour`() {
        val minutes = listOf(1, 2, 3, 4, 5, 6, 7, 8, 100).map { retryDelay(it).toMinutes() }
        assertEquals(listOf<Long>(1, 2, 4, 8, 16, 32, 60, 60, 60), minutes)
    }

    @Test
    fun `a retry without a failure waits the shortest time`() {
        assertEquals(Duration.ofMinutes(1), retryDelay(0))
    }

    @Test
    fun `the daily run is five minutes after the next local midnight`() {
        val zone = java.time.ZoneId.of("Europe/Berlin")
        val evening = java.time.Instant.parse("2026-11-06T19:00:00Z")
        assertEquals(Duration.ofHours(4).plusMinutes(5), delayUntilAfterMidnight(evening, zone))
    }

    @Test
    fun `just after midnight the daily run is on the next day`() {
        val zone = java.time.ZoneId.of("UTC")
        val justAfter = java.time.Instant.parse("2026-11-06T00:01:00Z")
        assertEquals(Duration.ofHours(24).plusMinutes(4), delayUntilAfterMidnight(justAfter, zone))
    }

    @Test
    fun `a day with a clock change counts its real length`() {
        val zone = java.time.ZoneId.of("Europe/Berlin")
        val startOfLongDay = java.time.Instant.parse("2026-10-24T22:00:00Z")
        assertEquals(Duration.ofHours(25).plusMinutes(5), delayUntilAfterMidnight(startOfLongDay, zone))
    }
}
