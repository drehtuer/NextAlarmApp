package de.drehtuer.nextalarm.domain

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SoundGainTest {
    @Test
    fun `without a ramp or a maximum the sound plays at full level`() {
        assertEquals(1f, soundGain(Duration.ZERO, 0, null))
    }

    @Test
    fun `without a ramp the maximum applies straight away`() {
        assertEquals(0.5f, soundGain(Duration.ZERO, 0, 0.5f))
    }

    @Test
    fun `a ramp rises from silent to the top and stays there`() {
        assertEquals(0f, soundGain(Duration.ZERO, 10, null))
        assertEquals(0.5f, soundGain(Duration.ofSeconds(5), 10, null))
        assertEquals(1f, soundGain(Duration.ofSeconds(10), 10, null))
        assertEquals(1f, soundGain(Duration.ofSeconds(11), 10, null))
    }

    @Test
    fun `a ramp rises to the maximum`() {
        assertEquals(0.25f, soundGain(Duration.ofSeconds(5), 10, 0.5f))
        assertEquals(0.5f, soundGain(Duration.ofSeconds(20), 10, 0.5f))
    }

    @Test
    fun `a maximum of zero is silent throughout`() {
        assertEquals(0f, soundGain(Duration.ZERO, 0, 0f))
        assertEquals(0f, soundGain(Duration.ofSeconds(5), 10, 0f))
        assertEquals(0f, soundGain(Duration.ofSeconds(20), 10, 0f))
    }
}

private class FakeOutput(private val broken: Set<String> = emptySet()) : SoundOutput<String> {
    val log = mutableListOf<String>()
    val failures = mutableListOf<() -> Unit>()

    override fun start(source: String, gain: Float, onFailure: () -> Unit): Boolean {
        log += "start:$source:$gain"
        failures += onFailure
        return source !in broken
    }

    override fun setGain(gain: Float) {
        log += "gain:$gain"
    }

    override fun stop() {
        log += "stop"
    }
}

class FallbackSoundTest {
    private fun sound(output: SoundOutput<String>) = FallbackSound({ listOf("file", "system", "bundled") }, output)

    @Test
    fun `the first source that works plays, at the given gain`() {
        val output = FakeOutput()
        sound(output).play(SoundRef.SystemDefault, 0.5f)
        assertEquals(listOf("stop", "start:file:0.5"), output.log)
    }

    @Test
    fun `the sources are asked for this sound`() {
        val asked = mutableListOf<SoundRef>()
        val chain = FallbackSound({ asked += it; listOf("file") }, FakeOutput())
        chain.play(SoundRef.File("a"), 1f)
        assertEquals(listOf<SoundRef>(SoundRef.File("a")), asked)
    }

    @Test
    fun `a source that cannot be played is skipped`() {
        val output = FakeOutput(broken = setOf("file", "system"))
        sound(output).play(SoundRef.SystemDefault, 1f)
        assertEquals(listOf("stop", "start:file:1.0", "start:system:1.0", "start:bundled:1.0"), output.log)
    }

    @Test
    fun `if nothing can be played, it stays quiet without failing`() {
        val output = FakeOutput(broken = setOf("file", "system", "bundled"))
        sound(output).play(SoundRef.SystemDefault, 1f)
        assertEquals(4, output.log.size)
    }

    @Test
    fun `a source that stops working later is replaced, at the current gain`() {
        val output = FakeOutput()
        val chain = sound(output)
        chain.play(SoundRef.SystemDefault, 0f)
        chain.setGain(0.25f)
        output.failures.single().invoke()
        assertEquals(listOf("stop", "start:file:0.0", "gain:0.25", "stop", "start:system:0.25"), output.log)
    }

    @Test
    fun `a failure of a source that was given up is ignored`() {
        val output = FakeOutput()
        val chain = sound(output)
        chain.play(SoundRef.SystemDefault, 1f)
        val old = output.failures.single()
        chain.stop()
        output.log.clear()
        old()
        assertTrue(output.log.isEmpty())
    }

    @Test
    fun `after stopping, nothing is left to fall back on`() {
        val output = FakeOutput()
        val chain = sound(output)
        chain.play(SoundRef.SystemDefault, 1f)
        chain.stop()
        assertEquals("stop", output.log.last())
    }

    @Test
    fun `playing again starts over with the first source`() {
        val output = FakeOutput()
        val chain = sound(output)
        chain.play(SoundRef.SystemDefault, 1f)
        output.failures.single().invoke()
        chain.play(SoundRef.SystemDefault, 1f)
        assertEquals("start:file:1.0", output.log.last())
    }
}

private class FakeTimers : RingTimers {
    class Timer(val delay: Duration, val block: () -> Unit) {
        var cancelled = false
    }

    val all = mutableListOf<Timer>()
    val active get() = all.filter { !it.cancelled }

    override fun after(delay: Duration, block: () -> Unit): () -> Unit {
        val timer = Timer(delay, block)
        all += timer
        return { timer.cancelled = true }
    }

    /** Runs the one timer with this delay, as if it elapsed. */
    fun elapse(delay: Duration) {
        val timer = active.last { it.delay == delay }
        timer.cancelled = true
        timer.block()
    }
}

private class FakeSound : AlarmSound {
    val log = mutableListOf<String>()

    override fun play(sound: SoundRef, gain: Float) {
        log += "play:$sound:$gain"
    }

    override fun setGain(gain: Float) {
        log += "gain:$gain"
    }

    override fun stop() {
        log += "stop"
    }
}

private class FakeVibration : AlarmVibration {
    var vibrating = false
    var starts = 0

    override fun start() {
        vibrating = true
        starts++
    }

    override fun stop() {
        vibrating = false
    }
}

private class FakeWakeLock : RingWakeLock {
    var heldFor: Duration? = null

    override fun hold(atMost: Duration) {
        heldFor = atMost
    }

    override fun release() {
        heldFor = null
    }
}

class AlarmRingerTest {
    private val sound = FakeSound()
    private val vibration = FakeVibration()
    private val timers = FakeTimers()
    private val wakeLock = FakeWakeLock()
    private val timedOut = mutableListOf<List<Long>>()
    private val changes = mutableListOf<List<Long>>()
    private val ringer = AlarmRinger(sound, vibration, timers, wakeLock, { timedOut += it }) { list -> changes += list.map { it.id } }

    private val threeMinutes = Duration.ofMinutes(3)
    private val rampStep = Duration.ofMillis(250)

    private fun alarm(
        id: Long = 1,
        vibrate: Boolean = false,
        rampSeconds: Int = 0,
        maxVolume: Float? = null,
        timeoutMinutes: Int = 3,
    ) = AlarmInstance(
        id,
        AlarmKey.forTestAlarm(id),
        NOW,
        AlarmState.Ringing,
        0,
        false,
        snapshot(vibrate = vibrate).copy(
            volumeRampSeconds = rampSeconds,
            maxVolume = maxVolume,
            ringTimeoutMinutes = timeoutMinutes,
        ),
    )

    @Test
    fun `the first alarm starts the sound at its gain and keeps the CPU awake`() {
        ringer.join(alarm(maxVolume = 0.5f))
        assertEquals(listOf("play:SystemDefault:0.5"), sound.log)
        assertEquals(Duration.ofMinutes(4), wakeLock.heldFor)
        assertEquals(listOf(1L), ringer.ringing().map { it.id })
        assertEquals(listOf(listOf(1L)), changes)
    }

    @Test
    fun `an alarm that joins does not restart the sound, but does restart the timeout`() {
        ringer.join(alarm(1))
        val first = timers.active.single()
        ringer.join(alarm(2))
        assertEquals(1, sound.log.size)
        assertTrue(first.cancelled)
        assertEquals(threeMinutes, timers.active.single().delay)
        assertEquals(listOf(1L, 2L), ringer.ringing().map { it.id })
    }

    @Test
    fun `an alarm that joins again restarts the timeout, and is not listed twice`() {
        ringer.join(alarm(1))
        val first = timers.active.single()
        ringer.join(alarm(1))
        assertTrue(first.cancelled)
        assertEquals(1, ringer.ringing().size)
        assertEquals(1, sound.log.size)
    }

    @Test
    fun `the shared timeout is the longest one`() {
        ringer.join(alarm(1, timeoutMinutes = 5))
        ringer.join(alarm(2, timeoutMinutes = 3))
        assertEquals(Duration.ofMinutes(5), timers.active.single().delay)
        assertEquals(Duration.ofMinutes(6), wakeLock.heldFor)
    }

    @Test
    fun `when the timeout runs out, it names the ringing alarms`() {
        ringer.join(alarm(1))
        ringer.join(alarm(2))
        timers.elapse(threeMinutes)
        assertEquals(listOf(listOf(1L, 2L)), timedOut)
    }

    @Test
    fun `a timeout that was replaced has no effect, even if it fired just before`() {
        ringer.join(alarm(1))
        val old = timers.active.single()
        ringer.join(alarm(2))
        old.block()
        assertTrue(timedOut.isEmpty())
    }

    @Test
    fun `a timeout that fires when nothing rings any more has no effect`() {
        ringer.join(alarm(1))
        val old = timers.active.single()
        ringer.leave(1)
        old.block()
        assertTrue(timedOut.isEmpty())
    }

    @Test
    fun `the sound goes on until the last alarm has left`() {
        ringer.join(alarm(1))
        ringer.join(alarm(2))
        ringer.leave(1)
        assertFalse("stop" in sound.log)
        assertEquals(listOf(2L), ringer.ringing().map { it.id })
        ringer.leave(2)
        assertEquals("stop", sound.log.last())
        assertEquals(null, wakeLock.heldFor)
        assertTrue(timers.active.isEmpty())
        assertEquals(listOf(listOf(1L), listOf(1L, 2L), listOf(2L), emptyList()), changes)
    }

    @Test
    fun `an alarm that does not ring has nothing to leave`() {
        ringer.leave(1)
        assertTrue(sound.log.isEmpty())
        assertTrue(changes.isEmpty())
    }

    @Test
    fun `an alarm without vibration does not vibrate`() {
        ringer.join(alarm(vibrate = false))
        assertFalse(vibration.vibrating)
        assertEquals(0, vibration.starts)
    }

    @Test
    fun `an alarm with vibration vibrates, and stops when it leaves`() {
        ringer.join(alarm(vibrate = true))
        assertTrue(vibration.vibrating)
        ringer.leave(1)
        assertFalse(vibration.vibrating)
    }

    @Test
    fun `the phone vibrates while any ringing alarm asks for it`() {
        ringer.join(alarm(1, vibrate = false))
        ringer.join(alarm(2, vibrate = true))
        ringer.join(alarm(3, vibrate = true))
        assertTrue(vibration.vibrating)
        assertEquals(1, vibration.starts)
        ringer.leave(2)
        assertTrue(vibration.vibrating)
        ringer.leave(3)
        assertFalse(vibration.vibrating)
        assertEquals(listOf(1L), ringer.ringing().map { it.id })
    }

    @Test
    fun `without a ramp no ramp timer runs`() {
        ringer.join(alarm(rampSeconds = 0))
        assertEquals(listOf(threeMinutes), timers.active.map { it.delay })
    }

    @Test
    fun `a ramp starts silent and rises to the maximum, then stops`() {
        ringer.join(alarm(rampSeconds = 1, maxVolume = 0.8f))
        assertEquals("play:SystemDefault:0.0", sound.log.last())
        repeat(4) { timers.elapse(rampStep) }
        assertEquals(listOf("gain:0.2", "gain:0.4", "gain:0.6", "gain:0.8"), sound.log.drop(1))
        assertEquals(listOf(threeMinutes), timers.active.map { it.delay })
    }

    @Test
    fun `a ramp longer than the ringing reaches full level within the ring time`() {
        ringer.join(alarm(rampSeconds = 300, timeoutMinutes = 1))
        repeat(120) { timers.elapse(rampStep) }
        assertEquals("gain:0.5", sound.log.last())
        repeat(120) { timers.elapse(rampStep) }
        assertEquals("gain:1.0", sound.log.last())
        assertEquals(listOf(Duration.ofMinutes(1)), timers.active.map { it.delay })
    }

    @Test
    fun `a ramp shorter than the ringing keeps its length`() {
        assertEquals(30, effectiveRampSeconds(alarm(rampSeconds = 30, timeoutMinutes = 1).snapshot))
        assertEquals(60, effectiveRampSeconds(alarm(rampSeconds = 300, timeoutMinutes = 1).snapshot))
        assertEquals(300, effectiveRampSeconds(alarm(rampSeconds = 300, timeoutMinutes = 5).snapshot))
    }

    @Test
    fun `a ramp to no maximum rises to full level`() {
        ringer.join(alarm(rampSeconds = 1))
        repeat(4) { timers.elapse(rampStep) }
        assertEquals("gain:1.0", sound.log.last())
    }

    @Test
    fun `a silent maximum has no ramp to run`() {
        ringer.join(alarm(rampSeconds = 30, maxVolume = 0f))
        assertEquals(listOf("play:SystemDefault:0.0"), sound.log)
        assertEquals(listOf(threeMinutes), timers.active.map { it.delay })
    }

    @Test
    fun `an alarm that joins does not disturb the ramp`() {
        ringer.join(alarm(1, rampSeconds = 1))
        ringer.join(alarm(2, maxVolume = 0.1f))
        timers.elapse(rampStep)
        assertEquals(listOf("play:SystemDefault:0.0", "gain:0.25"), sound.log)
    }

    @Test
    fun `stopping ends the ramp, even if its timer fired just before`() {
        ringer.join(alarm(rampSeconds = 1))
        val step = timers.active.first { it.delay == rampStep }
        ringer.leave(1)
        assertTrue(step.cancelled)
        sound.log.clear()
        step.block()
        assertTrue(sound.log.isEmpty())
    }

    @Test
    fun `a new round rings again from the start`() {
        ringer.join(alarm(1))
        ringer.leave(1)
        sound.log.clear()
        ringer.join(alarm(2, maxVolume = 0.5f))
        assertEquals(listOf("play:SystemDefault:0.5"), sound.log)
        timers.elapse(threeMinutes)
        assertEquals(listOf(listOf(2L)), timedOut)
    }
}
