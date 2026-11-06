package de.drehtuer.nextalarm.planning

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import dagger.Lazy
import de.drehtuer.nextalarm.domain.PlannerEngine
import javax.inject.Inject

/** One planning run. A failed run still ends successfully here, because the planner schedules its own retry. */
class PlanWorker(
    context: Context,
    params: WorkerParameters,
    private val engine: PlannerEngine,
    private val planning: PlanningWork,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        planning.armTriggers()
        engine.runPlanning(inputData.getLong(KEY_GENERATION, NO_GENERATION))
        return Result.success()
    }

    companion object {
        const val KEY_GENERATION = "generation"

        /** A job without a generation counts as stale, so its result is ignored. */
        private const val NO_GENERATION = -1L
    }
}

/** Sends `ReplanRequested` for every re-planning trigger. */
class ReplanWorker(
    context: Context,
    params: WorkerParameters,
    private val engine: PlannerEngine,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        engine.replanRequested()
        return Result.success()
    }
}

class NextAlarmWorkerFactory @Inject constructor(
    private val engine: Lazy<PlannerEngine>,
    private val planning: Lazy<PlanningWork>,
) : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? = when (workerClassName) {
        PlanWorker::class.java.name -> PlanWorker(appContext, workerParameters, engine.get(), planning.get())
        ReplanWorker::class.java.name -> ReplanWorker(appContext, workerParameters, engine.get())
        else -> null
    }
}
