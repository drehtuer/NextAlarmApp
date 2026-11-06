package de.drehtuer.nextalarm.data

import android.app.AlarmManager
import android.app.PendingIntent
import de.drehtuer.nextalarm.domain.AlarmScheduler
import java.time.Instant

/** The intents of an alarm. `:app` provides them, because it owns the receiver and the screens they lead to. */
interface AlarmIntents {
    /** Delivered when the alarm fires. The same id must give an equal intent, so that it can be cancelled. */
    fun fire(instanceId: Long): PendingIntent

    /** Opened when the user taps the alarm in the system UI. */
    fun show(instanceId: Long): PendingIntent
}

class AndroidAlarmScheduler(
    private val alarmManager: AlarmManager,
    private val intents: AlarmIntents,
) : AlarmScheduler {
    override fun schedule(instanceId: Long, at: Instant) {
        val info = AlarmManager.AlarmClockInfo(at.toEpochMilli(), intents.show(instanceId))
        alarmManager.setAlarmClock(info, intents.fire(instanceId))
    }

    override fun cancel(instanceId: Long) {
        alarmManager.cancel(intents.fire(instanceId))
    }
}
