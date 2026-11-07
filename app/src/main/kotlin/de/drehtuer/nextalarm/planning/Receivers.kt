package de.drehtuer.nextalarm.planning

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.WorkManager
import dagger.hilt.android.AndroidEntryPoint
import de.drehtuer.nextalarm.ringing.AlarmBroadcasts
import javax.inject.Inject

/** Boot, clock changes and app updates each ask for a re-plan. See the manifest for the list. */
class ReplanReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        PlanningWork(WorkManager.getInstance(context)).requestReplan()
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
