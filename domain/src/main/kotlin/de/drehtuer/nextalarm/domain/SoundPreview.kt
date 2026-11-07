package de.drehtuer.nextalarm.domain

import java.time.Duration

/** The longest the alarm sound plays on screen 5. Long enough to know it, short enough not to disturb anyone. */
val SOUND_PREVIEW_LIMIT: Duration = Duration.ofSeconds(10)

/** Whether the alarm sound plays on screen 5, or why it didn't start. */
enum class SoundPreviewStatus { IDLE, PLAYING, IN_CALL, ALARM_RINGING }

/**
 * Plays the alarm sound on screen 5, through the same sources in the same order as an alarm, with its fallback to the
 * next one: [newSound] is a `FallbackSound` of its own, so that what is heard is what will ring. See
 * docs/architecture.md#hearing-the-sound-on-screen-5.
 *
 * It plays at the phone's alarm volume as it is: the player's gain is 1, there is no ramp, and the stream is never
 * changed, so the maximum volume does not apply. It stops at [stop], after [SOUND_PREVIEW_LIMIT], when a call starts, when
 * something else takes the audio focus for good and when no source is left. It doesn't start during a call or while an
 * alarm rings, and a starting alarm stops it ([stop], from the alarm path).
 *
 * Nothing here throws, as the alarm path calls [stop].
 */
class SoundPreview(
    newSound: (onExhausted: () -> Unit) -> AlarmSound,
    private val focus: AudioFocusHold,
    private val calls: CallWatch,
    private val timers: RingTimers,
    private val alarmRinging: () -> Boolean,
    private val onProblem: AudioProblem,
) {
    /** Which play is the current one, because a timer, a call or the end of the sources can arrive after it stopped. */
    @Volatile
    private var run = 0

    // `FallbackSound` reports with its own lock held, so the end is handled on a turn of its own, and not under that lock.
    private val sound = newSound {
        val which = run
        timers.after(Duration.ZERO) { ended(which) }
    }

    /** What belongs to the sound that plays. */
    private class Playing(
        val onStopped: () -> Unit,
        val stopWatchingCalls: () -> Unit,
        val cancelLimit: () -> Unit,
    )

    private var current: Playing? = null

    /**
     * Plays [sound] in place of whatever plays. The result is [SoundPreviewStatus.PLAYING], or why it didn't start.
     * [onStopped] is called once when it stops, for whichever reason, unless it didn't start.
     */
    @Synchronized
    fun play(sound: SoundRef, onStopped: () -> Unit): SoundPreviewStatus {
        halt()
        if (alarmRinging()) return SoundPreviewStatus.ALARM_RINGING
        val which = ++run
        // Listening comes first, so that a call that starts in the moment between the two is not missed.
        val stopWatchingCalls = calls.watch { active -> if (active) ended(which) }
        if (calls.active()) {
            attempt("Could not stop watching for calls", stopWatchingCalls)
            return SoundPreviewStatus.IN_CALL
        }
        current = Playing(onStopped, stopWatchingCalls, timers.after(SOUND_PREVIEW_LIMIT) { ended(which) })
        focus.take { ended(which) }
        this.sound.play(sound, FULL_GAIN)
        return SoundPreviewStatus.PLAYING
    }

    /** Does nothing if it doesn't play. */
    @Synchronized
    fun stop() {
        halt()
    }

    @Synchronized
    private fun ended(which: Int) {
        if (which == run) halt()
    }

    /** One part that fails must not keep the others going. */
    private fun halt() {
        val stopping = current ?: return
        current = null
        run++
        attempt("Could not cancel the limit of the sound preview", stopping.cancelLimit)
        attempt("Could not stop watching for calls", stopping.stopWatchingCalls)
        attempt("Could not stop the sound preview", sound::stop)
        focus.release()
        attempt("Could not report the end of the sound preview", stopping.onStopped)
    }

    private inline fun attempt(problem: String, step: () -> Unit) {
        try {
            step()
        } catch (failure: RuntimeException) {
            onProblem(problem, failure)
        }
    }

    private companion object {
        const val FULL_GAIN = 1f
    }
}
