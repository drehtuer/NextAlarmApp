package de.drehtuer.nextalarm.domain

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// What screen 6 says and allows. The wording is specified in docs/design.md.

const val MIN_NEWS_MINUTES = 5
const val MAX_NEWS_MINUTES = 60
private const val NEWS_MINUTES_STEP = 5

private const val SECONDS_PER_MINUTE = 60
private const val HALF_A_MINUTE_SECONDS = 30
private val EPISODE_DATE = DateTimeFormatter.ofPattern("d MMMM", Locale.ENGLISH)

/** The next longer step of the stepper. A stored value between two steps goes to the next one. */
fun longerNewsMinutes(minutes: Int): Int =
    ((minutes / NEWS_MINUTES_STEP + 1) * NEWS_MINUTES_STEP).coerceIn(MIN_NEWS_MINUTES, MAX_NEWS_MINUTES)

/** The next shorter step of the stepper. A stored value between two steps goes to the one before it. */
fun shorterNewsMinutes(minutes: Int): Int =
    ((minutes - 1) / NEWS_MINUTES_STEP * NEWS_MINUTES_STEP).coerceIn(MIN_NEWS_MINUTES, MAX_NEWS_MINUTES)

/** What is known about what the address returned. */
sealed interface NewsCheck {
    /** Not asked, or it failed while the user was still typing, which is not worth a complaint. */
    data object None : NewsCheck

    /** A feed that can be played. */
    data class Found(val feed: Feed) : NewsCheck

    /** A feed, but the phone could not play it. */
    data class Unplayable(val feed: Feed) : NewsCheck

    /** A test found no feed. */
    data class Failed(val failure: FeedFailure) : NewsCheck

    /** A test found a feed with nothing in it. */
    data object Empty : NewsCheck
}

/** A feed with nothing to read or play is of no use to an alarm, so it does not count as working. */
fun newsCheck(result: FeedResult): NewsCheck = when (result) {
    is FeedResult.Failed -> NewsCheck.Failed(result.failure)
    is FeedResult.Found -> when {
        result.feed.kind == NewsType.PODCAST && result.feed.latestEpisode == null -> NewsCheck.Empty
        result.feed.kind == NewsType.RSS && result.feed.entries.isEmpty() -> NewsCheck.Empty
        else -> NewsCheck.Found(result.feed)
    }
}

/** What the user has entered on screen 6, and what the settings hold. */
data class NewsDraft(
    val address: String = "",
    val minutes: Int = NewsSource.DEFAULT_NEWS_MINUTES,
    val saved: NewsSource? = null,
    val check: NewsCheck = NewsCheck.None,
)

/**
 * What gets stored for an address. It takes a [NewsCheck.Found], because nothing else may be stored: a broken address
 * never replaces what the alarms use.
 */
fun newsSourceToSave(draft: NewsDraft, found: NewsCheck.Found): NewsSource =
    NewsSource(found.feed.kind, draft.address.trim(), draft.minutes)

/** How far a test has got. */
sealed interface NewsTestProgress {
    /** Waiting for the feed. */
    data class Getting(val address: String) : NewsTestProgress

    /** Empty for the moment before the first or after the last thing is played. */
    data class Playing(val playing: WakeUpPlaying?) : NewsTestProgress
}

data class NewsTestLine(
    val text: String,
    /** Between 0 and 1. Empty if there is no telling, for which the bar just shows that something is happening. */
    val progress: Float?,
)

data class DetectedFeedModel(
    val kind: NewsType,
    val kindLine: String,
    val name: String,
    /** Empty if the feed says nothing that could be shown. */
    val detail: String?,
)

/** What screen 6 shows. */
data class NewsModel(
    val address: String,
    val helper: String,
    /** The field shows its error border. */
    val addressFailed: Boolean,
    val detected: DetectedFeedModel?,
    val minutes: String,
    val canShorten: Boolean,
    val canLengthen: Boolean,
    val testing: Boolean,
    val canTest: Boolean,
    val testLine: NewsTestLine?,
    val failure: BannerText?,
    val canTurnOff: Boolean,
)

fun newsKindLine(kind: NewsType): String = when (kind) {
    NewsType.RSS -> "RSS feed · read aloud"
    NewsType.PODCAST -> "Podcast · latest episode plays"
}

private const val NO_FEED_HELPER = "This address didn't return a feed."

private fun foundHelper(kind: NewsType): String = when (kind) {
    NewsType.RSS -> "RSS feed found."
    NewsType.PODCAST -> "Podcast found."
}

/** The helper text below the field. */
fun newsHelper(check: NewsCheck): String = when (check) {
    NewsCheck.None -> "Paste the address of an RSS or podcast feed."
    is NewsCheck.Found -> foundHelper(check.feed.kind)
    is NewsCheck.Unplayable -> foundHelper(check.feed.kind)
    is NewsCheck.Failed ->
        if (check.failure == FeedFailure.InsecureFeed) "RSS feeds need an https address." else NO_FEED_HELPER
    NewsCheck.Empty -> NO_FEED_HELPER
}

/** "12 headlines today", or how many there are if none of them is from today, or the feed gives no dates. */
fun headlinesDetail(entries: List<FeedEntry>, today: LocalDate, zone: ZoneId): String {
    val ofToday = entries.count { it.published?.atZone(zone)?.toLocalDate() == today }
    return when {
        ofToday == 1 -> "1 headline today"
        ofToday > 1 -> "$ofToday headlines today"
        entries.size == 1 -> "1 headline"
        else -> "${entries.size} headlines"
    }
}

private fun episodeDay(published: Instant, today: LocalDate, zone: ZoneId): String {
    val day = published.atZone(zone).toLocalDate()
    return when (day) {
        today -> "today"
        today.minusDays(1) -> "yesterday"
        else -> day.format(EPISODE_DATE)
    }
}

/** At least one minute: an episode of 20 seconds is not nothing. */
private fun episodeLength(duration: Duration): String {
    val minutes = (duration.seconds + HALF_A_MINUTE_SECONDS) / SECONDS_PER_MINUTE
    return "${minutes.coerceAtLeast(1)} min"
}

/** "Deutschlandfunk · today, 18 min", with whichever of these the feed gives. Empty if it gives none of them. */
fun podcastDetail(author: String?, episode: PodcastEpisode?, today: LocalDate, zone: ZoneId): String? {
    val about = listOfNotNull(
        episode?.published?.let { episodeDay(it, today, zone) },
        episode?.duration?.let(::episodeLength),
    ).joinToString(", ").ifEmpty { null }
    return listOfNotNull(author, about).joinToString(" · ").ifEmpty { null }
}

private fun detectedFeed(feed: Feed, now: Instant, zone: ZoneId): DetectedFeedModel {
    val today = now.atZone(zone).toLocalDate()
    return DetectedFeedModel(
        kind = feed.kind,
        kindLine = newsKindLine(feed.kind),
        name = feed.name,
        detail = when (feed.kind) {
            NewsType.RSS -> headlinesDetail(feed.entries, today, zone)
            NewsType.PODCAST -> podcastDetail(feed.author, feed.latestEpisode, today, zone)
        },
    )
}

private fun failureReason(failure: FeedFailure): String = when (failure) {
    FeedFailure.InvalidAddress -> "This isn't a web address: it has to start with https:// or http://."
    is FeedFailure.Http -> {
        val answer = listOfNotNull(failure.code, failure.message?.ifBlank { null }).joinToString(" ")
        "The server answered “$answer”."
    }
    FeedFailure.Unreachable -> "The server can't be reached, or the phone has no connection."
    FeedFailure.TooLarge -> "The answer is too large to be read as a feed."
    FeedFailure.NotAFeed -> "The answer isn't a feed NextAlarm can read."
    FeedFailure.InsecureFeed -> "This RSS feed uses plain http, which NextAlarm only allows for podcasts. Use its https address."
}

private fun playbackFailure(kind: NewsType): String = when (kind) {
    NewsType.RSS -> "The feed was found, but the phone couldn't read it aloud. Check its text-to-speech settings, then try again."
    NewsType.PODCAST -> "The podcast was found, but its latest episode wouldn't play. Check the connection, then try again."
}

/**
 * The end of the banner says what happens at the next alarm. If another feed that worked is still stored, that one goes
 * on playing, as a broken address never replaces it.
 */
private fun untilItWorks(draft: NewsDraft): String {
    val saved = draft.saved
    return if (saved != null && saved.url != draft.address.trim()) {
        "Until it works, alarms keep using ${feedFallbackName(saved.url)}."
    } else {
        "Until it works, alarms skip the news."
    }
}

/**
 * The banner of a failed test, or of a feed that no test can fix: unlike an address that gave nothing, plain http is
 * known not to be a typing mistake, so it also comes from the detection, with a title of its own.
 */
fun newsFailureBanner(draft: NewsDraft): BannerText? {
    val reason = when (val check = draft.check) {
        NewsCheck.None, is NewsCheck.Found -> return null
        NewsCheck.Failed(FeedFailure.InsecureFeed) ->
            return BannerText("This feed can't be used.", "${failureReason(FeedFailure.InsecureFeed)} ${untilItWorks(draft)}")
        is NewsCheck.Unplayable -> playbackFailure(check.feed.kind)
        is NewsCheck.Failed -> "${failureReason(check.failure)} Check the address, then try again."
        NewsCheck.Empty -> "The feed has nothing in it to play. Check the address, then try again."
    }
    return BannerText("Test failed.", "$reason ${untilItWorks(draft)}")
}

private fun playingLine(name: String, elapsed: Duration, total: Duration, progress: Float?) =
    NewsTestLine("Playing “$name” · ${formatPlayTime(elapsed)} of ${formatPlayTime(total)}", progress)

/** The line below the button while a test runs. A shorter episode counts up to its own end, not to the limit. */
fun newsTestLine(progress: NewsTestProgress, now: Instant): NewsTestLine = when (progress) {
    is NewsTestProgress.Getting -> NewsTestLine("Getting the news · ${feedFallbackName(progress.address)}", null)
    is NewsTestProgress.Playing -> when (val playing = progress.playing) {
        is WakeUpPlaying.ReadingNews -> {
            val elapsed = Duration.between(playing.startedAt, now)
            playingLine(playing.feedName, elapsed, playing.limit, fraction(elapsed, playing.limit))
        }
        is WakeUpPlaying.PlayingPodcast -> {
            val total = minOf(playing.duration ?: playing.limit, playing.limit)
            playingLine(playing.feedName, playing.position, total, if (playing.started) fraction(playing.position, total) else null)
        }
        else -> NewsTestLine("Getting the news", null)
    }
}

/** Screen 6 at [now], with a test at [test] if one runs. */
fun buildNews(draft: NewsDraft, test: NewsTestProgress?, now: Instant, zone: ZoneId): NewsModel {
    val feed = when (val check = draft.check) {
        is NewsCheck.Found -> check.feed
        is NewsCheck.Unplayable -> check.feed
        else -> null
    }
    return NewsModel(
        address = draft.address,
        helper = newsHelper(draft.check),
        addressFailed = draft.check is NewsCheck.Failed || draft.check == NewsCheck.Empty,
        detected = feed?.let { detectedFeed(it, now, zone) },
        minutes = minutesLabel(draft.minutes),
        // A running test keeps the limit it started with, and the line below the button names it.
        canShorten = test == null && draft.minutes > MIN_NEWS_MINUTES,
        canLengthen = test == null && draft.minutes < MAX_NEWS_MINUTES,
        testing = test != null,
        canTest = test != null || draft.address.isNotBlank(),
        testLine = test?.let { newsTestLine(it, now) },
        failure = newsFailureBanner(draft),
        canTurnOff = draft.saved != null || draft.address.isNotEmpty(),
    )
}

/**
 * Plays a feed for the test on screen 6, with the parts of the wake-up automation and by its rules: `WakeUpDirector`
 * plays it as the news of an alarm that exists nowhere else, so the limits and the stream are the ones an alarm would
 * get, also during a call and when other audio starts. Its parts are its own, so that a test cannot get into the
 * automation's state.
 */
class NewsTestPlayer(
    speech: SpeechOutput,
    podcast: PodcastOutput,
    timers: RingTimers,
    wakeLock: RingWakeLock,
    focus: AudioFocusHold,
    private val calls: CallWatch,
    private val now: () -> Instant,
) {
    private var feed: FeedResult = FeedResult.Failed(FeedFailure.NotAFeed)
    private var stopsAt: Instant = Instant.EPOCH
    private var onEnded: (Boolean) -> Unit = {}

    /** Something was heard, which is the difference between a test that came to its end and one that failed. */
    @Volatile
    private var heard = false

    private val director = WakeUpDirector(
        speech = HeardSpeech(speech),
        podcast = HeardPodcast(podcast),
        // The caller has just fetched it, so it is not fetched twice, and is the same version as the one on screen.
        feeds = { _, onResult ->
            onResult(feed)
            NOTHING_TO_CANCEL
        },
        timers = timers,
        wakeLock = wakeLock,
        focus = focus,
        calls = calls,
        now = now,
        // Only the news is ever asked for, so its end or being stopped by other audio is all that is ever reported.
        report = { _, event -> ended(event) },
        onChanged = {},
    )

    /**
     * Plays [feed] until it ends, the limit of [source] is reached, or [stop]. `onEnded` is told whether it worked, can be
     * called on any thread and is not called after [stop].
     */
    fun play(source: NewsSource, feed: Feed, onEnded: (Boolean) -> Unit) {
        stop()
        this.feed = FeedResult.Found(feed)
        this.onEnded = onEnded
        heard = false
        stopsAt = now().plus(Duration.ofMinutes(source.maxMinutes.toLong()))
        director.startNews(testAlarm(source))
    }

    /** What plays right now, including how far a podcast has got. */
    fun playing(): WakeUpPlaying? = director.status().playing

    fun stop() {
        director.leave(TEST_ALARM_ID)
    }

    /**
     * Reaching the limit before the first utterance is done is no failure: a long first entry can take that long. Neither is
     * a call or other audio getting in the way, as these say nothing about the feed.
     */
    private fun ended(event: AlarmEvent) {
        val worked = heard || !now().isBefore(stopsAt) || event == AlarmEvent.StopWakeUp || calls.active()
        director.leave(TEST_ALARM_ID)
        onEnded(worked)
    }

    private fun testAlarm(source: NewsSource) = AlarmInstance(
        id = TEST_ALARM_ID,
        key = AlarmKey.forTestAlarm(TEST_ALARM_ID),
        triggerAt = stopsAt,
        state = AlarmState.NewsPlaying,
        snoozeCount = 0,
        autoSnoozed = false,
        snapshot = Settings.DEFAULT.snapshotFor(null).copy(news = source),
    )

    private inner class HeardSpeech(private val output: SpeechOutput) : SpeechOutput by output {
        override fun speak(text: String, onDone: (Boolean) -> Unit) {
            output.speak(text) { worked ->
                if (worked) heard = true
                onDone(worked)
            }
        }
    }

    private inner class HeardPodcast(private val output: PodcastOutput) : PodcastOutput by output {
        override fun play(url: String, onStarted: () -> Unit, onEnded: () -> Unit) {
            output.play(
                url,
                onStarted = {
                    heard = true
                    onStarted()
                },
                onEnded = onEnded,
            )
        }
    }

    private companion object {
        const val TEST_ALARM_ID = -1L
        val NOTHING_TO_CANCEL: () -> Unit = {}
    }
}
