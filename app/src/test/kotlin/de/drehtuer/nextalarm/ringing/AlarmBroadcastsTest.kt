package de.drehtuer.nextalarm.ringing

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import de.drehtuer.nextalarm.data.DataComponents
import de.drehtuer.nextalarm.domain.AlarmAction
import de.drehtuer.nextalarm.domain.AlarmEffects
import de.drehtuer.nextalarm.domain.AlarmEngine
import de.drehtuer.nextalarm.domain.AlarmInstance
import de.drehtuer.nextalarm.domain.AlarmKey
import de.drehtuer.nextalarm.domain.AlarmRepository
import de.drehtuer.nextalarm.domain.AlarmRinger
import de.drehtuer.nextalarm.domain.AlarmState
import de.drehtuer.nextalarm.domain.Settings
import de.drehtuer.nextalarm.domain.snapshotFor
import de.drehtuer.nextalarm.planning.AlarmReceiver
import de.drehtuer.nextalarm.planning.MutexEngineLock
import de.drehtuer.nextalarm.planning.alarmInstanceId
import de.drehtuer.nextalarm.planning.alarmUri
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AlarmBroadcastsTest {
    private object NoEffects : AlarmEffects {
        override fun perform(instance: AlarmInstance, action: AlarmAction) = Unit
        override fun showNextAlarm(alarm: AlarmInstance?) = Unit
    }

    private val alarms = FakeAlarms(
        alarm(id = 1, state = AlarmState.Scheduled, triggerAt = AT.minus(Duration.ofMinutes(7))),
        alarm(id = 2, state = AlarmState.Ringing),
        alarm(id = 3, state = AlarmState.Ringing),
        alarm(id = 4, state = AlarmState.Snoozed),
    )
    private val scheduler = FakeScheduler()
    private val engine = AlarmEngine(alarms, scheduler, NoEffects, { AT }, MutexEngineLock())

    // With these fakes nothing leaves the thread, so everything is done when a call returns.
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var serviceStarts = 0
    private val foreground = AlarmForeground(AlarmNotifications(context)) { serviceStarts++ }
    private val sound = FakeSound()
    private val ringer = AlarmRinger(sound, FakeVibration(), FakeTimers(), FakeWakeLock(), {}, { foreground.ringingChanged(it) })
    private val broadcasts = AlarmBroadcasts(alarmScope(Dispatchers.Unconfined), engine, foreground, ringer, { AT })

    /** The real effects where they matter here. */
    private val ringingEffects = object : AlarmEffects {
        override fun perform(instance: AlarmInstance, action: AlarmAction) {
            when (action) {
                AlarmAction.JoinAlarmService -> ringer.join(instance)
                AlarmAction.LeaveAlarmService, AlarmAction.LeaveAllServices -> ringer.leave(instance.id)
                else -> Unit
            }
        }

        override fun showNextAlarm(alarm: AlarmInstance?) = Unit
        override fun ringing() = ringer.ringing()
    }
    private val ringingEngine = AlarmEngine(alarms, scheduler, ringingEffects, { AT }, MutexEngineLock())
    private val ringingBroadcasts =
        AlarmBroadcasts(alarmScope(Dispatchers.Unconfined), ringingEngine, foreground, ringer, { AT })
    private var finished = 0

    private fun state(id: Long) = alarms.stored[id]?.state

    private fun action(name: String, id: Long? = null) =
        Intent(name).also { intent -> id?.let { intent.data = alarmUri(it) } }

    @Test
    fun `Stop in the media notification ends the wake-up automation`() {
        alarms.stored[5] = alarm(id = 5, state = AlarmState.Announcing)
        alarms.stored[6] = alarm(id = 6, state = AlarmState.NewsPlaying)
        broadcasts.actionTapped(action(AlarmActionReceiver.ACTION_STOP_WAKE_UP)) { finished++ }
        assertEquals(AlarmState.Done, state(5))
        assertEquals(AlarmState.Done, state(6))
        assertEquals(AlarmState.Ringing, state(2))
        assertEquals(1, finished)
    }

    @Test
    fun `a fired alarm gets how late it is`() {
        broadcasts.fired(Intent().setData(alarmUri(1))) { finished++ }
        assertEquals(AlarmState.Ringing, state(1))
        assertEquals(Duration.ofMinutes(7), alarms.stored[1]?.lateBy)
        assertEquals(1, finished)
    }

    @Test
    fun `an intent without an alarm does nothing, but still finishes`() {
        val before = alarms.stored.toMap()
        broadcasts.fired(Intent()) { finished++ }
        broadcasts.fired(Intent().setData(Uri.parse("nextalarm://alarm/nothing"))) { finished++ }
        assertEquals(before, alarms.stored)
        assertEquals(2, finished)
    }

    @Test
    fun `an alarm's intent names the alarm`() {
        assertEquals(5L, alarmInstanceId(Intent().setData(alarmUri(5))))
        assertNull(alarmInstanceId(Intent()))
    }

    @Test
    fun `Snooze on the notification goes to all ringing alarms`() {
        broadcasts.actionTapped(action(AlarmActionReceiver.ACTION_SNOOZE_ALL)) { finished++ }
        assertEquals(AlarmState.Snoozed, state(2))
        assertEquals(AlarmState.Snoozed, state(3))
        assertEquals(0, alarms.stored[4]?.snoozeCount)
        assertEquals(1, finished)
    }

    @Test
    fun `Dismiss on the notification goes to all ringing alarms, and not to snoozed ones`() {
        broadcasts.actionTapped(action(AlarmActionReceiver.ACTION_DISMISS_ALL)) { finished++ }
        assertEquals(AlarmState.Done, state(2))
        assertEquals(AlarmState.Done, state(3))
        assertEquals(AlarmState.Snoozed, state(4))
        assertEquals(1, finished)
    }

    @Test
    fun `Dismiss on a snooze notification goes to that alarm only`() {
        broadcasts.actionTapped(action(AlarmActionReceiver.ACTION_DISMISS, id = 4)) { finished++ }
        assertEquals(AlarmState.Done, state(4))
        assertEquals(AlarmState.Ringing, state(2))
        assertEquals(1, finished)
    }

    @Test
    fun `Dismiss without an alarm does nothing`() {
        broadcasts.actionTapped(action(AlarmActionReceiver.ACTION_DISMISS)) { finished++ }
        assertEquals(AlarmState.Snoozed, state(4))
        assertEquals(1, finished)
    }

    @Test
    fun `a swiped notification of a ringing alarm comes back`() {
        foreground.ringingChanged(listOf(alarm(id = 2)))
        val shown = mutableListOf<Int>()
        val service = object : AlarmForegroundHost {
            override fun showForeground(id: Int, notification: android.app.Notification) {
                shown += id
            }

            override fun finishRinging() = Unit
        }
        foreground.attach(service)
        shown.clear()
        broadcasts.actionTapped(action(AlarmActionReceiver.ACTION_RINGING_SWIPED)) { finished++ }
        assertEquals(listOf(AlarmNotifications.ringingId(2)), shown)
        assertEquals(1, finished)
    }

    @Test
    fun `a swipe that comes after the alarm has ended brings nothing back`() {
        broadcasts.actionTapped(action(AlarmActionReceiver.ACTION_RINGING_SWIPED)) { finished++ }
        assertEquals(0, serviceStarts)
        assertEquals(1, finished)
    }

    @Test
    fun `an unknown action does nothing`() {
        broadcasts.actionTapped(action("something else")) { finished++ }
        assertEquals(AlarmState.Ringing, state(2))
        assertEquals(1, finished)
    }

    @Test
    fun `the sweep looks after every alarm`() {
        broadcasts.sweep { finished++ }
        assertEquals(AlarmState.Ringing, state(1))
        assertEquals(1, finished)
    }

    @Test
    fun `the sweep can run without anyone waiting for it`() {
        broadcasts.sweep()
        assertEquals(AlarmState.Ringing, state(1))
    }

    @Test
    fun `a failure ends the work properly and does not bring the app down`() {
        alarms.failure = IllegalStateException("storage failed")
        broadcasts.sweep { finished++ }
        assertEquals(1, finished)
    }

    @Test
    fun `an alarm that cannot be read rings anyway, the bundled sound through the service, and finishes`() {
        alarms.failure = IllegalStateException("storage failed")
        ringingBroadcasts.fired(Intent().setData(alarmUri(1))) { finished++ }
        assertEquals(1, finished)
        assertEquals(1, serviceStarts)
        val ringing = ringer.ringing().single()
        assertEquals(1, ringing.id)
        assertEquals(true, ringing.key.isEmergency)
        // Like any other alarm, it waits for the service.
        assertEquals(false, sound.lastResort)
        ringer.foregroundSettled()
        assertEquals(true, sound.lastResort)
    }

    @Test
    fun `Dismiss ends it without the database, which it does not touch`() {
        alarms.failure = IllegalStateException("storage failed")
        val before = alarms.stored.toMap()
        ringingBroadcasts.fired(Intent().setData(alarmUri(1))) {}
        ringingBroadcasts.actionTapped(action(AlarmActionReceiver.ACTION_DISMISS_ALL)) { finished++ }
        assertEquals(1, finished)
        assertEquals(true, ringer.ringing().isEmpty())
        assertEquals(false, sound.lastResort)
        assertEquals(before, alarms.stored)
    }

    @Test
    fun `Dismiss ends it without touching the stored alarm, also if the database works again by then`() {
        alarms.failure = IllegalStateException("storage failed")
        val before = alarms.stored.toMap()
        ringingBroadcasts.fired(Intent().setData(alarmUri(1))) {}
        alarms.failure = null
        ringingBroadcasts.actionTapped(action(AlarmActionReceiver.ACTION_DISMISS_ALL)) {}
        assertEquals(false, ringer.ringing().any { it.id == 1L })
        assertEquals(before[1], alarms.stored[1])
    }

    @Test
    fun `an alarm whose state cannot be stored rings as itself, and not a second time as an emergency`() {
        alarms.failOnUpdate = IllegalStateException("storage failed")
        ringingBroadcasts.fired(Intent().setData(alarmUri(1))) { finished++ }
        assertEquals(1, finished)
        assertEquals(false, ringer.ringing().single().key.isEmergency)
    }

    @Test
    fun `an intent without an alarm rings nothing, also if the database fails`() {
        alarms.failure = IllegalStateException("storage failed")
        ringingBroadcasts.fired(Intent()) { finished++ }
        assertEquals(true, ringer.ringing().isEmpty())
        assertEquals(1, finished)
    }

    @Test
    fun `a database that hangs is treated like one that fails`() {
        val hanging = object : AlarmRepository by alarms {
            override suspend fun find(id: Long): AlarmInstance? = kotlinx.coroutines.awaitCancellation()
        }
        val engine = AlarmEngine(hanging, scheduler, ringingEffects, { AT }, MutexEngineLock())
        val done = java.util.concurrent.CountDownLatch(1)
        val impatient = AlarmBroadcasts(
            alarmScope(Dispatchers.Default),
            engine,
            foreground,
            ringer,
            { AT },
            Duration.ofMillis(50),
        )
        impatient.fired(Intent().setData(alarmUri(1))) { done.countDown() }
        assertEquals(true, done.await(10, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(true, ringer.ringing().single().key.isEmergency)
    }

    @Test
    fun `being cancelled is not a failure that rings`() {
        val cancelling = object : AlarmRepository by alarms {
            override suspend fun find(id: Long): AlarmInstance? = throw kotlin.coroutines.cancellation.CancellationException("cancelled")
        }
        val engine = AlarmEngine(cancelling, scheduler, ringingEffects, { AT }, MutexEngineLock())
        val plain = AlarmBroadcasts(alarmScope(Dispatchers.Unconfined), engine, foreground, ringer, { AT })
        plain.fired(Intent().setData(alarmUri(1))) { finished++ }
        assertEquals(1, finished)
        assertEquals(true, ringer.ringing().isEmpty())
    }

    private class WaitingService : AlarmForegroundHost {
        var finished = 0

        override fun showForeground(id: Int, notification: android.app.Notification) = Unit

        override fun finishRinging() {
            finished++
        }
    }

    @Test
    fun `the end of the sweep lets a service that waited for it stop`() {
        alarms.stored.clear()
        val service = WaitingService()
        foreground.attach(service)
        assertEquals(0, service.finished)
        broadcasts.sweep()
        assertEquals(1, service.finished)
    }

    @Test
    fun `a sweep that fails does not leave the service waiting for ever`() {
        val service = WaitingService()
        foreground.attach(service)
        alarms.failure = IllegalStateException("storage failed")
        broadcasts.sweep()
        assertEquals(1, service.finished)
    }
}

/** On the real application and its real parts, as Android delivers these broadcasts. No real alarm rings. */
@RunWith(RobolectricTestRunner::class)
class AlarmReceiversTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val data = DataComponents(context)
    private val time = Instant.now()

    private fun stored(state: AlarmState) = runBlocking {
        val created = data.alarms.create(AlarmKey.forEvent(1, time), time, Settings.DEFAULT.snapshotFor("Gym"))
        created.copy(state = state).also { data.alarms.update(it) }
    }

    private fun state(id: Long) = runBlocking { data.alarms.find(id)?.state }

    @Test
    fun `the alarm receiver rings the alarm in the intent`() {
        val instance = stored(AlarmState.Scheduled)
        context.sendBroadcast(Intent(context, AlarmReceiver::class.java).setData(alarmUri(instance.id)))
        awaitUntil { state(instance.id) == AlarmState.Ringing }
    }

    @Test
    fun `the action receiver dismisses the alarm in the intent, through to the end`() {
        val instance = stored(AlarmState.Snoozed)
        val intent = Intent(context, AlarmActionReceiver::class.java)
            .setAction(AlarmActionReceiver.ACTION_DISMISS)
            .setData(alarmUri(instance.id))
        context.sendBroadcast(intent)
        awaitUntil { state(instance.id) == AlarmState.Done }
    }

    @Test
    fun `the locked boot receiver runs the sweep`() {
        val instance = stored(AlarmState.Announcing)
        context.sendBroadcast(Intent(context, LockedBootReceiver::class.java))
        awaitUntil { state(instance.id) == AlarmState.Done }
    }
}
