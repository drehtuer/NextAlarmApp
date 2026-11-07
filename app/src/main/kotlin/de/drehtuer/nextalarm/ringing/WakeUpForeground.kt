package de.drehtuer.nextalarm.ringing

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.util.Log
import de.drehtuer.nextalarm.R
import de.drehtuer.nextalarm.domain.WAKE_UP_PLACEHOLDER_TEXT
import de.drehtuer.nextalarm.domain.WakeUpPlaying
import de.drehtuer.nextalarm.domain.WakeUpStatus
import de.drehtuer.nextalarm.domain.wakeUpNotificationText
import de.drehtuer.nextalarm.planning.notificationBuilder
import java.time.ZoneId

/** What [WakeUpForeground] needs from the running `WakeUpService`. */
interface WakeUpForegroundHost {
    fun showForeground(id: Int, notification: Notification)
    fun finishWakeUp()
}

/**
 * The media notification. It can be used on the lock screen, so it is public. Its Stop reaches
 * `AlarmActionReceiver`, which also works before the first unlock.
 */
class WakeUpNotifications(
    private val context: Context,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun playing(status: WakeUpStatus, session: MediaSession.Token): Notification {
        val text = wakeUpNotificationText(status, zone())
        return builder(text.title, text.text)
            .setContentIntent(screen())
            .setStyle(Notification.MediaStyle().setMediaSession(session).setShowActionsInCompactView(0))
            .addAction(
                Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_stop), "Stop", stopIntent())
                    .build(),
            )
            .build()
    }

    /** What `WakeUpService` shows if it has to be in the foreground while there is nothing to show. */
    fun placeholder(): Notification = builder(WAKE_UP_PLACEHOLDER_TEXT.title, WAKE_UP_PLACEHOLDER_TEXT.text).build()

    /** Without the service, which Android can refuse. */
    fun show(status: WakeUpStatus, session: MediaSession.Token) {
        manager.notify(ID, playing(status, session))
    }

    fun cancel() {
        manager.cancel(ID)
    }

    private fun builder(title: String, text: String): Notification.Builder {
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Wake-up", NotificationManager.IMPORTANCE_LOW))
        return notificationBuilder(context, CHANNEL, title, text)
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
    }

    private fun screen(): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_SCREEN,
        Intent(context, WakeUpActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun stopIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_STOP,
        Intent(context, AlarmActionReceiver::class.java).setAction(AlarmActionReceiver.ACTION_STOP_WAKE_UP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val CHANNEL = "wake_up"

        /** The ids below 100 are the app's own. See `AppNotifications` and `AlarmNotifications`. */
        const val ID = 4

        private const val REQUEST_SCREEN = 4
        private const val REQUEST_STOP = 5
    }
}

/**
 * The `MediaSession` of the running automation, through which the lock screen and headsets can stop it. The system's
 * media controls only offer the transport actions of a session and its custom ones, and stopping is not among what they
 * show, so it is there as a custom action too.
 */
class WakeUpMedia(
    context: Context,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val newSession: () -> MediaSession = { MediaSession(context, "NextAlarm") },
    private val onStop: () -> Unit,
) {
    private var session: MediaSession? = null

    internal val callback = object : MediaSession.Callback() {
        override fun onStop() = this@WakeUpMedia.onStop()

        override fun onCustomAction(action: String, extras: android.os.Bundle?) {
            if (action == ACTION_STOP) this@WakeUpMedia.onStop()
        }
    }

    /** Starts the session if needed, and brings it up to date. */
    fun update(status: WakeUpStatus): MediaSession.Token {
        val current = session ?: newSession().also {
            it.setCallback(callback, Handler(Looper.getMainLooper()))
            it.isActive = true
            session = it
        }
        current.setMetadata(metadata(status))
        current.setPlaybackState(playback(status))
        return current.sessionToken
    }

    fun release() {
        session?.release()
        session = null
    }

    internal fun metadata(status: WakeUpStatus): MediaMetadata {
        val text = wakeUpNotificationText(status, zone())
        val builder = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, text.title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, text.text)
        (status.playing as? WakeUpPlaying.PlayingPodcast)?.duration?.let {
            builder.putLong(MediaMetadata.METADATA_KEY_DURATION, it.toMillis())
        }
        return builder.build()
    }

    internal fun playback(status: WakeUpStatus): PlaybackState {
        val playing = status.playing
        val waiting = playing == null || playing is WakeUpPlaying.GettingNews ||
            (playing is WakeUpPlaying.PlayingPodcast && !playing.started)
        val position = (playing as? WakeUpPlaying.PlayingPodcast)?.position?.toMillis()
        return PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_STOP)
            .addCustomAction(
                PlaybackState.CustomAction.Builder(ACTION_STOP, "Stop", R.drawable.ic_stop).build(),
            )
            .setState(
                if (waiting) PlaybackState.STATE_BUFFERING else PlaybackState.STATE_PLAYING,
                position ?: PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                1f,
            )
            .build()
    }

    companion object {
        const val ACTION_STOP = "de.drehtuer.nextalarm.media.STOP"
    }
}

/**
 * Keeps `WakeUpService` and the media notification in step with the automation, which itself runs in `WakeUpDirector`,
 * in the app's process. Like `AlarmForeground`, it copes with Android refusing the service: the automation then runs on
 * as long as the process lives, and its notification is posted as usual.
 */
class WakeUpForeground(
    private val notifications: WakeUpNotifications,
    private val media: WakeUpMedia,
    private val startService: () -> Unit,
) {
    private var host: WakeUpForegroundHost? = null
    private var status = WakeUpStatus.IDLE

    @Synchronized
    fun changed(status: WakeUpStatus) {
        this.status = status
        sync()
    }

    /** Also when the wake-up screen comes up: an app in the foreground can start a service that was refused before. */
    @Synchronized
    fun sync() {
        val current = host
        when {
            !status.running -> {
                if (current != null) finish(current)
                notifications.cancel()
                media.release()
            }
            current != null -> show(current)
            else -> start()
        }
    }

    /** The service started. Android demands that it goes into the foreground now, even if nothing is left to play. */
    @Synchronized
    fun attach(service: WakeUpForegroundHost) {
        host = service
        if (status.running) {
            show(service)
        } else {
            foreground(service) { notifications.placeholder() }
            finish(service)
        }
    }

    @Synchronized
    fun detach(service: WakeUpForegroundHost) {
        if (host === service) host = null
    }

    private fun show(service: WakeUpForegroundHost) {
        val token = media.update(status)
        foreground(service) { notifications.playing(status, token) }
    }

    /** Stopping comes after forgetting the service, so that a new automation starts a new one. */
    private fun finish(service: WakeUpForegroundHost) {
        host = null
        service.finishWakeUp()
    }

    private fun foreground(service: WakeUpForegroundHost, notification: () -> Notification) {
        try {
            service.showForeground(WakeUpNotifications.ID, notification())
        } catch (refused: IllegalStateException) {
            Log.w(TAG, "The wake-up service could not go into the foreground", refused)
            postWithoutService()
        }
    }

    private fun start() {
        try {
            startService()
        } catch (refused: IllegalStateException) {
            Log.w(TAG, "The wake-up service could not start", refused)
        }
        // The controls should be there at once, and the service takes this notification over when it comes up.
        postWithoutService()
    }

    private fun postWithoutService() {
        if (status.running) notifications.show(status, media.update(status))
    }

    private companion object {
        const val TAG = "NextAlarm"
    }
}
