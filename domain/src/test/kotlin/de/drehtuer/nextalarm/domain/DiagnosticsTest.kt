package de.drehtuer.nextalarm.domain

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The examples of docs/design.md: "now" is Friday 6 November, 20:00. */
class DiagnosticsTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val now = Instant.parse("2026-11-06T19:00:00Z")
    private val checked = Instant.parse("2026-11-06T18:58:00Z")
    private val run = PlanningRun(checked, Duration.ofMillis(400), 4, null)
    private val davx = DeviceCalendar(CalendarId(1), "NextAlarm", "me@cloud.example", "bitfire.at.davdroid", null)

    private fun record(state: PlannerState, run: PlanningRun? = this.run) = PlannerRecord(state, 1, lastRun = run)

    private fun failed(failure: PlanningFailure, at: Instant = checked) = PlanningRun(at, Duration.ZERO, null, failure)

    private fun alarm(
        id: Long = 1,
        state: AlarmState = AlarmState.Done,
        at: String = "2026-11-05T06:00:00Z",
        title: String? = "Gym",
        snoozes: Int = 0,
        lateBy: Duration? = null,
    ) = AlarmInstance(id, AlarmKey.forEvent(id, Instant.parse(at)), Instant.parse(at), state, snoozes, false, snapshot(title), lateBy)

    private fun waiting(count: Int) = List(count) {
        alarm(id = it + 1L, state = AlarmState.Scheduled, at = "2026-11-07T05:00:00Z")
    }

    private fun status(state: PlannerState, run: PlanningRun? = this.run, alarms: List<AlarmInstance> = emptyList()) =
        statusCard(record(state, run), alarms, now, zone)

    private fun row(alarm: AlarmInstance) = pastAlarmRows(listOf(alarm), zone).single()

    @Test
    fun `a test alarm rings soon, with the current settings, under its own title`() {
        val settings = settings(vibrate = true)
        val entry = testAlarmEntry(now, settings)
        assertEquals(now.plus(TEST_ALARM_DELAY), entry.triggerAt)
        assertEquals(Duration.ofSeconds(5), TEST_ALARM_DELAY)
        assertEquals(settings.snapshotFor("Test alarm"), entry.snapshot)
        assertEquals("“Test alarm” rings in about 5 seconds, with your current settings.", TEST_ALARM_NOTE)
    }

    @Test
    fun `a test alarm stays out of the plan diff, and two get their own keys`() {
        val entry = testAlarmEntry(now, settings())
        assertFalse(entry.key.isFromCalendar)
        val later = testAlarmEntry(now.plusMillis(1), settings())
        assertTrue(entry.key != later.key)
        val stored = AlarmInstance(1, entry.key, entry.triggerAt, AlarmState.Scheduled, 0, false, entry.snapshot)
        assertTrue(diff(Plan(emptyList()), listOf(stored), now).isEmpty())
    }

    @Test
    fun `a card for Planned gives the last check`() {
        val card = status(PlannerState.Planned)
        assertEquals(StatusKind.PLANNED, card.kind)
        assertEquals("Alarms are planned", card.title)
        assertEquals("Last check today at 19:58 took 0.4 s and found 4 alarms in the next 48 hours.", card.body)
    }

    @Test
    fun `a card for Planned counts properly`() {
        assertEquals(
            "Last check today at 19:58 took 0.4 s and found 1 alarm in the next 48 hours.",
            status(PlannerState.Planned, run.copy(alarmCount = 1)).body,
        )
        assertEquals(
            "Last check today at 19:58 took 0.4 s and found no alarms in the next 48 hours.",
            status(PlannerState.Planned, run.copy(alarmCount = 0)).body,
        )
        assertEquals(
            "Last check today at 19:58 took 0.4 s and found no alarms in the next 48 hours.",
            status(PlannerState.Planned, run.copy(alarmCount = null)).body,
        )
    }

    @Test
    fun `a card for Planned without a finished check says so`() {
        assertEquals("No check has finished yet.", status(PlannerState.Planned, null).body)
    }

    @Test
    fun `an older check names its day`() {
        val yesterday = run.copy(startedAt = Instant.parse("2026-11-05T18:58:00Z"))
        assertEquals(
            "Last check yesterday at 19:58 took 0.4 s and found 4 alarms in the next 48 hours.",
            status(PlannerState.Planned, yesterday).body,
        )
        val earlier = run.copy(startedAt = Instant.parse("2026-11-03T18:58:00Z"))
        assertEquals(
            "Last check Tuesday at 19:58 took 0.4 s and found 4 alarms in the next 48 hours.",
            status(PlannerState.Planned, earlier).body,
        )
    }

    @Test
    fun `a card for Planning says alarms stay, and gives no start time because none is stored`() {
        val card = status(PlannerState.Planning)
        assertEquals(StatusKind.PLANNING, card.kind)
        assertEquals("Checking the calendar…", card.title)
        assertEquals("Alarms already planned stay in place.", card.body)
    }

    @Test
    fun `a card for a phone not set up says what to do`() {
        val card = status(PlannerState.Unconfigured)
        assertEquals(StatusKind.NOT_SET_UP, card.kind)
        assertEquals("Not set up yet", card.title)
        assertEquals("Finish setup to plan alarms.", card.body)
    }

    @Test
    fun `a card for FailSafe gives the failure, what still rings, and the fix`() {
        val card = status(PlannerState.FailSafe, failed(PlanningFailure.NO_PERMISSION), waiting(4))
        assertEquals(StatusKind.FAIL_SAFE, card.kind)
        assertEquals("Last check failed", card.title)
        assertEquals(
            "Today at 19:58: calendar permission was removed. The 4 alarms already planned will still ring, but " +
                "changes in the calendar are not picked up. Allow calendar access again to fix it.",
            card.body,
        )
    }

    @Test
    fun `every failure has its own line and fix`() {
        val bodies = mapOf(
            PlanningFailure.NO_CALENDAR to listOf("no alarm calendar is chosen", "Choose an alarm calendar in the settings to fix it."),
            PlanningFailure.NO_PERMISSION to listOf("calendar permission was removed", "Allow calendar access again to fix it."),
            PlanningFailure.CALENDAR_GONE to listOf(
                "the alarm calendar is no longer on this phone",
                "Choose another alarm calendar in the settings to fix it.",
            ),
            PlanningFailure.READ_FAILED to listOf(
                "the calendar couldn't be read",
                "NextAlarm keeps trying. If this lasts, check that the calendar is still on this phone.",
            ),
            PlanningFailure.APPLY_FAILED to listOf("the alarms couldn't be updated", "NextAlarm keeps trying. If this lasts, restart the phone."),
        )
        assertEquals(PlanningFailure.entries.toSet(), bodies.keys)
        bodies.forEach { (failure, parts) ->
            val body = status(PlannerState.FailSafe, failed(failure), waiting(2)).body
            assertTrue(body.startsWith("Today at 19:58: ${parts[0]}. "), body)
            assertTrue(body.endsWith(parts[1]), body)
        }
    }

    @Test
    fun `a card for FailSafe counts what still rings`() {
        val one = status(PlannerState.FailSafe, failed(PlanningFailure.READ_FAILED), waiting(1)).body
        assertTrue("The 1 alarm already planned will still ring" in one, one)
        val none = status(PlannerState.FailSafe, failed(PlanningFailure.READ_FAILED)).body
        assertTrue("No alarms are planned, and changes in the calendar are not picked up." in none, none)
    }

    @Test
    fun `a card for FailSafe without a known failure still says what rings`() {
        assertEquals(
            "The 1 alarm already planned will still ring, but changes in the calendar are not picked up.",
            status(PlannerState.FailSafe, null, waiting(1)).body,
        )
        assertEquals(
            "No alarms are planned, and changes in the calendar are not picked up.",
            status(PlannerState.FailSafe, run).body,
        )
    }

    @Test
    fun `the settings row sums up the last check`() {
        assertEquals("Last check 19:58 · all good", diagnosticsSummary(record(PlannerState.Planned), zone))
        assertEquals(
            "Last check failed",
            diagnosticsSummary(record(PlannerState.FailSafe, failed(PlanningFailure.READ_FAILED)), zone),
        )
        assertEquals("Not checked yet", diagnosticsSummary(record(PlannerState.Planning, null), zone))
    }

    @Test
    fun `the calendar fact tells a calendar that can't be read from none chosen`() {
        assertEquals("NextAlarm · DAVx⁵", calendarFact(davx.id, davx))
        assertEquals("None chosen. Nothing rings", calendarFact(null, null))
        assertEquals("Can't be read right now", calendarFact(davx.id, null))
        assertEquals("Can't be read right now", calendarFact(davx.id, null, PlanningFailure.READ_FAILED))
        assertEquals("No longer on this phone. Choose another", calendarFact(davx.id, null, PlanningFailure.CALENDAR_GONE))
        assertEquals("NextAlarm · DAVx⁵", calendarFact(davx.id, davx, PlanningFailure.CALENDAR_GONE))
    }

    @Test
    fun `the full-screen fact says what happens without it`() {
        assertEquals("Allowed", fullScreenFact(true))
        assertEquals("Not allowed: alarms ring as a notification", fullScreenFact(false))
    }

    @Test
    fun `a dismissed alarm says how often it was snoozed`() {
        assertEquals("Dismissed", row(alarm()).outcome)
        assertEquals("Dismissed after 1 snooze", row(alarm(snoozes = 1)).outcome)
        assertEquals("Dismissed after 2 snoozes", row(alarm(snoozes = 2)).outcome)
        assertEquals(OutcomeKind.DISMISSED, row(alarm(snoozes = 2)).kind)
    }

    @Test
    fun `an alarm that rang late says so, also after a snooze`() {
        val late = row(alarm(lateBy = Duration.ofMinutes(7), snoozes = 1))
        assertEquals("Rang 7 min late (phone was off)", late.outcome)
        assertEquals(OutcomeKind.LATE, late.kind)
    }

    @Test
    fun `the other final states have their chips`() {
        val unanswered = row(alarm(state = AlarmState.Unanswered))
        assertEquals("Not answered (rang twice)" to OutcomeKind.UNANSWERED, unanswered.outcome to unanswered.kind)
        val cancelled = row(alarm(state = AlarmState.Cancelled))
        assertEquals("Event moved or deleted before it rang" to OutcomeKind.CANCELLED, cancelled.outcome to cancelled.kind)
        val missed = row(alarm(state = AlarmState.Missed))
        assertEquals("Missed: phone was off" to OutcomeKind.MISSED, missed.outcome to missed.kind)
    }

    @Test
    fun `alarms that have not ended are not past alarms`() {
        val open = listOf(
            AlarmState.Scheduled,
            AlarmState.Ringing,
            AlarmState.Snoozed,
            AlarmState.Announcing,
            AlarmState.NewsPlaying,
        ).map { alarm(state = it) }
        assertTrue(pastAlarmRows(open, zone).isEmpty())
    }

    @Test
    fun `a row gives the day, time and title`() {
        val row = row(alarm(id = 9))
        assertEquals(9, row.id)
        assertEquals("Thu 07:00", row.time)
        assertEquals("Gym", row.title)
        assertNull(row(alarm(title = null)).title)
    }

    @Test
    fun `a snoozed alarm is listed at the time of its event`() {
        val event = Instant.parse("2026-11-05T06:00:00Z")
        val snoozed = alarm().copy(key = AlarmKey.forEvent(1, event), triggerAt = event.plusSeconds(600))
        assertEquals("Thu 07:00", row(snoozed).time)
    }

    @Test
    fun `a test alarm is listed at its own time, with seconds`() {
        val test = alarm(at = "2026-11-05T06:00:30Z").copy(key = AlarmKey.forTestAlarm(1))
        assertEquals("Thu 07:00:30", row(test).time)
    }

    @Test
    fun `the newest comes first, and two at the same time stay in a fixed order`() {
        val rows = pastAlarmRows(
            listOf(
                alarm(id = 1, at = "2026-11-04T06:00:00Z"),
                alarm(id = 2, at = "2026-11-05T06:00:00Z"),
                alarm(id = 3, at = "2026-11-05T06:00:00Z"),
            ),
            zone,
        )
        assertEquals(listOf(3L, 2L, 1L), rows.map { it.id })
    }

    @Test
    fun `the model has all its parts`() {
        val model = buildDiagnostics(record(PlannerState.Planned), listOf(alarm()), now, zone, "NextAlarm · DAVx⁵", true)
        assertEquals("Alarms are planned", model.status.title)
        assertEquals("NextAlarm · DAVx⁵", model.calendar)
        assertEquals("When the calendar changes, and at least once a day", model.nextCheck)
        assertEquals("Allowed", model.fullScreen)
        assertEquals(1, model.pastAlarms.size)
        assertEquals("nextalarm-log-2026-11-06.txt", model.logFileName)
    }

    @Test
    fun `the log names its date in the device's time zone`() {
        assertEquals("nextalarm-log-2026-11-07.txt", logFileName(Instant.parse("2026-11-06T23:30:00Z"), zone))
    }

    @Test
    fun `the log has the planner, the last check and the past alarms, and says titles are included`() {
        val log = buildLogExport(
            now,
            zone,
            PlannerRecord(PlannerState.Planned, 3, consecutiveFailures = 0, lastRun = run),
            listOf(
                alarm(id = 1, title = "Gym", snoozes = 2),
                alarm(id = 2, state = AlarmState.Missed, at = "2026-11-06T06:00:00Z", title = null),
                alarm(id = 3, state = AlarmState.Scheduled, title = "Not ended"),
            ),
        )
        assertEquals(
            """
            NextAlarm log
            Exported: 2026-11-06 20:00:00 (Europe/Berlin)
            The log includes the titles of your alarms, and nothing else personal.

            Planner state: Planned
            Failures in a row: 0
            Last check: 2026-11-06 19:58:00
            Took: 0.4 s
            Result: Found 4 alarms

            Past alarms:
            2026-11-06 07:00:00 | Missed: phone was off | No title
            2026-11-05 07:00:00 | Dismissed after 2 snoozes | Gym

            """.trimIndent(),
            log,
        )
    }

    @Test
    fun `the log gives a failed check its reason`() {
        val log = buildLogExport(
            now,
            zone,
            PlannerRecord(PlannerState.FailSafe, 3, consecutiveFailures = 2, lastRun = failed(PlanningFailure.READ_FAILED)),
            emptyList(),
        )
        assertTrue("Planner state: FailSafe\nFailures in a row: 2\n" in log, log)
        assertTrue("Result: Failed: the calendar couldn't be read\n" in log, log)
        assertTrue("Past alarms:\nNone\n" in log, log)
    }

    @Test
    fun `the log can do without a last check, and a count`() {
        val none = buildLogExport(now, zone, PlannerRecord.INITIAL, emptyList())
        assertTrue("Last check: none yet\n" in none, none)
        val noCount = buildLogExport(now, zone, record(PlannerState.Planned, run.copy(alarmCount = null)), emptyList())
        assertTrue("Result: Found no alarms\n" in noCount, noCount)
    }

    @Test
    fun `a title stays on its own line of the log`() {
        val log = buildLogExport(now, zone, PlannerRecord.INITIAL, listOf(alarm(title = "Two\nlines")))
        assertTrue("| Dismissed | Two lines\n" in log, log)
    }

    @Test
    fun `the log holds no calendar, account or sound names`() {
        val named = settings().copy(sound = SoundRef.File("secret.mp3")).snapshotFor("Gym")
        val log = buildLogExport(now, zone, record(PlannerState.Planned), listOf(alarm().copy(snapshot = named)))
        listOf("secret", "me@cloud", "NextAlarm · ", "DAVx").forEach { assertFalse(it in log, it) }
    }
}
