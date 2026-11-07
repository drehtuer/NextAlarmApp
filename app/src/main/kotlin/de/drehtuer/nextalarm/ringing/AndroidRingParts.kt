package de.drehtuer.nextalarm.ringing

import android.content.ContentResolver
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import de.drehtuer.nextalarm.data.SoundSource
import de.drehtuer.nextalarm.domain.AlarmVibration
import de.drehtuer.nextalarm.domain.RingTimers
import de.drehtuer.nextalarm.domain.RingWakeLock
import de.drehtuer.nextalarm.domain.SoundOutput
import java.io.IOException
import java.time.Duration

private const val TAG = "NextAlarm"

/**
 * Plays alarm sounds with `MediaPlayer`, as `USAGE_ALARM`, so that they pass Do Not Disturb. The volume is only ever
 * the player's own gain: the app never changes the system's stream volume.
 */
class MediaPlayerOutput(
    private val context: Context,
    private val newPlayer: () -> MediaPlayer = ::MediaPlayer,
) : SoundOutput<SoundSource> {
    private var player: MediaPlayer? = null

    override fun start(source: SoundSource, gain: Float, onFailure: () -> Unit): Boolean {
        val candidate = newPlayer()
        return try {
            candidate.setAudioAttributes(ALARM_AUDIO)
            when (source) {
                is SoundSource.Stored -> candidate.setDataSource(source.file.path)
                is SoundSource.System -> candidate.setDataSource(context, source.uri)
                is SoundSource.Bundled -> candidate.setDataSource(context, resourceUri(source.resourceId))
            }
            candidate.isLooping = true
            candidate.setOnErrorListener { _, _, _ ->
                onFailure()
                true
            }
            candidate.prepare()
            // Before it starts, so that a silent or quiet alarm is never loud for a moment.
            candidate.setVolume(gain, gain)
            candidate.start()
            player = candidate
            true
        } catch (failure: IOException) {
            giveUp(candidate, failure)
        } catch (failure: RuntimeException) {
            giveUp(candidate, failure)
        }
    }

    /** The player can be in its error state here, if its error is still on its way to the listener. */
    override fun setGain(gain: Float) {
        try {
            player?.setVolume(gain, gain)
        } catch (failure: IllegalStateException) {
            Log.w(TAG, "Could not set the alarm volume", failure)
        }
    }

    override fun stop() {
        player?.release()
        player = null
    }

    private fun giveUp(candidate: MediaPlayer, failure: Exception): Boolean {
        Log.w(TAG, "Could not play an alarm sound", failure)
        candidate.release()
        return false
    }

    private fun resourceUri(resourceId: Int): Uri = Uri.Builder()
        .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
        .authority(context.packageName)
        .appendPath(resourceId.toString())
        .build()

    private companion object {
        val ALARM_AUDIO: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
    }
}

class AndroidVibration(context: Context) : AlarmVibration {
    private val vibrator = context.getSystemService(VibratorManager::class.java).defaultVibrator

    override fun start() {
        vibrator.vibrate(
            VibrationEffect.createWaveform(PATTERN_MILLIS, REPEAT_FROM_START),
            VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM),
        )
    }

    override fun stop() {
        vibrator.cancel()
    }

    private companion object {
        val PATTERN_MILLIS = longArrayOf(0, 800, 800)
        const val REPEAT_FROM_START = 0
    }
}

/** Runs on the main thread. Its clock stands still while the CPU sleeps, which the wake lock prevents while ringing. */
class HandlerRingTimers(private val handler: Handler = Handler(Looper.getMainLooper())) : RingTimers {
    override fun after(delay: Duration, block: () -> Unit): () -> Unit {
        val runnable = Runnable(block)
        handler.postDelayed(runnable, delay.toMillis())
        return { handler.removeCallbacks(runnable) }
    }
}

class AndroidRingWakeLock(context: Context) : RingWakeLock {
    private val lock = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NextAlarm:ringing")
        .apply { setReferenceCounted(false) }

    override fun hold(atMost: Duration) {
        lock.acquire(atMost.toMillis())
    }

    override fun release() {
        if (lock.isHeld) lock.release()
    }
}
