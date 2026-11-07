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
    private val foreground = AlarmForeground(notifications) {
        check(!refuseStart) { "Foreground services are not allowed now" }
        starts++
    }

    private fun posted(id: Long) = shadowOf(manager).getNotification(AlarmNotifications.ringingId(id))

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
        val host = FakeHost()
        foreground.attach(host)
        assertEquals(listOf(AlarmNotifications.PLACEHOLDER_ID to "Alarm"), host.shown)
        assertEquals(1, host.finished)
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
