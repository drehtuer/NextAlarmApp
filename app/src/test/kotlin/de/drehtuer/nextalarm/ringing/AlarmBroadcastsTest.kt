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
    private val broadcasts = AlarmBroadcasts(alarmScope(Dispatchers.Unconfined), engine)
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
