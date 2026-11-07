package de.drehtuer.nextalarm.planning

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import de.drehtuer.nextalarm.MainActivity
import de.drehtuer.nextalarm.R
import de.drehtuer.nextalarm.domain.AlarmInstance
import de.drehtuer.nextalarm.domain.PlannerEffects
import de.drehtuer.nextalarm.domain.PlanningFailure
import de.drehtuer.nextalarm.domain.failSafeWarningText
import de.drehtuer.nextalarm.domain.nextAlarmText
import de.drehtuer.nextalarm.tile.AndroidTileRefresh
import de.drehtuer.nextalarm.tile.TileRefresh
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * The "Next alarm" and "Fail-safe warning" notifications. The wording is specified in docs/design.md. Whatever changes
 * the next alarm comes through [showNextAlarm], so this is also where the quick settings tile hears about it.
 */
class AppNotifications(
    private val context: Context,
    private val now: () -> Instant = Instant::now,
    private val tile: TileRefresh = AndroidTileRefresh(context),
) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun showNextAlarm(alarm: AlarmInstance?) {
        postNextAlarm(alarm)
        tile.request()
    }

    private fun postNextAlarm(alarm: AlarmInstance?) {
        if (alarm == null) {
            manager.cancel(NEXT_ALARM_ID)
            return
        }
        ensureChannels()
        val text = nextAlarmText(alarm, now(), ZoneId.systemDefault())
        manager.notify(
            NEXT_ALARM_ID,
            notificationBuilder(context, NEXT_ALARM_CHANNEL, text.title, text.text).setOngoing(true).build(),
        )
    }

    fun showFailSafeWarning(failure: PlanningFailure?) {
        ensureChannels()
        val text = failSafeWarningText(failure)
        val notification = notificationBuilder(
            context,
            WARNING_CHANNEL,
            text.title,
            text.text,
        ).setStyle(Notification.BigTextStyle().bigText(text.text)).setOngoing(true).addAction(notificationAction(context, "Open diagnostics", openDiagnosticsIntent(context))).build()
        manager.notify(FAIL_SAFE_ID, notification)
    }

    fun removeFailSafeWarning() {
        manager.cancel(FAIL_SAFE_ID)
    }

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
    }
}

private const val ACCENT = 0xFF00639B.toInt()
private const val REQUEST_DIAGNOSTICS = 1

/** The look that all notifications share. Tapping one opens the app, unless the caller sets something else. */
fun notificationBuilder(context: Context, channel: String, title: String, text: String): Notification.Builder =
    Notification.Builder(context, channel)
        .setSmallIcon(R.drawable.ic_stat_alarm)
        .setColor(ACCENT)
        .setContentTitle(title)
        .setContentText(text)
        .setContentIntent(openAppIntent(context))

/** Only opens the app. */
fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
    context,
    0,
    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
)

fun notificationAction(context: Context, title: String, intent: PendingIntent): Notification.Action =
    Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_stat_alarm), title, intent).build()

/**
 * Opens screen 7, for "Open diagnostics" and "Open history". The action keeps this apart from the intent that only
 * opens the app. The flags make a running app get it in `onNewIntent`: otherwise Android only brings it to the front.
 */
fun openDiagnosticsIntent(context: Context): PendingIntent = PendingIntent.getActivity(
    context,
    REQUEST_DIAGNOSTICS,
    Intent(context, MainActivity::class.java)
        .setAction(MainActivity.ACTION_OPEN_DIAGNOSTICS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
)

/** What planning asks of Android: its jobs from [PlanningWork], its notifications from [AppNotifications]. */
class AppPlannerEffects(
    private val work: PlanningWork,
    private val notifications: AppNotifications,
) : PlannerEffects {
    override fun cancelPlanningJob() = work.cancelPlanningJob()
    override fun startPlanningJob(generation: Long) = work.startPlanningJob(generation)
    override fun scheduleRetry(delay: Duration) = work.scheduleRetry(delay)
    override fun showFailSafeWarning(failure: PlanningFailure?) = notifications.showFailSafeWarning(failure)
    override fun removeFailSafeWarning() = notifications.removeFailSafeWarning()
    override fun showNextAlarm(alarm: AlarmInstance?) = notifications.showNextAlarm(alarm)
}
