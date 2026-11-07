package de.drehtuer.nextalarm

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.drehtuer.nextalarm.data.DataComponents
import de.drehtuer.nextalarm.data.SoundSource
import de.drehtuer.nextalarm.data.SoundStore
import de.drehtuer.nextalarm.domain.AlarmInstance
import de.drehtuer.nextalarm.domain.AlarmKey
import de.drehtuer.nextalarm.domain.AlarmState
import de.drehtuer.nextalarm.domain.NewsType
import de.drehtuer.nextalarm.domain.Settings
import de.drehtuer.nextalarm.domain.SoundRef
import de.drehtuer.nextalarm.domain.parseFeed
import de.drehtuer.nextalarm.domain.snapshotFor
import de.drehtuer.nextalarm.planning.AlarmReceiver
import de.drehtuer.nextalarm.planning.AppNotifications
import de.drehtuer.nextalarm.planning.alarmUri
import de.drehtuer.nextalarm.ringing.AlarmActionReceiver
import de.drehtuer.nextalarm.ringing.AlarmActivity
import de.drehtuer.nextalarm.ringing.MediaPlayerOutput
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What only a real phone can show, without making a sound: every alarm here plays at volume 0, without vibration or
 * speech, and these tests can safely run while someone sleeps next to the phone. They use their own alarms in the app's
 * real database and delete them, and they don't touch the settings or the planner.
 *
 * Set the argument `holdSeconds` to keep the alarm screen up for a screenshot.
 */
@RunWith(AndroidJUnit4::class)
class SilentDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val data = DataComponents(context)
    private val arguments: Bundle = InstrumentationRegistry.getArguments()
    private val created = mutableListOf<Long>()

    /** Silent in every way that the snapshot can say so. */
    private val silent = Settings.DEFAULT.copy(vibrate = false, maxVolume = 0f, speakReason = false, news = null)

    private fun silentAlarm(title: String): AlarmInstance = runBlocking {
        val now = Instant.now()
        data.alarms.create(AlarmKey.forTestAlarm(now.toEpochMilli()), now, silent.snapshotFor(title)).also {
            created += it.id
        }
    }

    private fun state(id: Long): AlarmState? = runBlocking { data.alarms.find(id)?.state }

    private fun awaitState(id: Long, expected: AlarmState) {
        repeat(WAIT_STEPS) {
            if (state(id) == expected) return
            Thread.sleep(POLL_MILLIS)
        }
        assertEquals(expected, state(id))
    }

    private fun fire(alarm: AlarmInstance) {
        context.sendBroadcast(Intent(context, AlarmReceiver::class.java).setData(alarmUri(alarm.id)))
    }

    private fun send(action: String) {
        context.sendBroadcast(Intent(context, AlarmActionReceiver::class.java).setAction(action))
    }

    private fun activeNotifications() =
        context.getSystemService(NotificationManager::class.java).activeNotifications.toList()

    /** Nothing keeps ringing, whatever a test did, and its alarms are gone. */
    @After
    fun cleanUp() = runBlocking {
        created.forEach { id ->
            val instance = data.alarms.find(id)
            if (instance != null && instance.state != AlarmState.Done) {
                val action = if (instance.state == AlarmState.Ringing) {
                    AlarmActionReceiver.ACTION_DISMISS_ALL
                } else {
                    AlarmActionReceiver.ACTION_DISMISS
                }
                context.sendBroadcast(
                    Intent(context, AlarmActionReceiver::class.java).setAction(action).setData(alarmUri(id)),
                )
                awaitState(id, AlarmState.Done)
            }
            data.alarms.delete(id)
        }
    }

    @Test
    fun aFiredAlarmRingsAndDismissEndsIt() {
        val alarm = silentAlarm("Device test alarm")
        fire(alarm)
        awaitState(alarm.id, AlarmState.Ringing)
        // The state is stored first, so that a crash can't lose it, and the notification follows.
        repeat(WAIT_STEPS) {
            if (activeNotifications().any { it.id != NEXT_ALARM_ID }) return@repeat
            Thread.sleep(POLL_MILLIS)
        }
        assertTrue(activeNotifications().any { it.id != NEXT_ALARM_ID })

        val hold = arguments.getString("holdSeconds")?.toLong() ?: 0
        if (hold > 0) {
            context.startActivity(Intent(context, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Thread.sleep(hold * MILLIS_PER_SECOND)
        }

        send(AlarmActionReceiver.ACTION_DISMISS_ALL)
        awaitState(alarm.id, AlarmState.Done)
    }

    @Test
    fun snoozeWaitsAndDismissFromTheNotificationEndsIt() {
        val alarm = silentAlarm("Device test snooze")
        fire(alarm)
        awaitState(alarm.id, AlarmState.Ringing)
        send(AlarmActionReceiver.ACTION_SNOOZE_ALL)
        awaitState(alarm.id, AlarmState.Snoozed)
        val snoozed = runBlocking { data.alarms.find(alarm.id) }
        assertTrue(snoozed!!.triggerAt.isAfter(Instant.now()))
        assertEquals(1, snoozed.snoozeCount)

        context.sendBroadcast(
            Intent(context, AlarmActionReceiver::class.java)
                .setAction(AlarmActionReceiver.ACTION_DISMISS)
                .setData(alarmUri(alarm.id)),
        )
        awaitState(alarm.id, AlarmState.Done)
    }

    @Test
    fun severalAlarmsRingTogetherAndEndTogether() {
        val first = silentAlarm("Device test first")
        val second = silentAlarm("Device test second")
        fire(first)
        awaitState(first.id, AlarmState.Ringing)
        fire(second)
        awaitState(second.id, AlarmState.Ringing)
        send(AlarmActionReceiver.ACTION_DISMISS_ALL)
        awaitState(first.id, AlarmState.Done)
        awaitState(second.id, AlarmState.Done)
    }

    /** Includes the phone's own default sound, if it is reachable: the alarm has to fall back if not. */
    @Test
    fun everySoundSourceCanBeOpenedAtVolumeZero() {
        val sources = SoundStore(context).sources(SoundRef.SystemDefault)
        assertTrue(sources.last() is SoundSource.Bundled)
        sources.forEach { source ->
            val output = MediaPlayerOutput(context)
            try {
                assertTrue("Could not open $source", output.start(source, 0f) {})
            } finally {
                output.stop()
            }
        }
    }

    /** Android's XML parser differs from the JVM's that the unit tests run on. */
    @Test
    fun androidsXmlParserReadsARealFeed() {
        val rss = """<?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0"><channel><title>News</title>
            <item><title>Headline</title><description>Text</description></item>
            </channel></rss>""".toByteArray()
        val feed = parseFeed(rss, "fallback")
        assertNotNull(feed)
        assertEquals("News", feed!!.name)
        assertEquals(NewsType.RSS, feed.kind)
        assertEquals("Headline", feed.entries.single().title)
    }

    @Test
    fun androidsXmlParserReadsAPodcast() {
        val podcast = """<?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0"><channel><title>Cast</title>
            <item><title>Episode</title><enclosure url="https://example.org/a.mp3" type="audio/mpeg" length="1"/></item>
            </channel></rss>""".toByteArray()
        val feed = parseFeed(podcast, "fallback")
        assertNotNull(feed)
        assertEquals(NewsType.PODCAST, feed!!.kind)
        assertEquals("https://example.org/a.mp3", feed.latestEpisode?.audioUrl)
    }

    @Test
    fun androidsXmlParserRefusesEntityTricks() {
        val hostile = """<?xml version="1.0"?>
            <!DOCTYPE rss [<!ENTITY a "aaaaaaaaaa"><!ENTITY b "&a;&a;&a;&a;&a;&a;&a;&a;">]>
            <rss version="2.0"><channel><title>&b;</title></channel></rss>""".toByteArray()
        assertNull(parseFeed(hostile, "fallback"))
    }

    private companion object {
        const val WAIT_STEPS = 500
        const val POLL_MILLIS = 20L
        const val MILLIS_PER_SECOND = 1_000L
        const val NEXT_ALARM_ID = AppNotifications.NEXT_ALARM_ID
    }
}
