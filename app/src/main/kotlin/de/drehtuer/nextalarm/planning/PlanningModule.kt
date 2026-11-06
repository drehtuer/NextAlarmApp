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
import de.drehtuer.nextalarm.domain.AlarmEffects
import de.drehtuer.nextalarm.domain.AlarmEngine
import de.drehtuer.nextalarm.domain.PlannerEngine
import de.drehtuer.nextalarm.domain.PlannerLock
import de.drehtuer.nextalarm.ui.AppPorts
import java.time.Instant
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The intent data keeps the alarms apart, so that each one can be cancelled on its own. */
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

    private fun alarmUri(instanceId: Long) = Uri.parse("nextalarm://alarm/$instanceId")
}

class MutexPlannerLock : PlannerLock {
    private val mutex = Mutex()

    override suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock { block() }
}

/** Nothing can ring yet, so these actions cannot happen. Step 6.2 replaces this with the real services. */
val NO_ALARM_EFFECTS = AlarmEffects { _, _ -> }

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
    ): PlannerEngine = PlannerEngine(
        planner = data.planner,
        settings = data.settings,
        calendars = data.calendars,
        alarms = data.alarms,
        alarmEngine = AlarmEngine(data.alarms, data.scheduler(AppAlarmIntents(context)), NO_ALARM_EFFECTS, Instant::now),
        effects = AppPlannerEffects(planning, AppNotifications(context)),
        lock = MutexPlannerLock(),
        now = Instant::now,
    )

    @Provides
    @Singleton
    fun appPorts(
        @ApplicationContext context: Context,
        data: DataComponents,
        engine: PlannerEngine,
    ) = AppPorts(
        settings = data.settings,
        planner = data.planner,
        alarms = data.alarms,
        calendars = data.calendars,
        engine = engine,
        planningChanges = WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(PlanningWork.PLAN_WORK),
    )
}
