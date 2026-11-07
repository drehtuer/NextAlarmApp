package de.drehtuer.nextalarm.domain

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EmergencyKeyTest {
    @Test
    fun `only an emergency key says it is one, and it takes no part in the plan`() {
        assertTrue(AlarmKey.forEmergency(1).isEmergency)
        assertFalse(AlarmKey.forEmergency(1).isFromCalendar)
        assertFalse(AlarmKey.forEvent(1, NOW).isEmergency)
        assertFalse(AlarmKey.forTestAlarm(1).isEmergency)
    }
}

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
        assertEquals(emptySet(), defaults.alarmCalendars)
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
        val calendar = DeviceCalendar(CalendarId(3), "Alarms", "me@example.org", "LOCAL", 5)
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
    fun `ticking adds a calendar to those chosen, and leaves the rest of the settings alone`() {
        val settings = settings(speakReason = false).withAlarmCalendar(CalendarId(2), ticked = true)
        assertEquals(setOf(CalendarId(1), CalendarId(2)), settings.alarmCalendars)
        assertFalse(settings.speakReason)
    }

    @Test
    fun `unticking removes only that calendar`() {
        val both = settings().copy(alarmCalendars = setOf(CalendarId(1), CalendarId(2)))
        assertEquals(setOf(CalendarId(2)), both.withAlarmCalendar(CalendarId(1), ticked = false).alarmCalendars)
    }

    @Test
    fun `ticking or unticking twice changes nothing`() {
        val one = settings()
        assertEquals(one, one.withAlarmCalendar(CalendarId(1), ticked = true))
        assertEquals(one, one.withAlarmCalendar(CalendarId(2), ticked = false))
    }

    @Test
    fun `unticking the last calendar leaves none chosen`() {
        assertEquals(emptySet(), settings().withAlarmCalendar(CalendarId(1), ticked = false).alarmCalendars)
    }

    @Test
    fun `snapshot copies every setting`() {
        val settings = Settings(
            alarmCalendars = setOf(CalendarId(7)),
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
    fun `a sound copy keeps the file's own name after its unique part`() {
        val id = "123e4567-e89b-12d3-a456-426614174000"
        val sound = SoundRef.File.forCopy(id, "morning.mp3")
        assertEquals("sound-$id-morning.mp3", sound.name)
        assertEquals("morning.mp3", sound.displayName)
    }

    @Test
    fun `a sound copy's name cannot leave its directory or get too long for a file name`() {
        val id = "123e4567-e89b-12d3-a456-426614174000"
        assertEquals("sound-$id-.._.._a_b_c", SoundRef.File.forCopy(id, "../../a\\b\nc").name)
        val long = SoundRef.File.forCopy(id, "é".repeat(1000))
        assertEquals(64, long.displayName.length)
        assertTrue(long.name.toByteArray().size <= 255)
    }

    @Test
    fun `a sound with any other name shows that name`() {
        assertEquals("sound-1", SoundRef.File("sound-1").displayName)
    }

    @Test
    fun `test alarm key is not from the calendar`() {
        val key = AlarmKey.forTestAlarm(5)
        assertEquals("test:5", key.value)
        assertFalse(key.isFromCalendar)
    }
}
