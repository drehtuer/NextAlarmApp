package de.drehtuer.nextalarm.domain

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class AlarmEngineTest {
    private val alarms = FakeAlarmRepository()
    private val scheduler = FakeScheduler()
    private val clock = FakeClock()
    private val performed = mutableListOf<AlarmAction>()
    private val nextAlarms = mutableListOf<AlarmInstance?>()
    private val effects = object : AlarmEffects {
        override fun perform(instance: AlarmInstance, action: AlarmAction) {
            performed += action
        }

        override fun showNextAlarm(alarm: AlarmInstance?) {
            nextAlarms += alarm
        }
    }
    private val engine = AlarmEngine(alarms, scheduler, effects, clock::now, FakeLock())

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
    fun `a snooze rings on the minute the screen announced`() = runBlocking {
        val instance = stored(AlarmState.Ringing)
        clock.time = NOW.plusSeconds(30)
        engine.handle(instance.id, AlarmEvent.Snooze)
        assertEquals(NOW.plus(Duration.ofMinutes(10)), alarms.find(instance.id)?.triggerAt)
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

    @Test
    fun `firing works out how late the alarm is`() = runBlocking {
        val instance = stored(AlarmState.Scheduled, triggerAt = NOW.minus(Duration.ofMinutes(7)))
        engine.fire(instance.id)
        val updated = alarms.find(instance.id)!!
        assertEquals(AlarmState.Ringing, updated.state)
        assertEquals(Duration.ofMinutes(7), updated.lateBy)
    }

    @Test
    fun `firing an unknown alarm does nothing`() = runBlocking {
        engine.fire(404)
        assertTrue(performed.isEmpty())
    }

    @Test
    fun `an alarm that fires too late is missed`() = runBlocking {
        val instance = stored(AlarmState.Scheduled, triggerAt = NOW.minus(Duration.ofMinutes(61)))
        engine.fire(instance.id)
        val updated = alarms.find(instance.id)!!
        assertEquals(AlarmState.Missed, updated.state)
        assertNull(updated.lateBy)
    }

    @Test
    fun `an alarm that rings on time has not been late`() = runBlocking {
        val instance = stored(AlarmState.Scheduled, triggerAt = NOW.minusSeconds(30))
        engine.fire(instance.id)
        assertNull(alarms.find(instance.id)?.lateBy)
    }

    @Test
    fun `the late notification can say how late`() = runBlocking {
        val instance = stored(AlarmState.Scheduled, triggerAt = NOW.minus(Duration.ofMinutes(7)))
        val seen = mutableListOf<AlarmInstance>()
        val effects = object : AlarmEffects {
            override fun perform(instance: AlarmInstance, action: AlarmAction) {
                if (action == AlarmAction.PostLateNotification) seen += instance
            }

            override fun showNextAlarm(alarm: AlarmInstance?) = Unit
        }
        AlarmEngine(alarms, scheduler, effects, clock::now, FakeLock()).fire(instance.id)
        assertEquals(Duration.ofMinutes(7), seen.single().lateBy)
    }

    @Test
    fun `an alarm that rang late stays late through a snooze that ends on time`() = runBlocking {
        val instance = stored(AlarmState.Scheduled, triggerAt = NOW.minus(Duration.ofMinutes(7)))
        engine.fire(instance.id)
        engine.handle(instance.id, AlarmEvent.Snooze)
        clock.time = NOW.plus(Duration.ofMinutes(10))
        engine.fire(instance.id)
        assertEquals(AlarmState.Ringing, alarms.find(instance.id)?.state)
        assertEquals(Duration.ofMinutes(7), alarms.find(instance.id)?.lateBy)
    }

    @Test
    fun `a snooze that ends late counts as late`() = runBlocking {
        val instance = stored(AlarmState.Ringing)
        engine.handle(instance.id, AlarmEvent.Snooze)
        clock.time = NOW.plus(Duration.ofMinutes(30))
        engine.fire(instance.id)
        assertEquals(Duration.ofMinutes(20), alarms.find(instance.id)?.lateBy)
    }

    @Test
    fun `an alarm that rings again after a crash does not count as late`() = runBlocking {
        val instance = stored(AlarmState.Ringing, triggerAt = NOW.minus(Duration.ofMinutes(5)))
        engine.fire(instance.id)
        assertEquals(AlarmState.Ringing, alarms.find(instance.id)?.state)
        assertNull(alarms.find(instance.id)?.lateBy)
    }

    @Test
    fun `an event goes to the ringing alarms only`() = runBlocking {
        stored(AlarmState.Ringing, id = 1)
        stored(AlarmState.Ringing, id = 2)
        stored(AlarmState.Snoozed, id = 3)
        stored(AlarmState.Scheduled, id = 4)
        engine.sendToRinging(AlarmEvent.Snooze)
        assertEquals(AlarmState.Snoozed, alarms.find(1)?.state)
        assertEquals(AlarmState.Snoozed, alarms.find(2)?.state)
        assertEquals(0, alarms.find(3)?.snoozeCount)
        assertEquals(AlarmState.Scheduled, alarms.find(4)?.state)
    }

    @Test
    fun `dismissing all ringing alarms leaves snoozed ones waiting`() = runBlocking {
        stored(AlarmState.Ringing, id = 1)
        stored(AlarmState.Snoozed, id = 2)
        engine.sendToRinging(AlarmEvent.Dismiss)
        assertEquals(AlarmState.Announcing, alarms.find(1)?.state)
        assertEquals(AlarmState.Snoozed, alarms.find(2)?.state)
    }

    @Test
    fun `stopping the wake-up automation ends every alarm in it, and only those`() = runBlocking {
        stored(AlarmState.Announcing, id = 1)
        stored(AlarmState.NewsPlaying, id = 2)
        stored(AlarmState.Ringing, id = 3)
        stored(AlarmState.Snoozed, id = 4)
        engine.stopWakeUp()
        assertEquals(AlarmState.Done, alarms.find(1)?.state)
        assertEquals(AlarmState.Done, alarms.find(2)?.state)
        assertEquals(AlarmState.Ringing, alarms.find(3)?.state)
        assertEquals(AlarmState.Snoozed, alarms.find(4)?.state)
        assertEquals(2, performed.count { it == AlarmAction.LeaveAllServices })
    }

    @Test
    fun `the recovery sweep does what the persisted state asks for`() = runBlocking {
        stored(AlarmState.Scheduled, id = 1)
        stored(AlarmState.Snoozed, id = 2)
        stored(AlarmState.Scheduled, id = 3, triggerAt = NOW.minus(Duration.ofMinutes(5)))
        stored(AlarmState.Ringing, id = 4, triggerAt = NOW.minus(Duration.ofMinutes(61)))
        stored(AlarmState.Announcing, id = 5)
        stored(AlarmState.Done, id = 6)
        engine.recover()
        assertEquals(setOf(1L, 2L), scheduler.scheduled.keys)
        assertEquals(AlarmState.Scheduled, alarms.find(1)?.state)
        assertEquals(AlarmState.Snoozed, alarms.find(2)?.state)
        assertEquals(AlarmState.Ringing, alarms.find(3)?.state)
        assertEquals(AlarmState.Missed, alarms.find(4)?.state)
        assertEquals(AlarmState.Done, alarms.find(5)?.state)
        assertEquals(AlarmState.Done, alarms.find(6)?.state)
    }

    @Test
    fun `a sweep gives a snoozed alarm its notification back, as a reboot clears it`() = runBlocking {
        stored(AlarmState.Snoozed, id = 1)
        stored(AlarmState.Scheduled, id = 2)
        engine.recover()
        assertEquals(listOf<AlarmAction>(AlarmAction.PostSnoozeNotification), performed)
    }

    @Test
    fun `running the sweep twice is harmless`() = runBlocking {
        val instance = stored(AlarmState.Scheduled, triggerAt = NOW.minus(Duration.ofMinutes(5)))
        engine.recover()
        val once = alarms.find(instance.id)
        performed.clear()
        engine.recover()
        assertEquals(once, alarms.find(instance.id))
        assertEquals(listOf<AlarmAction>(AlarmAction.JoinAlarmService), performed)
    }

    @Test
    fun `the sweep shows the next alarm, which a reboot cleared`() = runBlocking {
        val instance = stored(AlarmState.Scheduled)
        engine.recover()
        assertEquals(listOf<AlarmInstance?>(instance), nextAlarms)
    }

    @Test
    fun `a change of state refreshes the next alarm`() = runBlocking {
        val first = stored(AlarmState.Scheduled, id = 1)
        val second = stored(AlarmState.Scheduled, id = 2, triggerAt = future.plusSeconds(60))
        engine.handle(first.id, AlarmEvent.Remove)
        assertEquals(listOf<AlarmInstance?>(second), nextAlarms)
        engine.handle(second.id, AlarmEvent.Remove)
        assertEquals(listOf(second, null), nextAlarms)
    }

    @Test
    fun `an ignored event does not refresh the next alarm`() = runBlocking {
        val instance = stored(AlarmState.Done)
        engine.handle(instance.id, AlarmEvent.Remove)
        assertTrue(nextAlarms.isEmpty())
    }

    @Test
    fun `every call goes through the lock`() = runBlocking {
        val lock = FakeLock()
        val locked = AlarmEngine(alarms, scheduler, effects, clock::now, lock)
        val instance = stored(AlarmState.Scheduled)
        locked.create(PlanEntry(AlarmKey.forEvent(9, future), future, snapshot()))
        locked.handle(instance.id, AlarmEvent.Refresh(snapshot()))
        locked.fire(instance.id)
        locked.sendToRinging(AlarmEvent.Snooze)
        locked.stopWakeUp()
        locked.registerWaiting()
        locked.recover()
        assertEquals(7, lock.uses)
    }
}
