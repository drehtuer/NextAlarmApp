package de.drehtuer.nextalarm.planning

import android.provider.CalendarContract
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import de.drehtuer.nextalarm.domain.PlannerEffects
import de.drehtuer.nextalarm.domain.delayUntilAfterMidnight
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** Starts planning jobs and re-planning triggers through WorkManager. See docs/architecture.md#re-planning-triggers. */
class PlanningWork(
    private val workManager: WorkManager,
    private val now: () -> Instant = Instant::now,
) : PlannerEffects {

    override fun cancelPlanningJob() {
        workManager.cancelUniqueWork(PLAN_WORK)
    }

    /** A unique job: a newer run replaces a running one, so concurrent triggers collapse into one. */
    override fun startPlanningJob(generation: Long) {
        val request = OneTimeWorkRequestBuilder<PlanWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setInputData(Data.Builder().putLong(PlanWorker.KEY_GENERATION, generation).build())
            .build()
        workManager.enqueueUniqueWork(PLAN_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    override fun scheduleRetry(delay: Duration) {
        val request = OneTimeWorkRequestBuilder<ReplanWorker>().setInitialDelay(delay).build()
        workManager.enqueueUniqueWork(RETRY_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    // The warning notification comes with step 5.5. Until then, the planner state records the failure.
    override fun showFailSafeWarning() = Unit

    override fun removeFailSafeWarning() = Unit

    /** Asks for a re-plan as soon as possible. */
    fun requestReplan() {
        val request = OneTimeWorkRequestBuilder<ReplanWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        workManager.enqueueUniqueWork(REPLAN_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    /**
     * Arms the calendar and daily triggers. A planning run calls this before it reads the calendar, so that a change
     * during the run is not missed. A content trigger fires only once, which is why every run arms it again.
     */
    fun armTriggers() {
        val calendarRequest = OneTimeWorkRequestBuilder<ReplanWorker>()
            .setConstraints(
                Constraints.Builder()
                    .addContentUriTrigger(CalendarContract.CONTENT_URI, triggerForDescendants = true)
                    .build(),
            )
            .build()
        workManager.enqueueUniqueWork(CALENDAR_WORK, ExistingWorkPolicy.REPLACE, calendarRequest)

        val dailyRequest = PeriodicWorkRequestBuilder<ReplanWorker>(Duration.ofDays(1))
            .setInitialDelay(delayUntilAfterMidnight(now(), ZoneId.systemDefault()))
            .build()
        workManager.enqueueUniquePeriodicWork(DAILY_WORK, ExistingPeriodicWorkPolicy.KEEP, dailyRequest)
    }

    companion object {
        const val PLAN_WORK = "plan"
        const val REPLAN_WORK = "replan"
        const val RETRY_WORK = "replan-retry"
        const val CALENDAR_WORK = "replan-calendar"
        const val DAILY_WORK = "replan-daily"
    }
}
