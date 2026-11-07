package de.drehtuer.nextalarm.ringing

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import de.drehtuer.nextalarm.domain.AlarmEngine
import de.drehtuer.nextalarm.domain.AlarmEvent
import de.drehtuer.nextalarm.planning.alarmInstanceId
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * What the broadcasts of the alarm path do. Each one reports through `finish`, which is what keeps the process and the
 * CPU awake until the work is done. All of this also runs before the first unlock.
 */
class AlarmBroadcasts(
    private val scope: CoroutineScope,
    private val engine: AlarmEngine,
    private val foreground: AlarmForeground,
) {
    /** An alarm's time has come. */
    fun fired(intent: Intent, finish: () -> Unit) = run(finish) {
        alarmInstanceId(intent)?.let { engine.fire(it) }
    }

    /** The user tapped an action of an alarm notification. */
    fun actionTapped(intent: Intent, finish: () -> Unit) = run(finish) {
        when (intent.action) {
            AlarmActionReceiver.ACTION_SNOOZE_ALL -> engine.sendToRinging(AlarmEvent.Snooze)
            AlarmActionReceiver.ACTION_DISMISS_ALL -> engine.sendToRinging(AlarmEvent.Dismiss)
            AlarmActionReceiver.ACTION_DISMISS -> alarmInstanceId(intent)?.let { engine.handle(it, AlarmEvent.Dismiss) }
            AlarmActionReceiver.ACTION_STOP_WAKE_UP -> engine.stopWakeUp()
        }
    }

    /** The recovery sweep, on `LOCKED_BOOT_COMPLETED` and at every process start. */
    fun sweep(finish: () -> Unit = {}) = run({
        foreground.sweepFinished()
        finish()
    }) { engine.recover() }

    private fun run(finish: () -> Unit, work: suspend () -> Unit) {
        scope.launch {
            try {
                work()
            } finally {
                finish()
            }
        }
    }
}

/** Actions of the alarm notifications. */
@AndroidEntryPoint
class AlarmActionReceiver : BroadcastReceiver() {
    @Inject
    lateinit var broadcasts: AlarmBroadcasts

    override fun onReceive(context: Context, intent: Intent) {
        broadcasts.actionTapped(intent, goAsync()::finish)
    }

    companion object {
        const val ACTION_SNOOZE_ALL = "de.drehtuer.nextalarm.SNOOZE_ALL"
        const val ACTION_DISMISS_ALL = "de.drehtuer.nextalarm.DISMISS_ALL"
        const val ACTION_DISMISS = "de.drehtuer.nextalarm.DISMISS"
        const val ACTION_STOP_WAKE_UP = "de.drehtuer.nextalarm.STOP_WAKE_UP"
    }
}

/** The phone booted and has not been unlocked yet. See docs/architecture.md#direct-boot. */
@AndroidEntryPoint
class LockedBootReceiver : BroadcastReceiver() {
    @Inject
    lateinit var broadcasts: AlarmBroadcasts

    override fun onReceive(context: Context, intent: Intent) {
        broadcasts.sweep(goAsync()::finish)
    }
}
