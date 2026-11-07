package de.drehtuer.nextalarm.domain

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

// What the wake-up screen and the media notification say. The wording is specified in docs/design.md.

private val GREETING_DATE = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH)
private const val SECONDS_PER_MINUTE = 60
private const val SECONDS_PER_HOUR = 3600

enum class PlayingKind { SPEECH, NEWS, PODCAST }

/** The "now playing" card. */
data class NowPlayingModel(
    val kind: PlayingKind,
    val kindLine: String,
    val mainLine: String,
    /** How far it has got. Empty while there is nothing to count. */
    val elapsed: String?,
    /** "18:30" or "up to 15:00". Empty if nothing is known. */
    val total: String?,
    /** Between 0 and 1. Empty if there is no telling, for which the bar just shows that something is happening. */
    val progress: Float?,
)

/** What the wake-up screen (screen 9) shows. */
data class WakeUpScreenModel(
    val greeting: String,
    val time: String,
    /** An entry is empty if the event has no title. */
    val titles: List<String?>,
    /** Empty for the moment between two steps. */
    val playing: NowPlayingModel?,
)

/** The screen at [now], to the minute, like the alarm screen. */
fun wakeUpScreenModel(now: Instant, zone: ZoneId, status: WakeUpStatus): WakeUpScreenModel = WakeUpScreenModel(
    greeting = "Good morning · ${now.atZone(zone).format(GREETING_DATE)}",
    time = formatTime(now.truncatedTo(ChronoUnit.MINUTES), zone),
    titles = status.titles,
    playing = status.playing?.let { nowPlaying(it, now) },
)

private fun nowPlaying(playing: WakeUpPlaying, now: Instant): NowPlayingModel = when (playing) {
    is WakeUpPlaying.Speaking -> NowPlayingModel(
        PlayingKind.SPEECH,
        if (playing.count > 1) "Speaking the titles" else "Speaking the title",
        if (playing.count > 1) "${playing.index} of ${playing.count}: ${playing.title}" else playing.title,
        formatPlayTime(Duration.between(playing.startedAt, now)),
        null,
        null,
    )
    is WakeUpPlaying.GettingNews -> NowPlayingModel(PlayingKind.NEWS, "Getting the news", playing.source, null, null, null)
    is WakeUpPlaying.ReadingNews -> {
        val elapsed = Duration.between(playing.startedAt, now)
        NowPlayingModel(
            PlayingKind.NEWS,
            "Reading the news · ${playing.feedName}",
            "Headline ${playing.index} of ${playing.count}",
            formatPlayTime(elapsed),
            "up to ${formatPlayTime(playing.limit)}",
            fraction(elapsed, playing.limit),
        )
    }
    is WakeUpPlaying.PlayingPodcast -> NowPlayingModel(
        PlayingKind.PODCAST,
        "Podcast · ${playing.author ?: playing.feedName}",
        playing.episodeTitle,
        formatPlayTime(playing.position),
        playing.duration?.let(::formatPlayTime) ?: "up to ${formatPlayTime(playing.limit)}",
        if (playing.started) fraction(playing.position, playing.duration ?: playing.limit) else null,
    )
}

private fun fraction(part: Duration, whole: Duration): Float =
    (part.toMillis().toFloat() / whole.toMillis()).coerceIn(0f, 1f)

/** "4:12", and "1:02:03" for an hour or more. */
fun formatPlayTime(time: Duration): String {
    val seconds = time.seconds.coerceAtLeast(0)
    val minutes = seconds / SECONDS_PER_MINUTE % SECONDS_PER_MINUTE
    val rest = seconds % SECONDS_PER_MINUTE
    return if (seconds >= SECONDS_PER_HOUR) {
        "%d:%02d:%02d".format(Locale.ROOT, seconds / SECONDS_PER_HOUR, minutes, rest)
    } else {
        "%d:%02d".format(Locale.ROOT, minutes, rest)
    }
}

/** For a notification that cannot be about anything yet, because Android demands one before there is something to show. */
val WAKE_UP_PLACEHOLDER_TEXT = NotificationText("NextAlarm", "Good morning")

/** The media notification. */
fun wakeUpNotificationText(status: WakeUpStatus, zone: ZoneId): NotificationText {
    val stopsBy = status.newsStopsAt?.let { "stops by ${formatTime(it, zone)} at the latest" }.orEmpty()
    return when (val playing = status.playing) {
        null -> WAKE_UP_PLACEHOLDER_TEXT
        is WakeUpPlaying.Speaking -> NotificationText(
            if (playing.count > 1) "Speaking the titles" else "Speaking the title",
            playing.title,
        )
        is WakeUpPlaying.GettingNews -> NotificationText("Getting the news", "${playing.source} · $stopsBy")
        is WakeUpPlaying.ReadingNews -> NotificationText("Playing: ${playing.feedName}", "Reading the news · $stopsBy")
        is WakeUpPlaying.PlayingPodcast ->
            NotificationText("Playing: ${playing.feedName}", "${playing.author ?: playing.feedName} podcast · $stopsBy")
    }
}
