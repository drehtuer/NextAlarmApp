package de.drehtuer.nextalarm.ringing

import dagger.Lazy
import de.drehtuer.nextalarm.domain.AlarmEngine
import de.drehtuer.nextalarm.domain.AlarmEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Reports from the ringer and the wake-up automation to [AlarmEngine]. They can come from an effect, which runs under the
 * engine's lock, so each call goes through a coroutine of its own in [scope] instead of waiting for that lock where it is
 * made. [engine] is lazy, as the engine is built from the parts that report to it.
 */
class EngineCalls(private val scope: CoroutineScope, private val engine: Lazy<AlarmEngine>) {
    fun ringTimedOut(instanceIds: List<Long>) {
        scope.launch { engine.get().ringTimedOut(instanceIds) }
    }

    fun handle(instanceId: Long, event: AlarmEvent) {
        scope.launch { engine.get().handle(instanceId, event) }
    }

    fun stopWakeUp() {
        scope.launch { engine.get().stopWakeUp() }
    }
}
