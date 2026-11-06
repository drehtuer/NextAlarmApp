package de.drehtuer.nextalarm.planning

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.WorkManager

/** Boot, clock changes and app updates each ask for a re-plan. See the manifest for the list. */
class ReplanReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        PlanningWork(WorkManager.getInstance(context)).requestReplan()
    }
}

/** Receives a fired alarm. It has nothing to ring yet: step 6.1 sends `Fire(lateBy)` from here. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Unit
}
