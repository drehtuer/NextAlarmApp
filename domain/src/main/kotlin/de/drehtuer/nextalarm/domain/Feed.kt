package de.drehtuer.nextalarm.domain

import java.net.URI
import java.net.URISyntaxException
import java.time.Duration
import java.time.Instant

/** An article of a feed, as text that can be read aloud. */
data class FeedEntry(
    val title: String,
    val summary: String?,
    val published: Instant?,
) {
    /** What is read aloud. It fits into one `TextToSpeech` utterance, because the parser limits both parts. */
    val spokenText: String
        get() = when {
            summary == null || summary == title -> title
            title.last() in SENTENCE_ENDS -> "$title $summary"
            else -> "$title. $summary"
        }

    private companion object {
        const val SENTENCE_ENDS = ".!?:"
    }
}

/** The audio of a podcast. [audioUrl] has been checked to be http or https. */
data class PodcastEpisode(
    val title: String,
    val audioUrl: String,
    /** What the feed says. The player knows better, once it has started. */
    val duration: Duration?,
    val published: Instant?,
)

/**
 * What a feed address returned. A feed with an audio enclosure is a podcast: [kind] is `PODCAST` and [latestEpisode] is
 * set. Any other feed is `RSS`. [entries] holds its articles in both cases, newest as the feed orders them.
 */
data class Feed(
    val kind: NewsType,
    /** The feed's title, or the host of its address if it has none. */
    val name: String,
    /** The podcast's publisher ("Deutschlandfunk"). */
    val author: String?,
    val entries: List<FeedEntry>,
    val latestEpisode: PodcastEpisode?,
)

/** Why an address gave no feed. */
sealed interface FeedFailure {
    /** It is not an http or https address. Also when a server redirects to something else. */
    data object InvalidAddress : FeedFailure

    /** The server answered with an error, such as 404. */
    data class Http(val code: Int, val message: String?) : FeedFailure

    /** No network, no such server, a timeout, or too many redirects. */
    data object Unreachable : FeedFailure

    /** Over [MAX_FEED_BYTES]. */
    data object TooLarge : FeedFailure

    /** The answer is not a feed, or not one that is safe to read. */
    data object NotAFeed : FeedFailure
}

sealed interface FeedResult {
    data class Found(val feed: Feed) : FeedResult
    data class Failed(val failure: FeedFailure) : FeedResult
}

/** The result of a download, in memory and at most as large as asked for. */
sealed interface FeedDownload {
    class Bytes(val bytes: ByteArray) : FeedDownload
    data class Failed(val failure: FeedFailure) : FeedDownload
}

/** Downloads a feed. It blocks, and it stays within `maxBytes` however much the server sends. */
fun interface FeedDownloader {
    fun download(url: String, maxBytes: Long): FeedDownload
}

/**
 * Finds out what is at an address. This is what the wake-up automation uses, and the news screen for its detection and
 * test: `DataComponents.feeds` provides it. It reports failures in its result and does not throw them.
 */
fun interface FeedSource {
    suspend fun fetchFeed(url: String): FeedResult
}

/** Podcast feeds with a long back catalogue can be several megabytes. Nothing beyond this is kept in memory. */
const val MAX_FEED_BYTES: Long = 16L * 1024 * 1024

/** Downloads and parses the feed at [url]. It blocks. */
fun readFeed(url: String, downloader: FeedDownloader): FeedResult {
    val address = url.trim()
    if (!isWebAddress(address)) return FeedResult.Failed(FeedFailure.InvalidAddress)
    return when (val download = downloader.download(address, MAX_FEED_BYTES)) {
        is FeedDownload.Failed -> FeedResult.Failed(download.failure)
        is FeedDownload.Bytes ->
            parseFeed(download.bytes, feedFallbackName(address))?.let { FeedResult.Found(it) }
                ?: FeedResult.Failed(FeedFailure.NotAFeed)
    }
}

/** Addresses come from the user and from feeds, and only these may reach the network or the media player. */
fun isWebAddress(address: String): Boolean = try {
    val uri = URI(address)
    uri.scheme?.lowercase() in WEB_SCHEMES && uri.host != null
} catch (_: URISyntaxException) {
    false
}

/** A name for a feed without a title: "tagesschau.de". */
fun feedFallbackName(address: String): String = try {
    URI(address).host?.removePrefix("www.") ?: address
} catch (_: URISyntaxException) {
    address
}

private val WEB_SCHEMES = setOf("http", "https")
