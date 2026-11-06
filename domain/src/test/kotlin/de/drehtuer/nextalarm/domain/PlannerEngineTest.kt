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
    private val alarmEngine = AlarmEngine(alarms, scheduler, { _, _ -> }, clock::now)
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

    @Test
    fun `a run reads the 48 hours of the chosen calendar`() = runBlocking {
        planning()
        engine.runPlanning(1)
        assertEquals(CalendarId(1), calendars.readCalendar)
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
        settingsRepo.settings = settings().copy(alarmCalendar = null)
        planning()
        engine.runPlanning(1)
        assertEquals(PlanningFailure.NO_CALENDAR, planner.record.lastRun?.failure)
        assertNull(calendars.readCalendar)
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
