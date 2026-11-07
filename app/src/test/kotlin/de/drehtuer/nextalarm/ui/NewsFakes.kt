package de.drehtuer.nextalarm.ui

import de.drehtuer.nextalarm.domain.Feed
import de.drehtuer.nextalarm.domain.FeedEntry
import de.drehtuer.nextalarm.domain.FeedResult
import de.drehtuer.nextalarm.domain.FeedSource
import de.drehtuer.nextalarm.domain.NewsTestPlayer
import de.drehtuer.nextalarm.domain.NewsType
import de.drehtuer.nextalarm.domain.PodcastEpisode
import de.drehtuer.nextalarm.domain.PodcastOutput
import de.drehtuer.nextalarm.domain.RingTimers
import de.drehtuer.nextalarm.domain.RingWakeLock
import de.drehtuer.nextalarm.domain.SpeechOutput
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred

val RSS_FEED = Feed(
    NewsType.RSS,
    "tagesschau.de",
    null,
    listOf(FeedEntry("One", null, null), FeedEntry("Two", null, null)),
    null,
)

val PODCAST_FEED = Feed(
    NewsType.PODCAST,
    "Der Tag",
    "Deutschlandfunk",
    listOf(FeedEntry("Friday", null, null)),
    PodcastEpisode("Friday", "https://example.org/friday.mp3", Duration.ofMinutes(18), null),
)

/** Answers without a network: at once with [result], or when the test completes [waiting]. */
class FakeFeeds(var result: FeedResult = FeedResult.Found(RSS_FEED)) : FeedSource {
    val asked = mutableListOf<String>()
    var waiting: CompletableDeferred<FeedResult>? = null

    override suspend fun fetchFeed(url: String): FeedResult {
        asked += url
        return waiting?.await() ?: result
    }
}

class FakeSpeech : SpeechOutput {
    val said = mutableListOf<String>()
    var pending: ((Boolean) -> Unit)? = null
    var immediate: Boolean? = null

    override fun speak(text: String, onDone: (Boolean) -> Unit) {
        said += text
        val answer = immediate
        if (answer != null) onDone(answer) else pending = onDone
    }

    override fun stop() {
        pending = null
    }
}

class FakePodcast : PodcastOutput {
    val played = mutableListOf<String>()
    var onStarted: () -> Unit = {}
    var onEnded: () -> Unit = {}
    var stops = 0
    var position: Duration = Duration.ZERO

    override fun play(url: String, onStarted: () -> Unit, onEnded: () -> Unit) {
        played += url
        this.onStarted = onStarted
        this.onEnded = onEnded
    }

    override fun position() = position
    override fun duration(): Duration? = null

    override fun stop() {
        stops++
    }
}

class FakeNewsTimers : RingTimers {
    val waiting = linkedMapOf<Duration, () -> Unit>()

    override fun after(delay: Duration, block: () -> Unit): () -> Unit {
        waiting[delay] = block
        return { waiting.remove(delay) }
    }
}

class FakeNewsWakeLock : RingWakeLock {
    var held = false

    override fun hold(atMost: Duration) {
        held = true
    }

    override fun release() {
        held = false
    }
}

/** A complete set of parts for screen 6. */
class NewsParts(now: () -> Instant) {
    val feeds = FakeFeeds()
    val speech = FakeSpeech()
    val podcast = FakePodcast()
    val timers = FakeNewsTimers()
    val wakeLock = FakeNewsWakeLock()
    val ports = NewsPorts(feeds, NewsTestPlayer(speech, podcast, timers, wakeLock, now))
}
