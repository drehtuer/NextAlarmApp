package de.drehtuer.nextalarm.ringing

import android.app.Notification
import android.util.Log
import de.drehtuer.nextalarm.domain.AlarmInstance

/** What [AlarmForeground] needs from the running `AlarmService`. */
interface AlarmForegroundHost {
    fun showForeground(id: Int, notification: Notification)
    fun finishRinging()
}

/**
 * Keeps `AlarmService` in step with the ringing alarms. The sound itself runs in `AlarmRinger`, in the app's process. The
 * service is what lets the process live on while the app is in the background, what shows the notification, and what lets
 * the sound be heard at all: see docs/architecture.md#background-audio-on-android-17. So [onSettled] tells the ringer when
 * the service has gone into the foreground, or that Android refused it. That goes out after this class's lock is released,
 * as the ringer calls in here while it holds its own.
 *
 * The first ringing alarm's notification belongs to the service, the others are posted as usual. Android removes the
 * notification of a service when the service moves on to another one, and when it stops.
 */
class AlarmForeground(
    private val notifications: AlarmNotifications,
    private val onSettled: () -> Unit = {},
    private val startService: () -> Unit,
) {
    private var host: AlarmForegroundHost? = null
    private var ringing: List<AlarmInstance> = emptyList()
    private var settled = false
    private var sweepDone = false

    fun ringingChanged(alarms: List<AlarmInstance>) = locked {
        ringing = alarms
        syncNow()
    }

    /** Also when the alarm screen comes up: an app in the foreground can start a service that was refused before. */
    fun sync() = locked { syncNow() }

    /**
     * The service started. Android demands that it goes into the foreground now, even if the alarms have left in the
     * meantime, and even if it was restarted because it is sticky. In that last case the recovery sweep is still finding
     * out what rings, and a service that has gone is one it would have to start from the background, which is likely
     * refused. So nothing ringing only counts once the sweep has finished.
     */
    fun attach(service: AlarmForegroundHost) = locked {
        host = service
        if (ringing.isEmpty()) {
            foreground(service, AlarmNotifications.PLACEHOLDER_ID) { notifications.placeholder() }
            if (sweepDone) finish(service)
        } else {
            show(service)
        }
    }

    /** The recovery sweep of this process is over, however it went. Anything that rings has reported by now. */
    fun sweepFinished() = locked {
        sweepDone = true
        syncNow()
    }

    @Synchronized
    fun detach(service: AlarmForegroundHost) {
        if (host === service) host = null
    }

    private fun locked(work: () -> Unit) {
        val tell = synchronized(this) {
            work()
            settled.also { settled = false }
        }
        if (tell) onSettled()
    }

    private fun syncNow() {
        val current = host
        when {
            current != null && ringing.isEmpty() -> if (sweepDone) finish(current)
            current != null -> show(current)
            ringing.isNotEmpty() -> start()
        }
    }

    private fun show(service: AlarmForegroundHost) {
        val first = ringing.first()
        foreground(service, AlarmNotifications.ringingId(first.id)) { notifications.ringing(first) }
        ringing.drop(1).forEach(notifications::showRinging)
    }

    /** Stopping comes after forgetting the service, so that a new alarm starts a new one instead of using this one. */
    private fun finish(service: AlarmForegroundHost) {
        host = null
        service.finishRinging()
    }

    private fun foreground(service: AlarmForegroundHost, id: Int, notification: () -> Notification) {
        try {
            service.showForeground(id, notification())
            notifications.cancelForegroundRefused()
            settled = ringing.isNotEmpty()
        } catch (refused: RuntimeException) {
            refused("The alarm service could not go into the foreground", refused)
        }
    }

    /** The notifications come first, so that they are there whatever the service does. It takes its own over when it comes up. */
    private fun start() {
        try {
            ringing.forEach(notifications::showRinging)
            startService()
        } catch (refused: RuntimeException) {
            refused("The alarm service could not start", refused)
        }
    }

    /**
     * Without the service the sound is likely silenced, but there is still a chance of it, so it plays anyway. What the
     * user can do is see that something is wrong, and that the alarm can be stopped.
     */
    private fun refused(message: String, failure: RuntimeException) {
        Log.e(TAG, message, failure)
        settled = true
        if (ringing.isEmpty()) return
        ringing.forEach(notifications::showRinging)
        notifications.showForegroundRefused()
    }

    private companion object {
        const val TAG = "NextAlarm"
    }
}
