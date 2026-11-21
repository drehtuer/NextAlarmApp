package de.drehtuer.nextalarm.domain

import java.time.Duration
import java.time.Instant

/**
 * Says one text at a time, through the media stream, with the device's default engine and voice. `onDone` is told whether
 * it worked, and is not called after [stop].
 */
interface SpeechOutput {
    /** [language] is a language tag ("de-DE"). Empty, or one the engine can't speak, means the phone's language. */
    fun speak(text: String, language: String?, onDone: (Boolean) -> Unit)

    /** Stops at once, and gives up the engine until the next [speak]. */
    fun stop()
}

/** The languages the phone's speech engine can speak, as language tags. Empty if it can't say. */
fun interface SpeechLanguages {
    suspend fun available(): List<String>
}

/** Plays the audio at an address, through the media stream. */
interface PodcastOutput {
    /**
     * `onStarted` is called when the audio plays. `onEnded` is called when it has played to the end, or failed at any
     * point. Neither is called after [stop].
     */
    fun play(url: String, onStarted: () -> Unit, onEnded: () -> Unit)
    fun position(): Duration

    /** Empty while the player does not know it. */
    fun duration(): Duration?

    /** For audio that has started, and only for a moment. */
    fun pause()

    /** Goes on after [pause]. `onEnded` is called if it can't. */
    fun resume()
    fun stop()
}

/**
 * Holds audio back until the wake-up service is in the foreground, as Android 17 silences it otherwise, with no error. Only
 * one thing plays at a time, so only the latest action waits. After [limit] it goes ahead anyway, as a chance of sound beats
 * none. [beforeAudio] comes just before the audio starts, for what Android also only allows with the service up. See
 * docs/architecture.md#background-audio-on-android-17.
 */
class ForegroundGate(
    private val timers: RingTimers,
    private val limit: Duration = Duration.ofSeconds(3),
    private val beforeAudio: () -> Unit = {},
) {
    private var isOpen = false
    private var waiting: (() -> Unit)? = null
    private var cancelLimit: () -> Unit = {}

    /** Runs [action] when the service is up, which can be now. It runs outside this class's lock. */
    fun whenOpen(action: () -> Unit) {
        val now = synchronized(this) {
            if (!isOpen) {
                waiting = action
                cancelLimit()
                cancelLimit = timers.after(limit, ::open)
            }
            isOpen
        }
        if (now) begin(action)
    }

    fun open() {
        val action = synchronized(this) {
            isOpen = true
            cancelLimit()
            waiting.also { waiting = null }
        }
        action?.let(::begin)
    }

    private fun begin(action: () -> Unit) {
        beforeAudio()
        action()
    }

    /** The service has gone, or what waited is no longer wanted. */
    @Synchronized
    fun close() {
        isOpen = false
        forget()
    }

    @Synchronized
    fun forget() {
        cancelLimit()
        waiting = null
    }
}

/** Speech that starts when [gate] lets it. */
class GatedSpeech(private val output: SpeechOutput, private val gate: ForegroundGate) : SpeechOutput {
    override fun speak(text: String, language: String?, onDone: (Boolean) -> Unit) =
        gate.whenOpen { output.speak(text, language, onDone) }

    override fun stop() {
        gate.forget()
        output.stop()
    }
}

/** A podcast that starts when [gate] lets it. */
class GatedPodcast(private val output: PodcastOutput, private val gate: ForegroundGate) : PodcastOutput by output {
    override fun play(url: String, onStarted: () -> Unit, onEnded: () -> Unit) =
        gate.whenOpen { output.play(url, onStarted, onEnded) }

    override fun stop() {
        gate.forget()
        output.stop()
    }
}

/** Fetches a feed in the background. The result cancels it. */
fun interface NewsFetcher {
    fun fetch(url: String, onResult: (FeedResult) -> Unit): () -> Unit
}

/** What the wake-up automation does at the moment. */
sealed interface WakeUpPlaying {
    /** The [index]th of [count] titles (from 1). */
    data class Speaking(val title: String, val index: Int, val count: Int, val startedAt: Instant) : WakeUpPlaying

    /** Waiting for the feed. */
    data class GettingNews(val source: String) : WakeUpPlaying

    /** Saying that the feed could not be had. */
    data class NewsUnavailable(val source: String) : WakeUpPlaying

    /** Getting or saying the weather for [place]. */
    data class Weather(val place: String) : WakeUpPlaying

    /** Reading the [index]th of [count] entries (from 1). */
    data class ReadingNews(
        val feedName: String,
        val index: Int,
        val count: Int,
        val startedAt: Instant,
        val limit: Duration,
    ) : WakeUpPlaying

    data class PlayingPodcast(
        val feedName: String,
        val author: String?,
        val episodeTitle: String,
        /** False while the audio is still being fetched. */
        val started: Boolean,
        val position: Duration,
        val duration: Duration?,
        val limit: Duration,
    ) : WakeUpPlaying
}

data class WakeUpStatus(
    /** The titles of all alarms in this automation. An entry is empty if the event has no title. */
    val titles: List<String?>,
    /** Empty for the moment between two steps. */
    val playing: WakeUpPlaying?,
    /** When the news will be stopped, if it has started. */
    val newsStopsAt: Instant?,
) {
    /** The automation runs for as long as any alarm is in it. */
    val running: Boolean
        get() = titles.isNotEmpty()

    companion object {
        val IDLE = WakeUpStatus(emptyList(), null, null)
    }
}

/**
 * The wake-up automation: says the title of every dismissed alarm in turn, then plays the news.
 * See docs/architecture.md#wake-up-automation-and-the-lock-screen.
 *
 * Only one thing plays at a time. Several alarms share one automation, as they share the ringing:
 *
 * - All titles are said, one after the other, each alarm getting its `AnnouncementDone`. Then comes the news, once.
 * - An alarm that wants news while titles are still being said waits for them, and joins news that is already playing.
 *   All of them get their `NewsDone` when it ends. If their news sources differ, the first alarm's is played.
 * - A title to say while news plays stops the news, which starts again after it, so that no two voices talk at once.
 *
 * No audio plays during a call, and other audio pauses while this plays. See docs/architecture.md#calls-and-audio-focus.
 *
 * All of this runs under a lock of its own, and the engine's is held when [startAnnouncement], [startNews] and [leave]
 * are called: [report] must therefore not call the engine directly.
 */
class WakeUpDirector(
    private val speech: SpeechOutput,
    private val podcast: PodcastOutput,
    private val feeds: NewsFetcher,
    private val timers: RingTimers,
    private val wakeLock: RingWakeLock,
    private val focus: AudioFocusHold,
    private val calls: CallWatch,
    private val now: () -> Instant,
    private val report: (Long, AlarmEvent) -> Unit,
    private val onChanged: (WakeUpStatus) -> Unit,
    /**
     * Told about a feed that could not be had (no network, no feed, nothing in it), after which that is said. Without it,
     * the news just ends, as for the test on screen 6, which shows why itself.
     */
    private val onNewsUnavailable: ((NewsSource) -> Unit)? = null,
    /** The weather after the feed. Without it, there is none, as for the test on screen 6. */
    private val forecasts: WeatherFetcher = NO_WEATHER,
) : WakeUpAutomation {
    private sealed interface Phase {
        data object Fetching : Phase
        data object Unavailable : Phase
        data object WaitingForWeather : Phase
        data object SpeakingWeather : Phase
        class Reading(val feed: Feed, val index: Int) : Phase
        class Playing(val feed: Feed, val episode: PodcastEpisode, val started: Boolean) : Phase
    }

    /** What an alarm wants after its title: the feed, the weather, or both. */
    private class NewsStep(val source: NewsSource?, val weather: WeatherSource?)

    /**
     * The step after the titles: the feed, if there is one, then the weather, if there is that. [audioFrom] is when the
     * first sound may come, which is the end of the pause after the titles.
     */
    private class News(val step: NewsStep, val startedAt: Instant, val audioFrom: Instant) {
        val source: NewsSource? = step.source
        var phase: Phase = Phase.Fetching

        /** A call paused the podcast. */
        var paused = false

        /** The weather is fetched while the feed plays, so that it is ready when the feed ends. */
        var forecast: SpokenText? = null
        var forecastArrived = false
        var weatherStarted = false
    }

    private class Title(val alarmId: Long, val text: String)

    /** Every alarm in the automation, until the state machine lets it [leave]. */
    private val members = hashSetOf<Long>()

    /** What the screen shows stays until the automation ends, also when an alarm is through with it. */
    private val titles = linkedMapOf<Long, String?>()
    private val waitingTitles = ArrayDeque<Title>()
    private var speaking: Title? = null
    private var speakingSince: Instant = Instant.EPOCH
    private var titleIndex = 0
    private var titleCount = 0

    private val newsAlarms = linkedMapOf<Long, NewsStep>()
    private var news: News? = null

    /** Told apart from each other, because a callback or a timer can arrive just after what it belongs to has ended. */
    private var speechRun = 0

    private var onSpoken: (Boolean) -> Unit = {}
    private var cancelSpeechLimit: () -> Unit = {}
    private var cancelNewsLimit: () -> Unit = {}
    private var cancelFetch: () -> Unit = {}
    private var cancelStartLimit: () -> Unit = {}
    private var cancelPause: () -> Unit = {}
    private var cancelWeatherFetch: () -> Unit = {}
    private var cancelWeatherWait: () -> Unit = {}
    private var stopWatchingCalls: () -> Unit = {}

    /**
     * Titles have been said, so the news that comes next waits for its pause. The news usually only comes a moment later,
     * when the state machine has heard that the title is done.
     */
    private var pauseBeforeNews = false

    @Synchronized
    override fun startAnnouncement(alarm: AlarmInstance) {
        join(alarm)
        val title = alarm.snapshot.reason
        if (title == null || calls.active()) {
            report(alarm.id, AlarmEvent.AnnouncementDone)
            publish()
            return
        }
        waitingTitles.addLast(Title(alarm.id, title))
        titleCount++
        stopNewsPlayback()
        if (speaking == null) speakNextTitle() else publish()
    }

    @Synchronized
    override fun startNews(alarm: AlarmInstance) {
        join(alarm)
        val snapshot = alarm.snapshot
        if (!snapshot.hasNewsStep) {
            report(alarm.id, AlarmEvent.NewsDone)
            publish()
            return
        }
        newsAlarms[alarm.id] = NewsStep(snapshot.news, snapshot.weather)
        if (speaking == null && news == null) startNewsPlayback() else publish()
    }

    /** It is over for this alarm, for whichever reason. An alarm that was not in the automation has nothing to leave. */
    @Synchronized
    override fun leave(instanceId: Long) {
        if (!members.remove(instanceId)) return
        if (waitingTitles.removeAll { it.alarmId == instanceId }) titleCount--
        newsAlarms.remove(instanceId)
        when {
            members.isEmpty() -> finish()
            speaking?.alarmId == instanceId -> {
                abandonSpeech()
                speakNextTitle()
            }
            newsAlarms.isEmpty() && news != null -> {
                stopNewsPlayback()
                publish()
            }
            else -> publish()
        }
    }

    /** A new alarm has to be heard on its own, and its dismissal starts the automation again, with its own title. */
    @Synchronized
    override fun alarmRings() {
        if (members.isNotEmpty()) endForEveryAlarm()
    }

    @Synchronized
    override fun stopAll() {
        if (members.isEmpty()) return
        members.clear()
        waitingTitles.clear()
        newsAlarms.clear()
        finish()
    }

    /** As it is right now, including how far a podcast has got. */
    @Synchronized
    fun status(): WakeUpStatus = WakeUpStatus(
        titles = titles.values.toList(),
        playing = playing(),
        newsStopsAt = news?.let { it.startedAt.plus(limitOf(it.step)) },
    )

    private fun join(alarm: AlarmInstance) {
        if (members.isEmpty()) {
            wakeLock.hold(SPEECH_WAKE_LOCK)
            stopWatchingCalls = calls.watch(::callChanged)
        }
        members += alarm.id
        titles[alarm.id] = alarm.snapshot.reason
    }

    private fun speakNextTitle() {
        val title = waitingTitles.removeFirstOrNull()
        if (title == null) {
            if (titleIndex > 0) pauseBeforeNews = true
            titleIndex = 0
            titleCount = 0
            if (newsAlarms.isNotEmpty()) startNewsPlayback() else publish()
            return
        }
        if (calls.active()) {
            report(title.alarmId, AlarmEvent.AnnouncementDone)
            speakNextTitle()
            return
        }
        speaking = title
        speakingSince = now()
        titleIndex++
        publish()
        // The phone's language: the title is the user's own.
        say(title.text, null) {
            speaking = null
            report(title.alarmId, AlarmEvent.AnnouncementDone)
            speakNextTitle()
        }
    }

    /**
     * A failed title is treated like a spoken one, and failed reading ends the news, so there is only this one way out.
     * The limit is for an engine that never answers, which would otherwise keep the automation waiting.
     */
    private fun say(text: String, language: String?, onDone: (Boolean) -> Unit) {
        focus.take(::focusLost)
        val run = ++speechRun
        onSpoken = onDone
        cancelSpeechLimit()
        cancelSpeechLimit = timers.after(speechLimit(text)) { speechTimedOut(run) }
        speech.speak(text, language) { worked -> spoken(run, worked) }
    }

    @Synchronized
    private fun speechTimedOut(run: Int) {
        if (run != speechRun) return
        speech.stop()
        spoken(run, false)
    }

    @Synchronized
    private fun spoken(run: Int, worked: Boolean) {
        if (run != speechRun) return
        speechRun++
        cancelSpeechLimit()
        onSpoken(worked)
    }

    private fun abandonSpeech() {
        speechRun++
        cancelSpeechLimit()
        speech.stop()
        speaking = null
    }

    private fun startNewsPlayback() {
        if (calls.active()) {
            reportNewsDone()
            publish()
            return
        }
        val step = newsAlarms.values.first()
        val source = step.source
        val start = now()
        val pauseMillis = (source?.pauseMillis ?: NewsSource.DEFAULT_PAUSE_MILLIS).toLong()
        val pause = if (pauseBeforeNews) Duration.ofMillis(pauseMillis) else Duration.ZERO
        pauseBeforeNews = false
        val current = News(step, start, start.plus(pause))
        news = current
        wakeLock.hold(limitOf(step).plus(NEWS_WAKE_LOCK_MARGIN))
        cancelNewsLimit = timers.after(limitOf(step)) { newsEnded(current) }
        publish()
        step.weather?.let { weather ->
            cancelWeatherFetch = forecasts.fetch(weather) { result -> weatherArrived(current, result) }
        }
        if (source != null) {
            cancelFetch = feeds.fetch(source.url) { result -> feedArrived(current, result) }
        } else {
            // Only the weather: the feed is over before it began.
            afterPause(current) { newsEnded(current) }
        }
    }

    @Synchronized
    private fun feedArrived(current: News, result: FeedResult) {
        val source = current.source
        if (news !== current || source == null) return
        val feed = (result as? FeedResult.Found)?.feed
        val episode = feed?.latestEpisode
        when {
            calls.active() -> newsEnded(current)
            feed == null -> afterPause(current) { unavailable(current, source) }
            source.type == NewsType.PODCAST && episode != null ->
                afterPause(current) { playEpisode(current, feed, episode) }
            source.type == NewsType.PODCAST || feed.entries.isEmpty() -> afterPause(current) { unavailable(current, source) }
            else -> afterPause(current) { readEntry(current, feed, 0) }
        }
    }

    /** Waits for the rest of the pause after the titles, if any is left after the download. */
    private fun afterPause(current: News, action: () -> Unit) {
        val wait = Duration.between(now(), current.audioFrom)
        if (wait.isNegative || wait.isZero) {
            action()
        } else {
            cancelPause = timers.after(wait) { pauseOver(current, action) }
        }
    }

    @Synchronized
    private fun pauseOver(current: News, action: () -> Unit) {
        if (news !== current) return
        if (calls.active()) newsEnded(current) else action()
    }

    /** Silence would sound like a fault, so the failure is said, in the phone's language, as the app's own words are. */
    private fun unavailable(current: News, source: NewsSource) {
        val tell = onNewsUnavailable
        if (tell == null) {
            newsEnded(current)
            return
        }
        tell(source)
        current.phase = Phase.Unavailable
        publish()
        say(NEWS_UNAVAILABLE_TEXT, null) { newsEnded(current) }
    }

    private fun readEntry(current: News, feed: Feed, index: Int) {
        current.phase = Phase.Reading(feed, index)
        publish()
        say(feed.entries[index].spokenText, current.source?.language ?: feed.language) { worked ->
            if (worked && index + 1 < feed.entries.size) readEntry(current, feed, index + 1) else newsEnded(current)
        }
    }

    private fun playEpisode(current: News, feed: Feed, episode: PodcastEpisode) {
        current.phase = Phase.Playing(feed, episode, started = false)
        publish()
        focus.take(::focusLost)
        cancelStartLimit = timers.after(PODCAST_START_LIMIT) { newsEnded(current) }
        podcast.play(
            episode.audioUrl,
            onStarted = { podcastStarted(current, feed, episode) },
            onEnded = { newsEnded(current) },
        )
    }

    @Synchronized
    private fun podcastStarted(current: News, feed: Feed, episode: PodcastEpisode) {
        if (news !== current) return
        cancelStartLimit()
        current.phase = Phase.Playing(feed, episode, started = true)
        publish()
    }

    /**
     * The feed played to its end, reached its limit, or failed: the state machine does not tell these apart. The weather
     * comes next, if there is any and no call is in the way, and the step ends after it.
     */
    @Synchronized
    private fun newsEnded(current: News) {
        if (news !== current) return
        if (current.step.weather != null && !current.weatherStarted && !calls.active()) {
            stopFeed(current)
            startWeather(current)
            return
        }
        stopNewsPlayback()
        reportNewsDone()
        publish()
    }

    private fun startWeather(current: News) {
        current.weatherStarted = true
        val spoken = current.forecast
        when {
            spoken != null -> sayWeather(current, spoken)
            current.forecastArrived -> newsEnded(current)
            else -> {
                current.phase = Phase.WaitingForWeather
                publish()
                cancelWeatherWait = timers.after(WEATHER_WAIT) { weatherLate(current) }
            }
        }
    }

    @Synchronized
    private fun weatherArrived(current: News, result: SpokenText?) {
        if (news !== current) return
        current.forecast = result
        current.forecastArrived = true
        if (current.phase != Phase.WaitingForWeather) return
        cancelWeatherWait()
        if (result != null) sayWeather(current, result) else newsEnded(current)
    }

    @Synchronized
    private fun weatherLate(current: News) {
        if (news === current && current.phase == Phase.WaitingForWeather) newsEnded(current)
    }

    private fun sayWeather(current: News, spoken: SpokenText) {
        current.phase = Phase.SpeakingWeather
        publish()
        say(spoken.text, spoken.language) { newsEnded(current) }
    }

    private fun reportNewsDone() {
        val finished = newsAlarms.keys.toList()
        newsAlarms.clear()
        finished.forEach { report(it, AlarmEvent.NewsDone) }
    }

    /** Reporting the same state twice does nothing, so a mode that changes from one kind of call to another is no event here. */
    @Synchronized
    private fun callChanged(active: Boolean) {
        if (members.isEmpty()) return
        if (active) callStarted() else callEnded()
    }

    /**
     * Speech stops, and what is left to say is skipped. A podcast that plays waits for the call to end, and the limit keeps
     * running meanwhile. One that has not started yet counts as not having been played, as a call was there when it would.
     */
    private fun callStarted() {
        focus.release()
        val talking = speaking
        val current = news
        val phase = current?.phase
        when {
            talking != null -> {
                abandonSpeech()
                report(talking.alarmId, AlarmEvent.AnnouncementDone)
                speakNextTitle()
            }
            current == null || phase == Phase.Fetching -> Unit
            phase is Phase.Playing && phase.started -> {
                current.paused = true
                podcast.pause()
                publish()
            }
            else -> newsEnded(current)
        }
    }

    private fun callEnded() {
        val current = news
        if (current == null || !current.paused) return
        current.paused = false
        focus.take(::focusLost)
        podcast.resume()
        publish()
    }

    /**
     * The user started other audio, which ends the automation. A call that takes the focus is not that: it is handled above,
     * also for an internet call that takes it for good.
     */
    @Synchronized
    private fun focusLost() {
        if (!calls.active()) endForEveryAlarm()
    }

    /** Stops what plays, and ends the automation for every alarm in it, through the state machine. */
    private fun endForEveryAlarm() {
        if (speaking != null) abandonSpeech()
        stopNewsPlayback()
        waitingTitles.clear()
        newsAlarms.clear()
        members.toList().forEach { report(it, AlarmEvent.StopWakeUp) }
        publish()
    }

    private fun stopNewsPlayback() {
        val current = news ?: return
        news = null
        cancelNewsLimit()
        cancelWeatherFetch()
        cancelWeatherWait()
        stopFeed(current)
    }

    /** What plays stops, and what was on its way for it. The weather that is being fetched goes on. */
    private fun stopFeed(current: News) {
        cancelFetch()
        cancelStartLimit()
        cancelPause()
        val phase = current.phase
        if (phase is Phase.Reading || phase == Phase.Unavailable || phase == Phase.SpeakingWeather) {
            speechRun++
            cancelSpeechLimit()
            speech.stop()
        } else if (phase is Phase.Playing) {
            podcast.stop()
        }
    }

    private fun finish() {
        pauseBeforeNews = false
        if (speaking != null) abandonSpeech()
        stopNewsPlayback()
        speech.stop()
        titles.clear()
        titleIndex = 0
        titleCount = 0
        focus.release()
        stopWatchingCalls()
        wakeLock.release()
        publish()
    }

    private fun publish() = onChanged(status())

    private fun playing(): WakeUpPlaying? {
        speaking?.let {
            return WakeUpPlaying.Speaking(it.text, titleIndex, titleCount, speakingSince)
        }
        val current = news ?: return null
        val source = current.source
        val weather = current.step.weather?.let { WakeUpPlaying.Weather(it.placeName) }
        return when (val phase = current.phase) {
            Phase.Fetching -> if (source != null) WakeUpPlaying.GettingNews(feedFallbackName(source.url)) else weather
            Phase.Unavailable -> source?.let { WakeUpPlaying.NewsUnavailable(it.title ?: feedFallbackName(it.url)) }
            Phase.WaitingForWeather, Phase.SpeakingWeather -> weather
            is Phase.Reading -> WakeUpPlaying.ReadingNews(
                phase.feed.name,
                phase.index + 1,
                phase.feed.entries.size,
                current.startedAt,
                limitOf(current.step),
            )
            is Phase.Playing -> WakeUpPlaying.PlayingPodcast(
                phase.feed.name,
                phase.feed.author,
                phase.episode.title,
                phase.started,
                if (phase.started) podcast.position() else Duration.ZERO,
                (if (phase.started) podcast.duration() else null) ?: phase.episode.duration,
                limitOf(current.step),
            )
        }
    }

    /** The feed's maximum duration. The weather alone has no setting, as it is one sentence. */
    private fun limitOf(step: NewsStep): Duration =
        step.source?.let { Duration.ofMinutes(it.maxMinutes.toLong()) } ?: WEATHER_ONLY_LIMIT

    private fun speechLimit(text: String): Duration = SPEECH_BASE_LIMIT.plus(SPEECH_LIMIT_PER_CHARACTER.multipliedBy(text.length.toLong()))

    companion object {
        /** What is said when the feed can't be had. It is not news in every case: the feed is up to the user. */
        const val NEWS_UNAVAILABLE_TEXT = "The feed isn't available."

        /** Long enough for an engine to start and for most texts to be said. */
        private val SPEECH_BASE_LIMIT: Duration = Duration.ofSeconds(30)

        /** Far slower than anyone speaks. */
        private val SPEECH_LIMIT_PER_CHARACTER: Duration = Duration.ofMillis(200)

        /** Until there is news to hold it for. It only has to outlast the titles. */
        private val SPEECH_WAKE_LOCK: Duration = Duration.ofMinutes(10)
        private val NEWS_WAKE_LOCK_MARGIN: Duration = Duration.ofMinutes(1)

        /** Long enough for one sentence, for a step that is only the weather. */
        private val WEATHER_ONLY_LIMIT: Duration = Duration.ofMinutes(2)

        /** How long the weather may still take after the feed has ended. Most of its time it had while the feed played. */
        private val WEATHER_WAIT: Duration = Duration.ofSeconds(15)

        private val NOTHING_TO_CANCEL: () -> Unit = {}
        private val NO_WEATHER = WeatherFetcher { _, onResult ->
            onResult(null)
            NOTHING_TO_CANCEL
        }

        /** A stream that does not start by then is taken to have failed, rather than leaving the user in silence. */
        private val PODCAST_START_LIMIT: Duration = Duration.ofSeconds(45)
    }
}
