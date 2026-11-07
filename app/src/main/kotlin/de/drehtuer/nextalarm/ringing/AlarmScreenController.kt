package de.drehtuer.nextalarm.ringing

import android.util.Log
import de.drehtuer.nextalarm.domain.AlarmEngine
import de.drehtuer.nextalarm.domain.AlarmEvent
import de.drehtuer.nextalarm.domain.AlarmInstance
import de.drehtuer.nextalarm.domain.AlarmRepository
import de.drehtuer.nextalarm.domain.AlarmState
import java.time.Duration
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Tells whoever is interested that the ringing alarms changed. */
class RingingChanges {
    private val mutable = MutableStateFlow<List<AlarmInstance>>(emptyList())
    val alarms: StateFlow<List<AlarmInstance>> = mutable.asStateFlow()

    fun report(alarms: List<AlarmInstance>) {
        mutable.value = alarms
    }
}

/**
 * The logic of the alarm screen. The stored state says which alarms ring, also while the recovery sweep has not got to
 * them yet after a crash, and so does the ringer, which knows without the database. The screen ends when none is left,
 * however they ended. It works without the database, because it has to be possible to stop an alarm then.
 */
class AlarmScreenController(
    private val alarms: AlarmRepository,
    private val engine: AlarmEngine,
    private val changes: RingingChanges,
    private val foreground: AlarmForeground,
    /** Snooze and Dismiss outlive the screen, which closes as soon as they have taken effect. */
    private val appScope: CoroutineScope,
    private val clock: () -> Instant = Instant::now,
    private val clockPeriod: Duration = Duration.ofSeconds(1),
) {
    private val mutableRinging = MutableStateFlow<List<AlarmInstance>>(emptyList())
    private val mutableNow = MutableStateFlow(clock())
    private val mutableFinished = MutableStateFlow(false)
    private val mutableWakeUpFollows = MutableStateFlow(false)

    val ringing: StateFlow<List<AlarmInstance>> = mutableRinging.asStateFlow()
    val now: StateFlow<Instant> = mutableNow.asStateFlow()
    val finished: StateFlow<Boolean> = mutableFinished.asStateFlow()

    /** True if the wake-up automation runs, so that the wake-up screen should take over when this one is [finished]. */
    val wakeUpFollows: StateFlow<Boolean> = mutableWakeUpFollows.asStateFlow()

    /** Keeps the state current for as long as [scope] lives. */
    fun start(scope: CoroutineScope) {
        scope.launch { changes.alarms.collect { reload() } }
        scope.launch {
            while (true) {
                mutableNow.value = clock()
                delay(clockPeriod.toMillis())
            }
        }
    }

    fun onResume() = foreground.sync()

    fun snooze() = sendToRinging(AlarmEvent.Snooze)

    fun dismiss() = sendToRinging(AlarmEvent.Dismiss)

    /** If the state could not be stored, it says that alarms ring that do not, so only the ringer counts then. */
    private fun sendToRinging(event: AlarmEvent) {
        appScope.launch {
            try {
                engine.sendToRinging(event)
                reload()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.e(TAG, "An alarm could not be ended the normal way", failure)
                publish(changes.alarms.value, wakeUpFollows = false)
            }
        }
    }

    private suspend fun reload() {
        val all = try {
            alarms.all()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.e(TAG, "The alarms could not be read", failure)
            emptyList()
        }
        val stored = all.filter { it.state == AlarmState.Ringing }
        publish(
            stored + changes.alarms.value.filter { live -> stored.none { it.id == live.id } },
            all.any { it.state == AlarmState.Announcing || it.state == AlarmState.NewsPlaying },
        )
    }

    private fun publish(current: List<AlarmInstance>, wakeUpFollows: Boolean) {
        mutableRinging.value = current
        mutableWakeUpFollows.value = wakeUpFollows
        mutableFinished.value = current.isEmpty()
    }

    private companion object {
        const val TAG = "NextAlarm"
    }
}
