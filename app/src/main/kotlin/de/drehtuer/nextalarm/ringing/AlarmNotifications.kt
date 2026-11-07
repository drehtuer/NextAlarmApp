package de.drehtuer.nextalarm.ringing

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import de.drehtuer.nextalarm.domain.AlarmInstance
import de.drehtuer.nextalarm.domain.NotificationText
import de.drehtuer.nextalarm.domain.RINGING_PLACEHOLDER_TEXT
import de.drehtuer.nextalarm.domain.lateText
import de.drehtuer.nextalarm.domain.missedText
import de.drehtuer.nextalarm.domain.ringingText
import de.drehtuer.nextalarm.domain.snoozedText
import de.drehtuer.nextalarm.domain.unansweredText
import de.drehtuer.nextalarm.planning.AppNotifications
import de.drehtuer.nextalarm.planning.alarmUri
import de.drehtuer.nextalarm.planning.notificationAction
import de.drehtuer.nextalarm.planning.notificationBuilder
import de.drehtuer.nextalarm.planning.openDiagnosticsIntent
import java.time.ZoneId

/**
 * The notifications of an alarm that started. The wording is specified in docs/design.md.
 *
 * A full-screen intent needs a permission the user can deny. Android then shows the notification as a heads-up one,
 * which the high importance of the channel allows, with the same actions.
 */
class AlarmNotifications(
    private val context: Context,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    /** The notification of a ringing alarm, also for `AlarmService`, which has to post its first one itself. */
    fun ringing(alarm: AlarmInstance): Notification {
        val screen = alarmScreen()
        return ringingBuilder(ringingText(alarm))
            .setContentIntent(screen)
            .setFullScreenIntent(screen, true)
            .addAction(action("Snooze", actionIntent(AlarmActionReceiver.ACTION_SNOOZE_ALL)))
            .addAction(action("Dismiss", actionIntent(AlarmActionReceiver.ACTION_DISMISS_ALL)))
            .build()
    }

    /** What `AlarmService` shows if it has to be in the foreground before it knows of any alarm. */
    fun placeholder(): Notification = ringingBuilder(RINGING_PLACEHOLDER_TEXT).build()

    fun showRinging(alarm: AlarmInstance) {
        manager.notify(ringingId(alarm.id), ringing(alarm))
    }

    fun cancelRinging(instanceId: Long) {
        manager.cancel(ringingId(instanceId))
    }

    fun showSnoozed(alarm: AlarmInstance) {
        ensureChannels()
        val text = snoozedText(alarm, zone())
        val dismiss = actionIntent(AlarmActionReceiver.ACTION_DISMISS, alarm.id)
        val notification = notificationBuilder(context, SNOOZED_CHANNEL, text.title, text.text)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(action("Dismiss", dismiss))
            .build()
        manager.notify(snoozedId(alarm.id), notification)
    }

    fun cancelSnoozed(instanceId: Long) {
        manager.cancel(snoozedId(instanceId))
    }

    fun showLate(alarm: AlarmInstance) = showNotice(lateId(alarm.id), lateText(alarm, zone()))

    fun showMissed(alarm: AlarmInstance) = showNotice(missedId(alarm.id), missedText(alarm, zone()))

    fun showUnanswered(alarm: AlarmInstance) = showNotice(unansweredId(alarm.id), unansweredText(alarm, zone()))

    private fun showNotice(id: Int, text: NotificationText) {
        ensureChannels()
        val notification = notificationBuilder(context, AppNotifications.WARNING_CHANNEL, text.title, text.text)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(action("Open history", openDiagnosticsIntent(context)))
            .build()
        manager.notify(id, notification)
    }

    private fun ringingBuilder(text: NotificationText): Notification.Builder {
        ensureChannels()
        return notificationBuilder(context, ALARM_CHANNEL, text.title, text.text)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
    }

    private fun alarmScreen(): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_SCREEN,
        Intent(context, AlarmActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** The data keeps the intents of different alarms apart. */
    private fun actionIntent(intentAction: String, instanceId: Long? = null): PendingIntent {
        val intent = Intent(context, AlarmActionReceiver::class.java).setAction(intentAction)
        if (instanceId != null) intent.data = alarmUri(instanceId)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_ACTION,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun action(title: String, intent: PendingIntent): Notification.Action =
        notificationAction(context, title, intent)

    /** The service plays the sound and vibrates, so the channels of a ringing and a snoozed alarm stay silent. */
    private fun ensureChannels() {
        manager.createNotificationChannel(
            NotificationChannel(ALARM_CHANNEL, "Alarm", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
                enableVibration(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(SNOOZED_CHANNEL, "Snoozed", NotificationManager.IMPORTANCE_LOW),
        )
        manager.createNotificationChannel(
            NotificationChannel(AppNotifications.WARNING_CHANNEL, "Warnings", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    companion object {
        const val ALARM_CHANNEL = "alarm"
        const val SNOOZED_CHANNEL = "snoozed"

        /** For the foreground service while it has no alarm to show. No alarm has this id. */
        const val PLACEHOLDER_ID = 3

        private const val REQUEST_SCREEN = 1
        private const val REQUEST_ACTION = 2

        private const val FIRST_ALARM_ID = 100
        private const val KINDS = 4
        private const val ALARMS = 100_000_000L

        /** Every alarm has its own id of each kind. Far more alarms than a phone will ever see fit in the range. */
        private fun id(instanceId: Long, kind: Int) = FIRST_ALARM_ID + instanceId.mod(ALARMS).toInt() * KINDS + kind

        fun ringingId(instanceId: Long): Int = id(instanceId, 0)
        fun snoozedId(instanceId: Long): Int = id(instanceId, 1)
        fun lateId(instanceId: Long): Int = id(instanceId, 2)

        /** Only one of these can happen to an alarm, as both end it. */
        fun missedId(instanceId: Long): Int = id(instanceId, 3)
        fun unansweredId(instanceId: Long): Int = id(instanceId, 3)
    }
}
