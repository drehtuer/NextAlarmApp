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

/**
 * How long the volume ramp of this alarm takes. A ramp longer than the ring timeout would end the ringing before the alarm
 * is at full level (a 5 minute ramp with 1 minute of ringing peaks at 20%), and restart after a snooze, so it is cut to the
 * ring timeout. The stored settings stay as they are.
 */
fun effectiveRampSeconds(snapshot: AlarmSnapshot): Int =
    minOf(snapshot.volumeRampSeconds, snapshot.ringTimeoutMinutes * SECONDS_PER_MINUTE)

private const val SECONDS_PER_MINUTE = 60

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

fun interface RingTimers {
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
 *
 * The timeout normally ends the ringing through `AlarmEngine`. If that does not happen [HARD_STOP_MARGIN] later, the ringer
 * stops on its own, because a sound nobody can stop is worse than a stored state that is out of date.
 */
class AlarmRinger(
    private val sound: AlarmSound,
    private val vibration: AlarmVibration,
    private val timers: RingTimers,
    private val wakeLock: RingWakeLock,
    /** The ring timeout ran out for these alarms. Alarms that joined later have a new timeout. */
    private val onTimeout: (List<Long>) -> Unit,
    private val onChanged: (List<AlarmInstance>) -> Unit,
    /** The ringing went on past its timeout, and the ringer ended it on its own. */
    private val onHardStop: (List<AlarmInstance>) -> Unit = {},
) {
    private val alarms = linkedMapOf<Long, AlarmInstance>()
    private var vibrating = false
    private var cancelRamp: () -> Unit = {}
    private var cancelTimeout: () -> Unit = {}
    private var cancelHardStop: () -> Unit = {}

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
        val failure = if (alarms.isEmpty()) {
            stopAll()
        } else {
            updateVibration()
            null
        }
        onChanged(ringing())
        failure?.let { throw it }
    }

    private fun startSound(snapshot: AlarmSnapshot) {
        sound.play(snapshot.sound, soundGain(Duration.ZERO, effectiveRampSeconds(snapshot), snapshot.maxVolume))
        // Without a ramp, or with a silent maximum, the gain never changes.
        if (snapshot.volumeRampSeconds > 0 && snapshot.maxVolume != 0f) scheduleRamp(snapshot, Duration.ZERO, ++rampRun)
    }

    private fun scheduleRamp(snapshot: AlarmSnapshot, elapsed: Duration, run: Int) {
        cancelRamp = timers.after(RAMP_STEP) { rampStep(snapshot, elapsed.plus(RAMP_STEP), run) }
    }

    @Synchronized
    private fun rampStep(snapshot: AlarmSnapshot, elapsed: Duration, run: Int) {
        if (run != rampRun) return
        val ramp = effectiveRampSeconds(snapshot)
        sound.setGain(soundGain(elapsed, ramp, snapshot.maxVolume))
        if (elapsed.seconds < ramp) scheduleRamp(snapshot, elapsed, run)
    }

    private fun updateVibration() {
        val wanted = alarms.values.any { it.snapshot.vibrate }
        if (wanted == vibrating) return
        vibrating = wanted
        if (wanted) vibration.start() else vibration.stop()
    }

    private fun restartTimeout(timeout: Duration) {
        cancelTimeout()
        cancelHardStop()
        val run = ++timeoutRun
        cancelTimeout = timers.after(timeout) { timedOut(run) }
    }

    private fun timedOut(run: Int) {
        val expired = synchronized(this) {
            if (run != timeoutRun) return
            cancelHardStop = timers.after(HARD_STOP_MARGIN) { hardStop(run) }
            alarms.keys.toList()
        }
        onTimeout(expired)
    }

    @Synchronized
    private fun hardStop(run: Int) {
        if (run != timeoutRun) return
        val stopped = ringing()
        alarms.clear()
        val failure = stopAll()
        onChanged(emptyList())
        onHardStop(stopped)
        failure?.let { throw it }
    }

    /** One part that fails must not keep the others going. Reports the first failure. */
    private fun stopAll(): RuntimeException? {
        rampRun++
        timeoutRun++
        cancelRamp()
        cancelTimeout()
        cancelHardStop()
        return listOf(sound::stop, ::updateVibration, wakeLock::release).mapNotNull { step ->
            try {
                step()
                null
            } catch (failure: RuntimeException) {
                failure
            }
        }.firstOrNull()
    }

    private companion object {
        val RAMP_STEP: Duration = Duration.ofMillis(250)

        /** Far longer than storing a state takes, and within the margin of the wake lock, so that the CPU is awake for it. */
        val HARD_STOP_MARGIN: Duration = Duration.ofSeconds(30)

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

    /** It is over for this alarm, for whichever reason. Nothing happens if it was not in the automation. */
    fun leave(instanceId: Long)
}
