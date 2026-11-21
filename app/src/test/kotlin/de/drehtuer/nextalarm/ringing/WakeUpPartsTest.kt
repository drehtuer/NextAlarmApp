package de.drehtuer.nextalarm.ringing

import android.Manifest
import android.app.Application
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import androidx.test.core.app.ApplicationProvider
import de.drehtuer.nextalarm.domain.Coordinates
import de.drehtuer.nextalarm.domain.FeedFailure
import de.drehtuer.nextalarm.domain.FeedResult
import de.drehtuer.nextalarm.domain.FeedSource
import de.drehtuer.nextalarm.domain.Forecast
import de.drehtuer.nextalarm.domain.SpokenText
import de.drehtuer.nextalarm.domain.WeatherPlace
import de.drehtuer.nextalarm.domain.WeatherService
import de.drehtuer.nextalarm.domain.WeatherSource
import de.drehtuer.nextalarm.domain.forecastSentence
import java.io.IOException
import java.time.Duration
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.ShadowTextToSpeech
import org.robolectric.shadows.util.DataSource

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TextToSpeechOutputTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val engines = mutableListOf<TextToSpeech>()
    private val attributes = mutableListOf<AudioAttributes>()
    private var speakResult = TextToSpeech.SUCCESS

    private inner class RecordingEngine(listener: OnInitListener) : TextToSpeech(context, listener) {
        override fun setAudioAttributes(audioAttributes: AudioAttributes): Int {
            attributes += audioAttributes
            return super.setAudioAttributes(audioAttributes)
        }

        override fun speak(text: CharSequence?, queueMode: Int, params: android.os.Bundle?, utteranceId: String?): Int {
            super.speak(text, queueMode, params, utteranceId)
            return speakResult
        }
    }

    private val output = TextToSpeechOutput(context) { listener -> RecordingEngine(listener).also { engines += it } }
    private val results = mutableListOf<Boolean>()

    private fun speak(text: String = "Gym", language: String? = null) = output.speak(text, language) { results += it }

    private fun shadow(index: Int = engines.lastIndex): ShadowTextToSpeech = Shadow.extract(engines[index])

    private fun connect(status: Int = TextToSpeech.SUCCESS, index: Int = engines.lastIndex) =
        shadow(index).onInitListener.onInit(status)

    private fun done(id: String?) = shadow().utteranceProgressListener.onDone(id)

    @Test
    fun `the engine connects first, then speaks, through the media stream, with its own language and voice`() {
        speak("Flight to Hamburg")
        assertNull(shadow().lastSpokenText)
        connect()
        assertEquals("Flight to Hamburg", shadow().lastSpokenText)
        assertEquals(TextToSpeech.QUEUE_FLUSH, shadow().queueMode)
        assertEquals(AudioAttributes.USAGE_MEDIA, attributes.single().usage)
        assertEquals(AudioAttributes.CONTENT_TYPE_SPEECH, attributes.single().contentType)
        assertNull(shadow().currentLanguage)
        assertNull(shadow().currentVoice)
    }

    @Test
    fun `a text in a language the engine has is spoken in it, and the next one without a language gets the default back`() {
        ShadowTextToSpeech.addLanguageAvailability(Locale.GERMANY)
        speak("Eins", "de-DE")
        connect()
        assertEquals(Locale.GERMANY, shadow().currentLanguage)
        done("1")
        speak("Gym")
        assertEquals(Locale.getDefault(), shadow().currentLanguage)
        done("2")
        speak("Run")
        assertEquals("Run", shadow().lastSpokenText)
    }

    @Test
    fun `a language the engine doesn't have leaves its own`() {
        speak("Bonjour", "fr-FR")
        connect()
        assertEquals("Bonjour", shadow().lastSpokenText)
        assertNull(shadow().currentLanguage)
    }

    @Test
    fun `the end of the text is reported`() {
        speak()
        connect()
        assertTrue(results.isEmpty())
        shadow().utteranceProgressListener.onStart("1")
        done("1")
        assertEquals(listOf(true), results)
    }

    @Test
    fun `the end is reported only once`() {
        speak()
        connect()
        done("1")
        done("1")
        assertEquals(listOf(true), results)
    }

    @Test
    fun `the engine stays connected for the next text`() {
        speak("One")
        connect()
        done("1")
        speak("Two")
        assertEquals("Two", shadow().lastSpokenText)
        assertEquals(1, engines.size)
        done("2")
        assertEquals(listOf(true, true), results)
    }

    @Test
    fun `a text given while the engine connects is the one spoken`() {
        speak("One")
        speak("Two")
        connect()
        assertEquals("Two", shadow().lastSpokenText)
        assertEquals(1, engines.size)
    }

    @Test
    fun `errors are reported, in both of their forms`() {
        speak()
        connect()
        @Suppress("DEPRECATION")
        shadow().utteranceProgressListener.onError("1")
        speak()
        shadow().utteranceProgressListener.onError("2", TextToSpeech.ERROR_NETWORK)
        assertEquals(listOf(false, false), results)
    }

    @Test
    fun `reports about something else are ignored`() {
        speak()
        connect()
        done("99")
        done("not a number")
        done(null)
        assertTrue(results.isEmpty())
    }

    @Test
    fun `an engine that is not available reports failure and is let go, and the next text tries again`() {
        speak()
        connect(TextToSpeech.ERROR)
        assertEquals(listOf(false), results)
        assertTrue(shadow().isShutdown)
        speak()
        assertEquals(2, engines.size)
    }

    @Test
    fun `an engine that fails within its constructor is let go too`() {
        val created = mutableListOf<TextToSpeech>()
        val failing = TextToSpeechOutput(context) { listener ->
            RecordingEngine(listener).also {
                created += it
                listener.onInit(TextToSpeech.ERROR)
            }
        }
        failing.speak("Gym", null) { results += it }
        assertEquals(listOf(false), results)
        assertTrue((Shadow.extract(created.single()) as ShadowTextToSpeech).isShutdown)
    }

    @Test
    fun `an engine that says it is ready before it exists counts as failed`() {
        val odd = TextToSpeechOutput(context) { listener ->
            RecordingEngine(listener).also { listener.onInit(TextToSpeech.SUCCESS) }
        }
        odd.speak("Gym", null) { results += it }
        assertEquals(listOf(false), results)
    }

    @Test
    fun `an engine that cannot be created reports failure`() {
        val failing = TextToSpeechOutput(context) { error("No engine") }
        failing.speak("Gym", null) { results += it }
        assertEquals(listOf(false), results)
    }

    @Test
    fun `a text the engine refuses reports failure`() {
        speakResult = TextToSpeech.ERROR
        speak()
        connect()
        assertEquals(listOf(false), results)
        speakResult = TextToSpeech.SUCCESS
        speak()
        connect()
        speakResult = TextToSpeech.ERROR
        speak()
        assertEquals(listOf(false, false), results)
        assertEquals(2, engines.size)
    }

    @Test
    fun `stopping silences the engine and gives it up, and nothing is reported any more`() {
        speak()
        connect()
        val listener = shadow().utteranceProgressListener
        output.stop()
        assertTrue(shadow().isStopped)
        assertTrue(shadow().isShutdown)
        listener.onDone("1")
        assertTrue(results.isEmpty())
    }

    @Test
    fun `an engine that connects after it was stopped does nothing`() {
        speak()
        output.stop()
        connect()
        assertNull(shadow().lastSpokenText)
        assertTrue(results.isEmpty())
    }

    @Test
    fun `stopping without an engine is harmless, and it starts again after that`() {
        output.stop()
        speak()
        assertEquals(1, engines.size)
    }

    @Test
    fun `by default it uses the system's engine`() {
        TextToSpeechOutput(context).speak("Gym", null) { results += it }
        assertEquals(context, shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance()).context)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MediaPlayerPodcastOutputTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val url = "https://cdn.example/a.mp3"
    private val source = DataSource.toDataSource(url)
    private val players = mutableListOf<MediaPlayer>()
    private val output = MediaPlayerPodcastOutput(context) { MediaPlayer().also { players += it } }
    private var starts = 0
    private var ends = 0

    private fun play() = output.play(url, { starts++ }, { ends++ })

    private fun shadow() = shadowOf(players.last())

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Before
    fun setUp() {
        ShadowMediaPlayer.addMediaInfo(source, ShadowMediaPlayer.MediaInfo(60_000, 0))
    }

    private fun playStarted(): MediaPlayer {
        play()
        idle()
        return players.last()
    }

    @Test
    fun `pausing and resuming a podcast that plays hold it and let it go on, without ending it`() {
        val player = playStarted()
        output.pause()
        assertEquals(ShadowMediaPlayer.State.PAUSED, shadow().state)
        output.resume()
        assertEquals(ShadowMediaPlayer.State.STARTED, shadow().state)
        assertTrue(player.isPlaying)
        assertEquals(0, ends)
    }

    @Test
    fun `pausing and resuming with no player do nothing`() {
        output.pause()
        output.resume()
        assertEquals(0, ends)
        assertEquals(0, starts)
    }

    @Test
    fun `pausing and resuming a player that has not started do nothing`() {
        play()
        output.pause()
        output.resume()
        assertEquals(0, ends)
    }

    /** A player in its error state, from the moment it has started. */
    private fun brokenOutput(): MediaPlayerPodcastOutput {
        val output = MediaPlayerPodcastOutput(context) {
            object : MediaPlayer() {
                var started = false

                override fun start() {
                    if (started) throw IllegalStateException("In its error state")
                    started = true
                    super.start()
                }

                override fun pause(): Unit = throw IllegalStateException("In its error state")
            }.also { players += it }
        }
        output.play(url, { starts++ }, { ends++ })
        idle()
        return output
    }

    @Test
    fun `a player that can't pause is left alone, as its error is on its way`() {
        brokenOutput().pause()
        assertEquals(0, ends)
    }

    @Test
    fun `a player that can't go on is the end of the episode`() {
        brokenOutput().resume()
        assertEquals(1, ends)
    }

    @Test
    fun `an episode is streamed from its address, as media, and reports that it started`() {
        play()
        idle()
        assertEquals(source, shadow().dataSource)
        assertEquals(ShadowMediaPlayer.State.STARTED, shadow().state)
        assertEquals(AudioAttributes.USAGE_MEDIA, shadow().audioAttributes.usage)
        assertEquals(1, starts)
        assertEquals(0, ends)
    }

    @Test
    fun `the time played and the length are known once it plays`() {
        play()
        assertEquals(Duration.ZERO, output.position())
        assertNull(output.duration())
        idle()
        shadow().setCurrentPosition(5_000)
        assertEquals(Duration.ofSeconds(5), output.position())
        assertEquals(Duration.ofMinutes(1), output.duration())
    }

    @Test
    fun `a stream that does not know its length says so`() {
        ShadowMediaPlayer.addMediaInfo(source, ShadowMediaPlayer.MediaInfo(0, 0))
        play()
        idle()
        assertNull(output.duration())
    }

    @Test
    fun `the end of the episode is reported, and the player released`() {
        play()
        idle()
        shadow().invokeCompletionListener()
        assertEquals(1, ends)
        assertEquals(ShadowMediaPlayer.State.END, shadow().state)
        assertEquals(Duration.ZERO, output.position())
        assertNull(output.duration())
    }

    @Test
    fun `an error while playing is reported`() {
        play()
        idle()
        shadow().invokeErrorListener(MediaPlayer.MEDIA_ERROR_UNKNOWN, 0)
        assertEquals(1, ends)
    }

    @Test
    fun `an address that cannot be opened is reported`() {
        ShadowMediaPlayer.addException(source, IOException("No network"))
        play()
        assertEquals(1, ends)
        assertEquals(0, starts)
    }

    @Test
    fun `a player that refuses is reported`() {
        ShadowMediaPlayer.addException(source, IllegalArgumentException("Bad"))
        play()
        assertEquals(1, ends)
    }

    @Test
    fun `a player that cannot start after preparing is reported`() {
        val failing = MediaPlayerPodcastOutput(context) {
            object : MediaPlayer() {
                override fun start() = error("Not now")
            }.also { players += it }
        }
        failing.play(url, { starts++ }, { ends++ })
        idle()
        assertEquals(0, starts)
        assertEquals(1, ends)
    }

    @Test
    fun `a player in its error state does not bring anything down`() {
        val failing = MediaPlayerPodcastOutput(context) {
            object : MediaPlayer() {
                override fun getCurrentPosition(): Int = error("Error state")
                override fun getDuration(): Int = error("Error state")
            }.also { players += it }
        }
        failing.play(url, { starts++ }, { ends++ })
        idle()
        assertEquals(Duration.ZERO, failing.position())
        assertNull(failing.duration())
    }

    @Test
    fun `a length of zero or less means it is not known`() {
        listOf(0, -1).forEach { length ->
            val output = MediaPlayerPodcastOutput(context) {
                object : MediaPlayer() {
                    override fun getDuration() = length
                }
            }
            output.play(url, {}, {})
            idle()
            assertNull(output.duration(), "$length")
            output.stop()
        }
    }

    @Test
    fun `stopping releases the player, and it reports nothing after that`() {
        play()
        val player = shadow()
        val prepared = player.onPreparedListener
        val completion = player.onCompletionListener
        output.stop()
        assertEquals(ShadowMediaPlayer.State.END, player.state)
        prepared.onPrepared(players.last())
        completion.onCompletion(players.last())
        assertEquals(0, starts)
        assertEquals(0, ends)
    }

    @Test
    fun `stopping without a player is harmless`() {
        output.stop()
        assertEquals(Duration.ZERO, output.position())
    }

    @Test
    fun `a new episode replaces the old one`() {
        play()
        val first = shadow()
        play()
        assertEquals(ShadowMediaPlayer.State.END, first.state)
    }

    @Test
    fun `by default it uses a real player`() {
        val real = MediaPlayerPodcastOutput(context)
        real.play(url, { starts++ }, { ends++ })
        idle()
        assertEquals(1, starts)
        real.stop()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ScopedNewsFetcherTest {
    private val failed = FeedResult.Failed(FeedFailure.Unreachable)

    @Test
    fun `the result of a fetch is passed on`() {
        val results = mutableListOf<FeedResult>()
        val fetcher = ScopedNewsFetcher(alarmScope(Dispatchers.Unconfined), FeedSource { failed })
        fetcher.fetch("https://a.example/") { results += it }
        assertEquals(listOf<FeedResult>(failed), results)
    }

    @Test
    fun `a cancelled fetch reports nothing`() {
        val gate = CompletableDeferred<FeedResult>()
        val results = mutableListOf<FeedResult>()
        val fetcher = ScopedNewsFetcher(alarmScope(Dispatchers.Unconfined), FeedSource { gate.await() })
        val cancel = fetcher.fetch("https://a.example/") { results += it }
        cancel()
        gate.complete(failed)
        assertTrue(results.isEmpty())
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TextToSpeechLanguagesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val engines = mutableListOf<TextToSpeech>()

    private fun voice(locale: Locale, installed: Boolean = true) = Voice(
        "voice-${locale.toLanguageTag()}-$installed",
        locale,
        Voice.QUALITY_NORMAL,
        Voice.LATENCY_NORMAL,
        false,
        if (installed) emptySet() else setOf(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED),
    )

    private fun languages(status: Int? = TextToSpeech.SUCCESS, limit: Duration = Duration.ofSeconds(5)) = runBlocking {
        TextToSpeechLanguages(context, limit) { listener ->
            TextToSpeech(context, listener).also {
                engines += it
                if (status != null) listener.onInit(status)
            }
        }.available()
    }

    @Test
    fun `the languages of the installed voices are offered, each once`() {
        ShadowTextToSpeech.addVoice(voice(Locale.GERMANY))
        ShadowTextToSpeech.addVoice(voice(Locale.UK))
        ShadowTextToSpeech.addVoice(Voice("second", Locale.GERMANY, Voice.QUALITY_HIGH, Voice.LATENCY_NORMAL, false, emptySet()))
        ShadowTextToSpeech.addVoice(voice(Locale.FRANCE, installed = false))
        assertEquals(setOf("de-DE", "en-GB"), languages().toSet())
        assertEquals(2, languages().size)
        assertTrue(Shadow.extract<ShadowTextToSpeech>(engines.first()).isShutdown, "the engine is let go")
    }

    @Test
    fun `an engine that is not available offers nothing`() {
        ShadowTextToSpeech.addVoice(voice(Locale.GERMANY))
        assertTrue(languages(TextToSpeech.ERROR).isEmpty())
        assertTrue(Shadow.extract<ShadowTextToSpeech>(engines.single()).isShutdown)
    }

    @Test
    fun `an engine that does not answer offers nothing`() {
        assertTrue(languages(status = null, limit = Duration.ofMillis(10)).isEmpty())
    }

    @Test
    fun `an engine that can't start offers nothing`() {
        val failing = TextToSpeechLanguages(context) { error("No engine") }
        assertTrue(runBlocking { failing.available() }.isEmpty())
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class WeatherPartsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(LocationManager::class.java)

    private fun location(provider: String, latitude: Double, at: Long) = Location(provider).apply {
        this.latitude = latitude
        longitude = 13.4
        elapsedRealtimeNanos = at
    }

    private fun provide(provider: String, latitude: Double, at: Long) {
        shadowOf(manager).setProviderEnabled(provider, true)
        shadowOf(manager).simulateLocation(location(provider, latitude, at))
    }

    @Test
    fun `the last location is the newest any provider knows, with the permission`() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        provide(LocationManager.NETWORK_PROVIDER, 52.5, 2)
        provide(LocationManager.PASSIVE_PROVIDER, 48.1, 1)
        assertEquals(Coordinates(52.5, 13.4), AndroidLastLocation(context).coarse())
    }

    @Test
    fun `without the permission there is no location`() {
        provide(LocationManager.NETWORK_PROVIDER, 52.5, 2)
        assertNull(AndroidLastLocation(context).coarse())
    }

    @Test
    fun `a phone that knows nowhere has no location`() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        assertNull(AndroidLastLocation(context).coarse())
    }

    private class OneForecast(private val forecast: Forecast?) : WeatherService {
        override suspend fun forecast(at: Coordinates) = forecast
        override suspend fun search(name: String): List<WeatherPlace>? = null
    }

    @Test
    fun `the weather fetcher says the forecast in the phone's language, and can be cancelled`() {
        val results = mutableListOf<SpokenText?>()
        val forecast = Forecast(63, 4.0, 9.0, 80)
        val fetcher = ScopedWeatherFetcher(alarmScope(Dispatchers.Unconfined), OneForecast(forecast), { null }) { Locale.GERMANY }
        fetcher.fetch(WeatherSource("Berlin", 52.52, 13.41)) { results += it }
        assertEquals(listOf<SpokenText?>(SpokenText(forecastSentence("Berlin", forecast, "de"), "de")), results)
        val failing = ScopedWeatherFetcher(alarmScope(Dispatchers.Unconfined), OneForecast(null), { null })
        failing.fetch(WeatherSource("Berlin", 52.52, 13.41)) { results += it }()
        assertNull(results.last())
    }
}
