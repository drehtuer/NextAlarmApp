package de.drehtuer.nextalarm.planning

import android.app.AlarmManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import androidx.test.core.app.ApplicationProvider
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import dagger.Lazy
import de.drehtuer.nextalarm.NextAlarmApplication
import de.drehtuer.nextalarm.data.DataComponents
import de.drehtuer.nextalarm.domain.AlarmAction
import de.drehtuer.nextalarm.domain.AlarmEffects
import de.drehtuer.nextalarm.domain.AlarmEngine
import de.drehtuer.nextalarm.domain.AlarmInstance
import de.drehtuer.nextalarm.domain.PlannerState
import de.drehtuer.nextalarm.domain.PlanningFailure
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

/** Runs on a plain application, so that each test builds its own parts. No real calendar is involved. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PlanningTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var workManager: WorkManager
    private lateinit var planning: PlanningWork
    private lateinit var data: DataComponents

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        workManager = WorkManager.getInstance(context)
        planning = PlanningWork(workManager) { Instant.parse("2026-11-06T19:00:00Z") }
        data = DataComponents(context)
    }

    private fun work(name: String): WorkInfo = workManager.getWorkInfosForUniqueWork(name).get().single()

    private fun engine() = PlanningModule.plannerEngine(context, data, planning, alarmEngine())

    private fun alarmEngine() = AlarmEngine(
        data.alarms,
        data.scheduler(AppAlarmIntents(context)),
        object : AlarmEffects {
            override fun perform(instance: AlarmInstance, action: AlarmAction) = Unit
            override fun showNextAlarm(alarm: AlarmInstance?) = Unit
        },
        Instant::now,
        MutexEngineLock(),
    )

    private fun factory() = NextAlarmWorkerFactory(Lazy { engine() }, Lazy { planning })

    private inline fun <reified W : ListenableWorker> worker(input: Data = Data.EMPTY): W =
        TestListenableWorkerBuilder<W>(context)
            .setWorkerFactory(factory())
            .setInputData(input)
            .build()

    @Test
    fun `a planning job runs the plan worker`() {
        planning.startPlanningJob(7)
        assertTrue(work(PlanningWork.PLAN_WORK).tags.contains(PlanWorker::class.java.name))
    }

    @Test
    fun `a newer planning job replaces the running one`() {
        planning.startPlanningJob(1)
        val first = work(PlanningWork.PLAN_WORK).id
        planning.startPlanningJob(2)
        val infos = workManager.getWorkInfosForUniqueWork(PlanningWork.PLAN_WORK).get()
        assertEquals(1, infos.size)
        assertNotEquals(first, infos.single().id)
    }

    @Test
    fun `cancelling stops the planning job`() {
        val waiting = OneTimeWorkRequestBuilder<PlanWorker>().setInitialDelay(Duration.ofHours(1)).build()
        workManager.enqueueUniqueWork(PlanningWork.PLAN_WORK, ExistingWorkPolicy.REPLACE, waiting)
        planning.cancelPlanningJob()
        assertEquals(WorkInfo.State.CANCELLED, work(PlanningWork.PLAN_WORK).state)
    }

    @Test
    fun `a retry waits for the given time`() {
        planning.scheduleRetry(Duration.ofMinutes(8))
        val info = work(PlanningWork.RETRY_WORK)
        assertEquals(WorkInfo.State.ENQUEUED, info.state)
        assertTrue(info.nextScheduleTimeMillis > System.currentTimeMillis())
    }

    @Test
    fun `a re-plan request is queued`() {
        planning.requestReplan()
        assertTrue(work(PlanningWork.REPLAN_WORK).tags.contains(ReplanWorker::class.java.name))
    }

    @Test
    fun `the calendar trigger watches the calendar provider`() {
        planning.armTriggers()
        val triggers = work(PlanningWork.CALENDAR_WORK).constraints.contentUriTriggers
        assertEquals(CalendarContract.CONTENT_URI, triggers.single().uri)
        assertTrue(triggers.single().isTriggeredForDescendants)
    }

    @Test
    fun `arming again gives a fresh calendar trigger and keeps the daily one`() {
        planning.armTriggers()
        val calendar = work(PlanningWork.CALENDAR_WORK).id
        val daily = work(PlanningWork.DAILY_WORK).id
        planning.armTriggers()
        assertNotEquals(calendar, work(PlanningWork.CALENDAR_WORK).id)
        assertEquals(daily, work(PlanningWork.DAILY_WORK).id)
    }

    @Test
    fun `the default clock can be used`() {
        PlanningWork(workManager).armTriggers()
        assertEquals(WorkInfo.State.ENQUEUED, work(PlanningWork.DAILY_WORK).state)
    }

    @Test
    fun `the plan worker arms the triggers and ends well, even if planning fails`() = runBlocking {
        val result = worker<PlanWorker>(Data.Builder().putLong(PlanWorker.KEY_GENERATION, 1).build()).doWork()
        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(WorkInfo.State.ENQUEUED, work(PlanningWork.CALENDAR_WORK).state)
    }

    @Test
    fun `the plan worker hands its generation to the planner`() = runBlocking {
        engine().setupCompleted()
        val generation = data.planner.load().generation
        worker<PlanWorker>(Data.Builder().putLong(PlanWorker.KEY_GENERATION, generation).build()).doWork()
        val record = data.planner.load()
        assertEquals(PlannerState.FailSafe, record.state)
        assertEquals(PlanningFailure.NO_CALENDAR, record.lastRun?.failure)
    }

    @Test
    fun `a plan worker without a generation counts as stale`() = runBlocking {
        engine().setupCompleted()
        worker<PlanWorker>().doWork()
        assertEquals(PlannerState.Planning, data.planner.load().state)
    }

    @Test
    fun `the re-plan worker asks the planner, and setup makes that count`() = runBlocking {
        assertEquals(ListenableWorker.Result.success(), worker<ReplanWorker>().doWork())
        assertEquals(PlannerState.Unconfigured, data.planner.load().state)

        engine().setupCompleted()
        assertEquals(1, data.planner.load().generation)
        assertEquals(ListenableWorker.Result.success(), worker<ReplanWorker>().doWork())
        assertEquals(2, data.planner.load().generation)
    }

    @Test
    fun `the factory leaves unknown workers to WorkManager`() {
        lateinit var params: WorkerParameters
        val capturing = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                factory().createWorker(appContext, workerClassName, workerParameters).also { params = workerParameters }
        }
        assertIs<ReplanWorker>(TestListenableWorkerBuilder<ReplanWorker>(context).setWorkerFactory(capturing).build())
        assertNull(factory().createWorker(context, "unknown.Worker", params))
    }

    @Test
    fun `the module provides the data and planning parts`() = runBlocking {
        assertEquals(PlannerState.Unconfigured, PlanningModule.dataComponents(context).planner.load().state)
        PlanningModule.planningWork(context).requestReplan()
        assertTrue(work(PlanningWork.REPLAN_WORK).tags.contains(ReplanWorker::class.java.name))
    }

    @Test
    fun `the lock runs its block and hands back the result`() = runBlocking {
        assertEquals(3, MutexEngineLock().withLock { 3 })
    }

    @Test
    fun `each alarm has its own intents`() {
        val intents = AppAlarmIntents(context)
        assertEquals(intents.fire(1), intents.fire(1))
        assertNotEquals(intents.fire(1), intents.fire(2))
        assertEquals(intents.show(1), intents.show(1))
        assertNotEquals(intents.show(1), intents.show(2))
    }

    @Test
    fun `an alarm can be registered and cancelled with these intents`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val scheduler = data.scheduler(AppAlarmIntents(context))
        scheduler.schedule(1, Instant.now().plusSeconds(60))
        assertEquals(1, shadowOf(alarmManager).scheduledAlarms.size)
        scheduler.cancel(1)
        assertTrue(shadowOf(alarmManager).scheduledAlarms.isEmpty())
    }

    @Test
    fun `the boot, clock and update broadcasts ask for a re-plan`() {
        ReplanReceiver.ACTIONS.forEach { action ->
            workManager.cancelAllWork().result.get()
            workManager.pruneWork().result.get()
            ReplanReceiver().onReceive(context, Intent(action))
            assertTrue(work(PlanningWork.REPLAN_WORK).tags.contains(ReplanWorker::class.java.name), action)
        }
    }

    @Test
    fun `any other broadcast asks for nothing`() {
        ReplanReceiver().onReceive(context, Intent("de.example.SOMETHING_ELSE"))
        ReplanReceiver().onReceive(context, Intent())
        assertTrue(workManager.getWorkInfosForUniqueWork(PlanningWork.REPLAN_WORK).get().isEmpty())
    }

}

/** Reads the merged manifest, which is what Android delivers the broadcasts by. */
@RunWith(RobolectricTestRunner::class)
class PlanningManifestTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `the receiver listens for every re-planning broadcast`() {
        assertEquals(4, ReplanReceiver.ACTIONS.size)
        ReplanReceiver.ACTIONS.forEach { action ->
            val receivers = context.packageManager.queryBroadcastReceivers(Intent(action).setPackage(context.packageName), 0)
            assertEquals(listOf(ReplanReceiver::class.java.name), receivers.map { it.activityInfo.name }, action)
        }
    }

    @Test
    fun `the application configures WorkManager with its factory`() {
        val application = context as NextAlarmApplication
        assertEquals(application.workerFactory, application.workManagerConfiguration.workerFactory)
    }
}
