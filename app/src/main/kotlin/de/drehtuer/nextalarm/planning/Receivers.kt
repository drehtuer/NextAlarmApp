package de.drehtuer.nextalarm.planning

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.WorkManager
import dagger.hilt.android.AndroidEntryPoint
import de.drehtuer.nextalarm.ringing.AlarmBroadcasts
import javax.inject.Inject

/**
 * Boot, clock changes and app updates each ask for a re-plan. See the manifest for the list. Only the system sends
 * these, and the receiver is not exported, but anything else that reaches it is ignored all the same.
 */
class ReplanReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACTIONS) return
        PlanningWork(WorkManager.getInstance(context)).requestReplan()
    }

    companion object {
        /** The broadcasts the manifest registers this receiver for. */
        val ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
        )
    }
}

/**
 * Receives a fired alarm, which sends `Fire(lateBy)`. Alarms that are already registered with the system name this class,
 * so it keeps its name and package.
 */
@AndroidEntryPoint
class AlarmReceiver : BroadcastReceiver() {
    @Inject
    lateinit var broadcasts: AlarmBroadcasts

    override fun onReceive(context: Context, intent: Intent) {
        broadcasts.fired(intent, goAsync()::finish)
    }
}
