package de.drehtuer.nextalarm.planning

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import de.drehtuer.nextalarm.MainActivity
import de.drehtuer.nextalarm.R
import de.drehtuer.nextalarm.domain.AlarmInstance
import de.drehtuer.nextalarm.domain.PlannerEffects
import de.drehtuer.nextalarm.domain.nextAlarmText
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** The "Next alarm" and "Fail-safe warning" notifications. The wording is specified in docs/design.md. */
class AppNotifications(
    private val context: Context,
    private val now: () -> Instant = Instant::now,
) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun showNextAlarm(alarm: AlarmInstance?) {
        if (alarm == null) {
            manager.cancel(NEXT_ALARM_ID)
            return
        }
        ensureChannels()
        val text = nextAlarmText(alarm, now(), ZoneId.systemDefault())
        manager.notify(NEXT_ALARM_ID, builder(NEXT_ALARM_CHANNEL, text.title, text.text).setOngoing(true).build())
    }

    fun showFailSafeWarning() {
        ensureChannels()
        val notification = builder(
            WARNING_CHANNEL,
            "New alarms aren't being added",
            "NextAlarm can't read your calendar. Alarms already planned will still ring.",
        ).setOngoing(true).build()
        manager.notify(FAIL_SAFE_ID, notification)
    }

    fun removeFailSafeWarning() {
        manager.cancel(FAIL_SAFE_ID)
    }

    private fun builder(channel: String, title: String, text: String) = Notification.Builder(context, channel)
        .setSmallIcon(R.drawable.ic_stat_alarm)
        .setColor(ACCENT)
        .setContentTitle(title)
        .setContentText(text)
        .setContentIntent(
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )

    private fun ensureChannels() {
        manager.createNotificationChannel(
            NotificationChannel(NEXT_ALARM_CHANNEL, "Next alarm", NotificationManager.IMPORTANCE_LOW),
        )
        manager.createNotificationChannel(
            NotificationChannel(WARNING_CHANNEL, "Warnings", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    companion object {
        const val NEXT_ALARM_ID = 1
        const val FAIL_SAFE_ID = 2
        const val NEXT_ALARM_CHANNEL = "next_alarm"
        const val WARNING_CHANNEL = "warnings"
        private const val ACCENT = 0xFF00639B.toInt()
    }
}

/** What planning asks of Android: its jobs from [PlanningWork], its notifications from [AppNotifications]. */
class AppPlannerEffects(
    private val work: PlanningWork,
    private val notifications: AppNotifications,
) : PlannerEffects {
    override fun cancelPlanningJob() = work.cancelPlanningJob()
    override fun startPlanningJob(generation: Long) = work.startPlanningJob(generation)
    override fun scheduleRetry(delay: Duration) = work.scheduleRetry(delay)
    override fun showFailSafeWarning() = notifications.showFailSafeWarning()
    override fun removeFailSafeWarning() = notifications.removeFailSafeWarning()
    override fun showNextAlarm(alarm: AlarmInstance?) = notifications.showNextAlarm(alarm)
}
