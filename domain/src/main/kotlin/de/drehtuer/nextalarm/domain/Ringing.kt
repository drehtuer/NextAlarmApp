package de.drehtuer.nextalarm.domain

import java.time.Duration

/**
 * The gain of the alarm sound in the player itself, between 0 and 1. The system's stream volume is never changed: the
 * sound can only be quieter than the phone's alarm volume, and a [maxVolume] of 0 is silent.
 */
fun soundGain(elapsed: Duration, rampSeconds: Int, maxVolume: Float?): Float {
    val top = maxVolume ?: 1f
    if (rampSeconds <= 0) return top
    val progress = elapsed.toMillis().toFloat() / Duration.ofSeconds(rampSeconds.toLong()).toMillis()
    return top * progress.coerceIn(0f, 1f)
}

/** Plays one alarm sound at a time, in a loop. */
interface AlarmSound {
    fun play(sound: SoundRef, gain: Float)
    fun setGain(gain: Float)
    fun stop()
}

interface AlarmVibration {
    fun start()
    fun stop()
}

interface RingTimers {
    /** Runs [block] after [delay], also if the screen is off. The result cancels it. */
    fun after(delay: Duration, block: () -> Unit): () -> Unit
}

/** Keeps the CPU awake while alarms ring, so that the timers run. */
interface RingWakeLock {
    /** Holds the lock until [release], or for [atMost] if that never comes. Holding it again extends it. */
    fun hold(atMost: Duration)
    fun release()
}

/** Plays one source at a time, in a loop, at the player's own gain. */
interface SoundOutput<S> {
    /**
     * Starts [source], at [gain] from the first moment on. The result is false if it cannot be played, and
     * [onFailure] is called if it stops working later.
     */
    fun start(source: S, gain: Float, onFailure: () -> Unit): Boolean
    fun setGain(gain: Float)
    fun stop()
}

/** Plays the first of the sources that works, and moves on to the next one if it stops working. */
class FallbackSound<S>(
    private val sources: (SoundRef) -> List<S>,
    private val output: SoundOutput<S>,
) : AlarmSound {
    private var remaining: Iterator<S> = emptyList<S>().iterator()
    private var gain = 0f

    /** Which attempt is the current one, because a failure can be reported after it has been given up. */
    private var attempt = 0

    @Synchronized
    override fun play(sound: SoundRef, gain: Float) {
        output.stop()
        this.gain = gain
        remaining = sources(sound).iterator()
        playNext()
    }

    @Synchronized
    override fun setGain(gain: Float) {
        this.gain = gain
        output.setGain(gain)
    }

    @Synchronized
    override fun stop() {
        attempt++
        remaining = emptyList<S>().iterator()
        output.stop()
    }

    private fun playNext() {
        while (remaining.hasNext()) {
            val current = ++attempt
            if (output.start(remaining.next(), gain) { failed(current) }) return
        }
    }

    @Synchronized
    private fun failed(which: Int) {
        if (which != attempt) return
        output.stop()
        playNext()
    }
}

/**
 * What all ringing alarms share while they ring: one sound, one vibration and one ring timeout. An alarm that fires
 * while another one rings joins it. See docs/architecture.md#ringing.
 *
 * Alarm snapshots can differ if the settings changed between two alarms, which is settled as follows: the sound and its
 * volume stay those of the alarm that started it, the phone vibrates while any ringing alarm asks for it, and the
 * timeout is the longest one, so that no alarm rings for less time than it should.
 */
class AlarmRinger(
    private val sound: AlarmSound,
    private val vibration: AlarmVibration,
    private val timers: RingTimers,
    private val wakeLock: RingWakeLock,
    /** The ring timeout ran out for these alarms. Alarms that joined later have a new timeout. */
    private val onTimeout: (List<Long>) -> Unit,
    private val onChanged: (List<AlarmInstance>) -> Unit,
) {
    private val alarms = linkedMapOf<Long, AlarmInstance>()
    private var vibrating = false
    private var cancelRamp: () -> Unit = {}
    private var cancelTimeout: () -> Unit = {}

    /** Told apart from each other, because a timer can have fired just before it was cancelled. */
    private var rampRun = 0
    private var timeoutRun = 0

    @Synchronized
    fun ringing(): List<AlarmInstance> = alarms.values.toList()

    /** Also for an alarm that is already in, which restarts the timeout like any other joining alarm. */
    @Synchronized
    fun join(alarm: AlarmInstance) {
        val first = alarms.isEmpty()
        alarms[alarm.id] = alarm
        val timeout = Duration.ofMinutes(alarms.values.map { it.snapshot.ringTimeoutMinutes }.max().toLong())
        wakeLock.hold(timeout.plus(WAKE_LOCK_MARGIN))
        if (first) startSound(alarm.snapshot)
        updateVibration()
        restartTimeout(timeout)
        onChanged(ringing())
    }

    /** An alarm that does not ring has nothing to leave, which is the case for a dismissed snoozed alarm. */
    @Synchronized
    fun leave(instanceId: Long) {
        alarms.remove(instanceId) ?: return
        if (alarms.isEmpty()) stopAll() else updateVibration()
        onChanged(ringing())
    }

    private fun startSound(snapshot: AlarmSnapshot) {
        sound.play(snapshot.sound, soundGain(Duration.ZERO, snapshot.volumeRampSeconds, snapshot.maxVolume))
        // Without a ramp, or with a silent maximum, the gain never changes.
        if (snapshot.volumeRampSeconds > 0 && snapshot.maxVolume != 0f) scheduleRamp(snapshot, Duration.ZERO, ++rampRun)
    }

    private fun scheduleRamp(snapshot: AlarmSnapshot, elapsed: Duration, run: Int) {
        cancelRamp = timers.after(RAMP_STEP) { rampStep(snapshot, elapsed.plus(RAMP_STEP), run) }
    }

    @Synchronized
    private fun rampStep(snapshot: AlarmSnapshot, elapsed: Duration, run: Int) {
        if (run != rampRun) return
        sound.setGain(soundGain(elapsed, snapshot.volumeRampSeconds, snapshot.maxVolume))
        if (elapsed.seconds < snapshot.volumeRampSeconds) scheduleRamp(snapshot, elapsed, run)
    }

    private fun updateVibration() {
        val wanted = alarms.values.any { it.snapshot.vibrate }
        if (wanted == vibrating) return
        vibrating = wanted
        if (wanted) vibration.start() else vibration.stop()
    }

    private fun restartTimeout(timeout: Duration) {
        cancelTimeout()
        val run = ++timeoutRun
        cancelTimeout = timers.after(timeout) { timedOut(run) }
    }

    private fun timedOut(run: Int) {
        val expired = synchronized(this) { if (run == timeoutRun) alarms.keys.toList() else emptyList() }
        if (expired.isNotEmpty()) onTimeout(expired)
    }

    private fun stopAll() {
        rampRun++
        timeoutRun++
        cancelRamp()
        cancelTimeout()
        sound.stop()
        updateVibration()
        wakeLock.release()
    }

    private companion object {
        val RAMP_STEP: Duration = Duration.ofMillis(250)

        /** Long enough for the timeout to be handled, and short enough not to matter if something goes wrong. */
        val WAKE_LOCK_MARGIN: Duration = Duration.ofMinutes(1)
    }
}

/**
 * The wake-up automation that starts when an alarm is dismissed. It must report back through `AlarmEngine`
 * (`AnnouncementDone`, `NewsDone`), and not from inside these calls: they run while the engine holds its lock.
 */
interface WakeUpAutomation {
    fun startAnnouncement(alarm: AlarmInstance)
    fun startNews(alarm: AlarmInstance)
}
