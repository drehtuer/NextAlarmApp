package de.drehtuer.nextalarm.ringing

import de.drehtuer.nextalarm.domain.AlarmEngine
import de.drehtuer.nextalarm.domain.AlarmEvent
import de.drehtuer.nextalarm.domain.AlarmInstance
import de.drehtuer.nextalarm.domain.AlarmRepository
import de.drehtuer.nextalarm.domain.AlarmState
import java.time.Duration
import java.time.Instant
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
 * them yet after a crash. The screen ends when none is left, however they ended.
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

    val ringing: StateFlow<List<AlarmInstance>> = mutableRinging.asStateFlow()
    val now: StateFlow<Instant> = mutableNow.asStateFlow()
    val finished: StateFlow<Boolean> = mutableFinished.asStateFlow()

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

    private fun sendToRinging(event: AlarmEvent) {
        appScope.launch {
            engine.sendToRinging(event)
            reload()
        }
    }

    private suspend fun reload() {
        val current = alarms.all().filter { it.state == AlarmState.Ringing }
        mutableRinging.value = current
        mutableFinished.value = current.isEmpty()
    }
}
