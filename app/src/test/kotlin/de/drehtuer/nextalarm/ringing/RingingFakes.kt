package de.drehtuer.nextalarm.ringing

import de.drehtuer.nextalarm.domain.AlarmInstance
import de.drehtuer.nextalarm.domain.AlarmKey
import de.drehtuer.nextalarm.domain.AlarmRepository
import de.drehtuer.nextalarm.domain.AlarmScheduler
import de.drehtuer.nextalarm.domain.AlarmSnapshot
import de.drehtuer.nextalarm.domain.AlarmSound
import de.drehtuer.nextalarm.domain.AlarmState
import de.drehtuer.nextalarm.domain.AlarmStream
import de.drehtuer.nextalarm.domain.AlarmVolumeKeeper
import de.drehtuer.nextalarm.domain.AudioFocus
import de.drehtuer.nextalarm.domain.AudioFocusHold
import de.drehtuer.nextalarm.domain.CallWatch
import de.drehtuer.nextalarm.domain.PhoneCalls
import de.drehtuer.nextalarm.domain.RingAudio
import de.drehtuer.nextalarm.domain.SavedAlarmVolume
import de.drehtuer.nextalarm.domain.AlarmVibration
import de.drehtuer.nextalarm.domain.RingTimers
import de.drehtuer.nextalarm.domain.RingWakeLock
import de.drehtuer.nextalarm.domain.Settings
import de.drehtuer.nextalarm.domain.SoundPreview
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
    var plays = 0
    var gain: Float? = null

    override fun play(sound: SoundRef, gain: Float) {
        playing = sound
        plays++
        this.gain = gain
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

class FakeAlarmStream(var level: Int = 3) : AlarmStream {
    val sets = mutableListOf<Int>()

    override fun current() = level
    override fun min() = 1
    override fun max() = 7

    override fun set(level: Int) {
        sets += level
        this.level = level
    }
}

class FakeSavedVolume(var level: Int? = null) : SavedAlarmVolume {
    override fun load() = level

    override fun save(level: Int) {
        this.level = level
    }

    override fun clear() {
        level = null
    }
}

object NoCalls : PhoneCalls {
    override fun active() = false
    override fun watch(onChange: (Boolean) -> Unit): () -> Unit = {}
}

object FakeNoFocus : AudioFocus {
    override fun request(onLostForGood: () -> Unit) = true
    override fun abandon() = Unit
}

/** Surroundings of the sound that nobody needs to look at. */
fun fakeRingAudio(stream: AlarmStream = FakeAlarmStream(), saved: SavedAlarmVolume = FakeSavedVolume()) = RingAudio(
    AlarmVolumeKeeper(stream, saved) { _, _ -> },
    AudioFocusHold(FakeNoFocus) { _, _ -> },
    CallWatch(NoCalls) { _, _ -> },
    { _, _ -> },
)

/** Calls that a test can start and end. */
class SwitchableCalls(var isActive: Boolean = false) : PhoneCalls {
    private val watching = mutableListOf<(Boolean) -> Unit>()

    override fun active() = isActive

    override fun watch(onChange: (Boolean) -> Unit): () -> Unit {
        watching += onChange
        return { watching -= onChange }
    }

    fun change(active: Boolean) {
        isActive = active
        watching.toList().forEach { it(active) }
    }
}

/** The preview of screen 5, with nothing of the phone. [sound] says what it plays. */
class PreviewParts(alarmRinging: () -> Boolean = { false }) {
    val sound = FakeSound()
    val calls = SwitchableCalls()
    val preview = SoundPreview(
        newSound = { sound },
        focus = AudioFocusHold(FakeNoFocus) { _, _ -> },
        calls = CallWatch(calls) { _, _ -> },
        timers = FakeTimers(),
        alarmRinging = alarmRinging,
        onProblem = { _, _ -> },
    )
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
