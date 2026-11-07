package de.drehtuer.nextalarm.tile

import android.app.Application
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.test.core.app.ApplicationProvider
import de.drehtuer.nextalarm.MainActivity
import de.drehtuer.nextalarm.R
import de.drehtuer.nextalarm.data.DataComponents
import de.drehtuer.nextalarm.domain.AlarmInstance
import de.drehtuer.nextalarm.domain.AlarmKey
import de.drehtuer.nextalarm.domain.AlarmRepository
import de.drehtuer.nextalarm.domain.AlarmRinger
import de.drehtuer.nextalarm.domain.AlarmState
import de.drehtuer.nextalarm.domain.NextAlarmTile
import de.drehtuer.nextalarm.domain.Settings
import de.drehtuer.nextalarm.domain.WakeUpAutomation
import de.drehtuer.nextalarm.domain.formatTime
import de.drehtuer.nextalarm.domain.snapshotFor
import de.drehtuer.nextalarm.planning.AppNotifications
import de.drehtuer.nextalarm.planning.openAppIntent
import de.drehtuer.nextalarm.ringing.AlarmNotifications
import de.drehtuer.nextalarm.ringing.AppAlarmEffects
import de.drehtuer.nextalarm.ringing.FakeAlarms
import de.drehtuer.nextalarm.ringing.FakeSound
import de.drehtuer.nextalarm.ringing.FakeTimers
import de.drehtuer.nextalarm.ringing.FakeVibration
import de.drehtuer.nextalarm.ringing.FakeWakeLock
import de.drehtuer.nextalarm.ringing.alarm
import de.drehtuer.nextalarm.ringing.awaitUntil
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowService
import org.w3c.dom.Element

private val NOW: Instant = Instant.parse("2026-11-06T19:00:00Z")
private val BERLIN: ZoneId = ZoneId.of("Europe/Berlin")
private val TOMORROW: Instant = Instant.parse("2026-11-07T05:00:00Z")
private val SHOWS_ALARM = NextAlarmTile("Next alarm", "tomorrow 06:00", true)
private val SHOWS_NONE = NextAlarmTile("Next alarm", "None in 48 hours", false)

private fun scheduled(at: Instant = TOMORROW) = alarm(state = AlarmState.Scheduled, triggerAt = at)

/** A repository whose reads are decided by the test. */
private class ScriptedAlarms(private val read: suspend () -> List<AlarmInstance>) : AlarmRepository by FakeAlarms() {
    override suspend fun all(): List<AlarmInstance> = read()
}

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TileRefreshTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val nextAlarmId = AppNotifications.NEXT_ALARM_ID

    private fun failing() = AndroidTileRefresh(context) { _, _ -> throw IllegalStateException("No status bar") }

    @Test
    fun `it asks for the listening state of this tile`() {
        val asked = mutableListOf<ComponentName>()
        AndroidTileRefresh(context) { _, component -> asked += component }.request()
        assertEquals(listOf(ComponentName(context, NextAlarmTileService::class.java)), asked)
    }

    @Test
    fun `a failure to ask ends there`() {
        failing().request()
    }

    @Test
    fun `it can ask Android itself`() {
        ShadowLog.clear()
        AndroidTileRefresh(context).request()
        assertEquals(emptyList(), ShadowLog.getLogsForTag("NextAlarm").map { it.msg })
    }

    @Test
    fun `a failure to ask is logged`() {
        ShadowLog.clear()
        failing().request()
        assertEquals(listOf("Could not ask for a refresh of the tile"), ShadowLog.getLogsForTag("NextAlarm").map { it.msg })
    }

    @Test
    fun `the next alarm notification asks for a refresh, also when no alarm is left`() {
        var requests = 0
        val notifications = AppNotifications(context, tile = { requests++ })
        notifications.showNextAlarm(scheduled())
        assertEquals(1, requests)
        notifications.showNextAlarm(null)
        assertEquals(2, requests)
    }

    @Test
    fun `the notification is already there when the tile is asked`() {
        var postedAtRequest: Boolean? = null
        val notifications = AppNotifications(context, tile = {
            postedAtRequest = shadowOf(manager).getNotification(nextAlarmId) != null
        })
        notifications.showNextAlarm(scheduled())
        assertEquals(true, postedAtRequest)
    }

    @Test
    fun `a failing refresh changes nothing for the notification`() {
        val notifications = AppNotifications(context, tile = failing())
        notifications.showNextAlarm(scheduled())
        assertNotNull(shadowOf(manager).getNotification(nextAlarmId))
        notifications.showNextAlarm(null)
        assertNull(shadowOf(manager).getNotification(nextAlarmId))
    }

    @Test
    fun `a failing refresh never reaches the alarm path`() {
        val ringer = AlarmRinger(FakeSound(), FakeVibration(), FakeTimers(), FakeWakeLock(), de.drehtuer.nextalarm.ringing.fakeRingAudio(), {}, {})
        val wakeUp = object : WakeUpAutomation {
            override fun startAnnouncement(alarm: AlarmInstance) = Unit
            override fun startNews(alarm: AlarmInstance) = Unit
            override fun leave(instanceId: Long) = Unit
        }
        val effects = AppAlarmEffects(ringer, AlarmNotifications(context), AppNotifications(context, tile = failing()), wakeUp)
        effects.showNextAlarm(scheduled())
        assertNotNull(shadowOf(manager).getNotification(nextAlarmId))
    }
}

/** Runs on a dispatcher without a thread of its own, so everything up to the first real wait happens right away. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class NextAlarmTileReaderTest {
    private val shown = mutableListOf<NextAlarmTile>()

    private fun reader(read: suspend () -> List<AlarmInstance>) =
        NextAlarmTileReader(ScriptedAlarms(read), Dispatchers.Unconfined, { NOW }, { BERLIN })

    @Test
    fun `it shows the next alarm in the wording of the domain`() {
        reader { listOf(scheduled()) }.start { shown += it }
        assertEquals(listOf(SHOWS_ALARM), shown)
    }

    @Test
    fun `without an alarm it shows that there is none`() {
        reader { emptyList() }.start { shown += it }
        assertEquals(listOf(SHOWS_NONE), shown)
    }

    @Test
    fun `it uses the given clock and time zone`() {
        val tokyo = NextAlarmTileReader(FakeAlarms(scheduled()), Dispatchers.Unconfined, { NOW }, { ZoneId.of("Asia/Tokyo") })
        tokyo.start { shown += it }
        assertEquals("today 14:00", shown.single().subtitle)
    }

    @Test
    fun `by default it goes by the time of the phone`() {
        val future = Instant.now().plus(Duration.ofHours(1))
        NextAlarmTileReader(FakeAlarms(scheduled(future)), Dispatchers.Unconfined).start { shown += it }
        assertTrue(shown.single().active)
        assertTrue(shown.single().subtitle.endsWith(formatTime(future, ZoneId.systemDefault())))
    }

    @Test
    fun `a failed read leaves the tile as it is`() {
        reader { throw IllegalStateException("Database gone") }.start { shown += it }
        assertTrue(shown.isEmpty())
    }

    @Test
    fun `a failure to show ends in the reader, and the next refresh works`() {
        val reader = reader { listOf(scheduled()) }
        reader.start { throw IllegalStateException("Tile gone") }
        reader.start { shown += it }
        assertEquals(listOf(SHOWS_ALARM), shown)
    }

    @Test
    fun `a read cut short shows nothing`() {
        reader { throw CancellationException("Not wanted any more") }.start { shown += it }
        assertTrue(shown.isEmpty())
    }

    @Test
    fun `what is read after stopping is not shown`() {
        val answer = CompletableDeferred<List<AlarmInstance>>()
        val reader = reader { answer.await() }
        reader.start { shown += it }
        reader.stop()
        answer.complete(listOf(scheduled()))
        assertTrue(shown.isEmpty())
    }

    @Test
    fun `stopping with nothing to stop is harmless`() {
        val reader = reader { emptyList() }
        reader.stop()
        reader.start { shown += it }
        reader.stop()
        reader.stop()
        assertEquals(listOf(SHOWS_NONE), shown)
    }

    @Test
    fun `a new start replaces a read still under way`() {
        val first = CompletableDeferred<List<AlarmInstance>>()
        val answers = ArrayDeque(listOf<suspend () -> List<AlarmInstance>>({ first.await() }, { emptyList() }))
        val reader = reader { answers.removeFirst()() }
        reader.start { shown += it }
        reader.start { shown += it }
        first.complete(listOf(scheduled()))
        assertEquals(listOf(SHOWS_NONE), shown)
    }
}

/** Counts what goes to the system, as the shadow Robolectric has for this does nothing that can be seen. */
@Implements(Tile::class)
class RecordingTile {
    @Implementation
    fun updateTile() {
        updates++
    }

    companion object {
        var updates = 0
    }
}

/**
 * Holds the tile and keeps what the real service would hand to the system. Robolectric's own shadow only knows the older
 * variant of the call, and is no `ShadowService`, so `Service.onDestroy` fails under it.
 */
@Implements(TileService::class)
class RecordingTileService : ShadowService() {
    private val tile = Shadow.newInstanceOf(Tile::class.java)

    @Implementation
    fun getQsTile(): Tile = tile

    @Implementation
    fun startActivityAndCollapse(intent: PendingIntent) {
        collapsedTo = intent
    }

    companion object {
        var collapsedTo: PendingIntent? = null
    }
}

/** On the real application with its real Hilt graph. */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [RecordingTile::class, RecordingTileService::class])
class NextAlarmTileServiceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val controller = Robolectric.buildService(NextAlarmTileService::class.java)

    @Before
    fun setUp() {
        RecordingTile.updates = 0
        RecordingTileService.collapsedTo = null
    }

    private fun service(vararg alarms: AlarmInstance) = controller.create().get().also {
        it.reader = NextAlarmTileReader(FakeAlarms(*alarms), Dispatchers.Unconfined, { NOW }, { BERLIN })
    }

    @Test
    fun `listening shows the next alarm and sends it to the system`() {
        val service = service(scheduled())
        service.onStartListening()
        val tile = service.qsTile
        assertEquals("Next alarm", tile.label)
        assertEquals("tomorrow 06:00", tile.subtitle)
        assertEquals(Tile.STATE_ACTIVE, tile.state)
        assertEquals(1, RecordingTile.updates)
    }

    @Test
    fun `without an alarm the tile is inactive`() {
        val service = service()
        service.onStartListening()
        val tile = service.qsTile
        assertEquals("Next alarm", tile.label)
        assertEquals("None in 48 hours", tile.subtitle)
        assertEquals(Tile.STATE_INACTIVE, tile.state)
        assertEquals(1, RecordingTile.updates)
    }

    @Test
    fun `each time it starts listening it reads again`() {
        val alarms = FakeAlarms(scheduled())
        val service = controller.create().get()
        service.reader = NextAlarmTileReader(alarms, Dispatchers.Unconfined, { NOW }, { BERLIN })
        service.onStartListening()
        assertEquals("tomorrow 06:00", service.qsTile.subtitle)
        runBlocking { alarms.delete(1) }
        service.onStartListening()
        assertEquals("None in 48 hours", service.qsTile.subtitle)
        assertEquals(2, RecordingTile.updates)
    }

    @Test
    fun `no tile, nothing to do`() {
        showOnTile(null, SHOWS_ALARM)
        assertEquals(0, RecordingTile.updates)
    }

    @Test
    fun `tapping it opens the app and closes the panel`() {
        service().onClick()
        val tapped = assertNotNull(RecordingTileService.collapsedTo)
        assertEquals(openAppIntent(context), tapped)
        assertTrue(tapped.isActivity)
        assertTrue(tapped.isImmutable)
        assertEquals(MainActivity::class.java.name, shadowOf(tapped).savedIntent.component?.className)
    }

    @Test
    fun `when it stops listening or goes away, a read under way no longer counts`() {
        listOf<(NextAlarmTileService) -> Unit>({ it.onStopListening() }, { it.onDestroy() }).forEach { end ->
            val answer = CompletableDeferred<List<AlarmInstance>>()
            val service = Robolectric.buildService(NextAlarmTileService::class.java).create().get()
            service.reader = NextAlarmTileReader(ScriptedAlarms { answer.await() }, Dispatchers.Unconfined, { NOW }, { BERLIN })
            service.onStartListening()
            end(service)
            answer.complete(listOf(scheduled()))
            assertEquals(0, RecordingTile.updates)
        }
    }

    @Test
    fun `it reads the real database on the real graph`(): Unit = runBlocking {
        val future = Instant.now().plus(Duration.ofHours(1))
        val data = DataComponents(context)
        data.alarms.create(AlarmKey.forEvent(1, future), future, Settings.DEFAULT.snapshotFor("Gym"))
        val service = controller.create().get()
        service.onStartListening()
        awaitUntil { RecordingTile.updates > 0 }
        assertEquals(Tile.STATE_ACTIVE, service.qsTile.state)
        assertTrue(service.qsTile.subtitle.toString().endsWith(formatTime(future, ZoneId.systemDefault())))
        service.onDestroy()
    }

    @Test
    fun `the module builds a reader of the alarms`() {
        val data = DataComponents(context)
        val reader = TileModule.reader(data)
        var result: NextAlarmTile? = null
        reader.start { result = it }
        awaitUntil { result != null }
        assertEquals(SHOWS_NONE, result)
    }
}

/** What Android goes by comes from the manifest, so that is what is checked. */
@RunWith(RobolectricTestRunner::class)
class NextAlarmTileManifestTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val service = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }
        .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml")).documentElement
        .getElementsByTagName("service").let { nodes ->
            (0 until nodes.length).map { nodes.item(it) as Element }
                .single { it.getAttribute("android:name") == ".tile.NextAlarmTileService" }
        }

    @Test
    fun `the system finds the tile, and only the system can bind it`() {
        val found = context.packageManager.queryIntentServices(
            Intent(TileService.ACTION_QS_TILE).setPackage(context.packageName),
            PackageManager.GET_META_DATA,
        ).single().serviceInfo
        assertEquals(NextAlarmTileService::class.java.name, found.name)
        assertTrue(found.exported)
        assertEquals(android.Manifest.permission.BIND_QUICK_SETTINGS_TILE, found.permission)
        assertEquals("Next alarm", found.loadLabel(context.packageManager).toString())
        assertEquals(R.drawable.ic_stat_alarm, found.icon)
    }

    @Test
    fun `it is an active tile, without which asking for a refresh would do nothing`() {
        val found = context.packageManager.queryIntentServices(
            Intent(TileService.ACTION_QS_TILE).setPackage(context.packageName),
            PackageManager.GET_META_DATA,
        ).single().serviceInfo
        assertTrue(found.metaData.getBoolean(TileService.META_DATA_ACTIVE_TILE))
    }

    @Test
    fun `it does not start before the first unlock`() {
        assertFalse(service.hasAttribute("android:directBootAware"))
    }
}
