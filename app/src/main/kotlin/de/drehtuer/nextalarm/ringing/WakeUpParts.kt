package de.drehtuer.nextalarm.ringing

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import de.drehtuer.nextalarm.domain.FeedResult
import de.drehtuer.nextalarm.domain.FeedSource
import de.drehtuer.nextalarm.domain.NewsFetcher
import de.drehtuer.nextalarm.domain.PodcastOutput
import de.drehtuer.nextalarm.domain.SpeechOutput
import java.io.IOException
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private const val TAG = "NextAlarm"

/**
 * Speaks with the device's default engine, language and voice: nothing here chooses any of them.
 *
 * The engine is a separate app and takes a moment to connect, and it may not be available before the first unlock, in
 * which case `onDone` hears about it. Callbacks are made outside the lock, because the caller holds its own while it
 * calls this, and two locks taken in opposite orders could deadlock.
 */
class TextToSpeechOutput(
    context: Context,
    private val newEngine: (TextToSpeech.OnInitListener) -> TextToSpeech = { TextToSpeech(context, it) },
) : SpeechOutput {
    private var engine: TextToSpeech? = null
    private var ready = false
    private var waitingText = ""
    private var onDone: ((Boolean) -> Unit)? = null

    /** Told apart from each other, because an old engine or utterance can still report. */
    private var engineRun = 0
    private var utterance = 0

    override fun speak(text: String, onDone: (Boolean) -> Unit) {
        val id: Int
        val worked = synchronized(this) {
            id = ++utterance
            this.onDone = onDone
            waitingText = text
            val current = engine
            when {
                current == null -> connect()
                ready -> say(current)
                else -> true
            }
        }
        if (!worked) finish(id, worked = false)
    }

    @Synchronized
    override fun stop() {
        onDone = null
        waitingText = ""
        engine?.stop()
        dropEngine()
    }

    /** A failing engine can already report from within its constructor, on this thread. */
    private fun connect(): Boolean = try {
        val run = ++engineRun
        val created = newEngine { status -> connected(run, status) }
        if (run == engineRun) engine = created else created.shutdown()
        true
    } catch (failure: RuntimeException) {
        Log.w(TAG, "The text-to-speech engine could not start", failure)
        false
    }

    private fun connected(run: Int, status: Int) {
        val failed = synchronized(this) {
            if (run != engineRun) return
            if (status == TextToSpeech.SUCCESS && setUp()) {
                null
            } else {
                Log.w(TAG, "The text-to-speech engine is not available: $status")
                dropEngine()
                utterance
            }
        }
        if (failed != null) finish(failed, worked = false)
    }

    private fun setUp(): Boolean {
        val current = engine ?: return false
        current.setAudioAttributes(SPEECH_AUDIO)
        current.setOnUtteranceProgressListener(listener)
        ready = true
        return say(current)
    }

    /** The next text tries again, as the engine may have become available by then. */
    private fun dropEngine() {
        ready = false
        engineRun++
        engine?.shutdown()
        engine = null
    }

    private fun say(current: TextToSpeech): Boolean =
        current.speak(waitingText, TextToSpeech.QUEUE_FLUSH, null, utterance.toString()) == TextToSpeech.SUCCESS

    private fun finish(id: Int, worked: Boolean) {
        val callback = synchronized(this) {
            if (id != utterance) return
            onDone.also { onDone = null }
        }
        callback?.invoke(worked)
    }

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit

        override fun onDone(utteranceId: String?) = report(utteranceId, worked = true)

        @Deprecated("Part of the abstract class")
        override fun onError(utteranceId: String?) = report(utteranceId, worked = false)

        override fun onError(utteranceId: String?, errorCode: Int) = report(utteranceId, worked = false)

        private fun report(utteranceId: String?, worked: Boolean) {
            utteranceId?.toIntOrNull()?.let { finish(it, worked) }
        }
    }

    private companion object {
        val SPEECH_AUDIO: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }
}

/**
 * Streams an episode with `MediaPlayer`, as `USAGE_MEDIA`. The address has been checked to be http or https. Callbacks
 * are made outside the lock, for the reason given at [TextToSpeechOutput].
 */
class MediaPlayerPodcastOutput(
    private val context: Context,
    private val newPlayer: () -> MediaPlayer = ::MediaPlayer,
) : PodcastOutput {
    private var player: MediaPlayer? = null
    private var playing = false

    override fun play(url: String, onStarted: () -> Unit, onEnded: () -> Unit) {
        val candidate = synchronized(this) {
            release()
            newPlayer().also { player = it }
        }
        try {
            candidate.setAudioAttributes(PODCAST_AUDIO)
            candidate.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
            candidate.setOnPreparedListener { prepared(candidate, onStarted, onEnded) }
            candidate.setOnCompletionListener { ended(candidate, onEnded) }
            candidate.setOnErrorListener { _, _, _ ->
                ended(candidate, onEnded)
                true
            }
            candidate.setDataSource(url)
            candidate.prepareAsync()
        } catch (failure: IOException) {
            giveUp(candidate, failure, onEnded)
        } catch (failure: RuntimeException) {
            giveUp(candidate, failure, onEnded)
        }
    }

    @Synchronized
    override fun position(): Duration = ask { Duration.ofMillis(it.currentPosition.toLong()) } ?: Duration.ZERO

    /** A stream may not know how long it is, for which the player gives a value of zero or less. */
    @Synchronized
    override fun duration(): Duration? = ask { it.duration }?.takeIf { it > 0 }?.let { Duration.ofMillis(it.toLong()) }

    @Synchronized
    override fun stop() = release()

    private fun prepared(candidate: MediaPlayer, onStarted: () -> Unit, onEnded: () -> Unit) {
        val started = synchronized(this) {
            if (player !== candidate) return
            try {
                candidate.start()
                playing = true
                true
            } catch (failure: IllegalStateException) {
                Log.w(TAG, "Could not start the podcast", failure)
                false
            }
        }
        if (started) onStarted() else ended(candidate, onEnded)
    }

    private fun ended(candidate: MediaPlayer, onEnded: () -> Unit) {
        synchronized(this) {
            if (player !== candidate) return
            release()
        }
        onEnded()
    }

    private fun giveUp(candidate: MediaPlayer, failure: Exception, onEnded: () -> Unit) {
        Log.w(TAG, "Could not play the podcast", failure)
        ended(candidate, onEnded)
    }

    /** The player can be in its error state here, if its error is still on its way to the listener. */
    private fun <T> ask(question: (MediaPlayer) -> T): T? = try {
        player?.takeIf { playing }?.let(question)
    } catch (failure: IllegalStateException) {
        Log.w(TAG, "Could not ask the podcast player", failure)
        null
    }

    private fun release() {
        playing = false
        player?.release()
        player = null
    }

    private companion object {
        val PODCAST_AUDIO: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }
}

/** Fetches feeds in a coroutine of the alarm path, which outlives whatever started it. */
class ScopedNewsFetcher(
    private val scope: CoroutineScope,
    private val feeds: FeedSource,
) : NewsFetcher {
    override fun fetch(url: String, onResult: (FeedResult) -> Unit): () -> Unit {
        val job = scope.launch { onResult(feeds.fetchFeed(url)) }
        return { job.cancel() }
    }
}
