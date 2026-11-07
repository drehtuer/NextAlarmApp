package de.drehtuer.nextalarm.domain

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The wording comes from docs/design.md. */
class AlarmTextsTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val at = Instant.parse("2026-11-07T05:45:00Z")

    @Test
    fun `an alarm stopped by the limit says so`() {
        val text = ringLimitText(alarm("Gym"), zone)
        assertEquals("An alarm was stopped", text.title)
        assertEquals("“Gym” at 06:45 rang past its time and NextAlarm couldn't end it the normal way.", text.text)
    }

    @Test
    fun `the fail-safe warning names what it knows`() {
        val general = "NextAlarm can't read your calendar. Alarms already planned will still ring."
        assertEquals(general, failSafeWarningText(null).text)
        assertEquals(general, failSafeWarningText(PlanningFailure.READ_FAILED).text)
        assertEquals(
            "Your alarm calendar is no longer on this phone. Alarms already planned will still ring. Choose another calendar.",
            failSafeWarningText(PlanningFailure.CALENDAR_GONE).text,
        )
        assertEquals("New alarms aren't being added", failSafeWarningText(null).title)
    }

    private fun alarm(
        reason: String?,
        triggerAt: Instant = at,
        key: AlarmKey = AlarmKey.forEvent(1, at),
        lateBy: Duration? = null,
    ) = AlarmInstance(1, key, triggerAt, AlarmState.Ringing, 0, false, snapshot(reason), lateBy)

    @Test
    fun `a ringing alarm shows its title`() {
        assertEquals(NotificationText("Gym", "Ringing now"), ringingText(alarm("Gym")))
        assertEquals(NotificationText("Alarm (event has no title)", "Ringing now"), ringingText(alarm(null)))
    }

    @Test
    fun `there is a placeholder for a ringing alarm`() {
        assertEquals(NotificationText("Alarm", "Ringing now"), RINGING_PLACEHOLDER_TEXT)
    }

    @Test
    fun `a snoozed alarm says until when`() {
        assertEquals(NotificationText("Snoozed until 06:45", "Gym"), snoozedText(alarm("Gym"), zone))
        assertEquals(NotificationText("Snoozed until 06:45", "No title"), snoozedText(alarm(null), zone))
    }

    @Test
    fun `a late alarm says how late and when it was due`() {
        assertEquals(
            NotificationText("An alarm rang 7 min late", "“Train to Cologne” was due at 06:45, but the phone was off."),
            lateText(alarm("Train to Cologne", lateBy = Duration.ofMinutes(7).plusSeconds(30)), zone),
        )
        assertEquals(
            NotificationText("An alarm rang 7 min late", "An alarm was due at 06:45, but the phone was off."),
            lateText(alarm(null, lateBy = Duration.ofMinutes(7)), zone),
        )
        assertEquals("An alarm rang 0 min late", lateText(alarm("Gym"), zone).title)
    }

    @Test
    fun `a missed alarm says when its event was, also after a snooze`() {
        val snoozed = at.plusSeconds(600)
        assertEquals(
            NotificationText("An alarm didn't ring", "“Brunch with Lea” at 06:45 was missed because the phone was off."),
            missedText(alarm("Brunch with Lea", triggerAt = snoozed), zone),
        )
        assertEquals(
            NotificationText("An alarm didn't ring", "An alarm at 06:45 was missed because the phone was off."),
            missedText(alarm(null), zone),
        )
    }

    @Test
    fun `an unanswered alarm says when its event was, although the automatic snooze moved it`() {
        val snoozed = at.plusSeconds(780)
        assertEquals(
            NotificationText("An alarm wasn't answered", "“Gym” at 06:45 rang twice with no response. It won't ring again."),
            unansweredText(alarm("Gym", triggerAt = snoozed), zone),
        )
        assertEquals(
            NotificationText("An alarm wasn't answered", "An alarm at 06:45 rang twice with no response. It won't ring again."),
            unansweredText(alarm(null), zone),
        )
    }

    @Test
    fun `an alarm without an event goes by its trigger time`() {
        val test = alarm("Test", triggerAt = at.plusSeconds(60), key = AlarmKey.forTestAlarm(1))
        assertEquals("“Test” at 06:46 was missed because the phone was off.", missedText(test, zone).text)
    }

    @Test
    fun `the key of an event knows when it starts`() {
        assertEquals(at, AlarmKey.forEvent(5, at).eventStart)
        assertNull(AlarmKey.forTestAlarm(5).eventStart)
    }
}
