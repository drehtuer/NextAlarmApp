package de.drehtuer.nextalarm.domain

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    var failToStart: Set<String> = emptySet()
    var failToRelease = false

    override fun prepare(source: String, onFailure: () -> Unit): PreparedSound? {
        log += "prepare:$source"
        failures += onFailure
        if (source == "throws") error("player failed")
        if (source in broken) return null
        return object : PreparedSound {
            override fun start(gain: Float) {
                log += "start:$source:$gain"
                check(source !in failToStart) { "player failed" }
            }

            override fun setGain(gain: Float) {
                log += "gain:$source:$gain"
            }

            override fun release() {
                log += "release:$source"
                check(!failToRelease) { "player failed" }
            }
        }
    }
}

/** Keeps the work back until the test lets it run, as a thread that is slow to get there would. */
private class HeldWorker : SoundWorker {
    val held = ArrayDeque<() -> Unit>()

    override fun run(block: () -> Unit) {
        held.addLast(block)
    }

    fun runNext() = held.removeFirst().invoke()

    fun runAll() {
        while (held.isNotEmpty()) runNext()
    }
}

class FallbackSoundTest {
    private val timers = FakeTimers()
    private val limit = Duration.ofSeconds(5)
    private var exhausted = 0

    private fun sound(
        output: SoundOutput<String>,
        worker: SoundWorker = SoundWorker { it() },
        sources: (SoundRef) -> List<String> = { listOf("file", "system", "bundled") },
    ) = FallbackSound(sources, "last", output, worker, timers, { exhausted++ }, limit)

    @Test
    fun `the first source that works plays, at the given gain`() {
        val output = FakeOutput()
        sound(output).play(SoundRef.SystemDefault, 0.5f)
        assertEquals(listOf("prepare:file", "start:file:0.5"), output.log)
    }

    @Test
    fun `the sources are asked for this sound`() {
        val asked = mutableListOf<SoundRef>()
        val chain = sound(FakeOutput(), sources = { asked += it; listOf("file") })
        chain.play(SoundRef.File("a"), 1f)
        assertEquals(listOf<SoundRef>(SoundRef.File("a")), asked)
    }

    @Test
    fun `a source that cannot be played is skipped`() {
        val output = FakeOutput(broken = setOf("file", "system"))
        sound(output).play(SoundRef.SystemDefault, 1f)
        assertEquals(listOf("prepare:file", "prepare:system", "prepare:bundled", "start:bundled:1.0"), output.log)
        assertEquals(0, exhausted)
    }

    @Test
    fun `a player that throws is the same as a source that cannot be played`() {
        val output = FakeOutput()
        sound(output, sources = { listOf("throws", "system") }).play(SoundRef.SystemDefault, 1f)
        assertEquals("start:system:1.0", output.log.last())
    }

    @Test
    fun `a player that fails to start is released, and the next source is tried`() {
        val output = FakeOutput().apply { failToStart = setOf("file") }
        sound(output).play(SoundRef.SystemDefault, 1f)
        assertEquals(listOf("prepare:file", "start:file:1.0", "release:file", "prepare:system", "start:system:1.0"), output.log)
    }

    @Test
    fun `if the sources cannot be had, the last resort plays`() {
        val output = FakeOutput()
        sound(output, sources = { error("settings failed") }).play(SoundRef.SystemDefault, 1f)
        assertEquals(listOf("prepare:last", "start:last:1.0"), output.log)
    }

    @Test
    fun `if there are no sources, the last resort plays`() {
        val output = FakeOutput()
        sound(output, sources = { emptyList() }).play(SoundRef.SystemDefault, 1f)
        assertEquals(listOf("prepare:last", "start:last:1.0"), output.log)
    }

    @Test
    fun `if the sources do not come in time, the last resort plays, and they are ignored when they do`() {
        val output = FakeOutput()
        val worker = HeldWorker()
        sound(output, worker).play(SoundRef.SystemDefault, 1f)
        val slow = worker.held.removeFirst()
        timers.elapse(limit)
        worker.runAll()
        assertEquals(listOf("prepare:last", "start:last:1.0"), output.log)
        slow()
        assertEquals(listOf("prepare:last", "start:last:1.0"), output.log)
    }

    @Test
    fun `the last resort can be asked for directly, and needs no sources`() {
        val output = FakeOutput()
        sound(output, sources = { error("not to be asked") }).playLastResort(0.5f)
        assertEquals(listOf("prepare:last", "start:last:0.5"), output.log)
    }

    @Test
    fun `if nothing can be played, it says so once, without failing`() {
        val output = FakeOutput(broken = setOf("file", "system", "bundled"))
        sound(output).play(SoundRef.SystemDefault, 1f)
        assertEquals(1, exhausted)
        assertTrue(timers.active.isEmpty())
    }

    @Test
    fun `nothing that can block runs on the caller's thread`() {
        val output = FakeOutput()
        val worker = HeldWorker()
        sound(output, worker).play(SoundRef.SystemDefault, 1f)
        assertTrue(output.log.isEmpty())
        worker.runNext()
        assertTrue(output.log.isEmpty())
        worker.runNext()
        assertEquals(listOf("prepare:file", "start:file:1.0"), output.log)
    }

    @Test
    fun `a source that takes too long is given up for the next one, and released when it does come`() {
        val output = FakeOutput()
        val worker = HeldWorker()
        sound(output, worker).play(SoundRef.SystemDefault, 1f)
        worker.runNext()
        val hanging = worker.held.removeFirst()
        timers.elapse(limit)
        worker.runNext()
        assertEquals(listOf("prepare:system", "start:system:1.0"), output.log)
        hanging()
        assertEquals(listOf("prepare:system", "start:system:1.0", "prepare:file", "release:file"), output.log)
    }

    @Test
    fun `a source that starts in time is not given up`() {
        val output = FakeOutput()
        val worker = HeldWorker()
        sound(output, worker).play(SoundRef.SystemDefault, 1f)
        worker.runAll()
        assertTrue(timers.active.isEmpty())
    }

    @Test
    fun `stopping while a source is being prepared lets nothing start`() {
        val output = FakeOutput()
        val worker = HeldWorker()
        val chain = sound(output, worker)
        chain.play(SoundRef.SystemDefault, 1f)
        worker.runNext()
        chain.stop()
        worker.runNext()
        assertEquals(listOf("prepare:file", "release:file"), output.log)
        assertTrue(timers.active.isEmpty())
    }

    @Test
    fun `stopping while a source that cannot be played is being prepared moves on to nothing`() {
        val output = FakeOutput(broken = setOf("file"))
        val worker = HeldWorker()
        val chain = sound(output, worker)
        chain.play(SoundRef.SystemDefault, 1f)
        worker.runNext()
        chain.stop()
        worker.runNext()
        assertEquals(listOf("prepare:file"), output.log)
        assertEquals(0, exhausted)
    }

    @Test
    fun `a source that stops working later is replaced, at the current gain`() {
        val output = FakeOutput()
        val chain = sound(output)
        chain.play(SoundRef.SystemDefault, 0f)
        chain.setGain(0.25f)
        output.failures.single().invoke()
        assertEquals(
            listOf("prepare:file", "start:file:0.0", "gain:file:0.25", "release:file", "prepare:system", "start:system:0.25"),
            output.log,
        )
    }

    @Test
    fun `a change of gain while nothing plays yet counts from the start`() {
        val output = FakeOutput()
        val worker = HeldWorker()
        val chain = sound(output, worker)
        chain.play(SoundRef.SystemDefault, 0f)
        chain.setGain(0.25f)
        worker.runAll()
        assertEquals(listOf("prepare:file", "start:file:0.25"), output.log)
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
    fun `stopping releases what plays, and nothing is left to fall back on`() {
        val output = FakeOutput()
        val chain = sound(output)
        chain.play(SoundRef.SystemDefault, 1f)
        chain.stop()
        assertEquals("release:file", output.log.last())
        chain.stop()
        assertEquals(1, output.log.count { it == "release:file" })
    }

    @Test
    fun `a player that cannot be released does not get in the way`() {
        val output = FakeOutput()
        val chain = sound(output)
        chain.play(SoundRef.SystemDefault, 1f)
        output.failToRelease = true
        chain.stop()
        assertEquals("release:file", output.log.last())
    }

    @Test
    fun `playing again starts over with the first source`() {
        val output = FakeOutput()
        val chain = sound(output)
        chain.play(SoundRef.SystemDefault, 1f)
        output.failures.single().invoke()
        chain.play(SoundRef.SystemDefault, 1f)
        assertEquals("start:file:1.0", output.log.last())
        assertEquals("release:system", output.log[output.log.size - 3])
    }

    @Test
    fun `the default limit can be used`() {
        val output = FakeOutput()
        FallbackSound({ listOf("file") }, "last", output, { it() }, timers, {}).play(SoundRef.SystemDefault, 1f)
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

    override fun playLastResort(gain: Float) {
        log += "playLastResort:$gain"
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
    var failOnStop = false

    override fun start() {
        vibrating = true
        starts++
    }

    override fun stop() {
        check(!failOnStop) { "vibrator failed" }
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
    private val hardStopped = mutableListOf<List<Long>>()
    private val ringer: AlarmRinger = AlarmRinger(
        sound,
        vibration,
        timers,
        wakeLock,
        { timedOut += it },
        { list ->
            order += "changed"
            changes += list.map { it.id }
            if (serviceUp) ringer.foregroundSettled()
        },
        { list -> hardStopped += list.map { it.id } },
        { order += "late" },
    )

    /** Android lets the service start at once. These tests are about what comes after that, unless they say otherwise. */
    private var serviceUp = true
    private val order = mutableListOf<String>()
    private val foregroundWait = Duration.ofSeconds(3)

    private val threeMinutes = Duration.ofMinutes(3)
    private val rampStep = Duration.ofMillis(250)
    private val hardStopMargin = Duration.ofSeconds(30)

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
    fun `the sound waits for the service, and nothing plays until it has settled`() {
        serviceUp = false
        ringer.join(alarm(1, vibrate = true))
        assertTrue(sound.log.isEmpty())
        assertEquals(listOf(listOf(1L)), changes)
        assertTrue(vibration.vibrating)
        assertTrue(timers.active.any { it.delay == threeMinutes })
        ringer.foregroundSettled()
        assertEquals(listOf("play:SystemDefault:1.0"), sound.log)
        assertTrue(timers.active.none { it.delay == foregroundWait })
    }

    @Test
    fun `the sound starts anyway if the service takes too long, which is reported`() {
        serviceUp = false
        ringer.join(alarm(1))
        timers.elapse(foregroundWait)
        assertEquals(listOf("play:SystemDefault:1.0"), sound.log)
        assertEquals(listOf("changed", "late"), order)
    }

    @Test
    fun `the service settling after that does not start the sound again`() {
        serviceUp = false
        ringer.join(alarm(1))
        timers.elapse(foregroundWait)
        ringer.foregroundSettled()
        assertEquals(1, sound.log.size)
    }

    @Test
    fun `a wait that has been overtaken does nothing, even if its timer fired just before`() {
        serviceUp = false
        ringer.join(alarm(1))
        val wait = timers.active.first { it.delay == foregroundWait }
        ringer.foregroundSettled()
        assertTrue(wait.cancelled)
        wait.block()
        assertEquals(1, sound.log.size)
        assertEquals(listOf("changed"), order)
    }

    @Test
    fun `an alarm that leaves while the sound waits lets nothing play`() {
        serviceUp = false
        ringer.join(alarm(1))
        val wait = timers.active.first { it.delay == foregroundWait }
        ringer.leave(1)
        assertTrue(wait.cancelled)
        ringer.foregroundSettled()
        wait.block()
        assertEquals(listOf("stop"), sound.log)
    }

    @Test
    fun `an alarm that joins while the sound waits does not start it, or take it over`() {
        serviceUp = false
        ringer.join(alarm(1, maxVolume = 0.5f))
        ringer.join(alarm(2, maxVolume = 0.1f))
        assertTrue(sound.log.isEmpty())
        ringer.foregroundSettled()
        assertEquals(listOf("play:SystemDefault:0.5"), sound.log)
    }

    @Test
    fun `a service that settles with nothing waiting does nothing`() {
        ringer.foregroundSettled()
        assertTrue(sound.log.isEmpty())
    }

    @Test
    fun `without anyone to tell, a late service still lets the sound start`() {
        val quiet = AlarmRinger(sound, vibration, timers, wakeLock, {}, {})
        quiet.join(alarm(1))
        timers.elapse(foregroundWait)
        assertEquals(listOf("play:SystemDefault:1.0"), sound.log)
    }

    @Test
    fun `an emergency alarm plays the last resort at full level, after the service like any other`() {
        serviceUp = false
        ringer.join(emergencyAlarm(1, NOW))
        assertTrue(sound.log.isEmpty())
        ringer.foregroundSettled()
        assertEquals(listOf("playLastResort:1.0"), sound.log)
        assertTrue(vibration.vibrating)
    }

    @Test
    fun `an emergency alarm that joins the ringing leaves the sound as it is`() {
        ringer.join(alarm(1))
        ringer.join(emergencyAlarm(2, NOW))
        assertEquals(listOf("play:SystemDefault:1.0"), sound.log)
    }

    @Test
    fun `an emergency alarm is ready to ring, stands for its alarm, and ends with its one timeout`() {
        val alarm = emergencyAlarm(7, NOW)
        assertEquals(7, alarm.id)
        assertEquals(NOW, alarm.triggerAt)
        assertEquals(AlarmState.Ringing, alarm.state)
        assertEquals(EMERGENCY_ALARM_TITLE, alarm.snapshot.reason)
        assertEquals(null, alarm.snapshot.maxVolume)
        assertEquals(AlarmState.Unanswered, transition(alarm.state, AlarmEvent.RingTimeout, AlarmContext(alarm.autoSnoozed, alarm.snapshot)).state)
        assertEquals(AlarmState.Done, transition(alarm.state, AlarmEvent.Dismiss, AlarmContext(alarm.autoSnoozed, alarm.snapshot)).state)
    }

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
    fun `if nothing ends the ringing after the timeout, it stops on its own, without the engine`() {
        ringer.join(alarm(1, vibrate = true))
        ringer.join(alarm(2))
        timers.elapse(threeMinutes)
        assertEquals("play:SystemDefault:1.0", sound.log.last())
        timers.elapse(hardStopMargin)
        assertEquals("stop", sound.log.last())
        assertFalse(vibration.vibrating)
        assertEquals(null, wakeLock.heldFor)
        assertTrue(ringer.ringing().isEmpty())
        assertEquals(emptyList(), changes.last())
        assertEquals(listOf(listOf(1L, 2L)), hardStopped)
    }

    @Test
    fun `the limit does not apply if the timeout has ended the ringing`() {
        ringer.join(alarm(1))
        timers.elapse(threeMinutes)
        val limit = timers.active.single()
        assertEquals(hardStopMargin, limit.delay)
        ringer.leave(1)
        assertTrue(limit.cancelled)
        limit.block()
        assertTrue(hardStopped.isEmpty())
    }

    @Test
    fun `an alarm that joins after the timeout gets its full time`() {
        ringer.join(alarm(1))
        timers.elapse(threeMinutes)
        val limit = timers.active.single()
        ringer.join(alarm(2))
        assertTrue(limit.cancelled)
        limit.block()
        assertTrue(hardStopped.isEmpty())
        assertEquals(listOf(threeMinutes), timers.active.map { it.delay })
    }

    @Test
    fun `a part that fails to stop does not keep the others going, and is reported`() {
        ringer.join(alarm(1, vibrate = true))
        vibration.failOnStop = true
        assertFailsWith<IllegalStateException> { ringer.leave(1) }
        assertEquals("stop", sound.log.last())
        assertEquals(null, wakeLock.heldFor)
        assertEquals(emptyList(), changes.last())
    }

    @Test
    fun `a part that fails to stop does not keep the limit from stopping the rest`() {
        ringer.join(alarm(1, vibrate = true))
        timers.elapse(threeMinutes)
        vibration.failOnStop = true
        assertFailsWith<IllegalStateException> { timers.elapse(hardStopMargin) }
        assertEquals("stop", sound.log.last())
        assertEquals(listOf(listOf(1L)), hardStopped)
    }

    @Test
    fun `without anyone to tell, the limit still stops the ringing`() {
        val quiet = AlarmRinger(sound, vibration, timers, wakeLock, {}, {})
        quiet.join(alarm(1))
        quiet.foregroundSettled()
        timers.elapse(threeMinutes)
        timers.elapse(hardStopMargin)
        assertEquals("stop", sound.log.last())
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
