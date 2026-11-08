package de.drehtuer.nextalarm.planning

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.work.WorkManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import de.drehtuer.nextalarm.MainActivity
import de.drehtuer.nextalarm.data.AlarmIntents
import de.drehtuer.nextalarm.data.DataComponents
import de.drehtuer.nextalarm.domain.AlarmEngine
import de.drehtuer.nextalarm.domain.EngineLock
import de.drehtuer.nextalarm.domain.AudioFocusHold
import de.drehtuer.nextalarm.domain.CallWatch
import de.drehtuer.nextalarm.domain.NewsTestPlayer
import de.drehtuer.nextalarm.domain.PlannerEngine
import de.drehtuer.nextalarm.domain.SoundPreview
import de.drehtuer.nextalarm.ringing.AndroidPhoneCalls
import de.drehtuer.nextalarm.ringing.AndroidRingWakeLock
import de.drehtuer.nextalarm.ringing.HandlerRingTimers
import de.drehtuer.nextalarm.ringing.MediaPlayerPodcastOutput
import de.drehtuer.nextalarm.ringing.TextToSpeechOutput
import de.drehtuer.nextalarm.ringing.logAudioProblem
import de.drehtuer.nextalarm.ringing.mediaAudioFocus
import de.drehtuer.nextalarm.ui.AndroidPhoneAudio
import de.drehtuer.nextalarm.ui.AppPorts
import de.drehtuer.nextalarm.ui.ContentResolverLogDocuments
import de.drehtuer.nextalarm.ui.NewsPorts
import de.drehtuer.nextalarm.ui.PhonePorts
import de.drehtuer.nextalarm.ui.alarmNotificationsAllowed
import de.drehtuer.nextalarm.ui.canUseFullScreenIntent
import java.time.Instant
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The data of an alarm's intents. It keeps the alarms apart, so that each one can be cancelled on its own. */
fun alarmUri(instanceId: Long): Uri = Uri.parse("nextalarm://alarm/$instanceId")

/** Which alarm an intent is about, if any. */
fun alarmInstanceId(intent: Intent): Long? = intent.data?.lastPathSegment?.toLongOrNull()

class AppAlarmIntents(private val context: Context) : AlarmIntents {
    override fun fire(instanceId: Long): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, AlarmReceiver::class.java).setData(alarmUri(instanceId)),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    override fun show(instanceId: Long): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).setData(alarmUri(instanceId)),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

class MutexEngineLock : EngineLock {
    private val mutex = Mutex()

    override suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock { block() }
}

/**
 * The planning side, which only runs after the first unlock. The alarm path must not depend on anything here except
 * [dataComponents], which works from device-protected storage: see `AlarmModule`.
 */
@Module
@InstallIn(SingletonComponent::class)
object PlanningModule {
    @Provides
    @Singleton
    fun dataComponents(@ApplicationContext context: Context) = DataComponents(context)

    @Provides
    @Singleton
    fun planningWork(@ApplicationContext context: Context) = PlanningWork(WorkManager.getInstance(context))

    @Provides
    @Singleton
    fun plannerEngine(
        @ApplicationContext context: Context,
        data: DataComponents,
        planning: PlanningWork,
        alarmEngine: AlarmEngine,
    ): PlannerEngine = PlannerEngine(
        planner = data.planner,
        settings = data.settings,
        calendars = data.calendars,
        alarms = data.alarms,
        alarmEngine = alarmEngine,
        effects = AppPlannerEffects(planning, AppNotifications(context)),
        lock = MutexEngineLock(),
        now = Instant::now,
    )

    @Provides
    @Singleton
    fun appPorts(
        @ApplicationContext context: Context,
        data: DataComponents,
        engine: PlannerEngine,
        alarmEngine: AlarmEngine,
        preview: SoundPreview,
    ) = AppPorts(
        settings = data.settings,
        planner = data.planner,
        alarms = data.alarms,
        calendars = data.calendars,
        calendarSync = data.calendarSync,
        engine = engine,
        alarmEngine = alarmEngine,
        planningChanges = WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(PlanningWork.PLAN_WORK),
        phone = PhonePorts(
            audio = AndroidPhoneAudio(context),
            sounds = data.sounds,
            logs = ContentResolverLogDocuments(context.contentResolver),
            notificationsAllowed = { alarmNotificationsAllowed(context) },
            fullScreenAllowed = { canUseFullScreenIntent(context) },
        ),
        news = NewsPorts(
            feeds = data.feeds,
            player = NewsTestPlayer(
                speech = TextToSpeechOutput(context),
                podcast = MediaPlayerPodcastOutput(context),
                timers = HandlerRingTimers(),
                wakeLock = AndroidRingWakeLock(context, "NextAlarm:newstest"),
                focus = AudioFocusHold(mediaAudioFocus(context), ::logAudioProblem),
                calls = CallWatch(AndroidPhoneCalls(context), ::logAudioProblem),
                now = Instant::now,
            ),
        ),
        preview = preview,
        io = Dispatchers.IO,
    )
}
