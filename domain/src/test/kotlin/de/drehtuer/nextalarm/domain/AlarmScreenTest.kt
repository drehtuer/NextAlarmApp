package de.drehtuer.nextalarm.domain

import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The examples of docs/design.md: an alarm rings on Saturday 7 November at 06:00. */
class AlarmScreenTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val now = Instant.parse("2026-11-07T05:00:00Z")

    private fun ringing(id: Long, title: String?, at: Instant = now, snoozeMinutes: Int = 10) = AlarmInstance(
        id,
        AlarmKey.forEvent(id, at),
        at,
        AlarmState.Ringing,
        0,
        false,
        snapshot(title).copy(snoozeMinutes = snoozeMinutes),
    )

    @Test
    fun `one alarm shows its title and no count`() {
        assertEquals(
            AlarmScreenModel(
                date = "Saturday 7 November",
                time = "06:00",
                countLine = null,
                titles = listOf("Flight"),
                ringsAgain = "Rings again at 06:10",
            ),
            alarmScreenModel(now, zone, listOf(ringing(1, "Flight"))),
        )
    }

    @Test
    fun `several alarms show a count and every title`() {
        val model = alarmScreenModel(now, zone, listOf(ringing(1, "Flight"), ringing(2, "Pills"), ringing(3, "Taxi")))
        assertEquals("3 alarms", model.countLine)
        assertEquals(listOf("Flight", "Pills", "Taxi"), model.titles)
    }

    @Test
    fun `titles are ordered by trigger time, then id`() {
        val later = now.plusSeconds(30)
        val model = alarmScreenModel(
            now,
            zone,
            listOf(ringing(1, "Third", later), ringing(3, "Second"), ringing(2, "First"), ringing(4, "Fourth", later)),
        )
        assertEquals(listOf("First", "Second", "Third", "Fourth"), model.titles)
    }

    @Test
    fun `an event without a title gives an empty entry in its place`() {
        val model = alarmScreenModel(now, zone, listOf(ringing(1, null), ringing(2, "Flight")))
        assertEquals(listOf(null, "Flight"), model.titles)
        assertEquals("2 alarms", model.countLine)
        assertNull(alarmScreenModel(now, zone, listOf(ringing(1, null))).titles.single())
    }

    @Test
    fun `the time shows seconds only if they are not zero, and so does the time it rings again`() {
        val model = alarmScreenModel(now.plusSeconds(30), zone, listOf(ringing(1, "Flight")))
        assertEquals("06:00:30", model.time)
        assertEquals("Rings again at 06:10:30", model.ringsAgain)
    }

    @Test
    fun `it rings again after the snooze length of the alarm`() {
        val model = alarmScreenModel(now, zone, listOf(ringing(1, "Flight", snoozeMinutes = 25)))
        assertEquals("Rings again at 06:25", model.ringsAgain)
    }

    @Test
    fun `with different snooze lengths it says when the first one rings again`() {
        val model = alarmScreenModel(
            now,
            zone,
            listOf(ringing(1, "Flight", snoozeMinutes = 15), ringing(2, "Pills", snoozeMinutes = 5)),
        )
        assertEquals("Rings again at 06:05", model.ringsAgain)
    }

    @Test
    fun `date and time use the device time zone`() {
        val model = alarmScreenModel(now, ZoneId.of("Asia/Tokyo"), listOf(ringing(1, "Flight")))
        assertEquals("Saturday 7 November", model.date)
        assertEquals("14:00", model.time)
        assertEquals("Friday 6 November", alarmScreenModel(now, ZoneId.of("Pacific/Honolulu"), emptyList()).date)
    }

    @Test
    fun `ringing again after midnight wraps around`() {
        val lateAtNight = Instant.parse("2026-11-07T22:55:00Z")
        val model = alarmScreenModel(lateAtNight, zone, listOf(ringing(1, "Flight", lateAtNight)))
        assertEquals("Saturday 7 November", model.date)
        assertEquals("23:55", model.time)
        assertEquals("Rings again at 00:05", model.ringsAgain)
    }

    @Test
    fun `ringing again follows the clock across a daylight saving change`() {
        val beforeChange = Instant.parse("2026-10-25T00:55:00Z")
        val model = alarmScreenModel(beforeChange, zone, listOf(ringing(1, "Flight", beforeChange)))
        assertEquals("02:55", model.time)
        assertEquals("Rings again at 02:05", model.ringsAgain)
    }

    @Test
    fun `no ringing alarm does not crash and promises nothing`() {
        assertEquals(
            AlarmScreenModel("Saturday 7 November", "06:00", null, emptyList(), ""),
            alarmScreenModel(now, zone, emptyList()),
        )
    }

    @Test
    fun `a title is long above 60 characters`() {
        assertFalse(isLongAlarmTitle(""))
        assertFalse(isLongAlarmTitle("x".repeat(60)))
        assertTrue(isLongAlarmTitle("x".repeat(61)))
    }
}
