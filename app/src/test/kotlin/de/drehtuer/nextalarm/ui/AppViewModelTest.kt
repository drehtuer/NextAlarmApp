package de.drehtuer.nextalarm.ui

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import de.drehtuer.nextalarm.MainActivity
import de.drehtuer.nextalarm.data.DataComponents
import de.drehtuer.nextalarm.data.SoundFiles
import de.drehtuer.nextalarm.data.SoundImportResult
import de.drehtuer.nextalarm.domain.AlarmAction
import de.drehtuer.nextalarm.domain.AlarmVolume
import de.drehtuer.nextalarm.domain.FeedFailure
import de.drehtuer.nextalarm.domain.FeedResult
import de.drehtuer.nextalarm.domain.NewsSource
import de.drehtuer.nextalarm.domain.NewsType
import de.drehtuer.nextalarm.domain.LOG_FAILED_NOTE
import de.drehtuer.nextalarm.domain.LOG_SAVED_NOTE
import de.drehtuer.nextalarm.domain.LOG_TITLES_NOTE
import de.drehtuer.nextalarm.domain.PlannerRecord
import de.drehtuer.nextalarm.domain.PlanningRun
import de.drehtuer.nextalarm.domain.SettingsRepository
import de.drehtuer.nextalarm.domain.SoundRef
import de.drehtuer.nextalarm.domain.SoundRejection
import de.drehtuer.nextalarm.domain.TEST_ALARM_DELAY
import de.drehtuer.nextalarm.ringing.FakeScheduler
import de.drehtuer.nextalarm.domain.AlarmEffects
import de.drehtuer.nextalarm.domain.AlarmEngine
import de.drehtuer.nextalarm.domain.AlarmInstance
import de.drehtuer.nextalarm.domain.AlarmKey
import de.drehtuer.nextalarm.domain.AlarmState
import de.drehtuer.nextalarm.domain.CalendarEvent
import de.drehtuer.nextalarm.domain.CalendarId
import de.drehtuer.nextalarm.domain.CalendarRepository
import de.drehtuer.nextalarm.domain.DeviceCalendar
import de.drehtuer.nextalarm.domain.PlannerEffects
import de.drehtuer.nextalarm.domain.PlannerEngine
import de.drehtuer.nextalarm.domain.EngineLock
import de.drehtuer.nextalarm.domain.PlannerState
import de.drehtuer.nextalarm.domain.Settings
import de.drehtuer.nextalarm.domain.snapshotFor
import de.drehtuer.nextalarm.planning.AppNotifications
import de.drehtuer.nextalarm.planning.AppPlannerEffects
import de.drehtuer.nextalarm.planning.PlanningModule
import de.drehtuer.nextalarm.planning.PlanningWork
import androidx.work.WorkManager
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

private val NOW: Instant = Instant.parse("2026-11-06T19:00:00Z")
private val CALENDAR = DeviceCalendar(CalendarId(1), "NextAlarm", "me", "bitfire.at.davdroid", null)
private const val FEED_ADDRESS = "https://www.tagesschau.de/feed"

private class FakeCalendars(var result: () -> List<DeviceCalendar> = { listOf(CALENDAR) }) : CalendarRepository {
    var reads = 0

    override suspend fun calendars(): List<DeviceCalendar> {
        reads++
        return result()
    }

    override suspend fun events(calendar: CalendarId, from: Instant, to: Instant) = emptyList<CalendarEvent>()
}

private class RecordingEffects : PlannerEffects {
    val started = mutableListOf<Long>()
    override fun cancelPlanningJob() = Unit
    override fun startPlanningJob(generation: Long) {
        started += generation
    }

    override fun scheduleRetry(delay: Duration) = Unit
    override fun showFailSafeWarning() = Unit
    override fun removeFailSafeWarning() = Unit
    override fun showNextAlarm(alarm: AlarmInstance?) = Unit
}

private object NoAlarmEffects : AlarmEffects {
    override fun perform(instance: AlarmInstance, action: AlarmAction) = Unit
    override fun showNextAlarm(alarm: AlarmInstance?) = Unit
}

private object OpenLock : EngineLock {
    override suspend fun <T> withLock(block: suspend () -> T): T = block()
}

/** Uses the real database with a fake calendar provider, so no real calendar is read. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class AppViewModelTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val data = DataComponents(context)
    private val calendars = FakeCalendars()
    private val effects = RecordingEffects()
    private val changes = MutableSharedFlow<Unit>()
    private val scheduler = FakeScheduler()
    private val alarmEngine = AlarmEngine(data.alarms, scheduler, NoAlarmEffects, { NOW }, OpenLock)
    private val engine = PlannerEngine(data.planner, data.settings, calendars, data.alarms, alarmEngine, effects, OpenLock) { NOW }
    private val audio = FakeAudio()
    private val sounds = FakeSounds(data.settings)
    private val logs = FakeLogs()
    private var fullScreen = true
    private val news = NewsParts { NOW }
    private val viewModel = AppViewModel(
        AppPorts(
            settings = data.settings,
            planner = data.planner,
            alarms = data.alarms,
            calendars = calendars,
            engine = engine,
            alarmEngine = alarmEngine,
            planningChanges = changes,
            phone = PhonePorts(audio, sounds, logs) { fullScreen },
            news = news.ports,
            io = Dispatchers.Unconfined,
            now = { NOW },
            zone = { ZoneId.of("Europe/Berlin") },
        ),
    )
    private val picked = Uri.parse("content://docs/picked")

    private fun settle() = shadowOf(android.os.Looper.getMainLooper()).idle()

    /** Lets work that was started on the main thread and continues on background threads finish. */
    private fun awaitState(check: (AppState) -> Boolean): AppState = runBlocking {
        withTimeout(WAIT_MILLIS) {
            var state = viewModel.state.value
            while (!check(state)) {
                settle()
                Thread.sleep(POLL_MILLIS)
                state = viewModel.state.value
            }
            state
        }
    }

    private fun load(): AppState {
        viewModel.startWatching()
        val state = awaitState { it.loaded }
        viewModel.stopWatching()
        return state
    }

    @Test
    fun `the state starts unloaded`() {
        assertFalse(viewModel.state.value.loaded)
    }

    @Test
    fun `a new install is not set up, and has no selected calendar`() {
        val state = load()
        assertTrue(state.loaded)
        assertFalse(state.setupDone)
        assertNull(state.selectedCalendar)
        assertEquals(listOf(CALENDAR), state.calendars)
    }

    @Test
    fun `home shows the waiting alarms and the chosen calendar`() = runBlocking {
        val at = NOW.plus(Duration.ofHours(10))
        data.settings.save(Settings.DEFAULT.copy(alarmCalendar = CALENDAR.id))
        data.alarms.create(AlarmKey.forEvent(1, at), at, Settings.DEFAULT.snapshotFor("Flight"))
        val state = load()
        assertEquals(CALENDAR.id, state.selectedCalendar)
        assertEquals("Flight", state.home.hero?.title)
        assertEquals("From the calendar “NextAlarm” (DAVx⁵).", state.home.footer)
    }

    @Test
    fun `without calendar access there is nothing to list`() {
        calendars.result = { throw SecurityException("no permission") }
        assertEquals(emptyList(), load().calendars)
    }

    @Test
    fun `a cancelled read does not count as an empty list`() {
        calendars.result = { throw CancellationException("gone") }
        viewModel.startWatching()
        runBlocking {
            withTimeout(WAIT_MILLIS) {
                while (calendars.reads == 0) {
                    settle()
                    Thread.sleep(POLL_MILLIS)
                }
            }
        }
        settle()
        viewModel.stopWatching()
        assertFalse(viewModel.state.value.loaded)
    }

    @Test
    fun `completing setup starts planning`() {
        viewModel.completeSetup()
        val state = awaitState { it.setupDone }
        assertTrue(state.loaded)
        assertEquals(listOf(1L), effects.started)
    }

    @Test
    fun `choosing a calendar saves it, and re-plans once setup is done`() = runBlocking {
        viewModel.chooseCalendar(CALENDAR.id)
        awaitState { it.selectedCalendar == CALENDAR.id }
        assertTrue(effects.started.isEmpty())

        engine.setupCompleted()
        viewModel.chooseCalendar(CalendarId(2))
        awaitState { it.selectedCalendar == CalendarId(2) }
        assertEquals(listOf(1L, 2L), effects.started)
        assertEquals(PlannerState.Planning, data.planner.load().state)
    }

    @Test
    fun `watching loads the state at once and again when planning changes`() = runBlocking {
        viewModel.startWatching()
        awaitState { it.loaded && it.calendars.size == 1 }

        calendars.result = { emptyList() }
        withTimeout(WAIT_MILLIS) { changes.subscriptionCount.first { it > 0 } }
        changes.emit(Unit)
        awaitState { it.calendars.isEmpty() }
        viewModel.stopWatching()
    }

    @Test
    fun `after watching stops, changes are not picked up`() = runBlocking {
        viewModel.startWatching()
        awaitState { it.loaded }
        viewModel.stopWatching()
        assertEquals(0, changes.subscriptionCount.value)
    }

    @Test
    fun `watching again replaces the earlier watch`() = runBlocking {
        viewModel.startWatching()
        viewModel.startWatching()
        settle()
        withTimeout(WAIT_MILLIS) { changes.subscriptionCount.first { it == 1 } }
        viewModel.stopWatching()
        Unit
    }

    /** A changed setting counts once it is stored and a new plan was asked for, after setup. */
    private fun assertChanges(act: () -> Unit, stored: (Settings) -> Boolean) = runBlocking {
        engine.setupCompleted()
        val runs = effects.started.size
        act()
        awaitCondition { stored(data.settings.load()) && effects.started.size > runs }
    }

    private fun awaitCondition(check: suspend () -> Boolean) = runBlocking {
        withTimeout(WAIT_MILLIS) {
            while (!check()) {
                settle()
                Thread.sleep(POLL_MILLIS)
            }
        }
    }

    @Test
    fun `each setting is stored and asks for a new plan`() {
        assertChanges({ viewModel.setVolumeRamp(30) }) { it.volumeRampSeconds == 30 }
        assertChanges({ viewModel.setMaxVolume(0.5f) }) { it.maxVolume == 0.5f }
        assertChanges({ viewModel.setMaxVolume(null) }) { it.maxVolume == null }
        assertChanges({ viewModel.setVibrate(true) }) { it.vibrate }
        assertChanges({ viewModel.setRingFor(5) }) { it.ringTimeoutMinutes == 5 }
        assertChanges({ viewModel.setSnoozeLength(20) }) { it.snoozeMinutes == 20 }
        assertChanges({ viewModel.setSpeakTitle(true) }) { it.speakReason }
    }

    @Test
    fun `a changed setting shows at once`() {
        viewModel.setSnoozeLength(20)
        val state = awaitState { it.alarmSettings.snoozeLength.label == "20 min" }
        assertTrue(state.loaded)
    }

    @Test
    fun `quick changes don't lose each other`() = runBlocking {
        viewModel.setVibrate(true)
        viewModel.setSpeakTitle(true)
        viewModel.setRingFor(5)
        viewModel.setSnoozeLength(20)
        viewModel.setVolumeRamp(60)
        awaitState { it.alarmSettings.volumeRamp.label == "1 min" }
        awaitCondition {
            val settings = data.settings.load()
            settings.vibrate && settings.speakReason && settings.ringTimeoutMinutes == 5 &&
                settings.snoozeMinutes == 20 && settings.volumeRampSeconds == 60
        }
    }

    @Test
    fun `the alarm settings show the phone's sound and a low volume`() {
        audio.volume = AlarmVolume(1, 7)
        audio.name = "Bright Morning"
        val model = load().alarmSettings
        assertEquals("Phone default (Bright Morning)", model.soundName)
        assertEquals("Your phone's alarm volume is low (1 of 7).", model.lowVolume?.title)
    }

    @Test
    fun `coming back after changing the volume clears the banner`() {
        audio.volume = AlarmVolume(1, 7)
        assertNotNull(load().alarmSettings.lowVolume)
        audio.volume = AlarmVolume(5, 7)
        viewModel.startWatching()
        awaitState { it.alarmSettings.lowVolume == null }
        viewModel.stopWatching()
    }

    @Test
    fun `a picked sound replaces the old one, which is deleted only after the settings are saved`() = runBlocking {
        val old = SoundRef.File("old")
        val new = SoundRef.File("new")
        data.settings.save(Settings.DEFAULT.copy(sound = old))
        engine.setupCompleted()
        val runs = effects.started.size
        sounds.result = SoundImportResult.Imported(new)
        viewModel.importSound(picked)
        awaitState { it.alarmSettings.soundName == "new" }
        assertEquals(new, data.settings.load().sound)
        assertEquals(listOf(picked), sounds.imported)
        assertEquals(listOf<SoundRef>(old), sounds.deleted)
        assertEquals(new, sounds.storedAtDelete.single())
        assertEquals(runs + 1, effects.started.size)
        assertTrue(viewModel.state.value.alarmSettings.usesFile)
    }

    @Test
    fun `giving up a file goes back to the default, and deletes the copy after saving`() = runBlocking {
        val old = SoundRef.File("old")
        data.settings.save(Settings.DEFAULT.copy(sound = old))
        assertTrue(load().alarmSettings.usesFile)
        viewModel.useDefaultSound()
        awaitState { !it.alarmSettings.usesFile }
        assertEquals(SoundRef.SystemDefault, data.settings.load().sound)
        assertEquals(listOf<SoundRef>(old), sounds.deleted)
        assertEquals(SoundRef.SystemDefault, sounds.storedAtDelete.single())
    }

    @Test
    fun `a rejected file changes nothing and says why, until a file is taken`() = runBlocking {
        val old = SoundRef.File("old")
        data.settings.save(Settings.DEFAULT.copy(sound = old))
        sounds.result = SoundImportResult.Rejected(SoundRejection.NOT_PLAYABLE, "silence.m4a")
        viewModel.importSound(picked)
        val rejected = awaitState { it.alarmSettings.soundRejection != null }
        assertEquals("“silence.m4a” has no sound NextAlarm can play. Choose another file.", rejected.alarmSettings.soundRejection)
        assertEquals(old, data.settings.load().sound)
        assertTrue(sounds.deleted.isEmpty())

        sounds.result = SoundImportResult.Imported(SoundRef.File("new"))
        viewModel.importSound(picked)
        awaitState { it.alarmSettings.soundRejection == null }
        Unit
    }

    @Test
    fun `a rejection stays through a refresh`() {
        sounds.result = SoundImportResult.Rejected(SoundRejection.UNREADABLE, "a.mp3")
        viewModel.importSound(picked)
        awaitState { it.alarmSettings.soundRejection != null }
        assertNotNull(load().alarmSettings.soundRejection)
    }

    @Test
    fun `giving up a file also clears a rejection`() {
        sounds.result = SoundImportResult.Rejected(SoundRejection.UNREADABLE, "a.mp3")
        viewModel.importSound(picked)
        awaitState { it.alarmSettings.soundRejection != null }
        viewModel.useDefaultSound()
        awaitState { it.alarmSettings.soundRejection == null }
    }

    /** Detection waits until the typing has paused, on a clock that only moves when the test says so. */
    private fun detectNews(address: String) {
        viewModel.setNewsAddress(address)
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
    }

    @Test
    fun `screen 6 starts from what is stored`() = runBlocking {
        data.settings.save(Settings.DEFAULT.copy(news = NewsSource(NewsType.PODCAST, FEED_ADDRESS, 20)))
        news.feeds.result = FeedResult.Found(PODCAST_FEED)
        viewModel.openNews()
        val state = awaitState { it.news.detected != null }
        assertEquals(FEED_ADDRESS, state.news.address)
        assertEquals("20 min", state.news.minutes)
    }

    @Test
    fun `a feed that was found is stored, asks for a new plan and shows in the row of screen 5`() = runBlocking {
        engine.setupCompleted()
        val runs = effects.started.size
        detectNews(FEED_ADDRESS)
        val state = awaitState { it.alarmSettings.news == "RSS · tagesschau.de" }
        assertEquals("RSS feed found.", state.news.helper)
        assertEquals(NewsSource(NewsType.RSS, FEED_ADDRESS, 15), data.settings.load().news)
        assertEquals(runs + 1, effects.started.size)
    }

    @Test
    fun `an address that gives no feed stores nothing and asks for no new plan`() = runBlocking {
        engine.setupCompleted()
        val runs = effects.started.size
        news.feeds.result = FeedResult.Failed(FeedFailure.Http(404, "Not Found"))
        viewModel.setNewsAddress(FEED_ADDRESS)
        viewModel.toggleNewsTest()
        val state = awaitState { it.news.failure != null }
        assertTrue(state.news.addressFailed)
        assertNull(data.settings.load().news)
        assertEquals(runs, effects.started.size)
    }

    @Test
    fun `the stepper changes what is stored and asks for a new plan`() {
        detectNews(FEED_ADDRESS)
        awaitState { it.alarmSettings.news != "Off" }
        assertChanges({ viewModel.lengthenNews() }) { it.news?.maxMinutes == 20 }
        assertChanges({ viewModel.shortenNews() }) { it.news?.maxMinutes == 15 }
    }

    @Test
    fun `turning off stores no news and asks for a new plan`() {
        detectNews(FEED_ADDRESS)
        awaitState { it.alarmSettings.news != "Off" }
        assertChanges({ viewModel.turnOffNews() }) { it.news == null }
        awaitState { it.alarmSettings.news == "Off" }
    }

    @Test
    fun `a test plays, and stops at stop`() {
        viewModel.setNewsAddress(FEED_ADDRESS)
        viewModel.toggleNewsTest()
        awaitState { it.news.testing && news.speech.pending != null }
        viewModel.stopNewsTest()
        val state = awaitState { !it.news.testing }
        assertNull(state.news.testLine)
        assertNull(news.speech.pending)
    }

    @Test
    fun `closing screen 6 stops a test, and the next visit starts from what is stored`() = runBlocking {
        viewModel.setNewsAddress(FEED_ADDRESS)
        viewModel.toggleNewsTest()
        awaitState { it.news.testing && news.speech.pending != null }
        data.settings.save(Settings.DEFAULT)
        viewModel.closeNews()
        awaitState { !it.news.testing }
        assertNull(news.speech.pending)
        viewModel.openNews()
        awaitState { it.news.address.isEmpty() }
        Unit
    }

    @Test
    fun `the diagnostics show the planner, the facts and the past alarms`() = runBlocking {
        val at = NOW.minus(Duration.ofDays(1))
        val done = data.alarms.create(AlarmKey.forEvent(1, at), at, Settings.DEFAULT.snapshotFor("Gym"))
        data.alarms.update(done.copy(state = AlarmState.Done))
        data.settings.save(Settings.DEFAULT.copy(alarmCalendar = CALENDAR.id))
        val run = PlanningRun(NOW, Duration.ofMillis(400), 4, null)
        data.planner.save(PlannerRecord(PlannerState.Planned, 1, lastRun = run))
        fullScreen = false
        val state = load()
        assertEquals("Last check 20:00 · all good", state.diagnosticsSummary)
        assertEquals("Alarms are planned", state.diagnostics.status.title)
        assertEquals("NextAlarm · DAVx⁵", state.diagnostics.calendar)
        assertEquals("Not allowed: alarms ring as a notification", state.diagnostics.fullScreen)
        assertEquals(listOf("Gym"), state.diagnostics.pastAlarms.map { it.title })
    }

    @Test
    fun `a test alarm goes through the engine with the current settings, and is pending until it rings`() = runBlocking {
        data.settings.save(Settings.DEFAULT.copy(vibrate = true))
        viewModel.ringTestAlarm()
        assertTrue(viewModel.state.value.testAlarmPending)
        awaitCondition { data.alarms.all().isNotEmpty() && scheduler.scheduled.isNotEmpty() }
        val alarm = data.alarms.all().single()
        assertEquals(AlarmKey.forTestAlarm(NOW.toEpochMilli()), alarm.key)
        assertFalse(alarm.key.isFromCalendar)
        assertEquals(AlarmState.Scheduled, alarm.state)
        assertEquals("Test alarm", alarm.snapshot.reason)
        assertTrue(alarm.snapshot.vibrate)
        assertEquals(NOW.plus(TEST_ALARM_DELAY), scheduler.scheduled[alarm.id])

        // Waits for the state that follows the alarm's creation, which is what starts the delay.
        awaitState { it.home.hero?.title == "Test alarm" }
        settle()
        assertTrue(viewModel.state.value.testAlarmPending)
        awaitPendingEnd()
        assertEquals(1, data.alarms.all().size)
    }

    private fun awaitPendingEnd() = runBlocking {
        withTimeout(WAIT_MILLIS) {
            while (viewModel.state.value.testAlarmPending) {
                shadowOf(android.os.Looper.getMainLooper()).idleFor(TEST_ALARM_DELAY)
                Thread.sleep(POLL_MILLIS)
            }
        }
    }

    @Test
    fun `asking again while a test alarm is on its way adds no other`() = runBlocking {
        viewModel.ringTestAlarm()
        viewModel.ringTestAlarm()
        awaitCondition { data.alarms.all().isNotEmpty() }
        viewModel.ringTestAlarm()
        awaitState { it.home.hero?.title == "Test alarm" }
        assertEquals(1, data.alarms.all().size)
    }

    @Test
    fun `a new test alarm can be asked for once the last one has rung`() = runBlocking {
        viewModel.ringTestAlarm()
        awaitCondition { data.alarms.all().isNotEmpty() }
        awaitState { it.home.hero?.title == "Test alarm" }
        awaitPendingEnd()
        viewModel.ringTestAlarm()
        assertTrue(viewModel.state.value.testAlarmPending)
    }

    @Test
    fun `a re-plan leaves a test alarm alone`() = runBlocking {
        viewModel.ringTestAlarm()
        awaitCondition { data.alarms.all().isNotEmpty() }
        engine.setupCompleted()
        data.settings.save(Settings.DEFAULT.copy(alarmCalendar = CALENDAR.id))
        engine.runPlanning(data.planner.load().generation)
        assertEquals(PlannerState.Planned, data.planner.load().state)
        assertEquals(AlarmState.Scheduled, data.alarms.all().single().state)
    }

    @Test
    fun `an exported log holds what diagnostics show, and says it was saved`() = runBlocking {
        val at = NOW.minus(Duration.ofDays(1))
        val done = data.alarms.create(AlarmKey.forEvent(1, at), at, Settings.DEFAULT.snapshotFor("Gym"))
        data.alarms.update(done.copy(state = AlarmState.Done))
        viewModel.exportLog(picked)
        val state = awaitState { it.logNote != null }
        assertEquals(LOG_SAVED_NOTE, state.logNote)
        val (uri, text) = logs.written.single()
        assertEquals(picked, uri)
        assertTrue("Planner state: Unconfigured" in text, text)
        assertTrue("| Dismissed | Gym" in text, text)
        assertTrue(LOG_TITLES_NOTE in text, text)
    }

    @Test
    fun `a log that can't be written says so`() {
        logs.works = false
        viewModel.exportLog(picked)
        assertEquals(LOG_FAILED_NOTE, awaitState { it.logNote != null }.logNote)
    }

    @Test
    fun `the note of an export stays through a refresh`() {
        viewModel.exportLog(picked)
        awaitState { it.logNote != null }
        assertEquals(LOG_SAVED_NOTE, load().logNote)
    }

    private companion object {
        const val WAIT_MILLIS = 10_000L
        const val POLL_MILLIS = 10L
    }
}

private class FakeAudio : PhoneAudio {
    var volume = AlarmVolume(5, 7)
    var name: String? = null

    override fun alarmVolume() = volume
    override fun defaultSoundName() = name
}

/** Also notes what the settings say when a copy is deleted, because they must not name it any more. */
private class FakeSounds(private val settings: SettingsRepository) : SoundFiles {
    var result: SoundImportResult = SoundImportResult.Rejected(SoundRejection.UNREADABLE, "none")
    val imported = mutableListOf<Uri>()
    val deleted = mutableListOf<SoundRef>()
    val storedAtDelete = mutableListOf<SoundRef>()

    override suspend fun import(uri: Uri): SoundImportResult {
        imported += uri
        return result
    }

    override fun delete(sound: SoundRef) {
        deleted += sound
        storedAtDelete += runBlocking { settings.load().sound }
    }
}

private class FakeLogs : LogDocuments {
    var works = true
    val written = mutableListOf<Pair<Uri, String>>()

    override fun write(uri: Uri, text: String): Boolean {
        written += uri to text
        return works
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AppNotificationsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val notifications = AppNotifications(context) { NOW }

    private fun alarm(title: String?) = AlarmInstance(
        id = 1,
        key = AlarmKey.forTestAlarm(1),
        triggerAt = NOW.plus(Duration.ofDays(1)),
        state = AlarmState.Scheduled,
        snoozeCount = 0,
        autoSnoozed = false,
        snapshot = Settings.DEFAULT.snapshotFor(title),
    )

    private fun shown(id: Int) = shadowOf(manager).activeNotifications.find { it.id == id }?.notification

    @Test
    fun `the next alarm is shown silently and stays`() {
        notifications.showNextAlarm(alarm("Flight"))
        val notification = assertNotNull(shown(AppNotifications.NEXT_ALARM_ID))
        assertEquals("Flight", notification.extras.getString(android.app.Notification.EXTRA_TEXT))
        assertTrue(notification.extras.getString(android.app.Notification.EXTRA_TITLE)!!.startsWith("Next alarm: "))
        assertTrue(notification.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(NotificationManager.IMPORTANCE_LOW, manager.getNotificationChannel(AppNotifications.NEXT_ALARM_CHANNEL).importance)
        assertNotNull(notification.contentIntent)
    }

    @Test
    fun `no next alarm removes the notification`() {
        notifications.showNextAlarm(alarm("Flight"))
        notifications.showNextAlarm(null)
        assertNull(shown(AppNotifications.NEXT_ALARM_ID))
    }

    @Test
    fun `the fail-safe warning can be shown and removed`() {
        notifications.showFailSafeWarning()
        val notification = assertNotNull(shown(AppNotifications.FAIL_SAFE_ID))
        assertEquals("New alarms aren't being added", notification.extras.getString(android.app.Notification.EXTRA_TITLE))
        val action = notification.actions.single()
        assertEquals("Open diagnostics", action.title.toString())
        val intent = shadowOf(action.actionIntent).savedIntent
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertEquals(MainActivity.ACTION_OPEN_DIAGNOSTICS, intent.action)
        assertTrue(intent.flags and android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
        notifications.removeFailSafeWarning()
        assertNull(shown(AppNotifications.FAIL_SAFE_ID))
    }

    @Test
    fun `the default clock can be used`() {
        AppNotifications(context).showNextAlarm(alarm(null))
        assertEquals("No title", shown(AppNotifications.NEXT_ALARM_ID)?.extras?.getString(android.app.Notification.EXTRA_TEXT))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AppPlannerEffectsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var workManager: WorkManager
    private lateinit var effects: AppPlannerEffects

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        workManager = WorkManager.getInstance(context)
        effects = AppPlannerEffects(PlanningWork(workManager), AppNotifications(context))
    }

    private fun exists(name: String) = workManager.getWorkInfosForUniqueWork(name).get().isNotEmpty()

    @Test
    fun `jobs go to WorkManager`() {
        effects.startPlanningJob(1)
        effects.cancelPlanningJob()
        effects.scheduleRetry(Duration.ofMinutes(1))
        assertTrue(exists(PlanningWork.PLAN_WORK))
        assertTrue(exists(PlanningWork.RETRY_WORK))
    }

    @Test
    fun `notifications go to the notification manager`() {
        val manager = shadowOf(context.getSystemService(NotificationManager::class.java))
        effects.showFailSafeWarning()
        assertEquals(1, manager.activeNotifications.size)
        effects.removeFailSafeWarning()
        effects.showNextAlarm(null)
        assertEquals(0, manager.activeNotifications.size)
    }

    @Test
    fun `the module provides what the screens need`() = runBlocking {
        val data = PlanningModule.dataComponents(context)
        val engine = PlanningModule.plannerEngine(context, data, PlanningModule.planningWork(context), AlarmEngine(data.alarms, data.scheduler(de.drehtuer.nextalarm.planning.AppAlarmIntents(context)), NoAlarmEffects, { NOW }, OpenLock))
        val alarmEngine = AlarmEngine(data.alarms, FakeScheduler(), NoAlarmEffects, { NOW }, OpenLock)
        val ports = PlanningModule.appPorts(context, data, engine, alarmEngine)
        assertEquals(alarmEngine, ports.alarmEngine)
        assertEquals(data.sounds, ports.phone.sounds)
        assertEquals(data.feeds, ports.news.feeds)
        assertNull(ports.news.player.playing())
        assertEquals(context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent(), ports.phone.fullScreenAllowed())
        assertEquals(PlannerState.Unconfigured, ports.planner.load().state)
        assertTrue(ports.alarms.all().isEmpty())
        assertEquals(Settings.DEFAULT, ports.settings.load())
    }
}
