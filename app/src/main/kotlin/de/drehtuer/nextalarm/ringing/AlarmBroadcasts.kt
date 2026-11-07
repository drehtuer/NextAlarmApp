package de.drehtuer.nextalarm.ringing

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import de.drehtuer.nextalarm.domain.AlarmEngine
import de.drehtuer.nextalarm.domain.AlarmEvent
import de.drehtuer.nextalarm.domain.AlarmRinger
import de.drehtuer.nextalarm.domain.emergencyAlarm
import de.drehtuer.nextalarm.planning.alarmInstanceId
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What the broadcasts of the alarm path do. Each one reports through `finish`, which is what keeps the process and the
 * CPU awake until the work is done. All of this also runs before the first unlock.
 */
class AlarmBroadcasts(
    private val scope: CoroutineScope,
    private val engine: AlarmEngine,
    private val foreground: AlarmForeground,
    private val ringer: AlarmRinger,
    private val now: () -> Instant = Instant::now,
    private val fireLimit: Duration = Duration.ofSeconds(10),
) {
    /** An alarm's time has come. Whatever goes wrong on the way, something rings. */
    fun fired(intent: Intent, finish: () -> Unit) = run(finish) {
        alarmInstanceId(intent)?.let { fire(it) }
    }

    /** A database that hangs is as bad as one that fails, and the alarm is due now. */
    private suspend fun fire(id: Long) {
        try {
            val done = withTimeoutOrNull(fireLimit.toMillis()) { engine.fire(id) }
            if (done == null) ringAnyway(id, IllegalStateException("Firing took longer than $fireLimit"))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            ringAnyway(id, failure)
        }
    }

    /** If the failure came after the alarm joined the ringing, as it can, then nothing more is needed. */
    private fun ringAnyway(id: Long, failure: Exception) {
        Log.e(TAG, "Alarm $id could not be fired the normal way", failure)
        if (ringer.ringing().none { it.id == id }) ringer.join(emergencyAlarm(id, now()))
    }

    /** The user tapped an action of an alarm notification. */
    fun actionTapped(intent: Intent, finish: () -> Unit) = run(finish) {
        when (intent.action) {
            AlarmActionReceiver.ACTION_SNOOZE_ALL -> engine.sendToRinging(AlarmEvent.Snooze)
            AlarmActionReceiver.ACTION_DISMISS_ALL -> engine.sendToRinging(AlarmEvent.Dismiss)
            AlarmActionReceiver.ACTION_DISMISS -> alarmInstanceId(intent)?.let { engine.handle(it, AlarmEvent.Dismiss) }
            AlarmActionReceiver.ACTION_STOP_WAKE_UP -> engine.stopWakeUp()
            // Syncing shows the notifications of whatever still rings, and does nothing if nothing does.
            AlarmActionReceiver.ACTION_RINGING_SWIPED -> foreground.sync()
        }
    }

    /**
     * The recovery sweep, on `LOCKED_BOOT_COMPLETED` and at every process start. The volume comes before the service can
     * stop, as Android ignores a change of it without one.
     */
    fun sweep(finish: () -> Unit = {}) = run({
        ringer.restoreVolumeIfIdle()
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

    private companion object {
        const val TAG = "NextAlarm"
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
        const val ACTION_RINGING_SWIPED = "de.drehtuer.nextalarm.RINGING_SWIPED"
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
