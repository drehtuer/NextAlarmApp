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

    /** Effects like the real ones in the one way that matters here: they know what rings. */
    private inner class RingingEffects(vararg ringing: AlarmInstance) : AlarmEffects {
        val rings = ringing.toMutableList()
        val log = mutableListOf<Pair<Long, AlarmAction>>()
        var failFor: Set<Long> = emptySet()
        var failNextAlarm = false

        override fun perform(instance: AlarmInstance, action: AlarmAction) {
            check(instance.id !in failFor) { "effect failed" }
            log += instance.id to action
            when (action) {
                AlarmAction.JoinAlarmService -> rings += instance
                AlarmAction.LeaveAlarmService, AlarmAction.LeaveAllServices -> rings.removeAll { it.id == instance.id }
                else -> Unit
            }
        }

        override fun showNextAlarm(alarm: AlarmInstance?) {
            check(!failNextAlarm) { "effect failed" }
        }

        override fun ringing(): List<AlarmInstance> = rings.toList()
    }

    private fun engineWith(effects: AlarmEffects) = AlarmEngine(alarms, scheduler, effects, clock::now, FakeLock())

    private suspend fun fails(block: suspend () -> Unit): Throwable? = try {
        block()
        null
    } catch (failure: IllegalStateException) {
        failure
    }

    @Test
    fun `the sweep goes on after an alarm that fails, and reports the failure at the end`() = runBlocking {
        stored(AlarmState.Scheduled, id = 1)
        stored(AlarmState.Scheduled, id = 2)
        stored(AlarmState.Scheduled, id = 3, triggerAt = NOW.minusSeconds(60))
        stored(AlarmState.Scheduled, id = 4)
        stored(AlarmState.Scheduled, id = 5, triggerAt = NOW.minusSeconds(60))
        scheduler.failFor = setOf(1)
        alarms.failOnUpdate = { it == 3L }
        val effects = RingingEffects()
        assertTrue(fails { engineWith(effects).recover() } != null)
        assertEquals(setOf(2L, 4L), scheduler.scheduled.keys)
        assertEquals(setOf(3L, 5L), effects.rings.map { it.id }.toSet())
    }

    @Test
    fun `the sweep still shows the next alarm after a failure, and a failure there is reported too`() = runBlocking {
        stored(AlarmState.Scheduled, id = 1)
        scheduler.failFor = setOf(1)
        assertTrue(fails { engine.recover() } != null)
        assertEquals(1, nextAlarms.size)

        scheduler.failFor = emptySet()
        val effects = RingingEffects().apply { failNextAlarm = true }
        assertTrue(fails { engineWith(effects).recover() } != null)
        assertEquals(setOf(1L), scheduler.scheduled.keys)
    }

    @Test
    fun `registering goes on after an alarm that fails`() = runBlocking {
        stored(AlarmState.Scheduled, id = 1)
        stored(AlarmState.Snoozed, id = 2)
        stored(AlarmState.Scheduled, id = 3)
        scheduler.failFor = setOf(1, 2)
        assertTrue(fails { engine.registerWaiting() } != null)
        assertEquals(setOf(3L), scheduler.scheduled.keys)
    }

    @Test
    fun `a dismiss that fails for one alarm still reaches the others`() = runBlocking {
        val one = stored(AlarmState.Ringing, id = 1)
        val two = stored(AlarmState.Ringing, id = 2)
        val effects = RingingEffects(one, two).apply { failFor = setOf(1) }
        assertTrue(fails { engineWith(effects).sendToRinging(AlarmEvent.Dismiss) } != null)
        assertEquals(listOf(1L), effects.rings.map { it.id })
        assertEquals(AlarmState.Announcing, alarms.find(2)?.state)
    }

    @Test
    fun `a timeout that fails for one alarm still reaches the others`() = runBlocking {
        stored(AlarmState.Ringing, id = 1)
        stored(AlarmState.Ringing, id = 2)
        alarms.failOnUpdate = { it == 1L }
        assertTrue(fails { engine.ringTimedOut(listOf(1, 2)) } != null)
        assertEquals(AlarmState.Snoozed, alarms.find(2)?.state)
        assertEquals(2, performed.count { it == AlarmAction.LeaveAlarmService })
    }

    @Test
    fun `stopping the wake-up automation goes on after an alarm that fails`() = runBlocking {
        val failing = RingingEffects().apply { failFor = setOf(1) }
        stored(AlarmState.Announcing, id = 1)
        stored(AlarmState.NewsPlaying, id = 2)
        assertTrue(fails { engineWith(failing).stopWakeUp() } != null)
        assertEquals(AlarmState.Done, alarms.find(2)?.state)
    }

    @Test
    fun `being cancelled is not a failure to carry on from`(): Unit = runBlocking {
        stored(AlarmState.Scheduled, id = 1)
        stored(AlarmState.Scheduled, id = 2)
        val cancelling = object : AlarmScheduler {
            var calls = 0

            override fun schedule(instanceId: Long, at: java.time.Instant) {
                calls++
                throw kotlin.coroutines.cancellation.CancellationException("cancelled")
            }

            override fun cancel(instanceId: Long) = Unit
        }
        val engine = AlarmEngine(alarms, cancelling, NoAlarmEffects, clock::now, FakeLock())
        kotlin.test.assertFailsWith<kotlin.coroutines.cancellation.CancellationException> { engine.registerWaiting() }
        assertEquals(1, cancelling.calls)
    }

    @Test
    fun `several failures come out as the first, with the others attached`() = runBlocking {
        stored(AlarmState.Scheduled, id = 1)
        stored(AlarmState.Scheduled, id = 2)
        scheduler.failFor = setOf(1, 2)
        val failure = fails { engine.registerWaiting() }
        assertEquals(1, failure?.suppressedExceptions?.size)
    }

    @Test
    fun `if the state cannot be stored, Dismiss still stops the sound, without starting the wake-up automation`() = runBlocking {
        val instance = stored(AlarmState.Ringing)
        alarms.failOnUpdate = { true }
        val effects = RingingEffects(instance)
        assertTrue(fails { engineWith(effects).sendToRinging(AlarmEvent.Dismiss) } != null)
        assertTrue(effects.rings.isEmpty())
        assertEquals(listOf<Pair<Long, AlarmAction>>(1L to AlarmAction.LeaveAlarmService), effects.log)
        assertEquals(listOf(1L), scheduler.cancelled)
        assertEquals(AlarmState.Ringing, alarms.find(1)?.state)
    }

    @Test
    fun `if the state cannot be stored, news does not start either`() = runBlocking {
        val instance = alarms.add(
            AlarmInstance(1, AlarmKey.forEvent(1, future), future, AlarmState.Ringing, 0, false, snapshot(speakReason = false, news = NEWS)),
        )
        alarms.failOnUpdate = { true }
        val effects = RingingEffects(instance)
        assertTrue(fails { engineWith(effects).sendToRinging(AlarmEvent.Dismiss) } != null)
        assertEquals(listOf<Pair<Long, AlarmAction>>(1L to AlarmAction.LeaveAlarmService), effects.log)
    }

    @Test
    fun `if the state cannot be stored, the timeout still stops the sound, and the snooze still rings again`() = runBlocking {
        val instance = stored(AlarmState.Ringing)
        alarms.failOnUpdate = { true }
        val effects = RingingEffects(instance)
        assertTrue(fails { engineWith(effects).ringTimedOut(listOf(1)) } != null)
        assertTrue(effects.rings.isEmpty())
        assertEquals(NOW.plus(Duration.ofMinutes(10)), scheduler.scheduled[1])
        assertTrue(1L to AlarmAction.PostSnoozeNotification in effects.log)
    }

    @Test
    fun `if the state cannot be stored, a fired alarm still rings`() = runBlocking {
        stored(AlarmState.Scheduled, triggerAt = NOW)
        alarms.failOnUpdate = { true }
        val effects = RingingEffects()
        assertTrue(fails { engineWith(effects).fire(1) } != null)
        assertEquals(listOf(1L), effects.rings.map { it.id })
        assertEquals(AlarmState.Ringing, effects.rings.single().state)
    }

    @Test
    fun `an alarm that rings although the database does not say so can still be stopped`() = runBlocking {
        stored(AlarmState.Scheduled, triggerAt = NOW)
        alarms.failOnUpdate = { true }
        val effects = RingingEffects()
        val engine = engineWith(effects)
        fails { engine.fire(1) }
        alarms.failOnUpdate = { false }
        engine.sendToRinging(AlarmEvent.Dismiss)
        assertTrue(effects.rings.isEmpty())
        assertEquals(AlarmState.Announcing, alarms.find(1)?.state)
    }

    @Test
    fun `without a readable database, what rings can still be stopped`() = runBlocking {
        val instance = stored(AlarmState.Ringing)
        val effects = RingingEffects(instance)
        val engine = engineWith(effects)
        alarms.failOnRead = true
        alarms.failOnUpdate = { true }
        assertTrue(fails { engine.sendToRinging(AlarmEvent.Dismiss) } != null)
        assertTrue(effects.rings.isEmpty())

        effects.rings += instance
        assertTrue(fails { engine.ringTimedOut(listOf(1)) } != null)
        assertTrue(effects.rings.isEmpty())
    }

    @Test
    fun `if the state cannot be stored, a removed alarm stays, as a failure must never remove an alarm`() = runBlocking {
        stored(AlarmState.Scheduled)
        alarms.failOnUpdate = { true }
        assertTrue(fails { engine.handle(1, AlarmEvent.Remove) } != null)
        assertTrue(performed.isEmpty())
        assertTrue(scheduler.cancelled.isEmpty())
    }

    @Test
    fun `a failure of the next alarm does not get in the way of the alarm itself`() = runBlocking {
        val instance = stored(AlarmState.Ringing)
        val effects = RingingEffects(instance).apply { failNextAlarm = true }
        assertTrue(fails { engineWith(effects).sendToRinging(AlarmEvent.Dismiss) } != null)
        assertEquals(AlarmState.Announcing, alarms.find(1)?.state)
        assertTrue(effects.rings.isEmpty())
    }

    @Test
    fun `an effect that fails does not keep the others from running`() = runBlocking {
        val instance = stored(AlarmState.Ringing)
        val effects = object : AlarmEffects {
            override fun perform(instance: AlarmInstance, action: AlarmAction) {
                performed += action
                check(action != AlarmAction.LeaveAlarmService) { "effect failed" }
            }

            override fun showNextAlarm(alarm: AlarmInstance?) = Unit
        }
        assertTrue(fails { engineWith(effects).handle(instance.id, AlarmEvent.Snooze) } != null)
        assertTrue(AlarmAction.PostSnoozeNotification in performed)
    }

    @Test
    fun `an emergency alarm is ended without storing anything`() = runBlocking {
        val real = stored(AlarmState.Scheduled)
        val effects = RingingEffects(emergencyAlarm(real.id, NOW))
        engineWith(effects).sendToRinging(AlarmEvent.Dismiss)
        assertTrue(effects.rings.isEmpty())
        assertEquals(real, alarms.find(real.id))
        assertEquals(listOf(real.id), scheduler.cancelled)
    }

    @Test
    fun `an emergency alarm snoozed registers the alarm again, for the real one to ring`() = runBlocking {
        val real = stored(AlarmState.Scheduled)
        val effects = RingingEffects(emergencyAlarm(real.id, NOW))
        engineWith(effects).sendToRinging(AlarmEvent.Snooze)
        assertEquals(NOW.plus(Duration.ofMinutes(10)), scheduler.scheduled[real.id])
        assertEquals(real, alarms.find(real.id))
    }

    @Test
    fun `the default effects know of nothing that rings`() {
        assertTrue(NoAlarmEffects.ringing().isEmpty())
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
        locked.ringTimedOut(listOf(instance.id))
        assertEquals(8, lock.uses)
    }
}
