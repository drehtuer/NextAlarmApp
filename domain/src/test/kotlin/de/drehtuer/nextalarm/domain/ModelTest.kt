package de.drehtuer.nextalarm.domain

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelTest {
    @Test
    fun `a calendar event is timed and not cancelled by default`() {
        val event = CalendarEvent(1, NOW, "Title")
        assertFalse(event.allDay)
        assertFalse(event.cancelled)
    }

    @Test
    fun `settings default to the documented ring timeout and snooze length`() {
        assertEquals(3, settings().ringTimeoutMinutes)
        assertEquals(10, settings().snoozeMinutes)
    }

    @Test
    fun `the default settings change nothing and add nothing`() {
        val defaults = Settings.DEFAULT
        assertNull(defaults.alarmCalendar)
        assertEquals(SoundRef.SystemDefault, defaults.sound)
        assertFalse(defaults.vibrate)
        assertEquals(0, defaults.volumeRampSeconds)
        assertNull(defaults.maxVolume)
        assertFalse(defaults.speakReason)
        assertNull(defaults.news)
    }

    @Test
    fun `a volume is optional, and can be at either limit`() {
        assertNull(settings().copy(maxVolume = null).maxVolume)
        assertEquals(0f, settings().copy(maxVolume = 0f).maxVolume)
        assertEquals(1f, settings().copy(maxVolume = 1f).maxVolume)
    }

    @Test
    fun `the initial planner record is unconfigured`() {
        assertEquals(PlannerState.Unconfigured, PlannerRecord.INITIAL.state)
        assertEquals(0, PlannerRecord.INITIAL.generation)
    }

    @Test
    fun `a device calendar keeps its fields`() {
        val calendar = DeviceCalendar(CalendarId(3), "Alarms", "me@example.org")
        assertEquals(3, calendar.id.value)
        assertEquals("Alarms", calendar.name)
        assertEquals("me@example.org", calendar.accountName)
    }

    @Test
    fun `settings reject values out of range`() {
        assertFailsWith<IllegalArgumentException> { settings().copy(ringTimeoutMinutes = 0) }
        assertFailsWith<IllegalArgumentException> { settings().copy(snoozeMinutes = 0) }
        assertFailsWith<IllegalArgumentException> { settings().copy(volumeRampSeconds = -1) }
        assertFailsWith<IllegalArgumentException> { settings().copy(maxVolume = 1.5f) }
        assertFailsWith<IllegalArgumentException> { settings().copy(maxVolume = -0.1f) }
    }

    @Test
    fun `news defaults to 15 minutes and rejects a non-positive limit`() {
        assertEquals(15, NEWS.maxMinutes)
        assertFailsWith<IllegalArgumentException> { NEWS.copy(maxMinutes = 0) }
    }

    @Test
    fun `snapshot copies every setting`() {
        val settings = Settings(
            alarmCalendar = CalendarId(7),
            sound = SoundRef.File("a.mp3"),
            vibrate = false,
            volumeRampSeconds = 12,
            maxVolume = 0.5f,
            ringTimeoutMinutes = 4,
            snoozeMinutes = 8,
            speakReason = false,
            news = NEWS,
        )
        val expected = AlarmSnapshot(
            reason = "Title",
            sound = SoundRef.File("a.mp3"),
            vibrate = false,
            volumeRampSeconds = 12,
            maxVolume = 0.5f,
            ringTimeoutMinutes = 4,
            snoozeMinutes = 8,
            speakReason = false,
            news = NEWS,
        )
        assertEquals(expected, settings.snapshotFor("Title"))
    }

    @Test
    fun `missing or blank title gives no reason`() {
        assertNull(settings().snapshotFor(null).reason)
        assertNull(settings().snapshotFor("  ").reason)
    }

    @Test
    fun `event key contains the event id and start time`() {
        val key = AlarmKey.forEvent(42, Instant.ofEpochMilli(1000))
        assertEquals("event:42:1000", key.value)
        assertTrue(key.isFromCalendar)
    }

    @Test
    fun `test alarm key is not from the calendar`() {
        val key = AlarmKey.forTestAlarm(5)
        assertEquals("test:5", key.value)
        assertFalse(key.isFromCalendar)
    }
}
