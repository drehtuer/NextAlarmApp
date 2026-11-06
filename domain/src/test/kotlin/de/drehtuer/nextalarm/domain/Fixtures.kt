package de.drehtuer.nextalarm.domain

import java.time.Instant

val NOW: Instant = Instant.parse("2026-11-06T05:00:00Z")

val NEWS = NewsSource(NewsType.PODCAST, "https://example.org/feed.xml")

fun settings(speakReason: Boolean = true, news: NewsSource? = null, vibrate: Boolean = true) = Settings(
    alarmCalendar = CalendarId(1),
    sound = SoundRef.Fallback,
    vibrate = vibrate,
    volumeRampSeconds = 30,
    maxVolume = 1f,
    speakReason = speakReason,
    news = news,
)

fun snapshot(reason: String? = "Flight", speakReason: Boolean = true, news: NewsSource? = null, vibrate: Boolean = true) =
    settings(speakReason, news, vibrate).snapshotFor(reason)
