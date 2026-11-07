package de.drehtuer.nextalarm.ringing

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AlarmForegroundTest {
    private class FakeHost(val refuse: Boolean = false) : AlarmForegroundHost {
        val shown = mutableListOf<Pair<Int, String>>()
        var finished = 0

        override fun showForeground(id: Int, notification: Notification) {
            check(!refuse) { "Foreground services are not allowed now" }
            shown += id to notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
        }

        override fun finishRinging() {
            finished++
        }
    }

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val notifications = AlarmNotifications(context)
    private var starts = 0
    private var refuseStart = false
    private var settled = 0
    private val foreground = AlarmForeground(notifications, { settled++ }) {
        check(!refuseStart) { "Foreground services are not allowed now" }
        starts++
    }

    private fun posted(id: Long) = shadowOf(manager).getNotification(AlarmNotifications.ringingId(id))
    private fun warning() = shadowOf(manager).getNotification(AlarmNotifications.FOREGROUND_REFUSED_ID)

    @Test
    fun `the sound may start once the service has gone into the foreground, and not when it is only asked for`() {
        foreground.ringingChanged(listOf(alarm(1)))
        assertEquals(0, settled)
        foreground.attach(FakeHost())
        assertEquals(1, settled)
    }

    @Test
    fun `the sound may start if the service may not start, and a warning says it may be silent`() {
        refuseStart = true
        foreground.ringingChanged(listOf(alarm(1)))
        assertEquals(1, settled)
        val shown = assertNotNull(warning())
        assertEquals("This alarm may be silent", shown.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
    }

    @Test
    fun `the sound may start if the service may not go into the foreground, and a warning says it may be silent`() {
        foreground.ringingChanged(listOf(alarm(1)))
        foreground.attach(FakeHost(refuse = true))
        assertEquals(1, settled)
        assertNotNull(warning())
    }

    @Test
    fun `a refusal other than the usual one counts as a refusal too`() {
        val host = object : AlarmForegroundHost {
            override fun showForeground(id: Int, notification: Notification) = throw SecurityException("no permission")
            override fun finishRinging() = Unit
        }
        foreground.ringingChanged(listOf(alarm(1)))
        foreground.attach(host)
        assertEquals(1, settled)
        assertNotNull(warning())
    }

    @Test
    fun `the warning goes when the service does come up`() {
        refuseStart = true
        foreground.ringingChanged(listOf(alarm(1)))
        refuseStart = false
        foreground.sync()
        foreground.attach(FakeHost())
        assertNull(warning())
    }

    @Test
    fun `nothing to warn about without alarms`() {
        foreground.attach(FakeHost(refuse = true))
        assertNull(warning())
    }

    @Test
    fun `an alarm that joins a service that is up has nothing to wait for`() {
        foreground.ringingChanged(listOf(alarm(1)))
        foreground.attach(FakeHost())
        settled = 0
        foreground.ringingChanged(listOf(alarm(1), alarm(2)))
        assertEquals(1, settled)
    }

    /** The ringer calls in here while it holds its lock, so the way back must not be taken with this class's lock held. */
    @Test
    fun `the ringer is told after the lock is released`() {
        val probe = arrayOfNulls<AlarmForeground>(1)
        var heldDuringCall = true
        val checked = AlarmForeground(notifications, { heldDuringCall = Thread.holdsLock(probe[0]!!) }) {}
        probe[0] = checked
        checked.ringingChanged(listOf(alarm(1)))
        checked.attach(FakeHost())
        assertEquals(false, heldDuringCall)
    }

    @Test
    fun `without anyone to tell, it still works`() {
        val plain = AlarmForeground(notifications) { starts++ }
        plain.ringingChanged(listOf(alarm(1)))
        plain.attach(FakeHost())
        assertEquals(1, starts)
    }

    @Test
    fun `the first ringing alarm starts the service`() {
        foreground.ringingChanged(listOf(alarm()))
        assertEquals(1, starts)
    }

    @Test
    fun `nothing starts while nothing rings`() {
        foreground.ringingChanged(emptyList())
        foreground.sync()
        assertEquals(0, starts)
    }

    @Test
    fun `a started service shows the first alarm in the foreground and the others on their own`() {
        foreground.ringingChanged(listOf(alarm(1, reason = "One"), alarm(2, reason = "Two")))
        val host = FakeHost()
        foreground.attach(host)
        assertEquals(listOf(AlarmNotifications.ringingId(1) to "One"), host.shown)
        assertNull(posted(1))
        assertNotNull(posted(2))
    }

    @Test
    fun `an alarm that joins a running service gets its own notification, without starting it again`() {
        foreground.ringingChanged(listOf(alarm(1)))
        val host = FakeHost()
        foreground.attach(host)
        foreground.ringingChanged(listOf(alarm(1), alarm(2)))
        assertEquals(1, starts)
        assertNotNull(posted(2))
    }

    @Test
    fun `if the first alarm leaves, the service moves on to the next one`() {
        foreground.ringingChanged(listOf(alarm(1), alarm(2)))
        val host = FakeHost()
        foreground.attach(host)
        foreground.ringingChanged(listOf(alarm(2)))
        assertEquals(AlarmNotifications.ringingId(2), host.shown.last().first)
        assertEquals(0, host.finished)
    }

    @Test
    fun `when the last alarm has left, the service stops, without another notification`() {
        foreground.sweepFinished()
        foreground.ringingChanged(listOf(alarm(1)))
        val host = FakeHost()
        foreground.attach(host)
        host.shown.clear()
        foreground.ringingChanged(emptyList())
        assertEquals(1, host.finished)
        assertTrue(host.shown.isEmpty())
    }

    @Test
    fun `a new alarm after that starts a new service, instead of using the one that is stopping`() {
        foreground.sweepFinished()
        foreground.ringingChanged(listOf(alarm(1)))
        val host = FakeHost()
        foreground.attach(host)
        foreground.ringingChanged(emptyList())
        host.shown.clear()
        foreground.ringingChanged(listOf(alarm(2)))
        assertEquals(2, starts)
        assertTrue(host.shown.isEmpty())
    }

    @Test
    fun `a service that starts after the alarms have left still goes into the foreground, as Android demands, and stops`() {
        foreground.sweepFinished()
        val host = FakeHost()
        foreground.attach(host)
        assertEquals(listOf(AlarmNotifications.PLACEHOLDER_ID to "Alarm"), host.shown)
        assertEquals(1, host.finished)
    }

    @Test
    fun `a service restarted during the sweep goes into the foreground, and waits for the sweep before it decides`() {
        val host = FakeHost()
        foreground.attach(host)
        assertEquals(listOf(AlarmNotifications.PLACEHOLDER_ID to "Alarm"), host.shown)
        assertEquals(0, host.finished)
        foreground.sync()
        foreground.ringingChanged(emptyList())
        assertEquals(0, host.finished)
        foreground.sweepFinished()
        assertEquals(1, host.finished)
    }

    @Test
    fun `an alarm that the sweep rings uses the service that waited, without starting another from the background`() {
        val host = FakeHost()
        foreground.attach(host)
        foreground.ringingChanged(listOf(alarm(1, reason = "Gym")))
        assertEquals(AlarmNotifications.ringingId(1) to "Gym", host.shown.last())
        assertEquals(0, starts)
        assertEquals(1, settled)
        foreground.sweepFinished()
        assertEquals(0, host.finished)
        foreground.ringingChanged(emptyList())
        assertEquals(1, host.finished)
    }

    @Test
    fun `the end of a sweep with no service does nothing`() {
        foreground.sweepFinished()
        assertEquals(0, starts)
        assertEquals(0, settled)
    }

    @Test
    fun `the end of a sweep with something ringing and no service yet starts it, if that was refused before`() {
        refuseStart = true
        foreground.ringingChanged(listOf(alarm(1)))
        refuseStart = false
        foreground.sweepFinished()
        assertEquals(1, starts)
    }

    @Test
    fun `if the service may not start, the notifications are still posted`() {
        refuseStart = true
        foreground.ringingChanged(listOf(alarm(1), alarm(2)))
        assertNotNull(posted(1))
        assertNotNull(posted(2))
    }

    @Test
    fun `the alarm screen can start a service that was refused before`() {
        refuseStart = true
        foreground.ringingChanged(listOf(alarm(1)))
        refuseStart = false
        foreground.sync()
        assertEquals(1, starts)
    }

    @Test
    fun `if the service may not go into the foreground, the notifications are still posted`() {
        foreground.ringingChanged(listOf(alarm(1), alarm(2)))
        foreground.attach(FakeHost(refuse = true))
        assertNotNull(posted(1))
        assertNotNull(posted(2))
    }

    @Test
    fun `a refusal with nothing ringing does not get in the way of stopping`() {
        foreground.sweepFinished()
        val host = FakeHost(refuse = true)
        foreground.attach(host)
        assertEquals(1, host.finished)
    }

    @Test
    fun `a service that is gone is forgotten`() {
        foreground.ringingChanged(listOf(alarm(1)))
        foreground.attach(FakeHost().also { foreground.detach(it) })
        val host = FakeHost()
        foreground.attach(host)
        foreground.detach(host)
        foreground.sync()
        assertEquals(2, starts)
    }

    @Test
    fun `an old service does not take the place of a new one when it goes`() {
        foreground.ringingChanged(listOf(alarm(1)))
        val old = FakeHost()
        val new = FakeHost()
        foreground.attach(old)
        foreground.attach(new)
        foreground.detach(old)
        foreground.sync()
        assertEquals(2, new.shown.size)
        assertEquals(1, starts)
    }
}
