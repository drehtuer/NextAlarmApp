package de.drehtuer.nextalarm.ringing

import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import de.drehtuer.nextalarm.data.DataComponents
import de.drehtuer.nextalarm.domain.AlarmEffects
import de.drehtuer.nextalarm.domain.AlarmEngine
import de.drehtuer.nextalarm.domain.AlarmEvent
import de.drehtuer.nextalarm.domain.AlarmRinger
import de.drehtuer.nextalarm.domain.FallbackSound
import de.drehtuer.nextalarm.domain.WakeUpAutomation
import de.drehtuer.nextalarm.planning.AppAlarmIntents
import de.drehtuer.nextalarm.planning.AppNotifications
import de.drehtuer.nextalarm.planning.MutexEngineLock
import java.time.Instant
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Work of the alarm path that outlives whatever started it. A failure of one job does not end the others. */
fun alarmScope(dispatcher: CoroutineDispatcher): CoroutineScope = CoroutineScope(
    SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, failure ->
        Log.e("NextAlarm", "Alarm work failed", failure)
    },
)

/**
 * The alarm path. All of it can run before the first unlock, when credential-encrypted storage is not available, so
 * nothing here may lead to WorkManager, whose database is stored there. `PlanningModule` builds on this, not the other
 * way round. See docs/architecture.md#direct-boot.
 */
@Module
@InstallIn(SingletonComponent::class)
object AlarmModule {
    @Provides
    fun dispatcher(): CoroutineDispatcher = Dispatchers.Default

    @Provides
    @Singleton
    fun scope(dispatcher: CoroutineDispatcher): CoroutineScope = alarmScope(dispatcher)

    @Provides
    @Singleton
    fun notifications(@ApplicationContext context: Context) = AlarmNotifications(context)

    @Provides
    @Singleton
    fun foreground(@ApplicationContext context: Context, notifications: AlarmNotifications) =
        AlarmForeground(notifications) { context.startForegroundService(Intent(context, AlarmService::class.java)) }

    @Provides
    @Singleton
    fun ringingChanges() = RingingChanges()

    @Provides
    @Singleton
    fun ringer(
        @ApplicationContext context: Context,
        data: DataComponents,
        foreground: AlarmForeground,
        changes: RingingChanges,
        scope: CoroutineScope,
        engine: Lazy<AlarmEngine>,
    ) = AlarmRinger(
        sound = FallbackSound(data.sounds::sources, MediaPlayerOutput(context)),
        vibration = AndroidVibration(context),
        timers = HandlerRingTimers(),
        wakeLock = AndroidRingWakeLock(context),
        onTimeout = { ids ->
            scope.launch { ids.forEach { engine.get().handle(it, AlarmEvent.RingTimeout) } }
        },
        onChanged = { alarms ->
            foreground.ringingChanged(alarms)
            changes.report(alarms)
        },
    )

    /** The seam of Phase 7. */
    @Provides
    @Singleton
    fun wakeUp(scope: CoroutineScope, engine: Lazy<AlarmEngine>): WakeUpAutomation = SkippedWakeUp(scope, engine)

    @Provides
    @Singleton
    fun effects(
        @ApplicationContext context: Context,
        ringer: AlarmRinger,
        notifications: AlarmNotifications,
        wakeUp: WakeUpAutomation,
    ): AlarmEffects = AppAlarmEffects(ringer, notifications, AppNotifications(context), wakeUp)

    @Provides
    @Singleton
    fun engine(@ApplicationContext context: Context, data: DataComponents, effects: AlarmEffects) = AlarmEngine(
        alarms = data.alarms,
        scheduler = data.scheduler(AppAlarmIntents(context)),
        effects = effects,
        now = Instant::now,
        lock = MutexEngineLock(),
    )

    @Provides
    @Singleton
    fun broadcasts(scope: CoroutineScope, engine: AlarmEngine) = AlarmBroadcasts(scope, engine)

    /** A new one per screen. */
    @Provides
    fun screenController(
        data: DataComponents,
        engine: AlarmEngine,
        changes: RingingChanges,
        foreground: AlarmForeground,
        scope: CoroutineScope,
    ) = AlarmScreenController(data.alarms, engine, changes, foreground, scope)
}
