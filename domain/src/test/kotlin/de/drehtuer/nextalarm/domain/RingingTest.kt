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
    fun `with a maximum the player is at full level too, as the stream sets how loud that is`() {
        assertEquals(1f, soundGain(Duration.ZERO, 0, 0.5f))
    }

    @Test
    fun `a ramp rises from silent to the top and stays there`() {
        assertEquals(0f, soundGain(Duration.ZERO, 10, null))
        assertEquals(0.5f, soundGain(Duration.ofSeconds(5), 10, null))
        assertEquals(1f, soundGain(Duration.ofSeconds(10), 10, null))
        assertEquals(1f, soundGain(Duration.ofSeconds(11), 10, null))
    }

    @Test
    fun `with a maximum a ramp still rises from 0 to 1`() {
        assertEquals(0.5f, soundGain(Duration.ofSeconds(5), 10, 0.5f))
        assertEquals(1f, soundGain(Duration.ofSeconds(20), 10, 0.5f))
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
    fun `a player that fails to start and to release does not get in the way either`() {
        val output = FakeOutput().apply {
            failToStart = setOf("file")
            failToRelease = true
        }
        sound(output).play(SoundRef.SystemDefault, 1f)
        assertEquals("start:system:1.0", output.log.last())
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

private class FakeStream(var level: Int = 3, private val top: Int = 7) : AlarmStream {
    val log = mutableListOf<String>()
    var failure: RuntimeException? = null

    /** As Android does without a service: no effect, and no error. */
    var ignoring = false
    var roundTo: Int? = null

    override fun current() = level
    override fun min() = 1
    override fun max() = top

    override fun set(level: Int) {
        failure?.let { throw it }
        log += "set:$level"
        if (!ignoring) this.level = roundTo ?: level
    }
}

private class FakeSavedVolume : SavedAlarmVolume {
    var level: Int? = null
    var writes = 0
    var failing = false
    var failingToRead = false
    var log = mutableListOf<String>()

    override fun load(): Int? {
        check(!failingToRead) { "storage failed" }
        return level
    }

    override fun save(level: Int) {
        check(!failing) { "storage failed" }
        log += "save:$level"
        writes++
        this.level = level
    }

    override fun clear() {
        check(!failing) { "storage failed" }
        level = null
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

class CallWatchTest {
    private val calls = FakeCalls()
    private val problems = mutableListOf<String>()
    private val watch = CallWatch(calls) { message, _ -> problems += message }

    @Test
    fun `it says what the phone says`() {
        assertFalse(watch.active())
        calls.isActive = true
        assertTrue(watch.active())
        assertTrue(problems.isEmpty())
    }

    @Test
    fun `if there is no telling, there is no call, so that an alarm is heard`() {
        calls.isActive = true
        calls.failure = SecurityException("Not allowed")
        assertFalse(watch.active())
        assertEquals(listOf("Could not find out whether a call is active"), problems)
    }

    @Test
    fun `it tells about changes, until it is stopped`() {
        val heard = mutableListOf<Boolean>()
        val stop = watch.watch { heard += it }
        calls.change(true)
        stop()
        calls.change(false)
        assertEquals(listOf(true), heard)
    }

    @Test
    fun `not being able to watch is reported, and the result can still be called`() {
        calls.failToWatch = IllegalStateException("No service")
        watch.watch {}()
        assertEquals(listOf("Could not watch for calls"), problems)
    }
}

class AudioFocusHoldTest {
    private val focus = FakeFocus()
    private val problems = mutableListOf<String>()
    private val hold = AudioFocusHold(focus) { message, _ -> problems += message }
    private var lost = 0

    @Test
    fun `it asks once for as long as it has it`() {
        hold.take { lost++ }
        hold.take { lost++ }
        hold.retry()
        assertEquals(1, focus.requests)
        assertTrue(problems.isEmpty())
    }

    @Test
    fun `releasing gives it up, only once, and only if it was taken`() {
        hold.release()
        assertEquals(0, focus.abandons)
        hold.take {}
        hold.release()
        hold.release()
        assertEquals(1, focus.abandons)
    }

    @Test
    fun `it can be taken again after releasing`() {
        hold.take {}
        hold.release()
        hold.take {}
        assertEquals(2, focus.requests)
    }

    @Test
    fun `if it is denied that is reported, and the next take or retry asks again`() {
        focus.answer = false
        hold.take {}
        assertEquals(listOf("The audio focus was denied: other audio goes on"), problems)
        hold.take {}
        assertEquals(2, focus.requests)
        focus.answer = true
        hold.retry()
        assertEquals(3, focus.requests)
        hold.retry()
        assertEquals(3, focus.requests)
    }

    @Test
    fun `a retry with nothing wanted does not ask`() {
        hold.retry()
        assertEquals(0, focus.requests)
    }

    @Test
    fun `a request that throws counts as denied`() {
        focus.failure = SecurityException("Not allowed")
        hold.take {}
        assertTrue("Could not ask for the audio focus" in problems)
    }

    @Test
    fun `giving it up can fail without any consequence`() {
        hold.take {}
        focus.failure = IllegalStateException("No service")
        hold.release()
        assertEquals(listOf("Could not give up the audio focus"), problems)
    }

    @Test
    fun `losing it for good is told, and gives it up`() {
        hold.take { lost++ }
        focus.onLost()
        assertEquals(1, lost)
        assertEquals(1, focus.abandons)
    }

    @Test
    fun `losing it after it was given up is not told`() {
        hold.take { lost++ }
        hold.release()
        focus.onLost()
        assertEquals(0, lost)
    }
}

class WantsVibrationTest {
    private fun wants(vibrate: Boolean, maxVolume: Float?, inCall: Boolean) =
        wantsVibration(snapshot(vibrate = vibrate).copy(maxVolume = maxVolume), inCall)

    @Test
    fun `outside a call it follows the setting`() {
        assertTrue(wants(vibrate = true, maxVolume = null, inCall = false))
        assertFalse(wants(vibrate = false, maxVolume = null, inCall = false))
        assertFalse(wants(vibrate = false, maxVolume = 0f, inCall = false))
    }

    @Test
    fun `during a call it vibrates even with the setting off`() {
        assertTrue(wants(vibrate = false, maxVolume = null, inCall = true))
        assertTrue(wants(vibrate = false, maxVolume = 0.5f, inCall = true))
        assertTrue(wants(vibrate = true, maxVolume = null, inCall = true))
    }

    @Test
    fun `a silent alarm that says no vibration never vibrates, not even during a call`() {
        assertFalse(wants(vibrate = false, maxVolume = 0f, inCall = true))
    }

    @Test
    fun `a silent alarm that asks for vibration gets it`() {
        assertTrue(wants(vibrate = true, maxVolume = 0f, inCall = true))
    }
}

class StreamLevelTest {
    @Test
    fun `a maximum becomes the nearest step`() {
        assertEquals(4, streamLevel(0.5f, 1, 7))
        assertEquals(3, streamLevel(0.4f, 1, 7))
        assertEquals(5, streamLevel(0.7f, 1, 7))
    }

    @Test
    fun `100 percent is the top step`() {
        assertEquals(7, streamLevel(1f, 1, 7))
    }

    @Test
    fun `a small maximum is at least the lowest step, which can be heard`() {
        assertEquals(1, streamLevel(0.01f, 0, 7))
        assertEquals(2, streamLevel(0.01f, 2, 7))
    }

    @Test
    fun `a stream with fewer steps than its minimum still gets a level`() {
        assertEquals(1, streamLevel(0.5f, 1, 0))
    }
}

class AlarmVolumeKeeperTest {
    private val stream = FakeStream(level = 2)
    private val saved = FakeSavedVolume()
    private val problems = mutableListOf<String>()
    private val keeper = AlarmVolumeKeeper(stream, saved) { message, _ -> problems += message }

    @Test
    fun `a maximum sets the stream, after the user's volume is stored`() {
        saved.log = stream.log
        keeper.raise(0.5f)
        assertEquals(4, stream.level)
        assertEquals(2, saved.level)
        assertEquals(listOf("save:2", "set:4"), stream.log)
    }

    @Test
    fun `no maximum never touches the stream or the store`() {
        keeper.raise(null)
        keeper.restore()
        assertTrue(stream.log.isEmpty())
        assertEquals(0, saved.writes)
        assertEquals(2, stream.level)
    }

    @Test
    fun `a maximum of zero never touches the stream or the store, as someone may be asleep next to the phone`() {
        keeper.raise(0f)
        keeper.restore()
        assertTrue(stream.log.isEmpty())
        assertEquals(0, saved.writes)
        assertEquals(2, stream.level)
    }

    @Test
    fun `the user's volume is only stored once, so that a raised level is never taken for it`() {
        keeper.raise(0.5f)
        keeper.raise(1f)
        assertEquals(2, saved.level)
        assertEquals(1, saved.writes)
        keeper.restore()
        assertEquals(2, stream.level)
    }

    @Test
    fun `restoring puts the user's volume back and forgets it`() {
        keeper.raise(0.5f)
        keeper.restore()
        assertEquals(2, stream.level)
        assertEquals(null, saved.level)
    }

    @Test
    fun `restoring twice is harmless`() {
        keeper.raise(0.5f)
        keeper.restore()
        stream.level = 6
        keeper.restore()
        assertEquals(6, stream.level)
    }

    @Test
    fun `after a crash a new process restores what was stored`() {
        keeper.raise(0.5f)
        val after = AlarmVolumeKeeper(stream, saved) { message, _ -> problems += message }
        after.restore()
        assertEquals(2, stream.level)
        assertEquals(null, saved.level)
    }

    @Test
    fun `after a crash an alarm that rings again doesn't take the raised level for the user's own`() {
        keeper.raise(0.5f)
        val after = AlarmVolumeKeeper(stream, saved) { message, _ -> problems += message }
        after.raise(0.5f)
        assertEquals(2, saved.level)
        after.restore()
        assertEquals(2, stream.level)
    }

    @Test
    fun `a stream that refuses is reported, and nothing is thrown`() {
        stream.failure = SecurityException("Not allowed")
        keeper.raise(0.5f)
        assertEquals(listOf("The alarm volume could not be set"), problems)
    }

    @Test
    fun `setting a volume that Android ignores is reported`() {
        stream.ignoring = true
        keeper.raise(0.5f)
        assertEquals(listOf("Android did not set the alarm volume to 4"), problems)
    }

    @Test
    fun `a volume that cannot be stored is reported, the stream is still set and put back from memory`() {
        saved.failing = true
        keeper.raise(0.5f)
        assertEquals(4, stream.level)
        assertTrue("The alarm volume could not be stored" in problems)
        keeper.raise(1f)
        saved.failing = false
        keeper.restore()
        assertEquals(2, stream.level)
    }

    @Test
    fun `a store that cannot be read is reported, and the alarm still gets its volume`() {
        saved.failingToRead = true
        keeper.raise(0.5f)
        assertEquals(4, stream.level)
        assertTrue("The stored alarm volume could not be read" in problems)
    }

    @Test
    fun `a stream that refuses to go back is reported, and the volume is kept for the next try`() {
        keeper.raise(0.5f)
        stream.failure = SecurityException("Not allowed")
        keeper.restore()
        assertEquals(listOf("The alarm volume could not be put back"), problems)
        assertEquals(2, saved.level)
        stream.failure = null
        keeper.restore()
        assertEquals(2, stream.level)
        assertEquals(null, saved.level)
    }

    @Test
    fun `going back without any effect, as Android does without a service, keeps the volume for the next try`() {
        keeper.raise(0.5f)
        stream.ignoring = true
        keeper.restore()
        assertEquals(4, stream.level)
        assertEquals(2, saved.level)
        assertEquals(listOf("Android did not put the alarm volume back to 2: this is tried again later"), problems)
        stream.ignoring = false
        keeper.restore()
        assertEquals(2, stream.level)
        assertEquals(null, saved.level)
    }

    @Test
    fun `a stream that is already at the user's volume needs nothing, and that is no problem`() {
        keeper.raise(0.5f)
        stream.level = 2
        stream.ignoring = true
        keeper.restore()
        assertTrue(problems.isEmpty())
        assertEquals(null, saved.level)
    }

    @Test
    fun `a stream the system rounded when going back counts as done`() {
        keeper.raise(0.5f)
        stream.roundTo = 3
        keeper.restore()
        assertEquals(3, stream.level)
        assertEquals(null, saved.level)
    }

    @Test
    fun `a store that cannot forget is reported`() {
        keeper.raise(0.5f)
        saved.failing = true
        keeper.restore()
        assertEquals(2, stream.level)
        assertEquals(listOf("The alarm volume could not be put back"), problems)
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
    private val stream = FakeStream()
    private val savedVolume = FakeSavedVolume()
    private val problems = mutableListOf<String>()
    private val calls = FakeCalls()
    private val focus = FakeFocus()
    private val noteProblem: AudioProblem = { message, _ -> problems += message }
    private val audio = RingAudio(
        AlarmVolumeKeeper(stream, savedVolume, noteProblem),
        AudioFocusHold(focus, noteProblem),
        CallWatch(calls, noteProblem),
        noteProblem,
    )
    private val ringer: AlarmRinger = AlarmRinger(
        sound,
        vibration,
        timers,
        wakeLock,
        audio,
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
        assertEquals(listOf("play:SystemDefault:1.0"), sound.log)
        assertEquals(4, stream.level)
    }

    @Test
    fun `an alarm with a maximum sets the stream, before the sound and only once the service is up`() {
        serviceUp = false
        ringer.join(alarm(maxVolume = 0.5f))
        assertEquals(3, stream.level)
        ringer.foregroundSettled()
        assertEquals(4, stream.level)
        assertEquals(3, savedVolume.level)
        assertEquals(listOf("play:SystemDefault:1.0"), sound.log)
    }

    @Test
    fun `an alarm with no maximum never touches the stream`() {
        ringer.join(alarm(maxVolume = null))
        ringer.leave(1)
        assertTrue(stream.log.isEmpty())
        assertEquals(0, savedVolume.writes)
        assertEquals(listOf("play:SystemDefault:1.0", "stop"), sound.log)
    }

    @Test
    fun `a silent alarm never touches the stream, from start to end, and stays silent`() {
        ringer.join(alarm(maxVolume = 0f))
        ringer.leave(1)
        assertTrue(stream.log.isEmpty())
        assertEquals(0, savedVolume.writes)
        assertEquals(listOf("play:SystemDefault:0.0", "stop"), sound.log)
    }

    @Test
    fun `a silent alarm that another one joins stays silent, and the stream stays alone`() {
        ringer.join(alarm(1, maxVolume = 0f))
        ringer.join(alarm(2, maxVolume = 1f))
        assertTrue(stream.log.isEmpty())
        assertEquals(listOf("play:SystemDefault:0.0"), sound.log)
    }

    @Test
    fun `a second alarm keeps the level of the first, and does not store the raised one as the user's`() {
        ringer.join(alarm(1, maxVolume = 0.5f))
        ringer.join(alarm(2, maxVolume = 1f))
        assertEquals(4, stream.level)
        assertEquals(3, savedVolume.level)
        assertEquals(1, savedVolume.writes)
    }

    @Test
    fun `the volume only goes back when the last alarm has left`() {
        ringer.join(alarm(1, maxVolume = 0.5f))
        ringer.join(alarm(2))
        ringer.leave(1)
        assertEquals(4, stream.level)
        ringer.leave(2)
        assertEquals(3, stream.level)
        assertEquals(null, savedVolume.level)
    }

    @Test
    fun `the volume goes back when the ringer stops on its own`() {
        ringer.join(alarm(1, maxVolume = 0.5f))
        timers.elapse(threeMinutes)
        timers.elapse(hardStopMargin)
        assertEquals(3, stream.level)
    }

    @Test
    fun `an emergency alarm leaves the stream alone, and a saved volume goes back when it ends`() {
        savedVolume.level = 2
        ringer.join(emergencyAlarm(1, NOW))
        assertEquals(3, stream.level)
        assertEquals(listOf("playLastResort:1.0"), sound.log)
        ringer.leave(1)
        assertEquals(2, stream.level)
    }

    @Test
    fun `a volume that cannot be set does not stop the alarm`() {
        stream.failure = SecurityException("Not allowed")
        ringer.join(alarm(maxVolume = 0.5f))
        assertEquals(listOf("play:SystemDefault:1.0"), sound.log)
        assertEquals(listOf("The alarm volume could not be set"), problems)
    }

    @Test
    fun `a volume that cannot go back does not get in the way of the rest of the stop`() {
        ringer.join(alarm(maxVolume = 0.5f, vibrate = true))
        stream.failure = SecurityException("Not allowed")
        ringer.leave(1)
        assertFalse(vibration.vibrating)
        assertEquals(null, wakeLock.heldFor)
    }

    @Test
    fun `when nothing rings, a volume left behind by a crash goes back`() {
        savedVolume.level = 2
        ringer.restoreVolumeIfIdle()
        assertEquals(2, stream.level)
        assertEquals(null, savedVolume.level)
    }

    @Test
    fun `while an alarm rings, the volume stays until it ends`() {
        savedVolume.level = 2
        ringer.join(alarm(maxVolume = 0.5f))
        ringer.restoreVolumeIfIdle()
        assertEquals(4, stream.level)
        assertEquals(2, savedVolume.level)
        ringer.leave(1)
        assertEquals(2, stream.level)
    }

    @Test
    fun `with nothing left behind, nothing rings and the stream stays alone`() {
        ringer.restoreVolumeIfIdle()
        assertTrue(stream.log.isEmpty())
    }

    private val callCheck = Duration.ofSeconds(5)

    @Test
    fun `the sound asks for the focus, and gives it up when the alarm ends`() {
        ringer.join(alarm())
        assertEquals(1, focus.requests)
        assertEquals(0, focus.abandons)
        ringer.leave(1)
        assertEquals(1, focus.abandons)
    }

    @Test
    fun `a second alarm does not ask for the focus again, and it is only given up with the last`() {
        ringer.join(alarm(1))
        ringer.join(alarm(2))
        assertEquals(1, focus.requests)
        ringer.leave(1)
        assertEquals(0, focus.abandons)
        ringer.leave(2)
        assertEquals(1, focus.abandons)
    }

    @Test
    fun `the focus waits for the service, which Android demands`() {
        serviceUp = false
        ringer.join(alarm())
        assertEquals(0, focus.requests)
        ringer.foregroundSettled()
        assertEquals(1, focus.requests)
    }

    @Test
    fun `focus that is denied does not stop the sound`() {
        focus.answer = false
        ringer.join(alarm())
        assertEquals(listOf("play:SystemDefault:1.0"), sound.log)
    }

    @Test
    fun `focus that fails does not stop the sound`() {
        focus.failure = SecurityException("Not allowed")
        ringer.join(alarm())
        assertEquals(listOf("play:SystemDefault:1.0"), sound.log)
    }

    @Test
    fun `a silent alarm doesn't ask for the focus, so that it pauses nothing`() {
        ringer.join(alarm(maxVolume = 0f))
        ringer.leave(1)
        assertEquals(0, focus.requests)
        assertEquals(0, focus.abandons)
    }

    @Test
    fun `losing the focus does not stop the sound`() {
        ringer.join(alarm())
        focus.onLost()
        assertEquals(listOf("play:SystemDefault:1.0"), sound.log)
    }

    @Test
    fun `an alarm during a call has no sound and vibrates, with the vibration setting off`() {
        calls.isActive = true
        ringer.join(alarm(vibrate = false, maxVolume = 0.5f))
        assertTrue(sound.log.isEmpty())
        assertTrue(vibration.vibrating)
        assertEquals(0, focus.requests)
        assertTrue(stream.log.isEmpty())
    }

    @Test
    fun `an alarm during a call still times out as normal`() {
        calls.isActive = true
        ringer.join(alarm())
        timers.elapse(threeMinutes)
        assertEquals(listOf(listOf(1L)), timedOut)
        ringer.leave(1)
        assertFalse(vibration.vibrating)
        assertTrue(calls.watching.isEmpty())
    }

    @Test
    fun `when the call ends the sound starts, and the vibration follows the setting again`() {
        calls.isActive = true
        ringer.join(alarm(vibrate = false, maxVolume = 0.5f))
        calls.change(false)
        assertEquals(listOf("play:SystemDefault:1.0"), sound.log)
        assertFalse(vibration.vibrating)
        assertEquals(1, focus.requests)
        assertEquals(4, stream.level)
    }

    @Test
    fun `when the call ends an alarm that asks for vibration goes on vibrating`() {
        calls.isActive = true
        ringer.join(alarm(vibrate = true))
        assertEquals(1, vibration.starts)
        calls.change(false)
        assertTrue(vibration.vibrating)
        assertEquals(1, vibration.starts)
    }

    @Test
    fun `when a call starts the sound stops, it vibrates, and the focus goes`() {
        ringer.join(alarm(vibrate = false))
        calls.change(true)
        assertEquals(listOf("play:SystemDefault:1.0", "stop"), sound.log)
        assertTrue(vibration.vibrating)
        assertEquals(1, focus.abandons)
    }

    @Test
    fun `a call that comes and goes while it rings brings the sound back`() {
        ringer.join(alarm(vibrate = false))
        calls.change(true)
        calls.change(false)
        assertEquals(listOf("play:SystemDefault:1.0", "stop", "play:SystemDefault:1.0"), sound.log)
        assertFalse(vibration.vibrating)
        assertEquals(2, focus.requests)
    }

    @Test
    fun `a call that starts stops the ramp, which starts over when it ends`() {
        ringer.join(alarm(rampSeconds = 1))
        val step = timers.active.first { it.delay == rampStep }
        calls.change(true)
        assertTrue(step.cancelled)
        step.block()
        assertEquals(listOf("play:SystemDefault:0.0", "stop"), sound.log)
        calls.change(false)
        assertEquals("play:SystemDefault:0.0", sound.log.last())
        timers.elapse(rampStep)
        assertEquals("gain:0.25", sound.log.last())
    }

    @Test
    fun `the stream keeps its level through a call, and goes back at the end`() {
        ringer.join(alarm(maxVolume = 0.5f))
        calls.change(true)
        assertEquals(4, stream.level)
        calls.change(false)
        assertEquals(3, savedVolume.level)
        ringer.leave(1)
        assertEquals(3, stream.level)
    }

    @Test
    fun `an alarm that rang through a whole call never changed the stream`() {
        calls.isActive = true
        ringer.join(alarm(maxVolume = 0.5f))
        ringer.leave(1)
        assertTrue(stream.log.isEmpty())
    }

    @Test
    fun `if the phone can't say whether a call is active, it rings`() {
        calls.failure = SecurityException("Not allowed")
        ringer.join(alarm())
        assertEquals(listOf("play:SystemDefault:1.0"), sound.log)
        assertFalse(vibration.vibrating)
    }

    @Test
    fun `if calls can't be watched, it rings`() {
        calls.failToWatch = IllegalStateException("No service")
        ringer.join(alarm())
        assertEquals(listOf("play:SystemDefault:1.0"), sound.log)
        ringer.leave(1)
    }

    @Test
    fun `a silent alarm that says no vibration never vibrates in a call that was there first`() {
        calls.isActive = true
        ringer.join(alarm(vibrate = false, maxVolume = 0f))
        assertEquals(0, vibration.starts)
        calls.change(false)
        calls.change(true)
        assertEquals(0, vibration.starts)
    }

    @Test
    fun `a silent alarm that says no vibration never vibrates in a call that starts later`() {
        ringer.join(alarm(vibrate = false, maxVolume = 0f))
        calls.change(true)
        assertEquals(0, vibration.starts)
    }

    @Test
    fun `during a call any alarm that is not silent is enough to vibrate`() {
        calls.isActive = true
        ringer.join(alarm(1, vibrate = false, maxVolume = 0f))
        assertEquals(0, vibration.starts)
        ringer.join(alarm(2, vibrate = false))
        assertTrue(vibration.vibrating)
        ringer.leave(2)
        assertFalse(vibration.vibrating)
    }

    @Test
    fun `a call in the time the sound waits for the service only counts when it is up`() {
        serviceUp = false
        ringer.join(alarm())
        calls.change(true)
        assertTrue(vibration.vibrating)
        ringer.foregroundSettled()
        assertTrue(sound.log.isEmpty())
        calls.change(false)
        assertEquals(listOf("play:SystemDefault:1.0"), sound.log)
    }

    @Test
    fun `a call that ends in the time the sound waits for the service does not start it early`() {
        serviceUp = false
        calls.isActive = true
        ringer.join(alarm())
        calls.change(false)
        assertTrue(sound.log.isEmpty())
        ringer.foregroundSettled()
        assertEquals(listOf("play:SystemDefault:1.0"), sound.log)
    }

    @Test
    fun `after a call the sound is that of the first alarm, also if it has left`() {
        ringer.join(alarm(1, maxVolume = 0.5f))
        ringer.join(alarm(2, maxVolume = 1f))
        calls.change(true)
        ringer.leave(1)
        calls.change(false)
        assertEquals(4, stream.level)
        assertEquals("play:SystemDefault:1.0", sound.log.last())
    }

    @Test
    fun `an emergency alarm stays quiet during a call and plays the last resort after it`() {
        calls.isActive = true
        ringer.join(emergencyAlarm(1, NOW))
        assertTrue(sound.log.isEmpty())
        assertTrue(vibration.vibrating)
        calls.change(false)
        assertEquals(listOf("playLastResort:1.0"), sound.log)
    }

    @Test
    fun `the same news twice changes nothing`() {
        ringer.join(alarm())
        calls.change(false)
        assertEquals(listOf("play:SystemDefault:1.0"), sound.log)
        calls.change(true)
        calls.change(true)
        assertEquals(listOf("play:SystemDefault:1.0", "stop"), sound.log)
    }

    @Test
    fun `calls are only watched while it rings, and what comes late changes nothing`() {
        ringer.join(alarm())
        assertEquals(1, calls.watching.size)
        val late = calls.watching.single()
        ringer.leave(1)
        assertTrue(calls.watching.isEmpty())
        late(true)
        assertFalse(vibration.vibrating)
        assertEquals(listOf("play:SystemDefault:1.0", "stop"), sound.log)
    }

    @Test
    fun `if the end of a call is never told, the sound still comes`() {
        calls.isActive = true
        ringer.join(alarm())
        timers.elapse(callCheck)
        assertTrue(sound.log.isEmpty())
        calls.isActive = false
        timers.elapse(callCheck)
        assertEquals(listOf("play:SystemDefault:1.0"), sound.log)
        assertTrue(timers.active.none { it.delay == callCheck })
    }

    @Test
    fun `there is no looking for calls outside one, and it ends with the alarm`() {
        ringer.join(alarm())
        assertTrue(timers.active.none { it.delay == callCheck })
        calls.change(true)
        val check = timers.active.single { it.delay == callCheck }
        ringer.leave(1)
        assertTrue(check.cancelled)
        check.block()
        assertTrue(timers.active.none { it.delay == callCheck })
    }

    @Test
    fun `a check that comes too late does nothing`() {
        ringer.join(alarm())
        calls.change(true)
        val check = timers.active.single { it.delay == callCheck }
        calls.change(false)
        val sounds = sound.log.size
        calls.isActive = true
        check.block()
        assertEquals(sounds, sound.log.size)
    }

    @Test
    fun `a vibrator that fails at a call does not keep the sound from following`() {
        ringer.join(alarm(vibrate = false))
        calls.change(true)
        vibration.failOnStop = true
        calls.change(false)
        assertEquals("play:SystemDefault:1.0", sound.log.last())
        assertEquals(listOf("The vibration could not follow the call"), problems)
    }

    @Test
    fun `a sound that can't stop or start around a call is reported, and thrown nowhere`() {
        val broken = object : AlarmSound by sound {
            override fun play(sound: SoundRef, gain: Float) = error("No player")
            override fun stop() = error("No player")
        }
        val alone = AlarmRinger(broken, vibration, timers, wakeLock, audio, {}, {})
        calls.isActive = true
        alone.join(alarm())
        alone.foregroundSettled()
        calls.change(false)
        calls.change(true)
        assertEquals(
            listOf("The sound could not start after a call", "The sound could not be stopped for a call"),
            problems,
        )
    }

    @Test
    fun `a service that settles with nothing waiting does nothing`() {
        ringer.foregroundSettled()
        assertTrue(sound.log.isEmpty())
    }

    @Test
    fun `without anyone to tell, a late service still lets the sound start`() {
        val quiet = AlarmRinger(sound, vibration, timers, wakeLock, audio, {}, {})
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
        assertEquals(listOf("play:SystemDefault:1.0"), sound.log)
        assertEquals(4, stream.level)
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
    fun `a ramp starts silent and rises to full level, also with a maximum, then stops`() {
        ringer.join(alarm(rampSeconds = 1, maxVolume = 0.8f))
        assertEquals("play:SystemDefault:0.0", sound.log.last())
        repeat(4) { timers.elapse(rampStep) }
        assertEquals(listOf("gain:0.25", "gain:0.5", "gain:0.75", "gain:1.0"), sound.log.drop(1))
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
        val quiet = AlarmRinger(sound, vibration, timers, wakeLock, audio, {}, {})
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
        assertEquals(listOf("play:SystemDefault:1.0"), sound.log)
        assertEquals(4, stream.level)
        timers.elapse(threeMinutes)
        assertEquals(listOf(listOf(2L)), timedOut)
    }
}
