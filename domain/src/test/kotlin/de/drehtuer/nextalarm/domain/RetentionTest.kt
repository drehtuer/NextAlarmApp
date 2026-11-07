package de.drehtuer.nextalarm.domain

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

private val ALL_STATES = listOf(
    AlarmState.Scheduled,
    AlarmState.Ringing,
    AlarmState.Snoozed,
    AlarmState.Announcing,
    AlarmState.NewsPlaying,
    AlarmState.Done,
    AlarmState.Unanswered,
    AlarmState.Cancelled,
    AlarmState.Missed,
)

private val TERMINAL_STATES = setOf(AlarmState.Done, AlarmState.Unanswered, AlarmState.Cancelled, AlarmState.Missed)

class RetentionTest {
    private val old: Instant = NOW.minus(Duration.ofDays(30))

    private fun alarm(state: AlarmState, at: Instant = old, id: Long = 1) =
        AlarmInstance(id, AlarmKey.forTestAlarm(id), at, state, 0, false, snapshot())

    @Test
    fun `every state is terminal or not`() {
        ALL_STATES.forEach { assertEquals(it in TERMINAL_STATES, it.isTerminal, it.toString()) }
    }

    @Test
    fun `an old terminal alarm has expired`() {
        TERMINAL_STATES.forEach { state ->
            assertEquals(listOf(1L), expiredAlarms(listOf(alarm(state)), NOW).map { it.id }, state.toString())
        }
    }

    @Test
    fun `an alarm that has not ended never expires, however old`() {
        ALL_STATES.filterNot { it in TERMINAL_STATES }.forEach { state ->
            assertTrue(expiredAlarms(listOf(alarm(state)), NOW).isEmpty(), state.toString())
        }
    }

    @Test
    fun `an alarm stays for exactly 7 days`() {
        assertEquals(Duration.ofDays(7), ALARM_RETENTION)
        val edge = NOW.minus(ALARM_RETENTION)
        assertTrue(expiredAlarms(listOf(alarm(AlarmState.Done, edge)), NOW).isEmpty())
        assertEquals(1, expiredAlarms(listOf(alarm(AlarmState.Done, edge.minusMillis(1))), NOW).size)
    }

    @Test
    fun `an alarm cancelled ahead of its time stays`() {
        assertTrue(expiredAlarms(listOf(alarm(AlarmState.Cancelled, NOW.plus(Duration.ofDays(1)))), NOW).isEmpty())
    }

    @Test
    fun `the engine deletes expired alarms and nothing else, and registers nothing`() = runBlocking {
        val alarms = FakeAlarmRepository()
        val scheduler = FakeScheduler()
        val lock = FakeLock()
        val engine = AlarmEngine(alarms, scheduler, NoAlarmEffects, { NOW }, lock)
        alarms.add(alarm(AlarmState.Done, id = 1))
        alarms.add(alarm(AlarmState.Ringing, id = 2))
        alarms.add(alarm(AlarmState.Missed, NOW, id = 3))
        engine.removeExpired()
        assertEquals(listOf(2L, 3L), alarms.all().map { it.id })
        assertTrue(scheduler.scheduled.isEmpty())
        assertTrue(scheduler.cancelled.isEmpty())
        assertEquals(1, lock.uses)
    }

    @Test
    fun `a deleted alarm from the calendar does not come back, as its event is in the past`() {
        val event = CalendarEvent(1, old, "Old")
        assertTrue(plan(NOW, listOf(event), settings()).entries.isEmpty())
    }
}
