package de.drehtuer.nextalarm.domain

import java.time.Duration
import java.time.Instant

/**
 * Says one text at a time, through the media stream, with the device's default engine, language and voice.
 * `onDone` is told whether it worked, and is not called after [stop].
 */
interface SpeechOutput {
    fun speak(text: String, onDone: (Boolean) -> Unit)

    /** Stops at once, and gives up the engine until the next [speak]. */
    fun stop()
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
    fun stop()
}

/**
 * Holds audio back until the wake-up service is in the foreground, as Android 17 silences it otherwise, with no error. Only
 * one thing plays at a time, so only the latest action waits. After [limit] it goes ahead anyway, as a chance of sound beats
 * none. See docs/architecture.md#background-audio-on-android-17.
 */
class ForegroundGate(
    private val timers: RingTimers,
    private val limit: Duration = Duration.ofSeconds(3),
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
        if (now) action()
    }

    fun open() {
        val action = synchronized(this) {
            isOpen = true
            cancelLimit()
            waiting.also { waiting = null }
        }
        action?.invoke()
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
    override fun speak(text: String, onDone: (Boolean) -> Unit) = gate.whenOpen { output.speak(text, onDone) }

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
 * All of this runs under a lock of its own, and the engine's is held when [startAnnouncement], [startNews] and [leave]
 * are called: [report] must therefore not call the engine directly.
 */
class WakeUpDirector(
    private val speech: SpeechOutput,
    private val podcast: PodcastOutput,
    private val feeds: NewsFetcher,
    private val timers: RingTimers,
    private val wakeLock: RingWakeLock,
    private val now: () -> Instant,
    private val report: (Long, AlarmEvent) -> Unit,
    private val onChanged: (WakeUpStatus) -> Unit,
) : WakeUpAutomation {
    private sealed interface Phase {
        data object Fetching : Phase
        class Reading(val feed: Feed, val index: Int) : Phase
        class Playing(val feed: Feed, val episode: PodcastEpisode, val started: Boolean) : Phase
    }

    private class News(val source: NewsSource, val startedAt: Instant) {
        var phase: Phase = Phase.Fetching
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

    private val newsAlarms = linkedMapOf<Long, NewsSource>()
    private var news: News? = null

    /** Told apart from each other, because a callback or a timer can arrive just after what it belongs to has ended. */
    private var speechRun = 0

    private var onSpoken: (Boolean) -> Unit = {}
    private var cancelSpeechLimit: () -> Unit = {}
    private var cancelNewsLimit: () -> Unit = {}
    private var cancelFetch: () -> Unit = {}
    private var cancelStartLimit: () -> Unit = {}

    @Synchronized
    override fun startAnnouncement(alarm: AlarmInstance) {
        join(alarm)
        val title = alarm.snapshot.reason
        if (title == null) {
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
        val source = alarm.snapshot.news
        if (source == null) {
            report(alarm.id, AlarmEvent.NewsDone)
            publish()
            return
        }
        newsAlarms[alarm.id] = source
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

    /** As it is right now, including how far a podcast has got. */
    @Synchronized
    fun status(): WakeUpStatus = WakeUpStatus(
        titles = titles.values.toList(),
        playing = playing(),
        newsStopsAt = news?.let { it.startedAt.plus(limitOf(it.source)) },
    )

    private fun join(alarm: AlarmInstance) {
        if (members.isEmpty()) wakeLock.hold(SPEECH_WAKE_LOCK)
        members += alarm.id
        titles[alarm.id] = alarm.snapshot.reason
    }

    private fun speakNextTitle() {
        val title = waitingTitles.removeFirstOrNull()
        if (title == null) {
            titleIndex = 0
            titleCount = 0
            if (newsAlarms.isNotEmpty()) startNewsPlayback() else publish()
            return
        }
        speaking = title
        speakingSince = now()
        titleIndex++
        publish()
        say(title.text) {
            speaking = null
            report(title.alarmId, AlarmEvent.AnnouncementDone)
            speakNextTitle()
        }
    }

    /**
     * A failed title is treated like a spoken one, and failed reading ends the news, so there is only this one way out.
     * The limit is for an engine that never answers, which would otherwise keep the automation waiting.
     */
    private fun say(text: String, onDone: (Boolean) -> Unit) {
        val run = ++speechRun
        onSpoken = onDone
        cancelSpeechLimit()
        cancelSpeechLimit = timers.after(speechLimit(text)) { speechTimedOut(run) }
        speech.speak(text) { worked -> spoken(run, worked) }
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
        val source = newsAlarms.values.first()
        val current = News(source, now())
        news = current
        wakeLock.hold(limitOf(source).plus(NEWS_WAKE_LOCK_MARGIN))
        cancelNewsLimit = timers.after(limitOf(source)) { newsEnded(current) }
        publish()
        cancelFetch = feeds.fetch(source.url) { result -> feedArrived(current, result) }
    }

    @Synchronized
    private fun feedArrived(current: News, result: FeedResult) {
        if (news !== current) return
        val feed = (result as? FeedResult.Found)?.feed
        val episode = feed?.latestEpisode
        when {
            feed == null -> newsEnded(current)
            current.source.type == NewsType.PODCAST ->
                if (episode != null) playEpisode(current, feed, episode) else newsEnded(current)
            feed.entries.isEmpty() -> newsEnded(current)
            else -> readEntry(current, feed, 0)
        }
    }

    private fun readEntry(current: News, feed: Feed, index: Int) {
        current.phase = Phase.Reading(feed, index)
        publish()
        say(feed.entries[index].spokenText) { worked ->
            if (worked && index + 1 < feed.entries.size) readEntry(current, feed, index + 1) else newsEnded(current)
        }
    }

    private fun playEpisode(current: News, feed: Feed, episode: PodcastEpisode) {
        current.phase = Phase.Playing(feed, episode, started = false)
        publish()
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

    /** The news played to its end, reached its limit, or failed: the state machine does not tell these apart. */
    @Synchronized
    private fun newsEnded(current: News) {
        if (news !== current) return
        stopNewsPlayback()
        val finished = newsAlarms.keys.toList()
        newsAlarms.clear()
        finished.forEach { report(it, AlarmEvent.NewsDone) }
        publish()
    }

    private fun stopNewsPlayback() {
        val current = news ?: return
        news = null
        cancelNewsLimit()
        cancelFetch()
        cancelStartLimit()
        val phase = current.phase
        if (phase is Phase.Reading) {
            speechRun++
            cancelSpeechLimit()
            speech.stop()
        } else if (phase is Phase.Playing) {
            podcast.stop()
        }
    }

    private fun finish() {
        if (speaking != null) abandonSpeech()
        stopNewsPlayback()
        speech.stop()
        titles.clear()
        titleIndex = 0
        titleCount = 0
        wakeLock.release()
        publish()
    }

    private fun publish() = onChanged(status())

    private fun playing(): WakeUpPlaying? {
        speaking?.let {
            return WakeUpPlaying.Speaking(it.text, titleIndex, titleCount, speakingSince)
        }
        val current = news ?: return null
        return when (val phase = current.phase) {
            Phase.Fetching -> WakeUpPlaying.GettingNews(feedFallbackName(current.source.url))
            is Phase.Reading -> WakeUpPlaying.ReadingNews(
                phase.feed.name,
                phase.index + 1,
                phase.feed.entries.size,
                current.startedAt,
                limitOf(current.source),
            )
            is Phase.Playing -> WakeUpPlaying.PlayingPodcast(
                phase.feed.name,
                phase.feed.author,
                phase.episode.title,
                phase.started,
                if (phase.started) podcast.position() else Duration.ZERO,
                (if (phase.started) podcast.duration() else null) ?: phase.episode.duration,
                limitOf(current.source),
            )
        }
    }

    private fun limitOf(source: NewsSource): Duration = Duration.ofMinutes(source.maxMinutes.toLong())

    private fun speechLimit(text: String): Duration = SPEECH_BASE_LIMIT.plus(SPEECH_LIMIT_PER_CHARACTER.multipliedBy(text.length.toLong()))

    private companion object {
        /** Long enough for an engine to start and for most texts to be said. */
        val SPEECH_BASE_LIMIT: Duration = Duration.ofSeconds(30)

        /** Far slower than anyone speaks. */
        val SPEECH_LIMIT_PER_CHARACTER: Duration = Duration.ofMillis(200)

        /** Until there is news to hold it for. It only has to outlast the titles. */
        val SPEECH_WAKE_LOCK: Duration = Duration.ofMinutes(10)
        val NEWS_WAKE_LOCK_MARGIN: Duration = Duration.ofMinutes(1)

        /** A stream that does not start by then is taken to have failed, rather than leaving the user in silence. */
        val PODCAST_START_LIMIT: Duration = Duration.ofSeconds(45)
    }
}
