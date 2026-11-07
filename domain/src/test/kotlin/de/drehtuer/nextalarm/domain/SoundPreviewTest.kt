package de.drehtuer.nextalarm.domain

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class PreviewSound : AlarmSound {
    val log = mutableListOf<String>()
    var failToStop = false
    lateinit var onExhausted: () -> Unit

    override fun play(sound: SoundRef, gain: Float) {
        log += "play:$sound:$gain"
    }

    override fun playLastResort(gain: Float) {
        log += "playLastResort:$gain"
    }

    override fun setGain(gain: Float) {
        log += "gain:$gain"
    }

    override fun stop() {
        log += "stop"
        check(!failToStop) { "player failed" }
    }
}

private class PreviewTimers : RingTimers {
    class Timer(val delay: Duration, val block: () -> Unit) {
        var cancelled = false
    }

    val all = mutableListOf<Timer>()
    val active get() = all.filter { !it.cancelled }
    var failToCancel = false

    override fun after(delay: Duration, block: () -> Unit): () -> Unit {
        val timer = Timer(delay, block)
        all += timer
        return {
            check(!failToCancel) { "timer failed" }
            timer.cancelled = true
        }
    }

    /** Runs a timer, as if it elapsed. Also one that was cancelled, as one can fire just before it is. */
    fun fire(timer: Timer) = timer.block()

    fun last(delay: Duration) = all.last { it.delay == delay }
}

/** Calls whose watch can't be ended. */
private class StuckCalls(var isActive: Boolean = false) : PhoneCalls {
    override fun active() = isActive
    override fun watch(onChange: (Boolean) -> Unit): () -> Unit = { error("could not stop watching") }
}

class SoundPreviewTest {
    private val sound = PreviewSound()
    private val timers = PreviewTimers()
    private val calls = FakeCalls()
    private val focus = FakeFocus()
    private val problems = mutableListOf<String>()
    private var ringing = false
    private var stopped = 0
    private val file = SoundRef.File("sound-1-morning.mp3")

    private fun preview(phoneCalls: PhoneCalls = calls) = SoundPreview(
        newSound = { onExhausted ->
            sound.onExhausted = onExhausted
            sound
        },
        focus = AudioFocusHold(focus) { message, _ -> problems += message },
        calls = CallWatch(phoneCalls) { message, _ -> problems += message },
        timers = timers,
        alarmRinging = { ringing },
        onProblem = { message, _ -> problems += message },
    )

    private val preview = preview()

    private fun play(sound: SoundRef = file) = preview.play(sound) { stopped++ }

    @Test
    fun `it plays the sound at full gain, with the focus, for at most 10 seconds`() {
        assertEquals(SoundPreviewStatus.PLAYING, play())
        assertEquals(listOf("play:$file:1.0"), sound.log)
        assertEquals(1, focus.requests)
        assertEquals(1, calls.watching.size)
        assertEquals(listOf(SOUND_PREVIEW_LIMIT), timers.active.map { it.delay })
        assertEquals(Duration.ofSeconds(10), SOUND_PREVIEW_LIMIT)
        assertEquals(0, stopped)
    }

    @Test
    fun `stop ends it, gives the focus back and says so once`() {
        play()
        preview.stop()
        assertEquals("stop", sound.log.last())
        assertEquals(1, focus.abandons)
        assertTrue(calls.watching.isEmpty())
        assertTrue(timers.active.isEmpty())
        assertEquals(1, stopped)
        preview.stop()
        assertEquals(1, stopped)
        assertEquals(1, focus.abandons)
        assertTrue(problems.isEmpty())
    }

    @Test
    fun `stop does nothing if nothing plays`() {
        preview.stop()
        assertTrue(sound.log.isEmpty())
        assertEquals(0, focus.abandons)
        assertEquals(0, stopped)
    }

    @Test
    fun `it stops after 10 seconds`() {
        play()
        timers.fire(timers.last(SOUND_PREVIEW_LIMIT))
        assertEquals("stop", sound.log.last())
        assertEquals(1, focus.abandons)
        assertEquals(1, stopped)
    }

    @Test
    fun `a call that starts stops it, while the end of one changes nothing`() {
        play()
        calls.change(false)
        assertEquals(0, stopped)
        calls.change(true)
        assertEquals("stop", sound.log.last())
        assertEquals(1, stopped)
        assertTrue(calls.watching.isEmpty())
    }

    @Test
    fun `during a call it doesn't start, and watches nothing`() {
        calls.isActive = true
        assertEquals(SoundPreviewStatus.IN_CALL, play())
        assertTrue(sound.log.isEmpty())
        assertEquals(0, focus.requests)
        assertTrue(calls.watching.isEmpty())
        assertTrue(timers.all.isEmpty())
        preview.stop()
        assertEquals(0, stopped)
    }

    @Test
    fun `while an alarm rings it doesn't start, and watches nothing`() {
        ringing = true
        assertEquals(SoundPreviewStatus.ALARM_RINGING, play())
        assertTrue(sound.log.isEmpty())
        assertEquals(0, focus.requests)
        assertTrue(calls.watching.isEmpty())
        assertTrue(timers.all.isEmpty())
    }

    @Test
    fun `other audio that takes the focus for good stops it`() {
        play()
        focus.onLost()
        assertEquals("stop", sound.log.last())
        assertEquals(1, stopped)
    }

    @Test
    fun `when no source is left it stops, on a turn of its own`() {
        play()
        sound.onExhausted()
        assertEquals(0, stopped)
        timers.fire(timers.last(Duration.ZERO))
        assertEquals("stop", sound.log.last())
        assertEquals(1, stopped)
    }

    @Test
    fun `playing again replaces what plays, and what is left of the first one has no effect`() {
        var firstStopped = 0
        preview.play(file) { firstStopped++ }
        val firstLimit = timers.last(SOUND_PREVIEW_LIMIT)
        sound.onExhausted()
        val firstEnd = timers.last(Duration.ZERO)
        assertEquals(SoundPreviewStatus.PLAYING, play(SoundRef.SystemDefault))
        assertEquals(1, firstStopped)
        assertEquals(listOf("play:$file:1.0", "stop", "play:${SoundRef.SystemDefault}:1.0"), sound.log)
        assertEquals(1, calls.watching.size)
        timers.fire(firstLimit)
        timers.fire(firstEnd)
        assertEquals(0, stopped)
        assertEquals("play:${SoundRef.SystemDefault}:1.0", sound.log.last())
        preview.stop()
        assertEquals(1, stopped)
        assertEquals(1, firstStopped)
    }

    @Test
    fun `a part that fails doesn't keep the others from stopping`() {
        val preview = preview(StuckCalls())
        preview.play(file) { error("the screen failed") }
        sound.failToStop = true
        timers.failToCancel = true
        preview.stop()
        assertEquals(1, focus.abandons)
        assertEquals(
            listOf(
                "Could not cancel the limit of the sound preview",
                "Could not stop watching for calls",
                "Could not stop the sound preview",
                "Could not report the end of the sound preview",
            ),
            problems,
        )
        preview.stop()
        assertEquals(4, problems.size)
    }

    @Test
    fun `a watch on calls that can't be ended doesn't keep it from saying that a call is active`() {
        val preview = preview(StuckCalls(isActive = true))
        assertEquals(SoundPreviewStatus.IN_CALL, preview.play(file) { stopped++ })
        assertEquals(listOf("Could not stop watching for calls"), problems)
        assertFalse(sound.log.isNotEmpty())
    }
}
