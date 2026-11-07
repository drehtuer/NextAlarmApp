package de.drehtuer.nextalarm.ringing

import de.drehtuer.nextalarm.domain.AlarmInstance
import de.drehtuer.nextalarm.domain.AlarmKey
import de.drehtuer.nextalarm.domain.AlarmRepository
import de.drehtuer.nextalarm.domain.AlarmScheduler
import de.drehtuer.nextalarm.domain.AlarmSnapshot
import de.drehtuer.nextalarm.domain.AlarmSound
import de.drehtuer.nextalarm.domain.AlarmState
import de.drehtuer.nextalarm.domain.AlarmVibration
import de.drehtuer.nextalarm.domain.RingTimers
import de.drehtuer.nextalarm.domain.RingWakeLock
import de.drehtuer.nextalarm.domain.Settings
import de.drehtuer.nextalarm.domain.SoundRef
import de.drehtuer.nextalarm.domain.snapshotFor
import java.time.Duration
import java.time.Instant

val AT: Instant = Instant.parse("2026-11-07T05:45:00Z")

fun alarm(
    id: Long = 1,
    state: AlarmState = AlarmState.Ringing,
    reason: String? = "Gym",
    triggerAt: Instant = AT,
    snapshot: AlarmSnapshot = Settings.DEFAULT.snapshotFor(reason),
    lateBy: Duration? = null,
) = AlarmInstance(id, AlarmKey.forEvent(id, AT), triggerAt, state, 0, false, snapshot, lateBy)

class FakeAlarms(vararg alarms: AlarmInstance) : AlarmRepository {
    val stored = alarms.associateByTo(linkedMapOf()) { it.id }

    /** Reading fails. */
    var failure: Exception? = null
    var failOnUpdate: Exception? = null

    override suspend fun all(): List<AlarmInstance> {
        failure?.let { throw it }
        return stored.values.toList()
    }

    override suspend fun find(id: Long): AlarmInstance? {
        failure?.let { throw it }
        return stored[id]
    }

    override suspend fun create(key: AlarmKey, triggerAt: Instant, snapshot: AlarmSnapshot): AlarmInstance {
        val instance = AlarmInstance(stored.size + 1L, key, triggerAt, AlarmState.Scheduled, 0, false, snapshot)
        stored[instance.id] = instance
        return instance
    }

    override suspend fun update(instance: AlarmInstance) {
        failOnUpdate?.let { throw it }
        stored[instance.id] = instance
    }

    override suspend fun delete(id: Long) {
        stored.remove(id)
    }
}

class FakeScheduler : AlarmScheduler {
    val scheduled = linkedMapOf<Long, Instant>()

    override fun schedule(instanceId: Long, at: Instant) {
        scheduled[instanceId] = at
    }

    override fun cancel(instanceId: Long) {
        scheduled.remove(instanceId)
    }
}

class FakeSound : AlarmSound {
    var playing: SoundRef? = null
    var lastResort = false

    override fun play(sound: SoundRef, gain: Float) {
        playing = sound
    }

    override fun playLastResort(gain: Float) {
        lastResort = true
    }

    override fun setGain(gain: Float) = Unit

    override fun stop() {
        playing = null
        lastResort = false
    }
}

class FakeVibration : AlarmVibration {
    override fun start() = Unit
    override fun stop() = Unit
}

class FakeTimers : RingTimers {
    override fun after(delay: Duration, block: () -> Unit): () -> Unit = {}
}

class FakeWakeLock : RingWakeLock {
    override fun hold(atMost: Duration) = Unit
    override fun release() = Unit
}

/** Waits for work that continues on background threads. */
fun awaitUntil(check: () -> Boolean) {
    repeat(WAIT_STEPS) {
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        if (check()) return
        Thread.sleep(POLL_MILLIS)
    }
    error("The condition did not come true in time")
}

private const val WAIT_STEPS = 1500
private const val POLL_MILLIS = 20L
