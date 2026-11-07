package de.drehtuer.nextalarm.ringing

import android.content.ContentResolver
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
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
import de.drehtuer.nextalarm.domain.AlarmStream
import de.drehtuer.nextalarm.domain.AlarmVibration
import de.drehtuer.nextalarm.domain.PreparedSound
import de.drehtuer.nextalarm.domain.RingTimers
import de.drehtuer.nextalarm.domain.RingWakeLock
import de.drehtuer.nextalarm.domain.SoundOutput
import java.io.IOException
import java.time.Duration

private const val TAG = "NextAlarm"

/**
 * Plays alarm sounds with `MediaPlayer`, as `USAGE_ALARM`, so that they pass Do Not Disturb. This only sets the player's own
 * gain, while the system's stream volume is up to `AlarmVolumeKeeper`.
 */
class MediaPlayerOutput(
    private val context: Context,
    private val newPlayer: () -> MediaPlayer = ::MediaPlayer,
) : SoundOutput<SoundSource> {
    override fun prepare(source: SoundSource, onFailure: () -> Unit): PreparedSound? {
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
            MediaPlayerSound(candidate)
        } catch (failure: IOException) {
            giveUp(candidate, failure)
        } catch (failure: RuntimeException) {
            giveUp(candidate, failure)
        }
    }

    private fun giveUp(candidate: MediaPlayer, failure: Exception): PreparedSound? {
        Log.w(TAG, "Could not play an alarm sound", failure)
        candidate.release()
        return null
    }

    private fun resourceUri(resourceId: Int): Uri = Uri.Builder()
        .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
        .authority(context.packageName)
        .appendPath(resourceId.toString())
        .build()

    private class MediaPlayerSound(private val player: MediaPlayer) : PreparedSound {
        /** The gain comes first, so that a silent or quiet alarm is never loud for a moment. */
        override fun start(gain: Float) {
            player.setVolume(gain, gain)
            player.start()
        }

        /** The player can be in its error state here, if its error is still on its way to the listener. */
        override fun setGain(gain: Float) {
            try {
                player.setVolume(gain, gain)
            } catch (failure: IllegalStateException) {
                Log.w(TAG, "Could not set the alarm volume", failure)
            }
        }

        override fun release() = player.release()
    }

    private companion object {
        val ALARM_AUDIO: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
    }
}

/** The system's alarm stream. It changes without any sound or volume panel of its own. */
class AndroidAlarmStream(context: Context) : AlarmStream {
    private val audio = context.getSystemService(AudioManager::class.java)

    override fun current() = audio.getStreamVolume(AudioManager.STREAM_ALARM)
    override fun min() = audio.getStreamMinVolume(AudioManager.STREAM_ALARM)
    override fun max() = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)

    override fun set(level: Int) = audio.setStreamVolume(AudioManager.STREAM_ALARM, level, NO_FLAGS)

    private companion object {
        const val NO_FLAGS = 0
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

class AndroidRingWakeLock(context: Context, tag: String = "NextAlarm:ringing") : RingWakeLock {
    private val lock = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag)
        .apply { setReferenceCounted(false) }

    override fun hold(atMost: Duration) {
        lock.acquire(atMost.toMillis())
    }

    override fun release() {
        if (lock.isHeld) lock.release()
    }
}
