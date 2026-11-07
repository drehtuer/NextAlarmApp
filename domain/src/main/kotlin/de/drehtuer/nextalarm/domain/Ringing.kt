package de.drehtuer.nextalarm.domain

import java.time.Duration
import java.time.Instant
import kotlin.math.roundToInt

/**
 * The gain of the alarm sound in the player itself, between 0 and 1. How loud that is comes from the phone's alarm stream,
 * which [AlarmVolumeKeeper] sets if the alarm has a [maxVolume]. So the gain only says whether the sound is on and how far
 * the ramp has got. A [maxVolume] of 0 is silent, which is the one thing it does here.
 */
fun soundGain(elapsed: Duration, rampSeconds: Int, maxVolume: Float?): Float {
    val top = if (maxVolume == 0f) 0f else 1f
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

const val EMERGENCY_ALARM_TITLE = "Alarm (its details couldn't be read)"

/**
 * What rings if a registered alarm has fired and its stored alarm could not be handled, which needs nothing from the
 * database. It rings the bundled sound at full level, and vibrates. It is never stored ([AlarmEngine] goes by its key). It
 * counts as snoozed once on its own, so that its timeout ends it: an alarm that cannot be read cannot keep count.
 */
fun emergencyAlarm(instanceId: Long, now: Instant): AlarmInstance = AlarmInstance(
    id = instanceId,
    key = AlarmKey.forEmergency(instanceId),
    triggerAt = now,
    state = AlarmState.Ringing,
    snoozeCount = 0,
    autoSnoozed = true,
    snapshot = AlarmSnapshot(
        reason = EMERGENCY_ALARM_TITLE,
        sound = SoundRef.SystemDefault,
        vibrate = true,
        volumeRampSeconds = 0,
        maxVolume = null,
        ringTimeoutMinutes = Settings.DEFAULT_RING_TIMEOUT_MINUTES,
        snoozeMinutes = Settings.DEFAULT_SNOOZE_MINUTES,
        speakReason = false,
        news = null,
    ),
)

/**
 * The step of the alarm stream for [maxVolume], which is above 0. A value that rounds down to nothing still gets the lowest
 * step that can be heard: a value of 0 never comes here, as it means silent, and must leave the stream alone.
 */
fun streamLevel(maxVolume: Float, min: Int, max: Int): Int {
    val lowest = maxOf(min, 1)
    return (maxVolume * max).roundToInt().coerceIn(lowest, maxOf(lowest, max))
}

/** Something went wrong with the sound's surroundings. The alarm goes on, so this is only to be logged. */
typealias AudioProblem = (String, RuntimeException?) -> Unit

/** The phone's alarm stream, in its own steps. Android can ignore [set] without saying so: see [AlarmVolumeKeeper]. */
interface AlarmStream {
    fun current(): Int
    fun min(): Int
    fun max(): Int
    fun set(level: Int)
}

/** The user's alarm volume while an alarm has changed it. It has to survive a crash and a reboot, before the first unlock too. */
interface SavedAlarmVolume {
    fun load(): Int?

    /** Throws if it could not be stored. */
    fun save(level: Int)
    fun clear()
}

/**
 * Sets the phone's alarm stream for an alarm with a maximum volume, and puts it back afterwards. See
 * docs/architecture.md#ringing. Nothing here throws: an alarm that rings at the wrong volume beats one that doesn't ring.
 *
 * The user's volume is stored before the stream changes, and only if none is stored yet, so a later alarm, or one rung
 * again after a crash, doesn't take the raised level for the user's own. If storing fails, it is still known in memory, and
 * the alarm goes on with the volume it asked for: being woken comes before the volume after a crash.
 *
 * Android 17 ignores a change of the volume without an error when the app has no foreground service
 * (docs/architecture.md#background-audio-on-android-17), which is the case in the recovery sweep after a reboot. So [restore]
 * looks at whether it had any effect, and if not keeps the stored volume for the next try.
 */
class AlarmVolumeKeeper(
    private val stream: AlarmStream,
    private val saved: SavedAlarmVolume,
    private val onProblem: AudioProblem,
) {
    private var inMemory: Int? = null

    /** Empty and 0 leave the stream alone: the first means the phone's own volume, the second a silent alarm. */
    fun raise(maxVolume: Float?) {
        if (maxVolume == null || maxVolume == 0f) return
        attempt("The alarm volume could not be set") {
            val level = streamLevel(maxVolume, stream.min(), stream.max())
            rememberOnce()
            stream.set(level)
            if (stream.current() != level) onProblem("Android did not set the alarm volume to $level", null)
        }
    }

    /** Does nothing if no alarm changed the stream. */
    fun restore() {
        attempt("The alarm volume could not be put back") {
            val level = previous() ?: return
            val before = stream.current()
            stream.set(level)
            if (before != level && stream.current() == before) {
                onProblem("Android did not put the alarm volume back to $level: this is tried again later", null)
                return
            }
            inMemory = null
            saved.clear()
        }
    }

    private fun rememberOnce() {
        if (previous() != null) return
        val level = stream.current()
        inMemory = level
        attempt("The alarm volume could not be stored") { saved.save(level) }
    }

    private fun previous(): Int? {
        inMemory?.let { return it }
        var stored: Int? = null
        attempt("The stored alarm volume could not be read") { stored = saved.load() }
        return stored
    }

    private inline fun attempt(problem: String, step: () -> Unit) {
        try {
            step()
        } catch (failure: RuntimeException) {
            onProblem(problem, failure)
        }
    }
}

/** Whether the phone is in a call, a phone call or an internet one. */
interface PhoneCalls {
    fun active(): Boolean

    /** Tells about changes, until the result is called. */
    fun watch(onChange: (Boolean) -> Unit): () -> Unit
}

/**
 * [PhoneCalls] without its failures. If there is no telling whether a call is active, then there is none: an alarm that
 * talks over a call beats one that isn't heard.
 */
class CallWatch(private val calls: PhoneCalls, private val onProblem: AudioProblem) {
    fun active(): Boolean = try {
        calls.active()
    } catch (failure: RuntimeException) {
        onProblem("Could not find out whether a call is active", failure)
        false
    }

    fun watch(onChange: (Boolean) -> Unit): () -> Unit = try {
        calls.watch(onChange)
    } catch (failure: RuntimeException) {
        onProblem("Could not watch for calls", failure)
        NOTHING_TO_STOP
    }

    private companion object {
        val NOTHING_TO_STOP: () -> Unit = {}
    }
}

/** Asks other audio to pause, in the way the audio it is for needs. */
interface AudioFocus {
    /** True if it was given. [onLostForGood] is called if something else takes it for good, not for a moment. */
    fun request(onLostForGood: () -> Unit): Boolean
    fun abandon()
}

/**
 * Having [AudioFocus] for as long as something wants it. Nothing here throws, and focus that is denied is no reason to stay
 * silent: it only means that other audio goes on. Android 17 denies it without a foreground service, so [retry] asks again
 * when the service is up. See docs/architecture.md#background-audio-on-android-17.
 */
class AudioFocusHold(private val focus: AudioFocus, private val onProblem: AudioProblem) {
    private var wanted = false
    private var granted = false
    private var onLost: () -> Unit = {}

    @Synchronized
    fun take(onLostForGood: () -> Unit) {
        wanted = true
        onLost = onLostForGood
        if (!granted) request()
    }

    /** Asks again if it was denied, for audio that is about to start. */
    @Synchronized
    fun retry() {
        if (wanted && !granted) request()
    }

    /** Does nothing if it was not taken, so that a silent alarm has no effect on any other audio. */
    @Synchronized
    fun release() {
        if (!wanted) return
        wanted = false
        granted = false
        try {
            focus.abandon()
        } catch (failure: RuntimeException) {
            onProblem("Could not give up the audio focus", failure)
        }
    }

    private fun request() {
        granted = try {
            focus.request(::lost)
        } catch (failure: RuntimeException) {
            onProblem("Could not ask for the audio focus", failure)
            false
        }
        if (!granted) onProblem("The audio focus was denied: other audio goes on", null)
    }

    /** Outside this class's lock, as what is told takes its own, which it holds while it calls in here. */
    private fun lost() {
        val tell = synchronized(this) {
            if (!wanted) return
            release()
            onLost
        }
        tell()
    }
}

/** What the ringer needs around the sound. */
class RingAudio(
    val volume: AlarmVolumeKeeper,
    val focus: AudioFocusHold,
    val calls: CallWatch,
    val onProblem: AudioProblem,
)

/**
 * During a call an alarm vibrates, as it doesn't sound, even if its setting says no vibration. An alarm without any sound
 * of its own has no sound to make up for: it stays still if it says so, which the silent device tests rely on.
 */
fun wantsVibration(snapshot: AlarmSnapshot, inCall: Boolean): Boolean =
    snapshot.vibrate || (inCall && snapshot.maxVolume != 0f)

/** Plays one alarm sound at a time, in a loop. */
interface AlarmSound {
    /** Returns at once: the sound starts when it is ready. */
    fun play(sound: SoundRef, gain: Float)

    /** The one sound that needs nothing from the phone or the database. */
    fun playLastResort(gain: Float)
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

/** A source that is ready to play. Starting it does not block. */
interface PreparedSound {
    /** Starts in a loop, at [gain] from the first moment on. */
    fun start(gain: Float)
    fun setGain(gain: Float)
    fun release()
}

/** Opens one source at a time. */
fun interface SoundOutput<S> {
    /**
     * Gets [source] ready to play. This can take a long time or never return, on a provider that does not answer, so it
     * must not be called with any lock held. The result is empty if it cannot be played, and [onFailure] is called if it
     * stops working later.
     */
    fun prepare(source: S, onFailure: () -> Unit): PreparedSound?
}

/** Runs work that can block for a long time. A block that never returns must not hold up the next one. */
fun interface SoundWorker {
    fun run(block: () -> Unit)
}

/**
 * Plays the first of the sources that works, and moves on to the next one if it stops working or takes longer than
 * [prepareLimit] to get ready. Asking for the sources can fail or block too, and leads to [lastResort], which is the end of
 * the list anyway. Nothing that can block runs with a lock held or on the caller's thread.
 */
class FallbackSound<S>(
    private val sources: (SoundRef) -> List<S>,
    private val lastResort: S,
    private val output: SoundOutput<S>,
    private val worker: SoundWorker,
    private val timers: RingTimers,
    /** No source is left, so the alarm stays silent. */
    private val onExhausted: () -> Unit,
    private val prepareLimit: Duration = Duration.ofSeconds(5),
) : AlarmSound {
    private var remaining: Iterator<S> = emptyList<S>().iterator()
    private var gain = 0f
    private var current: PreparedSound? = null
    private var cancelLimit: () -> Unit = {}

    /** Which attempt is the current one, because a failure or a result can arrive after it has been given up. */
    private var attempt = 0

    @Synchronized
    override fun play(sound: SoundRef, gain: Float) {
        restart(gain)
        // If the sources do not come in time, this is what is left to try.
        remaining = listOf(lastResort).iterator()
        val which = beginAttempt()
        worker.run { readSources(sound, which) }
    }

    @Synchronized
    override fun playLastResort(gain: Float) {
        restart(gain)
        remaining = listOf(lastResort).iterator()
        playNext()
    }

    @Synchronized
    override fun setGain(gain: Float) {
        this.gain = gain
        current?.setGain(gain)
    }

    @Synchronized
    override fun stop() {
        attempt++
        remaining = emptyList<S>().iterator()
        cancelLimit()
        releaseCurrent()
    }

    private fun restart(gain: Float) {
        stop()
        this.gain = gain
    }

    private fun beginAttempt(): Int {
        val which = ++attempt
        cancelLimit()
        cancelLimit = timers.after(prepareLimit) { failed(which) }
        return which
    }

    private fun readSources(sound: SoundRef, which: Int) {
        val found = try {
            sources(sound)
        } catch (_: RuntimeException) {
            // There is always the last resort, which needs nothing from the phone.
            listOf(lastResort)
        }
        sourcesRead(found, which)
    }

    @Synchronized
    private fun sourcesRead(found: List<S>, which: Int) {
        if (which != attempt) return
        remaining = found.ifEmpty { listOf(lastResort) }.iterator()
        playNext()
    }

    private fun playNext() {
        cancelLimit()
        if (!remaining.hasNext()) {
            attempt++
            onExhausted()
            return
        }
        val source = remaining.next()
        val which = beginAttempt()
        worker.run { prepare(source, which) }
    }

    private fun prepare(source: S, which: Int) {
        val prepared = try {
            output.prepare(source) { failed(which) }
        } catch (_: RuntimeException) {
            // The same as a source that says it cannot be played.
            null
        }
        if (!prepared(prepared, which)) prepared?.release()
    }

    /** False if this attempt was given up in the meantime. */
    @Synchronized
    private fun prepared(sound: PreparedSound?, which: Int): Boolean {
        if (which != attempt) return false
        if (sound == null) {
            playNext()
            return true
        }
        current = sound
        try {
            sound.start(gain)
            cancelLimit()
        } catch (_: RuntimeException) {
            // The player gave up after all, which makes it a source that failed.
            failed(which)
        }
        return true
    }

    @Synchronized
    private fun failed(which: Int) {
        if (which != attempt) return
        releaseCurrent()
        playNext()
    }

    /** A player that cannot be released is no reason to keep the alarm from moving on. */
    private fun releaseCurrent() {
        val sound = current ?: return
        current = null
        try {
            sound.release()
        } catch (_: RuntimeException) {
            // There is nothing more that can be done about this player.
        }
    }
}

/**
 * What all ringing alarms share while they ring: one sound, one vibration and one ring timeout. An alarm that fires
 * while another one rings joins it. See docs/architecture.md#ringing.
 *
 * Alarm snapshots can differ if the settings changed between two alarms, which is settled as follows: the sound and its
 * volume, also the level of the phone's alarm stream, stay those of the alarm that started it, the phone vibrates while any ringing alarm asks for it, and the
 * timeout is the longest one, so that no alarm rings for less time than it should.
 *
 * The sound is the last thing to start, as Android silences it, without any error, unless a foreground service is running
 * or an activity is visible (see docs/architecture.md#background-audio-on-android-17). A first alarm therefore gets its
 * vibration and timeout, and reports through [onChanged], which leads to the notification and the service. The sound starts
 * at [foregroundSettled], or after [FOREGROUND_WAIT] if that does not come, because a chance of sound beats none.
 *
 * During a call there is no sound: it stops when a call starts, and starts when the call ends, with the alarm vibrating in
 * the meantime. See docs/architecture.md#calls-and-audio-focus.
 *
 * The timeout normally ends the ringing through `AlarmEngine`. If that does not happen [HARD_STOP_MARGIN] later, the ringer
 * stops on its own, because a sound nobody can stop is worse than a stored state that is out of date.
 */
class AlarmRinger(
    private val sound: AlarmSound,
    private val vibration: AlarmVibration,
    private val timers: RingTimers,
    private val wakeLock: RingWakeLock,
    private val audio: RingAudio,
    /** The ring timeout ran out for these alarms. Alarms that joined later have a new timeout. */
    private val onTimeout: (List<Long>) -> Unit,
    private val onChanged: (List<AlarmInstance>) -> Unit,
    /** The ringing went on past its timeout, and the ringer ended it on its own. */
    private val onHardStop: (List<AlarmInstance>) -> Unit = {},
    /** The service did not come up in time, so the sound starts without it. */
    private val onForegroundLate: () -> Unit = {},
) {
    private val alarms = linkedMapOf<Long, AlarmInstance>()
    private var vibrating = false
    private var cancelRamp: () -> Unit = {}
    private var cancelTimeout: () -> Unit = {}
    private var cancelHardStop: () -> Unit = {}
    private var cancelForegroundWait: () -> Unit = {}
    private var cancelCallCheck: () -> Unit = {}
    private var stopWatchingCalls: () -> Unit = {}

    /** The alarm whose sound waits for the service. */
    private var waitingToPlay: AlarmInstance? = null

    /** The alarm whose sound it is, also when it has left, for when a call ends. */
    private lateinit var soundAlarm: AlarmInstance
    private var inCall = false

    /** Told apart from each other, because a timer can have fired just before it was cancelled. */
    private var rampRun = 0
    private var timeoutRun = 0
    private var callCheckRun = 0

    @Synchronized
    fun ringing(): List<AlarmInstance> = alarms.values.toList()

    /** Also for an alarm that is already in, which restarts the timeout like any other joining alarm. */
    @Synchronized
    fun join(alarm: AlarmInstance) {
        val first = alarms.isEmpty()
        alarms[alarm.id] = alarm
        if (first) watchCalls()
        val timeout = Duration.ofMinutes(alarms.values.map { it.snapshot.ringTimeoutMinutes }.max().toLong())
        wakeLock.hold(timeout.plus(WAKE_LOCK_MARGIN))
        restartTimeout(timeout)
        updateVibration()
        if (first) waitForForeground(alarm)
        onChanged(ringing())
    }

    /**
     * The service has gone into the foreground, or Android refused it. In either case nothing more is to come, so the sound
     * starts now if it is waiting. Called from outside `AlarmForeground`'s lock.
     */
    @Synchronized
    fun foregroundSettled() {
        val alarm = waitingToPlay ?: return
        waitingToPlay = null
        cancelForegroundWait()
        startSound(alarm)
    }

    /**
     * Puts the phone's alarm volume back if an alarm left it changed and nothing rings: after a crash or a reboot, whose
     * sweep comes here, or if Android ignored the earlier try. An alarm that rings goes on using it, and does this when it ends.
     */
    @Synchronized
    fun restoreVolumeIfIdle() {
        if (alarms.isEmpty()) audio.volume.restore()
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

    private fun waitForForeground(alarm: AlarmInstance) {
        soundAlarm = alarm
        waitingToPlay = alarm
        cancelForegroundWait = timers.after(FOREGROUND_WAIT) { foregroundLate(alarm) }
    }

    @Synchronized
    private fun foregroundLate(alarm: AlarmInstance) {
        if (waitingToPlay !== alarm) return
        onForegroundLate()
        foregroundSettled()
    }

    /** Android 17 only lets focus and volume change with the service up, as it does the sound, so they come here. */
    private fun startSound(alarm: AlarmInstance) {
        if (inCall) return
        val snapshot = alarm.snapshot
        // A silent alarm has no reason to pause anything, and no effect on anyone who sleeps next to the phone.
        if (snapshot.maxVolume != 0f) audio.focus.take {}
        audio.volume.raise(snapshot.maxVolume)
        if (alarm.key.isEmergency) {
            sound.playLastResort(soundGain(Duration.ZERO, 0, snapshot.maxVolume))
            return
        }
        sound.play(snapshot.sound, soundGain(Duration.ZERO, effectiveRampSeconds(snapshot), snapshot.maxVolume))
        // Without a ramp, or with a silent maximum, the gain never changes.
        if (snapshot.volumeRampSeconds > 0 && snapshot.maxVolume != 0f) scheduleRamp(snapshot, Duration.ZERO, ++rampRun)
    }

    /** Listening comes first, so that a call that starts in the moment between the two is not missed. */
    private fun watchCalls() {
        stopWatchingCalls = audio.calls.watch(::callChanged)
        inCall = audio.calls.active()
        checkCallLater()
    }

    @Synchronized
    private fun callChanged(active: Boolean) {
        if (alarms.isEmpty() || active == inCall) return
        inCall = active
        checkCallLater()
        attempt("The vibration could not follow the call", ::updateVibration)
        if (waitingToPlay != null) return
        if (active) {
            attempt("The sound could not be stopped for a call", ::silence)
        } else {
            attempt("The sound could not start after a call") { startSound(soundAlarm) }
        }
    }

    /**
     * Android tells about a call that ends, but the sound must not depend on that: a missed end is an alarm without a
     * sound. So this looks for itself as well, for as long as the sound waits for a call.
     */
    private fun checkCallLater() {
        cancelCallCheck()
        val run = ++callCheckRun
        if (inCall) cancelCallCheck = timers.after(CALL_CHECK) { checkCall(run) }
    }

    @Synchronized
    private fun checkCall(run: Int) {
        if (run != callCheckRun) return
        callChanged(audio.calls.active())
        if (run == callCheckRun) checkCallLater()
    }

    private fun silence() {
        rampRun++
        cancelRamp()
        sound.stop()
        audio.focus.release()
    }

    private inline fun attempt(problem: String, step: () -> Unit) {
        try {
            step()
        } catch (failure: RuntimeException) {
            audio.onProblem(problem, failure)
        }
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
        val wanted = alarms.values.any { wantsVibration(it.snapshot, inCall) }
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
        cancelForegroundWait()
        waitingToPlay = null
        callCheckRun++
        cancelCallCheck()
        inCall = false
        return listOf(
            sound::stop,
            audio.focus::release,
            audio.volume::restore,
            ::updateVibration,
            wakeLock::release,
            stopWatchingCalls,
        ).mapNotNull { step ->
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

        /** A service that is allowed to start is up long before this. */
        val FOREGROUND_WAIT: Duration = Duration.ofSeconds(3)

        /** How long a sound can stay off after a call has ended, if Android doesn't say that it has. */
        val CALL_CHECK: Duration = Duration.ofSeconds(5)

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
