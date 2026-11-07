package de.drehtuer.nextalarm.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import de.drehtuer.nextalarm.domain.FeedFailure
import de.drehtuer.nextalarm.domain.FeedResult
import de.drehtuer.nextalarm.domain.PlannerRecord
import de.drehtuer.nextalarm.domain.Settings
import de.drehtuer.nextalarm.domain.SoundRef
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(RobolectricTestRunner::class)
class DataComponentsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val components = DataComponents(context)

    @Test
    fun `the saved alarm volume is there, and starts empty`() {
        assertEquals(null, components.savedVolume.load())
    }

    @Test
    fun `the repositories work on one database`() = runBlocking {
        assertEquals(Settings.DEFAULT, components.settings.load())
        assertEquals(PlannerRecord.INITIAL, components.planner.load())
        assertTrue(components.alarms.all().isEmpty())
    }

    @Test
    fun `feeds can be fetched, here without going near a network`() = runBlocking {
        assertEquals(FeedResult.Failed(FeedFailure.InvalidAddress), components.feeds.fetchFeed("ftp://a.example/"))
    }

    @Test
    fun `the scheduler registers with the system`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val intents = object : AlarmIntents {
            override fun fire(instanceId: Long): PendingIntent =
                PendingIntent.getBroadcast(context, 0, Intent("fire"), PendingIntent.FLAG_IMMUTABLE)

            override fun show(instanceId: Long): PendingIntent =
                PendingIntent.getActivity(context, 0, Intent("show"), PendingIntent.FLAG_IMMUTABLE)
        }
        val time = Instant.parse("2026-11-07T05:30:00Z")
        components.scheduler(intents).schedule(1, time)
        assertEquals(time.toEpochMilli(), context.getSystemService(AlarmManager::class.java).nextAlarmClock?.triggerTime)
    }

    @Test
    fun `the calendar repository is available without opening the database`() {
        assertTrue(components.calendars is AndroidCalendarRepository)
    }

    @Test
    fun `the sound store always offers the bundled sound`() {
        assertEquals(SoundSource.Bundled(R.raw.alarm_fallback), components.sounds.sources(SoundRef.SystemDefault).last())
    }
}
