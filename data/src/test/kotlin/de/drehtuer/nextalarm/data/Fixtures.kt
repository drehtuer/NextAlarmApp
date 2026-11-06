package de.drehtuer.nextalarm.data

import de.drehtuer.nextalarm.domain.AlarmSnapshot
import de.drehtuer.nextalarm.domain.NewsSource
import de.drehtuer.nextalarm.domain.NewsType
import de.drehtuer.nextalarm.domain.SoundRef

val NEWS = NewsSource(NewsType.RSS, "https://example.org/feed.xml", maxMinutes = 7)

/** Every field differs from its default, so that a lost or swapped column shows up. */
val SNAPSHOT = AlarmSnapshot(
    reason = "Flight",
    sound = SoundRef.File("sound-1"),
    vibrate = false,
    volumeRampSeconds = 12,
    maxVolume = 0.5f,
    ringTimeoutMinutes = 4,
    snoozeMinutes = 8,
    speakReason = false,
    news = NEWS,
)

val MINIMAL_SNAPSHOT = SNAPSHOT.copy(reason = null, sound = SoundRef.SystemDefault, maxVolume = null, news = null)
