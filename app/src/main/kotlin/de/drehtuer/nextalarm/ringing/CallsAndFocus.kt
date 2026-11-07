package de.drehtuer.nextalarm.ringing

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import de.drehtuer.nextalarm.domain.AudioFocus
import de.drehtuer.nextalarm.domain.PhoneCalls

/**
 * Whether the audio mode says that someone is on a call. This needs no permission and covers internet calls too.
 *
 * A phone that rings (`MODE_RINGTONE`) is not in a call, as nobody talks yet: if nobody answers, which is likely at that time
 * of day, that must not keep the alarm quiet. If the call is answered, the mode changes to one of these. The same goes for
 * `MODE_CALL_SCREENING`, where Android says that other audio may go on. Any other mode, also one that isn't known, is no call.
 */
internal fun isCallMode(mode: Int): Boolean = mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION

/** Reports on the main thread. */
class AndroidPhoneCalls(private val context: Context) : PhoneCalls {
    private val audio = context.getSystemService(AudioManager::class.java)

    override fun active(): Boolean = isCallMode(audio.mode)

    override fun watch(onChange: (Boolean) -> Unit): () -> Unit {
        val listener = AudioManager.OnModeChangedListener { mode -> onChange(isCallMode(mode)) }
        audio.addOnModeChangedListener(context.mainExecutor, listener)
        return { audio.removeOnModeChangedListener(listener) }
    }
}

/** The audio focus of one kind of audio. Each one has a listener of its own, so that Android tells them apart. */
class AndroidAudioFocus(
    context: Context,
    private val attributes: AudioAttributes,
    private val gain: Int,
) : AudioFocus {
    private val audio = context.getSystemService(AudioManager::class.java)
    private var current: AudioFocusRequest? = null

    override fun request(onLostForGood: () -> Unit): Boolean {
        val request = AudioFocusRequest.Builder(gain)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { change ->
                // Something that only needs a moment, such as another alarm, doesn't end anything.
                if (change == AudioManager.AUDIOFOCUS_LOSS) onLostForGood()
            }
            .build()
        current = request
        return audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    override fun abandon() {
        current?.let(audio::abandonAudioFocusRequest)
        current = null
    }
}

/**
 * For the alarm sound. It only takes the focus for a moment, so that music goes on by itself when the alarm ends, and so that
 * news that is playing doesn't take this for the user starting something else.
 */
fun alarmAudioFocus(context: Context) = AndroidAudioFocus(
    context,
    AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build(),
    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
)

/** For the speech and the podcasts of the wake-up automation. */
fun mediaAudioFocus(context: Context) = AndroidAudioFocus(
    context,
    AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build(),
    AudioManager.AUDIOFOCUS_GAIN,
)
