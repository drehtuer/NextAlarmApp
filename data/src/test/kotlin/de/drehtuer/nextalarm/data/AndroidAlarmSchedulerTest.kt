package de.drehtuer.nextalarm.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(RobolectricTestRunner::class)
class AndroidAlarmSchedulerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    private val intents = object : AlarmIntents {
        override fun fire(instanceId: Long): PendingIntent = PendingIntent.getBroadcast(
            context, instanceId.toInt(), Intent("fire"), PendingIntent.FLAG_IMMUTABLE,
        )

        override fun show(instanceId: Long): PendingIntent = PendingIntent.getActivity(
            context, instanceId.toInt(), Intent("show"), PendingIntent.FLAG_IMMUTABLE,
        )
    }
    private val scheduler = AndroidAlarmScheduler(alarmManager, intents)
    private val time = Instant.parse("2026-11-07T05:30:00Z")

    @Before
    fun allowExactAlarms() = ShadowAlarmManager.setCanScheduleExactAlarms(true)

    @Test
    fun `an alarm is registered as an alarm clock at its time`() {
        scheduler.schedule(1, time)
        val clock = assertNotNull(alarmManager.nextAlarmClock)
        assertEquals(time.toEpochMilli(), clock.triggerTime)
        assertEquals(intents.show(1), clock.showIntent)
        assertEquals(intents.fire(1), shadowOf(alarmManager).nextScheduledAlarm?.operation)
    }

    @Test
    fun `scheduling the same alarm again replaces it`() {
        scheduler.schedule(1, time)
        scheduler.schedule(1, time.plusSeconds(600))
        assertEquals(1, shadowOf(alarmManager).scheduledAlarms.size)
        assertEquals(time.plusSeconds(600).toEpochMilli(), alarmManager.nextAlarmClock?.triggerTime)
    }

    @Test
    fun `cancelling removes only that alarm`() {
        scheduler.schedule(1, time)
        scheduler.schedule(2, time.plusSeconds(60))
        scheduler.cancel(1)
        assertEquals(listOf(intents.fire(2)), shadowOf(alarmManager).scheduledAlarms.map { it.operation })
    }

    @Test
    fun `cancelling the last alarm leaves none`() {
        scheduler.schedule(1, time)
        scheduler.cancel(1)
        assertNull(alarmManager.nextAlarmClock)
    }
}
