package de.drehtuer.nextalarm.ringing

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import de.drehtuer.nextalarm.MainActivity
import de.drehtuer.nextalarm.planning.AppNotifications
import de.drehtuer.nextalarm.planning.alarmUri
import de.drehtuer.nextalarm.domain.AlarmState
import java.time.Duration
import java.time.ZoneId
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
class AlarmNotificationsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val notifications = AlarmNotifications(context) { ZoneId.of("Europe/Berlin") }

    private fun posted(id: Int): Notification? = shadowOf(manager).getNotification(id)
    private fun Notification.title() = extras.getCharSequence(Notification.EXTRA_TITLE).toString()
    private fun Notification.text() = extras.getCharSequence(Notification.EXTRA_TEXT).toString()
    private fun Notification.actionTitles() = actions.orEmpty().map { it.title.toString() }
    private fun Notification.intentOfAction(index: Int): Intent = shadowOf(actions[index].actionIntent).savedIntent

    @Test
    fun `a ringing alarm has its title and both actions`() {
        val notification = notifications.ringing(alarm())
        assertEquals("Gym", notification.title())
        assertEquals("Ringing now", notification.text())
        assertEquals(listOf("Snooze", "Dismiss"), notification.actionTitles())
        assertEquals(AlarmActionReceiver.ACTION_SNOOZE_ALL, notification.intentOfAction(0).action)
        assertEquals(AlarmActionReceiver.ACTION_DISMISS_ALL, notification.intentOfAction(1).action)
    }

    @Test
    fun `a ringing alarm is ongoing, and being swiped away is reported, as Android lets that happen`() {
        val notification = notifications.ringing(alarm())
        assertEquals(Notification.FLAG_ONGOING_EVENT, notification.flags and Notification.FLAG_ONGOING_EVENT)
        val swiped = shadowOf(notification.deleteIntent).savedIntent
        assertEquals(AlarmActionReceiver.ACTION_RINGING_SWIPED, swiped.action)
        assertEquals(AlarmActionReceiver::class.java.name, swiped.component?.className)
    }

    @Test
    fun `a ringing alarm opens the alarm screen in full screen`() {
        val notification = notifications.ringing(alarm())
        val fullScreen = shadowOf(notification.fullScreenIntent).savedIntent
        assertEquals(AlarmActivity::class.java.name, fullScreen.component?.className)
        assertEquals(notification.fullScreenIntent, notification.contentIntent)
        assertEquals(Notification.CATEGORY_ALARM, notification.category)
        assertEquals(Notification.VISIBILITY_PUBLIC, notification.visibility)
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
    }

    @Test
    fun `the channel of a ringing alarm can show on top, and leaves the sound to the service`() {
        notifications.ringing(alarm())
        val channel = manager.getNotificationChannel(AlarmNotifications.ALARM_CHANNEL)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
        assertNull(channel.sound)
        assertEquals(false, channel.shouldVibrate())
    }

    @Test
    fun `the placeholder opens nothing in full screen`() {
        val notification = notifications.placeholder()
        assertEquals("Alarm", notification.title())
        assertNull(notification.fullScreenIntent)
        assertTrue(notification.actionTitles().isEmpty())
    }

    @Test
    fun `a ringing alarm can be shown and removed`() {
        notifications.showRinging(alarm(id = 5))
        assertEquals("Gym", posted(AlarmNotifications.ringingId(5))?.title())
        notifications.cancelRinging(5)
        assertNull(posted(AlarmNotifications.ringingId(5)))
    }

    @Test
    fun `a snoozed alarm says until when and dismisses just itself`() {
        notifications.showSnoozed(alarm(id = 5, state = AlarmState.Snoozed))
        val notification = assertNotNull(posted(AlarmNotifications.snoozedId(5)))
        assertEquals("Snoozed until 06:45", notification.title())
        assertEquals("Gym", notification.text())
        assertEquals(listOf("Dismiss"), notification.actionTitles())
        val intent = notification.intentOfAction(0)
        assertEquals(AlarmActionReceiver.ACTION_DISMISS, intent.action)
        assertEquals(alarmUri(5), intent.data)
        assertEquals(AlarmActionReceiver::class.java.name, intent.component?.className)
    }

    @Test
    fun `a snoozed alarm can be removed`() {
        notifications.showSnoozed(alarm(id = 5))
        notifications.cancelSnoozed(5)
        assertNull(posted(AlarmNotifications.snoozedId(5)))
    }

    @Test
    fun `the snooze notifications of two alarms act on their own alarm`() {
        notifications.showSnoozed(alarm(id = 1))
        notifications.showSnoozed(alarm(id = 2))
        assertEquals(alarmUri(1), posted(AlarmNotifications.snoozedId(1))?.intentOfAction(0)?.data)
        assertEquals(alarmUri(2), posted(AlarmNotifications.snoozedId(2))?.intentOfAction(0)?.data)
    }

    @Test
    fun `a late alarm is reported, with a way to the history`() {
        notifications.showLate(alarm(reason = "Train to Cologne", lateBy = Duration.ofMinutes(7)))
        val notification = assertNotNull(posted(AlarmNotifications.lateId(1)))
        assertEquals("An alarm rang 7 min late", notification.title())
        assertEquals("“Train to Cologne” was due at 06:45, but the phone was off.", notification.text())
        assertEquals(listOf("Open history"), notification.actionTitles())
        assertOpensDiagnostics(notification)
    }

    private fun assertOpensDiagnostics(notification: Notification) {
        val intent = notification.intentOfAction(0)
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertEquals(MainActivity.ACTION_OPEN_DIAGNOSTICS, intent.action)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0, "so that a running app gets it")
    }

    @Test
    fun `a missed alarm is reported`() {
        notifications.showMissed(alarm(reason = "Brunch with Lea"))
        val notification = assertNotNull(posted(AlarmNotifications.missedId(1)))
        assertEquals("An alarm didn't ring", notification.title())
        assertEquals("“Brunch with Lea” at 06:45 was missed because the phone was off.", notification.text())
        assertEquals(listOf("Open history"), notification.actionTitles())
        assertOpensDiagnostics(notification)
    }

    @Test
    fun `an unanswered alarm is reported`() {
        notifications.showUnanswered(alarm())
        val notification = assertNotNull(posted(AlarmNotifications.unansweredId(1)))
        assertEquals("An alarm wasn't answered", notification.title())
        assertEquals("“Gym” at 06:45 rang twice with no response. It won't ring again.", notification.text())
        assertEquals(listOf("Open history"), notification.actionTitles())
        assertOpensDiagnostics(notification)
    }

    @Test
    fun `the warning that an alarm may be silent opens the alarm, and can be removed`() {
        notifications.showForegroundRefused()
        val notification = assertNotNull(posted(AlarmNotifications.FOREGROUND_REFUSED_ID))
        assertEquals("This alarm may be silent", notification.title())
        assertEquals(AlarmActivity::class.java.name, shadowOf(notification.contentIntent).savedIntent.component?.className)
        assertEquals(AlarmNotifications.ALARM_CHANNEL, notification.channelId)
        notifications.cancelForegroundRefused()
        assertNull(posted(AlarmNotifications.FOREGROUND_REFUSED_ID))
    }

    @Test
    fun `an alarm without any sound is reported`() {
        notifications.showNoSound()
        val notification = assertNotNull(posted(AlarmNotifications.NO_SOUND_ID))
        assertEquals("An alarm couldn't play a sound", notification.title())
    }

    @Test
    fun `an alarm stopped by the limit is reported`() {
        notifications.showRingLimit(alarm())
        val notification = assertNotNull(posted(AlarmNotifications.unansweredId(1)))
        assertEquals("An alarm was stopped", notification.title())
        assertEquals(listOf("Open history"), notification.actionTitles())
    }

    @Test
    fun `reports use the warnings channel, which exists`() {
        notifications.showMissed(alarm())
        assertEquals(AppNotifications.WARNING_CHANNEL, posted(AlarmNotifications.missedId(1))?.channelId)
        assertNotNull(manager.getNotificationChannel(AppNotifications.WARNING_CHANNEL))
    }

    @Test
    fun `no two notifications of two alarms share an id, and none takes one in use`() {
        val ids = listOf(1L, 2L, 3L).flatMap {
            listOf(AlarmNotifications.ringingId(it), AlarmNotifications.snoozedId(it), AlarmNotifications.lateId(it), AlarmNotifications.missedId(it))
        }
        assertEquals(ids.size, ids.toSet().size)
        val taken = setOf(AppNotifications.NEXT_ALARM_ID, AppNotifications.FAIL_SAFE_ID, AlarmNotifications.PLACEHOLDER_ID)
        assertTrue(ids.none { it in taken })
        assertEquals(AlarmNotifications.missedId(1), AlarmNotifications.unansweredId(1))
    }

    @Test
    fun `the default time zone can be used`() {
        AlarmNotifications(context).showLate(alarm())
        assertNotNull(posted(AlarmNotifications.lateId(1)))
    }
}
