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
 * Keeps `AlarmService` in step with the ringing alarms. The sound itself runs in `AlarmRinger`, in the app's process: the
 * service is what lets the process live on while the app is in the background, and what shows the notification.
 *
 * The first ringing alarm's notification belongs to the service, the others are posted as usual. Android removes the
 * notification of a service when the service moves on to another one, and when it stops.
 */
class AlarmForeground(
    private val notifications: AlarmNotifications,
    private val startService: () -> Unit,
) {
    private var host: AlarmForegroundHost? = null
    private var ringing: List<AlarmInstance> = emptyList()

    @Synchronized
    fun ringingChanged(alarms: List<AlarmInstance>) {
        ringing = alarms
        sync()
    }

    /** Also when the alarm screen comes up: an app in the foreground can start a service that was refused before. */
    @Synchronized
    fun sync() {
        val current = host
        when {
            current != null && ringing.isEmpty() -> finish(current)
            current != null -> show(current)
            ringing.isNotEmpty() -> start()
        }
    }

    /**
     * The service started. Android demands that it goes into the foreground now, even if the alarms have left in the
     * meantime, and even if it was restarted because it is sticky.
     */
    @Synchronized
    fun attach(service: AlarmForegroundHost) {
        host = service
        if (ringing.isEmpty()) {
            foreground(service, AlarmNotifications.PLACEHOLDER_ID) { notifications.placeholder() }
            finish(service)
        } else {
            show(service)
        }
    }

    @Synchronized
    fun detach(service: AlarmForegroundHost) {
        if (host === service) host = null
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

    /** The alarm still rings without the service, if Android refuses it: the sound runs in this process. */
    private fun foreground(service: AlarmForegroundHost, id: Int, notification: () -> Notification) {
        try {
            service.showForeground(id, notification())
        } catch (refused: IllegalStateException) {
            Log.w(TAG, "The alarm service could not go into the foreground", refused)
            ringing.forEach(notifications::showRinging)
        }
    }

    private fun start() {
        try {
            startService()
        } catch (refused: IllegalStateException) {
            Log.w(TAG, "The alarm service could not start", refused)
            ringing.forEach(notifications::showRinging)
        }
    }

    private companion object {
        const val TAG = "NextAlarm"
    }
}
