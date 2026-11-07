package de.drehtuer.nextalarm.ringing

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import androidx.test.core.app.ApplicationProvider
import de.drehtuer.nextalarm.R
import de.drehtuer.nextalarm.domain.WakeUpPlaying
import de.drehtuer.nextalarm.domain.WakeUpStatus
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

private val ZONE: ZoneId = ZoneId.of("Europe/Berlin")
private val STOPS_AT: Instant = Instant.parse("2026-11-07T05:16:00Z")
private val LIMIT: Duration = Duration.ofMinutes(15)

private fun speaking() = WakeUpStatus(listOf("Gym"), WakeUpPlaying.Speaking("Gym", 1, 1, AT), null)

private fun podcast(position: Duration = Duration.ofSeconds(90), started: Boolean = true, duration: Duration? = Duration.ofMinutes(18)) =
    WakeUpStatus(
        listOf("Gym"),
        WakeUpPlaying.PlayingPodcast("Der Tag", "Deutschlandfunk", "Episode", started, position, duration, LIMIT),
        STOPS_AT,
    )

private fun Notification.title() = extras.getCharSequence(Notification.EXTRA_TITLE).toString()

private fun Notification.text() = extras.getCharSequence(Notification.EXTRA_TEXT).toString()

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class WakeUpNotificationsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val notifications = WakeUpNotifications(context) { ZONE }
    private val session = MediaSession(context, "test")

    private fun playing() = notifications.playing(podcast(), session.sessionToken)

    @Test
    fun `it says what plays, in the words of the design`() {
        val notification = playing()
        assertEquals("Playing: Der Tag", notification.title())
        assertEquals("Deutschlandfunk podcast · stops by 06:16 at the latest", notification.text())
    }

    @Test
    fun `it is a media notification of that session`() {
        val token = playing().extras.getParcelable(Notification.EXTRA_MEDIA_SESSION, MediaSession.Token::class.java)
        assertEquals(session.sessionToken, token)
        assertEquals(Notification.MediaStyle::class.java.name, playing().extras.getString(Notification.EXTRA_TEMPLATE))
    }

    @Test
    fun `it can be used on the lock screen, and stays`() {
        val notification = playing()
        assertEquals(Notification.VISIBILITY_PUBLIC, notification.visibility)
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
    }

    @Test
    fun `its channel makes no sound of its own`() {
        playing()
        assertEquals(NotificationManager.IMPORTANCE_LOW, manager.getNotificationChannel(WakeUpNotifications.CHANNEL).importance)
    }

    @Test
    fun `Stop reaches the receiver that works before the first unlock`() {
        val stop = playing().actions.single()
        assertEquals("Stop", stop.title)
        val intent = shadowOf(stop.actionIntent).savedIntent
        assertEquals(AlarmActionReceiver.ACTION_STOP_WAKE_UP, intent.action)
        assertEquals(AlarmActionReceiver::class.java.name, intent.component?.className)
        assertTrue(shadowOf(stop.actionIntent).isBroadcast)
    }

    @Test
    fun `tapping it opens the wake-up screen`() {
        val intent = shadowOf(playing().contentIntent).savedIntent
        assertEquals(WakeUpActivity::class.java.name, intent.component?.className)
        assertTrue(shadowOf(playing().contentIntent).isActivity)
    }

    @Test
    fun `the placeholder has something to say`() {
        assertEquals("NextAlarm", notifications.placeholder().title())
    }

    @Test
    fun `it can be posted and taken away`() {
        notifications.show(speaking(), session.sessionToken)
        assertEquals("Speaking the title", shadowOf(manager).getNotification(WakeUpNotifications.ID).title())
        notifications.cancel()
        assertNull(shadowOf(manager).getNotification(WakeUpNotifications.ID))
    }

    @Test
    fun `its id is not one of the others`() {
        assertFalse(WakeUpNotifications.ID in listOf(1, 2, AlarmNotifications.PLACEHOLDER_ID))
        assertTrue(WakeUpNotifications.ID < AlarmNotifications.ringingId(0))
    }

    @Test
    fun `by default it goes by the time zone of the device`() {
        val defaults = WakeUpNotifications(context)
        assertNotNull(defaults.playing(podcast(), session.sessionToken).text())
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class WakeUpMediaTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val sessions = mutableListOf<MediaSession>()
    private var stops = 0
    private val media = WakeUpMedia(
        context,
        zone = { ZONE },
        newSession = { MediaSession(context, "test").also { sessions += it } },
    ) { stops++ }

    @Test
    fun `the session is active, one serves all updates, and it says what plays`() {
        val token = media.update(podcast())
        media.update(speaking())
        assertEquals(sessions.single().sessionToken, token)
        assertTrue(sessions.single().isActive)
    }

    @Test
    fun `the metadata says what plays`() {
        val metadata = media.metadata(podcast())
        assertEquals("Playing: Der Tag", metadata.getString(MediaMetadata.METADATA_KEY_TITLE))
        assertEquals("Deutschlandfunk podcast · stops by 06:16 at the latest", metadata.getString(MediaMetadata.METADATA_KEY_ARTIST))
        assertEquals(1_080_000, metadata.getLong(MediaMetadata.METADATA_KEY_DURATION))
    }

    @Test
    fun `a podcast that plays shows how far it has got`() {
        val state = media.playback(podcast())
        assertEquals(PlaybackState.STATE_PLAYING, state.state)
        assertEquals(90_000, state.position)
    }

    @Test
    fun `speech plays, and has no position or length`() {
        val state = media.playback(speaking())
        assertEquals(PlaybackState.STATE_PLAYING, state.state)
        assertEquals(PlaybackState.PLAYBACK_POSITION_UNKNOWN, state.position)
        assertFalse(media.metadata(speaking()).containsKey(MediaMetadata.METADATA_KEY_DURATION))
    }

    @Test
    fun `reading plays`() {
        val status = WakeUpStatus(listOf("Gym"), WakeUpPlaying.ReadingNews("A", 1, 2, AT, LIMIT), STOPS_AT)
        assertEquals(PlaybackState.STATE_PLAYING, media.playback(status).state)
    }

    @Test
    fun `whatever is still on its way is buffering`() {
        listOf(
            WakeUpStatus(listOf("Gym"), null, null),
            WakeUpStatus(listOf("Gym"), WakeUpPlaying.GettingNews("a.example"), STOPS_AT),
            podcast(position = Duration.ZERO, started = false),
        ).forEach { assertEquals(PlaybackState.STATE_BUFFERING, media.playback(it).state) }
    }

    @Test
    fun `a podcast of unknown length has no duration`() {
        assertFalse(media.metadata(podcast(duration = null)).containsKey(MediaMetadata.METADATA_KEY_DURATION))
    }

    @Test
    fun `it can be stopped, and that is all it offers`() {
        val state = media.playback(speaking())
        assertEquals(PlaybackState.ACTION_STOP, state.actions)
        val action = state.customActions.single()
        assertEquals(WakeUpMedia.ACTION_STOP, action.action)
        assertEquals("Stop", action.name)
        assertEquals(R.drawable.ic_stop, action.icon)
    }

    @Test
    fun `its controls stop the automation`() {
        media.callback.onStop()
        assertEquals(1, stops)
        media.callback.onCustomAction(WakeUpMedia.ACTION_STOP, null)
        assertEquals(2, stops)
    }

    @Test
    fun `an action it does not know does nothing`() {
        media.callback.onCustomAction("other", null)
        assertEquals(0, stops)
    }

    @Test
    fun `it is released, and comes back when needed`() {
        media.release()
        assertTrue(sessions.isEmpty())
        media.update(speaking())
        media.release()
        media.update(speaking())
        assertEquals(2, sessions.size)
        assertNotEquals(sessions[0], sessions[1])
    }

    @Test
    fun `by default it makes its own session`() {
        val defaults = WakeUpMedia(context) { stops++ }
        assertNotNull(defaults.update(podcast()))
        defaults.release()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class WakeUpForegroundTest {
    private class FakeHost(val refuse: Boolean = false) : WakeUpForegroundHost {
        val shown = mutableListOf<Pair<Int, String>>()
        var finished = 0

        override fun showForeground(id: Int, notification: Notification) {
            check(!refuse) { "Foreground services are not allowed now" }
            shown += id to notification.title()
        }

        override fun finishWakeUp() {
            finished++
        }
    }

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val sessions = mutableListOf<MediaSession>()
    private val media = WakeUpMedia(context, newSession = { MediaSession(context, "test").also { sessions += it } }) {}
    private var starts = 0
    private var refuseStart = false
    private val foreground = WakeUpForeground(WakeUpNotifications(context) { ZONE }, media) {
        check(!refuseStart) { "Foreground services are not allowed now" }
        starts++
    }

    private fun posted() = shadowOf(manager).getNotification(WakeUpNotifications.ID)

    @Test
    fun `an automation that starts starts the service, and its controls are there at once`() {
        foreground.changed(speaking())
        assertEquals(1, starts)
        assertEquals("Speaking the title", posted().title())
        assertTrue(sessions.single().isActive)
    }

    @Test
    fun `nothing starts while nothing runs`() {
        foreground.changed(WakeUpStatus.IDLE)
        foreground.sync()
        assertEquals(0, starts)
        assertTrue(sessions.isEmpty())
    }

    @Test
    fun `a started service shows what plays, and follows it`() {
        foreground.changed(speaking())
        val host = FakeHost()
        foreground.attach(host)
        assertEquals(listOf(WakeUpNotifications.ID to "Speaking the title"), host.shown)
        foreground.changed(podcast())
        assertEquals(WakeUpNotifications.ID to "Playing: Der Tag", host.shown.last())
        assertEquals(1, starts)
        assertEquals(0, host.finished)
    }

    @Test
    fun `the end stops the service and the session`() {
        foreground.changed(speaking())
        val host = FakeHost()
        foreground.attach(host)
        foreground.changed(WakeUpStatus.IDLE)
        assertEquals(1, host.finished)
        assertNull(posted())
        foreground.changed(speaking())
        assertEquals(2, sessions.size)
    }

    @Test
    fun `a service that comes up with nothing to do goes into the foreground as demanded, and stops`() {
        val host = FakeHost()
        foreground.attach(host)
        assertEquals(listOf(WakeUpNotifications.ID to "NextAlarm"), host.shown)
        assertEquals(1, host.finished)
    }

    @Test
    fun `after a service has gone, a new automation starts a new one`() {
        foreground.changed(speaking())
        val host = FakeHost()
        foreground.attach(host)
        foreground.changed(WakeUpStatus.IDLE)
        foreground.detach(host)
        foreground.changed(speaking())
        assertEquals(2, starts)
    }

    @Test
    fun `an old service going away does not disturb the new one`() {
        foreground.changed(speaking())
        val old = FakeHost()
        val new = FakeHost()
        foreground.attach(old)
        foreground.attach(new)
        foreground.detach(old)
        foreground.changed(podcast())
        assertEquals("Playing: Der Tag", new.shown.last().second)
        assertEquals(1, starts)
    }

    @Test
    fun `a service that goes away while it runs is started again by the screen`() {
        foreground.changed(speaking())
        val host = FakeHost()
        foreground.attach(host)
        foreground.detach(host)
        foreground.sync()
        assertEquals(2, starts)
    }

    @Test
    fun `a refused start leaves the notification, and the screen can try again`() {
        refuseStart = true
        foreground.changed(speaking())
        assertEquals(0, starts)
        assertEquals("Speaking the title", posted().title())
        refuseStart = false
        foreground.sync()
        assertEquals(1, starts)
    }

    @Test
    fun `a service refused the foreground leaves the notification`() {
        foreground.changed(speaking())
        manager.cancelAll()
        foreground.attach(FakeHost(refuse = true))
        assertEquals("Speaking the title", posted().title())
    }

    @Test
    fun `a service with nothing to do that is refused the foreground still stops, and posts nothing`() {
        val host = FakeHost(refuse = true)
        foreground.attach(host)
        assertEquals(1, host.finished)
        assertNull(posted())
    }
}
