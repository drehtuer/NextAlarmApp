package de.drehtuer.nextalarm.data.db

import de.drehtuer.nextalarm.data.NEWS
import de.drehtuer.nextalarm.domain.AlarmState
import de.drehtuer.nextalarm.domain.CalendarId
import de.drehtuer.nextalarm.domain.NewsType
import de.drehtuer.nextalarm.domain.PlannerState
import de.drehtuer.nextalarm.domain.SoundRef
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.junit.Test

/** The stored names are the database format, so they are spelled out here. */
class MappersTest {
    private val alarmStates = mapOf(
        AlarmState.Scheduled to "SCHEDULED",
        AlarmState.Ringing to "RINGING",
        AlarmState.Snoozed to "SNOOZED",
        AlarmState.Announcing to "ANNOUNCING",
        AlarmState.NewsPlaying to "NEWS_PLAYING",
        AlarmState.Done to "DONE",
        AlarmState.Unanswered to "UNANSWERED",
        AlarmState.Cancelled to "CANCELLED",
        AlarmState.Missed to "MISSED",
    )

    private val plannerStates = mapOf(
        PlannerState.Unconfigured to "UNCONFIGURED",
        PlannerState.Planning to "PLANNING",
        PlannerState.Planned to "PLANNED",
        PlannerState.FailSafe to "FAIL_SAFE",
    )

    @Test
    fun `alarm states map to their stored names and back`() {
        alarmStates.forEach { (state, column) ->
            assertEquals(column, state.toColumn())
            assertEquals(state, alarmStateFromColumn(column))
        }
    }

    @Test
    fun `planner states map to their stored names and back`() {
        plannerStates.forEach { (state, column) ->
            assertEquals(column, state.toColumn())
            assertEquals(state, plannerStateFromColumn(column))
        }
    }

    @Test
    fun `every planning failure is stored by name and read back`() {
        de.drehtuer.nextalarm.domain.PlanningFailure.entries.forEach { failure ->
            val run = de.drehtuer.nextalarm.domain.PlanningRun(java.time.Instant.EPOCH, java.time.Duration.ZERO, null, failure)
            assertEquals(failure.name, run.toColumns().failure)
            assertEquals(run, run.toColumns().toDomain())
        }
    }

    @Test
    fun `an unknown state is an error, not a default`() {
        assertFailsWith<IllegalArgumentException> { alarmStateFromColumn("RINGIN") }
        assertFailsWith<IllegalArgumentException> { plannerStateFromColumn("PLANNIN") }
    }

    @Test
    fun `the system default sound is stored as null`() {
        assertNull(SoundRef.SystemDefault.toColumn())
        assertEquals(SoundRef.SystemDefault, soundRefFromColumn(null))
    }

    @Test
    fun `a stored sound keeps its file name`() {
        assertEquals("a", SoundRef.File("a").toColumn())
        assertEquals(SoundRef.File("a"), soundRefFromColumn("a"))
    }

    @Test
    fun `the chosen calendars are stored sorted, and read back`() {
        val several = setOf(CalendarId(9), CalendarId(3), CalendarId(12))
        assertEquals("3,9,12", toAlarmCalendarsColumn(several))
        assertEquals(several, alarmCalendarsFromColumn("3,9,12"))
        assertEquals("9", toAlarmCalendarsColumn(setOf(CalendarId(9))))
        assertEquals(setOf(CalendarId(9)), alarmCalendarsFromColumn("9"))
    }

    @Test
    fun `no chosen calendars are stored as an empty column`() {
        assertEquals("", toAlarmCalendarsColumn(emptySet()))
        assertEquals(emptySet(), alarmCalendarsFromColumn(""))
    }

    @Test
    fun `a damaged calendar column is an error, not a smaller choice`() {
        assertFailsWith<NumberFormatException> { alarmCalendarsFromColumn("3,x") }
    }

    @Test
    fun `news maps to its columns and back`() {
        assertEquals(NewsColumns("RSS", NEWS.url, 7), NEWS.toColumns())
        assertEquals(NEWS, NEWS.toColumns().toDomain())
        assertEquals(NewsType.PODCAST, NewsColumns("PODCAST", "u", 1).toDomain().type)
    }

    @Test
    fun `unknown news type is an error`() {
        assertFailsWith<IllegalArgumentException> { NewsColumns("RADIO", "u", 1).toDomain() }
    }
}
