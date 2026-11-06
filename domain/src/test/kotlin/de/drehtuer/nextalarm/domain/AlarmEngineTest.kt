package de.drehtuer.nextalarm.domain

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class AlarmEngineTest {
    private val alarms = FakeAlarmRepository()
    private val scheduler = FakeScheduler()
    private val clock = FakeClock()
    private val performed = mutableListOf<AlarmAction>()
    private val engine = AlarmEngine(alarms, scheduler, { _, action -> performed += action }, clock::now)

    private val future = NOW.plus(Duration.ofHours(2))

    private fun stored(state: AlarmState, id: Long = 1, triggerAt: java.time.Instant = future, autoSnoozed: Boolean = false) =
        alarms.add(AlarmInstance(id, AlarmKey.forEvent(id, future), triggerAt, state, 0, autoSnoozed, snapshot()))

    @Test
    fun `creating stores the alarm and registers it`() = runBlocking {
        engine.create(PlanEntry(AlarmKey.forEvent(5, future), future, snapshot()))
        val created = alarms.all().single()
        assertEquals(AlarmState.Scheduled, created.state)
        assertEquals(future, scheduler.scheduled[created.id])
    }

    @Test
    fun `an unknown alarm is ignored`() = runBlocking {
        engine.handle(404, AlarmEvent.Remove)
        assertTrue(alarms.all().isEmpty())
    }

    @Test
    fun `an ignored event changes nothing`() = runBlocking {
        val instance = stored(AlarmState.Done)
        engine.handle(instance.id, AlarmEvent.Fire(Duration.ZERO))
        assertEquals(instance, alarms.find(instance.id))
        assertTrue(scheduler.scheduled.isEmpty())
        assertTrue(performed.isEmpty())
    }

    @Test
    fun `a refresh replaces the snapshot without registering again`() = runBlocking {
        val instance = stored(AlarmState.Scheduled)
        val newer = snapshot(reason = "New")
        engine.handle(instance.id, AlarmEvent.Refresh(newer))
        assertEquals(newer, alarms.find(instance.id)?.snapshot)
        assertTrue(scheduler.scheduled.isEmpty())
    }

    @Test
    fun `removing cancels the alarm and removes its notifications`() = runBlocking {
        val instance = stored(AlarmState.Scheduled)
        engine.handle(instance.id, AlarmEvent.Remove)
        assertEquals(AlarmState.Cancelled, alarms.find(instance.id)?.state)
        assertEquals(listOf(instance.id), scheduler.cancelled)
        assertEquals(listOf<AlarmAction>(AlarmAction.RemoveNotifications), performed)
    }

    @Test
    fun `firing hands the ringing over to the effects`() = runBlocking {
        val instance = stored(AlarmState.Scheduled)
        engine.handle(instance.id, AlarmEvent.Fire(Duration.ofMinutes(5)))
        assertEquals(AlarmState.Ringing, alarms.find(instance.id)?.state)
        assertEquals(listOf(AlarmAction.JoinAlarmService, AlarmAction.PostLateNotification), performed)
    }

    @Test
    fun `a manual snooze moves the alarm, counts it and clears the automatic mark`() = runBlocking {
        val instance = stored(AlarmState.Ringing, autoSnoozed = true)
        engine.handle(instance.id, AlarmEvent.Snooze)
        val updated = alarms.find(instance.id)!!
        val ringsAt = NOW.plus(Duration.ofMinutes(10))
        assertEquals(AlarmState.Snoozed, updated.state)
        assertEquals(ringsAt, updated.triggerAt)
        assertEquals(1, updated.snoozeCount)
        assertFalse(updated.autoSnoozed)
        assertEquals(ringsAt, scheduler.scheduled[instance.id])
        assertEquals(listOf(AlarmAction.LeaveAlarmService, AlarmAction.PostSnoozeNotification), performed)
    }

    @Test
    fun `a timeout snoozes automatically`() = runBlocking {
        val instance = stored(AlarmState.Ringing)
        engine.handle(instance.id, AlarmEvent.RingTimeout)
        assertTrue(alarms.find(instance.id)!!.autoSnoozed)
    }

    @Test
    fun `every service and notification action reaches the effects`() = runBlocking {
        fun at(id: Long, state: AlarmState, snapshot: AlarmSnapshot = snapshot(), autoSnoozed: Boolean = false) =
            alarms.add(AlarmInstance(id, AlarmKey.forEvent(id, future), future, state, 0, autoSnoozed, snapshot)).id

        engine.handle(at(1, AlarmState.Scheduled), AlarmEvent.Fire(Duration.ofMinutes(5)))
        engine.handle(at(2, AlarmState.Scheduled), AlarmEvent.Fire(Duration.ofHours(2)))
        engine.handle(at(3, AlarmState.Ringing), AlarmEvent.Snooze)
        engine.handle(at(4, AlarmState.Ringing, autoSnoozed = true), AlarmEvent.RingTimeout)
        engine.handle(at(5, AlarmState.Ringing), AlarmEvent.Dismiss)
        engine.handle(at(6, AlarmState.Ringing, snapshot(speakReason = false, news = NEWS)), AlarmEvent.Dismiss)
        engine.handle(at(7, AlarmState.Announcing), AlarmEvent.StopWakeUp)

        assertEquals(
            setOf(
                AlarmAction.JoinAlarmService,
                AlarmAction.LeaveAlarmService,
                AlarmAction.LeaveAllServices,
                AlarmAction.StartAnnouncement,
                AlarmAction.StartNews,
                AlarmAction.PostSnoozeNotification,
                AlarmAction.PostLateNotification,
                AlarmAction.PostMissedNotification,
                AlarmAction.PostUnansweredNotification,
                AlarmAction.RemoveNotifications,
            ),
            performed.toSet(),
        )
    }

    @Test
    fun `waiting alarms are registered again, others are not`() = runBlocking {
        stored(AlarmState.Scheduled, id = 1)
        stored(AlarmState.Snoozed, id = 2)
        stored(AlarmState.Ringing, id = 3)
        stored(AlarmState.Done, id = 4)
        stored(AlarmState.Scheduled, id = 5, triggerAt = NOW)
        stored(AlarmState.Snoozed, id = 6, triggerAt = NOW.minusSeconds(1))
        engine.registerWaiting()
        assertEquals(setOf(1L, 2L), scheduler.scheduled.keys)
    }
}
