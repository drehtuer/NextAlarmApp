package de.drehtuer.nextalarm.domain

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The examples of docs/design.md: "now" is Friday 6 November, 20:00. */
class HomeTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val now = Instant.parse("2026-11-06T19:00:00Z")
    private val planned = PlannerRecord(PlannerState.Planned, 1)
    private val davx = DeviceCalendar(CalendarId(1), "NextAlarm", "me@cloud.example", "bitfire.at.davdroid", null)

    private fun alarm(
        at: String,
        title: String? = "Flight",
        state: AlarmState = AlarmState.Scheduled,
        id: Long = 1,
    ) = AlarmInstance(id, AlarmKey.forEvent(id, Instant.parse(at)), Instant.parse(at), state, 0, false, snapshot(title))

    private fun home(vararg alarms: AlarmInstance, record: PlannerRecord = planned, calendar: DeviceCalendar? = davx) =
        buildHome(now, zone, alarms.toList(), record, calendar)

    @Test
    fun `seconds are shown only if they are not zero`() {
        assertEquals("06:00", formatTime(Instant.parse("2026-11-07T05:00:00Z"), zone))
        assertEquals("06:00:30", formatTime(Instant.parse("2026-11-07T05:00:30Z"), zone))
    }

    @Test
    fun `only alarms still to ring are waiting, in order`() {
        val alarms = listOf(
            alarm("2026-11-07T06:00:00Z", id = 1),
            alarm("2026-11-07T05:00:00Z", id = 3, state = AlarmState.Snoozed),
            alarm("2026-11-07T05:00:00Z", id = 2),
            alarm("2026-11-07T05:00:00Z", id = 4, state = AlarmState.Ringing),
            alarm("2026-11-07T05:00:00Z", id = 5, state = AlarmState.Done),
            alarm("2026-11-06T19:00:00Z", id = 6),
        )
        assertEquals(listOf(2L, 3L, 1L), waitingAlarms(alarms, now).map { it.id })
        assertEquals(2L, nextAlarm(alarms, now)?.id)
        assertNull(nextAlarm(emptyList(), now))
    }

    @Test
    fun `the hero shows an alarm later today`() {
        val hero = home(alarm("2026-11-06T21:30:00Z")).hero
        assertEquals(HomeHero("Next alarm", "22:30", "Today · in 2 h 30 min", "Flight"), hero)
    }

    @Test
    fun `the hero shows an alarm tomorrow`() {
        val hero = home(alarm("2026-11-07T05:00:00Z")).hero
        assertEquals("Tomorrow, Saturday · in 10 h", hero?.relativeDay)
        assertEquals("06:00", hero?.time)
    }

    @Test
    fun `the hero shows an alarm further ahead`() {
        assertEquals("Sunday · in 34 h", home(alarm("2026-11-08T05:00:00Z")).hero?.relativeDay)
    }

    @Test
    fun `less than an hour is given in minutes, rounded up`() {
        assertEquals("Today · in 10 min", home(alarm("2026-11-06T19:09:30Z")).hero?.relativeDay)
    }

    @Test
    fun `the hero says so when two alarms share a minute, and each keeps its row`() {
        val model = home(alarm("2026-11-07T05:00:00Z", id = 1), alarm("2026-11-07T05:00:30Z", id = 2))
        assertEquals("Next alarm · 2 alarms at 06:00", model.hero?.label)
        assertEquals(listOf("06:00", "06:00:30"), model.days.single().rows.map { it.time })
    }

    @Test
    fun `a snoozed next alarm is marked in the hero and its row`() {
        val model = home(alarm("2026-11-06T19:10:00Z", state = AlarmState.Snoozed))
        assertEquals("Snoozed", model.hero?.label)
        assertEquals("Rings again in 10 min", model.hero?.relativeDay)
        assertEquals("Snoozed until 20:10", model.days.single().rows.single().snoozedUntil)
    }

    @Test
    fun `an event without a title stays without one`() {
        val model = home(alarm("2026-11-07T05:00:00Z", title = null))
        assertNull(model.hero?.title)
        assertNull(model.days.single().rows.single().title)
    }

    @Test
    fun `alarms are grouped by day`() {
        val model = home(
            alarm("2026-11-06T21:00:00Z", id = 1),
            alarm("2026-11-07T05:00:00Z", id = 2),
            alarm("2026-11-08T05:00:00Z", id = 3),
        )
        assertEquals(
            listOf("Today · Friday 6 November", "Tomorrow · Saturday 7 November", "Sunday 8 November"),
            model.days.map { it.header },
        )
    }

    @Test
    fun `no alarms give the empty state, naming the calendar`() {
        val model = home()
        assertNull(model.hero)
        assertEquals(
            "Nothing is planned until Sunday 20:00. To set one, add a timed event to the NextAlarm calendar in your " +
                "calendar app.",
            model.emptyBody,
        )
    }

    @Test
    fun `the empty state works without a known calendar`() {
        assertEquals(
            "Nothing is planned until Sunday 20:00. To set one, add a timed event to your alarm calendar in your " +
                "calendar app.",
            home(calendar = null).emptyBody,
        )
    }

    @Test
    fun `alarms give no empty state`() {
        assertNull(home(alarm("2026-11-07T05:00:00Z")).emptyBody)
    }

    @Test
    fun `fail-safe says when the read failed`() {
        val failed = PlanningRun(Instant.parse("2026-11-06T18:58:00Z"), Duration.ZERO, null, PlanningFailure.READ_FAILED)
        assertEquals(
            "NextAlarm couldn't read your calendar at 19:58. The alarms below will still ring. Anything you add or " +
                "change in the calendar won't, and if this lasts 48 hours, nothing rings.",
            home(record = PlannerRecord(PlannerState.FailSafe, 1, 1, failed)).failSafeBody,
        )
    }

    @Test
    fun `fail-safe works without a recorded run`() {
        val body = home(record = PlannerRecord(PlannerState.FailSafe, 1)).failSafeBody
        assertEquals(true, body?.startsWith("NextAlarm couldn't read your calendar. The alarms"))
    }

    @Test
    fun `other states show no fail-safe banner`() {
        assertNull(home().failSafeBody)
    }

    @Test
    fun `the banner says so if the alarm calendar is gone`() {
        val failed = PlanningRun(now, Duration.ZERO, null, PlanningFailure.CALENDAR_GONE)
        assertEquals(
            "Your alarm calendar was no longer on this phone at 20:00. The alarms below will still ring, but nothing new " +
                "is added, and after 48 hours nothing rings. Choose another calendar in the settings.",
            home(record = PlannerRecord(PlannerState.FailSafe, 1, 1, failed)).failSafeBody,
        )
    }

    @Test
    fun `home warns while notifications are off, and only then`() {
        assertNull(home().notificationsOff)
        val banner = buildHome(now, zone, emptyList(), planned, davx, notificationsAllowed = false).notificationsOff
        assertEquals("Notifications are off", banner?.title)
        assertEquals(
            "A ringing alarm can't show its screen or its Snooze and Dismiss buttons. Open NextAlarm to stop it. Allow " +
                "notifications in the app settings to fix this.",
            banner?.body,
        )
    }

    private fun checkedAt(at: String) =
        planned.copy(lastRun = PlanningRun(Instant.parse(at), Duration.ZERO, 0, null))

    @Test
    fun `the footer names the calendar, its source and the last check`() {
        assertEquals(
            "From the calendar “NextAlarm” (DAVx⁵). Checked today at 19:58.",
            home(record = checkedAt("2026-11-06T18:58:00Z")).footer,
        )
    }

    @Test
    fun `the footer names older checks by their day`() {
        assertEquals(true, home(record = checkedAt("2026-11-05T18:58:00Z")).footer?.endsWith("Checked yesterday at 19:58."))
        assertEquals(true, home(record = checkedAt("2026-11-04T18:58:00Z")).footer?.endsWith("Checked Wednesday at 19:58."))
    }

    @Test
    fun `the footer leaves out what it does not know`() {
        assertEquals("From the calendar “NextAlarm” (DAVx⁵).", home().footer)
        assertEquals("Checked today at 19:58.", home(record = checkedAt("2026-11-06T18:58:00Z"), calendar = null).footer)
        assertNull(home(calendar = null).footer)
        assertEquals("From the calendar “Mine”.", home(calendar = DeviceCalendar(CalendarId(2), "Mine", null, "LOCAL", null)).footer)
    }

    @Test
    fun `the notification follows the home screen's days`() {
        assertEquals(
            NextAlarmText("Next alarm: today 22:30", "Flight"),
            nextAlarmText(alarm("2026-11-06T21:30:00Z"), now, zone),
        )
        assertEquals("Next alarm: tomorrow 06:00", nextAlarmText(alarm("2026-11-07T05:00:00Z"), now, zone).title)
        assertEquals("Next alarm: Sunday 06:00", nextAlarmText(alarm("2026-11-08T05:00:00Z"), now, zone).title)
    }

    @Test
    fun `the notification has a text for an event without a title`() {
        assertEquals("No title", nextAlarmText(alarm("2026-11-07T05:00:00Z", title = null), now, zone).text)
    }

    @Test
    fun `the tile says the day and time of the next alarm, in the words of the notification`() {
        fun tile(at: String) = nextAlarmTile(listOf(alarm(at)), now, zone)

        assertEquals(NextAlarmTile("Next alarm", "today 22:30", true), tile("2026-11-06T21:30:00Z"))
        assertEquals(NextAlarmTile("Next alarm", "tomorrow 06:00", true), tile("2026-11-07T05:00:00Z"))
        assertEquals(NextAlarmTile("Next alarm", "Sunday 06:00", true), tile("2026-11-08T05:00:00Z"))
        assertEquals(NextAlarmTile("Next alarm", "tomorrow 06:00:30", true), tile("2026-11-07T05:00:30Z"))
        assertEquals(
            nextAlarmText(alarm("2026-11-07T05:00:00Z"), now, zone).title,
            "Next alarm: ${tile("2026-11-07T05:00:00Z").subtitle}",
        )
    }

    @Test
    fun `the tile is inactive without an alarm`() {
        assertEquals(NextAlarmTile("Next alarm", "None in 48 hours", false), nextAlarmTile(emptyList(), now, zone))
    }

    @Test
    fun `the tile shows the earliest alarm still to ring`() {
        val alarms = listOf(
            alarm("2026-11-08T05:00:00Z", id = 1),
            alarm("2026-11-07T05:00:00Z", id = 2),
            alarm("2026-11-06T18:00:00Z", id = 3),
            alarm("2026-11-06T20:00:00Z", id = 4, state = AlarmState.Done),
        )
        assertEquals("tomorrow 06:00", nextAlarmTile(alarms, now, zone).subtitle)
    }

    @Test
    fun `the tile counts a snoozed alarm, and neither a passed nor a ringing one`() {
        val snoozed = alarm("2026-11-06T19:10:00Z", state = AlarmState.Snoozed)
        assertEquals("today 20:10", nextAlarmTile(listOf(snoozed), now, zone).subtitle)
        val gone = listOf(alarm("2026-11-06T18:00:00Z"), alarm("2026-11-06T20:00:00Z", state = AlarmState.Ringing))
        assertEquals("None in 48 hours", nextAlarmTile(gone, now, zone).subtitle)
    }

    @Test
    fun `calendars are grouped by account and sorted`() {
        val groups = groupCalendars(
            listOf(
                davx.copy(id = CalendarId(1), name = "work"),
                davx.copy(id = CalendarId(2), name = "Alarms"),
                DeviceCalendar(CalendarId(3), "Holidays", "Holidays", "at.bitfire.icsdroid", null),
                DeviceCalendar(CalendarId(4), "Phone", "local", "LOCAL", null),
                DeviceCalendar(CalendarId(5), "Other", "someone@example.org", "com.example", null),
                DeviceCalendar(CalendarId(6), "Nameless", null, null, null),
                DeviceCalendar(CalendarId(7), "Sync", null, "bitfire.at.davdroid", null),
            ),
        )
        assertEquals(
            listOf("DAVx⁵", "DAVx⁵ · me@cloud.example", "ICSx⁵ · Holidays", "On this phone", "someone@example.org"),
            groups.map { it.label },
        )
        assertEquals(listOf("Alarms", "work"), groups[1].calendars.map { it.name })
        assertEquals(listOf("Nameless", "Phone"), groups[3].calendars.map { it.name })
    }

    @Test
    fun `the settings summary names the calendar and its source`() {
        assertEquals("None chosen. Nothing rings", calendarSummary(null))
        assertEquals("NextAlarm · DAVx⁵", calendarSummary(davx))
        assertEquals("Other · me", calendarSummary(DeviceCalendar(CalendarId(5), "Other", "me", "com.example", null)))
        assertEquals("Solo", calendarSummary(DeviceCalendar(CalendarId(6), "Solo", null, null, null)))
    }
}
