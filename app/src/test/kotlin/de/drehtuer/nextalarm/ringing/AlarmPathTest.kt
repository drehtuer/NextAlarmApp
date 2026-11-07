package de.drehtuer.nextalarm.ringing

import android.app.AlarmManager
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Looper
import android.speech.tts.TextToSpeech
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import androidx.work.impl.WorkManagerImpl
import dagger.Lazy
import de.drehtuer.nextalarm.NextAlarmApplication
import de.drehtuer.nextalarm.data.DataComponents
import de.drehtuer.nextalarm.domain.AlarmEngine
import de.drehtuer.nextalarm.domain.AlarmEvent
import de.drehtuer.nextalarm.domain.AlarmKey
import de.drehtuer.nextalarm.domain.AlarmRinger
import de.drehtuer.nextalarm.domain.AlarmSnapshot
import de.drehtuer.nextalarm.domain.AlarmState
import de.drehtuer.nextalarm.domain.NewsSource
import de.drehtuer.nextalarm.domain.NewsType
import de.drehtuer.nextalarm.domain.PlanEntry
import de.drehtuer.nextalarm.domain.Settings
import de.drehtuer.nextalarm.domain.WakeUpStatus
import de.drehtuer.nextalarm.domain.snapshotFor
import de.drehtuer.nextalarm.planning.AlarmReceiver
import de.drehtuer.nextalarm.planning.alarmUri
import java.io.File
import java.time.Duration
import java.time.Instant
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowTextToSpeech
import org.w3c.dom.Element

/**
 * The parts as `AlarmModule` puts them together, on a plain application without WorkManager and with a context that fails
 * on any access to credential-encrypted storage: as it is before the first unlock. No real alarm rings.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AlarmPathTest {
    private class LockedContext(private val real: Context) : ContextWrapper(real) {
        private fun locked(): Nothing = throw AssertionError("Credential-encrypted storage is not available")

        override fun getApplicationContext(): Context = this
        override fun createDeviceProtectedStorageContext(): Context = real.createDeviceProtectedStorageContext()
        override fun getFilesDir(): File = locked()
        override fun getCacheDir(): File = locked()
        override fun getDatabasePath(name: String): File = locked()
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = locked()
    }

    private val real = ApplicationProvider.getApplicationContext<Context>()
    private val context = LockedContext(real)
    private val manager = real.getSystemService(NotificationManager::class.java)
    private val data = AlarmPathParts.data(context)
    private val parts = AlarmPathParts(context, data)
    private val engine = parts.engine
    private val snapshot = Settings.DEFAULT.copy(speakReason = true).snapshotFor("Gym")

    @Before
    fun setUp() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        // WorkManager keeps itself in static fields, which outlive the tests that set it up. Without them any use
        // of it on the alarm path fails here, so every test in this class also shows that the path does without it.
        listOf("sDelegatedInstance", "sDefaultInstance").forEach { name ->
            WorkManagerImpl::class.java.getDeclaredField(name).apply { isAccessible = true }.set(null, null)
        }
    }

    private fun state(id: Long) = runBlocking { data.alarms.find(id)?.state }

    private fun created(at: Instant, snapshot: AlarmSnapshot = this.snapshot) = runBlocking {
        engine.create(PlanEntry(AlarmKey.forEvent(1, at), at, snapshot))
        data.alarms.all().last()
    }

    private fun idleFor(time: Duration) = shadowOf(Looper.getMainLooper()).idleFor(time)

    @Test
    fun `WorkManager has really not been set up here`() {
        assertFailsWith<IllegalStateException> { WorkManager.getInstance(real) }
    }

    @Test
    fun `an alarm rings, before the first unlock, and joins the service`(): Unit = runBlocking {
        val alarm = created(Instant.now())
        engine.fire(alarm.id)
        assertEquals(AlarmState.Ringing, state(alarm.id))
        assertEquals(listOf(alarm.id), parts.ringer.ringing().map { it.id })
        val started = shadowOf(real as Application).nextStartedService
        assertEquals(AlarmService::class.java.name, started.component?.className)
    }

    @Test
    fun `the service is started before any sound, and the ringer goes on when it is up`(): Unit = runBlocking {
        val alarm = created(Instant.now(), Settings.DEFAULT.copy(maxVolume = 0f).snapshotFor("Gym"))
        engine.fire(alarm.id)
        assertEquals(AlarmService::class.java.name, shadowOf(real as Application).nextStartedService.component?.className)
        val host = object : AlarmForegroundHost {
            var shown = 0
            override fun showForeground(id: Int, notification: Notification) {
                shown++
            }

            override fun finishRinging() = Unit
        }
        parts.foreground.attach(host)
        assertEquals(1, host.shown)
        engine.sendToRinging(AlarmEvent.Dismiss)
        assertTrue(parts.ringer.ringing().isEmpty())
    }

    @Test
    fun `an alarm is registered with the system`() {
        created(Instant.now().plusSeconds(600))
        assertNotNull(real.getSystemService(AlarmManager::class.java).nextAlarmClock)
    }

    @Test
    fun `an unanswered alarm snoozes on its own after the timeout, and gives up after the second`(): Unit = runBlocking {
        val alarm = created(Instant.now())
        engine.fire(alarm.id)
        idleFor(Duration.ofMinutes(3))
        awaitUntil { state(alarm.id) == AlarmState.Snoozed }
        awaitUntil { parts.ringer.ringing().isEmpty() }
        awaitUntil { shadowOf(manager).getNotification(AlarmNotifications.snoozedId(alarm.id)) != null }

        engine.fire(alarm.id)
        assertEquals(AlarmState.Ringing, state(alarm.id))
        assertNull(shadowOf(manager).getNotification(AlarmNotifications.snoozedId(alarm.id)))
        idleFor(Duration.ofMinutes(3))
        awaitUntil { state(alarm.id) == AlarmState.Unanswered }
        awaitUntil { shadowOf(manager).getNotification(AlarmNotifications.unansweredId(alarm.id)) != null }
    }

    @Test
    fun `a dismissed alarm reaches the end, without a wake-up automation to run`(): Unit = runBlocking {
        val alarm = created(Instant.now(), Settings.DEFAULT.snapshotFor("Gym"))
        engine.fire(alarm.id)
        engine.sendToRinging(AlarmEvent.Dismiss)
        assertTrue(parts.ringer.ringing().isEmpty())
        assertEquals(AlarmState.Done, state(alarm.id))
        assertEquals(WakeUpStatus.IDLE, parts.director.status())
    }

    private fun ttsEngine() = shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance())

    /** The automation runs under the engine's lock and reports through a coroutine, so waiting here shows it does not hang. */
    @Test
    fun `the title of a dismissed alarm is spoken, before the first unlock, and the service joins in`(): Unit = runBlocking {
        val alarm = created(Instant.now())
        engine.fire(alarm.id)
        engine.sendToRinging(AlarmEvent.Dismiss)
        assertEquals(AlarmState.Announcing, state(alarm.id))
        val application = shadowOf(real as Application)
        val started = generateSequence { application.nextStartedService }.map { it.component?.className }.toList()
        assertTrue(WakeUpService::class.java.name in started)
        ttsEngine().onInitListener.onInit(TextToSpeech.SUCCESS)
        assertEquals("Gym", ttsEngine().lastSpokenText)
        assertEquals(snapshot.reason, parts.wakeUpChanges.status.value.titles.single())
        ttsEngine().utteranceProgressListener.onDone("1")
        awaitUntil { state(alarm.id) == AlarmState.Done }
        awaitUntil { parts.director.status() == WakeUpStatus.IDLE }
        assertTrue(ttsEngine().isShutdown)
    }

    @Test
    fun `without text to speech the alarm still reaches the end`(): Unit = runBlocking {
        val alarm = created(Instant.now())
        engine.fire(alarm.id)
        engine.sendToRinging(AlarmEvent.Dismiss)
        ttsEngine().onInitListener.onInit(TextToSpeech.ERROR)
        awaitUntil { state(alarm.id) == AlarmState.Done }
    }

    @Test
    fun `news that cannot be fetched ends the automation, here without going near a network`(): Unit = runBlocking {
        val news = NewsSource(NewsType.RSS, "ftp://not.on.the.web/feed")
        val alarm = created(Instant.now(), Settings.DEFAULT.copy(news = news).snapshotFor("Gym"))
        engine.fire(alarm.id)
        engine.sendToRinging(AlarmEvent.Dismiss)
        awaitUntil { state(alarm.id) == AlarmState.Done }
        awaitUntil { parts.director.status() == WakeUpStatus.IDLE }
    }

    @Test
    fun `Stop in the media controls ends the automation`(): Unit = runBlocking {
        val alarm = created(Instant.now())
        engine.fire(alarm.id)
        engine.sendToRinging(AlarmEvent.Dismiss)
        parts.wakeUpMedia.callback.onStop()
        awaitUntil { state(alarm.id) == AlarmState.Done }
        assertTrue(ttsEngine().isShutdown)
    }

    @Test
    fun `a controller can be had per wake-up screen, and sees the automation`(): Unit = runBlocking {
        val alarm = created(Instant.now())
        engine.fire(alarm.id)
        engine.sendToRinging(AlarmEvent.Dismiss)
        val controller = parts.wakeUpController()
        val screen = alarmScope(Dispatchers.Default)
        controller.start(screen)
        awaitUntil { controller.status.value.titles == listOf("Gym") }
        controller.stop()
        awaitUntil { state(alarm.id) == AlarmState.Done }
        screen.cancel()
    }

    @Test
    fun `the sweep rings what should be ringing`(): Unit = runBlocking {
        val alarm = created(Instant.now().minusSeconds(120))
        engine.recover()
        assertEquals(AlarmState.Ringing, state(alarm.id))
        assertNotNull(shadowOf(manager).getNotification(AlarmNotifications.lateId(alarm.id)))
    }

    @Test
    fun `an alarm over the limit does not ring and is reported`(): Unit = runBlocking {
        val alarm = created(Instant.now().minus(Duration.ofMinutes(61)))
        engine.fire(alarm.id)
        assertEquals(AlarmState.Missed, state(alarm.id))
        assertTrue(parts.ringer.ringing().isEmpty())
        assertNotNull(shadowOf(manager).getNotification(AlarmNotifications.missedId(alarm.id)))
    }

    @Test
    fun `the next alarm follows the state of the alarms`(): Unit = runBlocking {
        val alarm = created(Instant.now().plusSeconds(600))
        engine.handle(alarm.id, AlarmEvent.Remove)
        assertNull(shadowOf(manager).getNotification(de.drehtuer.nextalarm.planning.AppNotifications.NEXT_ALARM_ID))
    }

    @Test
    fun `a controller can be had per screen, and sees the ringing alarms`(): Unit = runBlocking {
        val alarm = created(Instant.now())
        engine.fire(alarm.id)
        val controller = parts.controller()
        val scope = alarmScope(Dispatchers.Default)
        controller.start(scope)
        awaitUntil { controller.ringing.value.map { it.id } == listOf(alarm.id) }
    }

    @Test
    fun `the module uses a background thread`() {
        assertEquals(Dispatchers.Default, AlarmModule.dispatcher())
    }
}

/** Puts the alarm path together like Hilt does, through the same provider functions. */
private class AlarmPathParts(context: Context, data: DataComponents) {
    private val scope = AlarmModule.scope(AlarmModule.dispatcher())
    private val notifications = AlarmModule.notifications(context)
    val foreground: AlarmForeground = AlarmModule.foreground(context, notifications, Lazy { ringer })
    private val changes = AlarmModule.ringingChanges()
    private val lazyEngine: Lazy<AlarmEngine> = Lazy { engine }
    val ringer: AlarmRinger =
        AlarmModule.ringer(context, data, foreground, changes, notifications, AlarmModule.soundWorker(scope), scope, lazyEngine)
    private val wakeUpNotifications = AlarmModule.wakeUpNotifications(context)
    val wakeUpMedia = AlarmModule.wakeUpMedia(context, scope, lazyEngine)
    private val wakeUpForeground = AlarmModule.wakeUpForeground(context, wakeUpNotifications, wakeUpMedia)
    val wakeUpChanges = AlarmModule.wakeUpChanges()
    val director = AlarmModule.wakeUpDirector(context, data, wakeUpForeground, wakeUpChanges, scope, lazyEngine)
    private val effects = AlarmModule.effects(context, ringer, notifications, AlarmModule.wakeUp(director))
    val engine: AlarmEngine = AlarmModule.engine(context, data, effects)
    private val controllerData = data

    fun controller() = AlarmModule.screenController(controllerData, engine, changes, foreground, scope)

    fun wakeUpController() =
        AlarmModule.wakeUpScreenController(controllerData, engine, director, wakeUpChanges, wakeUpForeground, scope)

    companion object {
        fun data(context: Context) = DataComponents(context)
    }
}

/** On the real application with its real Hilt graph. */
@RunWith(RobolectricTestRunner::class)
class AlarmServiceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun service() = Robolectric.buildService(AlarmService::class.java).create().get()

    @Test
    fun `it is sticky, and cannot be bound`() {
        val service = service()
        assertEquals(Service.START_STICKY, service.onStartCommand(null, 0, 1))
        assertNull(service.onBind(Intent()))
    }

    @Test
    fun `with nothing ringing it goes into the foreground as demanded, and stops`() {
        val service = service()
        service.onStartCommand(Intent(context, AlarmService::class.java), 0, 1)
        assertEquals(AlarmNotifications.PLACEHOLDER_ID, shadowOf(service).lastForegroundNotificationId)
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertTrue(shadowOf(service).isForegroundStopped)
    }

    @Test
    fun `with an alarm ringing it shows that alarm in the foreground, and stays`() {
        val service = service()
        service.foreground.ringingChanged(listOf(alarm(id = 5, reason = "Flight")))
        service.onStartCommand(null, 0, 1)
        assertEquals(AlarmNotifications.ringingId(5), shadowOf(service).lastForegroundNotificationId)
        val shown = shadowOf(service).lastForegroundNotification
        assertEquals("Flight", shown.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(false, shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun `it stops when the last alarm has left`() {
        val service = service()
        service.foreground.ringingChanged(listOf(alarm(id = 5)))
        service.onStartCommand(null, 0, 1)
        service.foreground.ringingChanged(emptyList())
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun `once it is gone, a new alarm starts it again`() {
        val service = service()
        service.foreground.ringingChanged(listOf(alarm(id = 5)))
        service.onStartCommand(null, 0, 1)
        val application = shadowOf(context as Application)
        while (application.nextStartedService != null) {
            // Only what happens after this point counts.
        }
        service.onDestroy()
        service.foreground.sync()
        assertEquals(AlarmService::class.java.name, application.nextStartedService?.component?.className)
    }
}

/** What Android goes by comes from the manifest, so that is what is checked. */
@RunWith(RobolectricTestRunner::class)
class AlarmManifestTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manifest = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }
        .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml")).documentElement

    private fun component(tag: String, name: String): Element {
        val nodes = manifest.getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }.single { it.getAttribute("android:name") == name }
    }

    @Test
    fun `everything on the alarm path works before the first unlock`() {
        listOf(
            "activity" to ".ringing.AlarmActivity",
            "service" to ".ringing.AlarmService",
            "activity" to ".ringing.WakeUpActivity",
            "service" to ".ringing.WakeUpService",
            "receiver" to ".planning.AlarmReceiver",
            "receiver" to ".ringing.AlarmActionReceiver",
            "receiver" to ".ringing.LockedBootReceiver",
        ).forEach { (tag, name) ->
            assertEquals("true", component(tag, name).getAttribute("android:directBootAware"), name)
            assertEquals("false", component(tag, name).getAttribute("android:exported"), name)
        }
    }

    @Test
    fun `the alarm and wake-up screens show over the lock screen, on black`() {
        listOf(".ringing.AlarmActivity", ".ringing.WakeUpActivity").forEach { name ->
            val activity = component("activity", name)
            assertEquals("true", activity.getAttribute("android:showWhenLocked"), name)
            assertEquals("true", activity.getAttribute("android:turnScreenOn"), name)
            assertEquals("@style/Theme.NextAlarm.Night", activity.getAttribute("android:theme"), name)
        }
    }

    @Test
    fun `the wake-up service is one of media playback`() {
        assertEquals("mediaPlayback", component("service", ".ringing.WakeUpService").getAttribute("android:foregroundServiceType"))
    }

    @Test
    fun `the text-to-speech engines are visible to the app`() {
        val queries = manifest.getElementsByTagName("queries").item(0) as Element
        val action = queries.getElementsByTagName("action").item(0) as Element
        assertEquals(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE, action.getAttribute("android:name"))
    }

    @Test
    fun `feeds can be plain http, which many podcast addresses still are`() {
        val application = manifest.getElementsByTagName("application").item(0) as Element
        assertEquals("true", application.getAttribute("android:usesCleartextTraffic"))
    }

    @Test
    fun `the service is of the type alarm clocks may use`() {
        assertEquals("systemExempted", component("service", ".ringing.AlarmService").getAttribute("android:foregroundServiceType"))
    }

    @Test
    fun `everything needed to ring is asked for`() {
        val requested = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().toSet()
        listOf(
            "FOREGROUND_SERVICE",
            "FOREGROUND_SERVICE_MEDIA_PLAYBACK",
            "FOREGROUND_SERVICE_SYSTEM_EXEMPTED",
            "INTERNET",
            "RECEIVE_BOOT_COMPLETED",
            "USE_EXACT_ALARM",
            "USE_FULL_SCREEN_INTENT",
            "VIBRATE",
            "WAKE_LOCK",
        ).forEach { assertTrue("android.permission.$it" in requested, it) }
    }

    @Test
    fun `the locked boot broadcast reaches its receiver`() {
        val intent = Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED).setPackage(context.packageName)
        val receivers = context.packageManager.queryBroadcastReceivers(intent, 0)
        assertEquals(listOf(LockedBootReceiver::class.java.name), receivers.map { it.activityInfo.name })
    }

    @Test
    fun `alarms that are already registered still find their receiver`() {
        assertEquals("de.drehtuer.nextalarm.planning.AlarmReceiver", AlarmReceiver::class.java.name)
        assertEquals(alarmUri(5).toString(), "nextalarm://alarm/5")
    }
}

/** On the real application with its real Hilt graph. */
@RunWith(RobolectricTestRunner::class)
class ProcessStartTest {
    @Test
    fun `every process start runs the recovery sweep`(): Unit = runBlocking {
        val application = ApplicationProvider.getApplicationContext<NextAlarmApplication>()
        val data = DataComponents(application)
        val time = Instant.now()
        val created = data.alarms.create(AlarmKey.forEvent(1, time), time, Settings.DEFAULT.snapshotFor("Gym"))
        data.alarms.update(created.copy(state = AlarmState.Announcing))
        application.onCreate()
        awaitUntil { runBlocking { data.alarms.find(created.id)?.state } == AlarmState.Done }
    }
}
