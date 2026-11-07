package de.drehtuer.nextalarm.ringing

import android.app.Application
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Looper
import android.os.Vibrator
import android.os.VibratorManager
import androidx.test.core.app.ApplicationProvider
import de.drehtuer.nextalarm.data.SoundSource
import de.drehtuer.nextalarm.domain.PreparedSound
import java.io.File
import java.io.IOException
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.ShadowPowerManager
import org.robolectric.shadows.util.DataSource

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MediaPlayerOutputTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val file = File("/sound-1")
    private val fileSource = DataSource.toDataSource(file.path)
    private val systemUri = Uri.parse("content://settings/system/alarm_alert")
    private val players = mutableListOf<MediaPlayer>()
    private val attributes = mutableListOf<AudioAttributes>()
    private var failing = false
    private var failures = 0

    private inner class RecordingPlayer : MediaPlayer() {
        override fun setAudioAttributes(value: AudioAttributes) {
            attributes += value
            super.setAudioAttributes(value)
        }

        override fun setVolume(left: Float, right: Float) {
            check(!failing) { "The player is in its error state" }
            super.setVolume(left, right)
        }
    }

    private val output = MediaPlayerOutput(context) { RecordingPlayer().also { players += it } }

    private var sound: PreparedSound? = null

    private fun play(source: SoundSource, gain: Float = 1f): Boolean {
        sound = output.prepare(source) { failures++ }
        sound?.start(gain)
        return sound != null
    }

    @Before
    fun setUp() {
        ShadowMediaPlayer.addMediaInfo(fileSource, ShadowMediaPlayer.MediaInfo(1000, 0))
    }

    @Test
    fun `a stored file plays in a loop as an alarm`() {
        assertTrue(play(SoundSource.Stored(file)))
        val shadow = shadowOf(players.single())
        assertEquals(ShadowMediaPlayer.State.STARTED, shadow.state)
        assertTrue(players.single().isLooping)
        assertEquals(AudioAttributes.USAGE_ALARM, attributes.single().usage)
    }

    @Test
    fun `the phone's default sound plays from its address`() {
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(context, systemUri), ShadowMediaPlayer.MediaInfo(1000, 0))
        assertTrue(play(SoundSource.System(systemUri)))
        assertEquals(ShadowMediaPlayer.State.STARTED, shadowOf(players.single()).state)
    }

    @Test
    fun `the bundled sound plays from the app's resources`() {
        val uri = Uri.parse("android.resource://${context.packageName}/42")
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(context, uri), ShadowMediaPlayer.MediaInfo(1000, 0))
        assertTrue(play(SoundSource.Bundled(42)))
        assertEquals(ShadowMediaPlayer.State.STARTED, shadowOf(players.single()).state)
    }

    @Test
    fun `the gain is set before the sound starts, and a gain of zero is silent`() {
        assertTrue(play(SoundSource.Stored(file), gain = 0f))
        val shadow = shadowOf(players.single())
        assertEquals(0f, shadow.leftVolume)
        assertEquals(0f, shadow.rightVolume)
    }

    @Test
    fun `a change of gain goes to the player`() {
        play(SoundSource.Stored(file))
        sound?.setGain(0.25f)
        assertEquals(0.25f, shadowOf(players.single()).leftVolume)
        assertEquals(0.25f, shadowOf(players.single()).rightVolume)
    }

    @Test
    fun `preparing does not start anything, and releasing what was not started is fine`() {
        val prepared = output.prepare(SoundSource.Stored(file)) {}
        assertEquals(ShadowMediaPlayer.State.PREPARED, shadowOf(players.single()).state)
        prepared?.release()
        assertEquals(ShadowMediaPlayer.State.END, shadowOf(players.single()).state)
    }

    @Test
    fun `a player in its error state does not bring the app down`() {
        play(SoundSource.Stored(file))
        failing = true
        sound?.setGain(0.25f)
        assertEquals(1f, shadowOf(players.single()).leftVolume)
    }

    @Test
    fun `a source that cannot be read is given up, and its player released`() {
        val broken = DataSource.toDataSource("/broken")
        ShadowMediaPlayer.addException(broken, IOException("unreadable"))
        assertFalse(play(SoundSource.Stored(File("/broken"))))
        assertEquals(ShadowMediaPlayer.State.END, shadowOf(players.single()).state)
    }

    @Test
    fun `a source the player refuses is given up, and its player released`() {
        // Robolectric's player refuses a source nobody told it about, as a real one does with an invalid argument.
        assertFalse(play(SoundSource.Stored(File("/unknown"))))
        assertEquals(ShadowMediaPlayer.State.END, shadowOf(players.single()).state)
    }

    @Test
    fun `a failure during playback is reported`() {
        play(SoundSource.Stored(file))
        shadowOf(players.single()).invokeErrorListener(1, 0)
        assertEquals(1, failures)
    }

    @Test
    fun `releasing ends the player`() {
        play(SoundSource.Stored(file))
        sound?.release()
        assertEquals(ShadowMediaPlayer.State.END, shadowOf(players.single()).state)
    }

    @Test
    fun `the system's stream volumes are never changed`() {
        val audio = context.getSystemService(AudioManager::class.java)
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 2, 0)
        play(SoundSource.Stored(file), gain = 0f)
        sound?.setGain(1f)
        sound?.release()
        assertEquals(2, audio.getStreamVolume(AudioManager.STREAM_ALARM))
    }

    @Test
    fun `the default player can be used`() {
        val plain = MediaPlayerOutput(context).prepare(SoundSource.Stored(file)) {}
        assertTrue(plain != null)
        plain.start(1f)
        plain.release()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AndroidRingPartsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `sound work runs on the dispatcher it is given, also when the scope has another one`() {
        val thread = AtomicReference<String>()
        val done = java.util.concurrent.CountDownLatch(1)
        val dispatcher = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "sound-test") }.asCoroutineDispatcher()
        scopedSoundWorker(alarmScope(Dispatchers.Default), dispatcher).run {
            thread.set(Thread.currentThread().name)
            done.countDown()
        }
        assertTrue(done.await(10, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue(thread.get().startsWith("sound-test"), thread.get())
        dispatcher.close()
    }

    private fun vibrator(): Vibrator = context.getSystemService(VibratorManager::class.java).defaultVibrator

    @Test
    fun `the vibration runs until it is stopped`() {
        val vibration = AndroidVibration(context)
        vibration.start()
        assertTrue(shadowOf(vibrator()).isVibrating)
        vibration.stop()
        assertFalse(shadowOf(vibrator()).isVibrating)
    }

    @Test
    fun `a timer runs after its delay`() {
        var ran = false
        HandlerRingTimers().after(Duration.ofMinutes(3)) { ran = true }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(2))
        assertFalse(ran)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(1))
        assertTrue(ran)
    }

    @Test
    fun `a cancelled timer does not run`() {
        var ran = false
        val cancel = HandlerRingTimers().after(Duration.ofMinutes(3)) { ran = true }
        cancel()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(4))
        assertFalse(ran)
    }

    @Test
    fun `the wake lock keeps the CPU awake until it is released`() {
        val lock = AndroidRingWakeLock(context)
        lock.hold(Duration.ofMinutes(4))
        val held = ShadowPowerManager.getLatestWakeLock()
        assertTrue(held.isHeld)
        lock.release()
        assertFalse(held.isHeld)
    }

    @Test
    fun `the wake lock says who holds it`() {
        AndroidRingWakeLock(context).hold(Duration.ofMinutes(1))
        assertEquals("NextAlarm:ringing", shadowOf(ShadowPowerManager.getLatestWakeLock()).tag)
        AndroidRingWakeLock(context, "NextAlarm:wakeup").hold(Duration.ofMinutes(1))
        assertEquals("NextAlarm:wakeup", shadowOf(ShadowPowerManager.getLatestWakeLock()).tag)
    }

    @Test
    fun `holding the wake lock twice takes one release, and releasing it again does nothing`() {
        val lock = AndroidRingWakeLock(context)
        lock.hold(Duration.ofMinutes(4))
        lock.hold(Duration.ofMinutes(4))
        val held = ShadowPowerManager.getLatestWakeLock()
        lock.release()
        assertFalse(held.isHeld)
        lock.release()
        assertFalse(held.isHeld)
    }
}
