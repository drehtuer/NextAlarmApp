package de.drehtuer.nextalarm.domain

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WakeUpScreenTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val at = Instant.parse("2026-11-07T05:06:30Z")
    private val limit = Duration.ofMinutes(15)

    private fun model(playing: WakeUpPlaying?, titles: List<String?> = listOf("Gym")) =
        wakeUpScreenModel(at, zone, WakeUpStatus(titles, playing, null))

    @Test
    fun `the greeting has the date, the time is to the minute, and the titles are as they are`() {
        val model = model(null, listOf("Gym", null))
        assertEquals("Good morning · Saturday 7 November", model.greeting)
        assertEquals("06:06", model.time)
        assertEquals(listOf("Gym", null), model.titles)
        assertNull(model.playing)
    }

    @Test
    fun `one title being spoken`() {
        assertEquals(
            NowPlayingModel(PlayingKind.SPEECH, "Speaking the title", "Gym", "0:03", null, null),
            model(WakeUpPlaying.Speaking("Gym", 1, 1, at.minusSeconds(3))).playing,
        )
    }

    @Test
    fun `several titles being spoken`() {
        assertEquals(
            NowPlayingModel(PlayingKind.SPEECH, "Speaking the titles", "2 of 3: Take morning medication", "0:00", null, null),
            model(WakeUpPlaying.Speaking("Take morning medication", 2, 3, at)).playing,
        )
    }

    @Test
    fun `waiting for the feed`() {
        assertEquals(
            NowPlayingModel(PlayingKind.NEWS, "Getting the news", "tagesschau.de", null, null, null),
            model(WakeUpPlaying.GettingNews("tagesschau.de")).playing,
        )
    }

    @Test
    fun `reading a feed`() {
        assertEquals(
            NowPlayingModel(PlayingKind.NEWS, "Reading the news · tagesschau.de", "Headline 3 of 12", "1:48", "up to 15:00", 0.12f),
            model(WakeUpPlaying.ReadingNews("tagesschau.de", 3, 12, at.minusSeconds(108), limit)).playing,
        )
    }

    @Test
    fun `a podcast that plays`() {
        assertEquals(
            NowPlayingModel(PlayingKind.PODCAST, "Podcast · Deutschlandfunk", "Der Tag, 6 November", "4:12", "18:30", 252f / 1110),
            model(
                WakeUpPlaying.PlayingPodcast(
                    "Der Tag",
                    "Deutschlandfunk",
                    "Der Tag, 6 November",
                    true,
                    Duration.ofSeconds(252),
                    Duration.ofSeconds(1110),
                    limit,
                ),
            ).playing,
        )
    }

    @Test
    fun `a podcast of unknown length goes by the limit, without an author by its name`() {
        assertEquals(
            NowPlayingModel(PlayingKind.PODCAST, "Podcast · Der Tag", "E", "1:30", "up to 15:00", 0.1f),
            model(WakeUpPlaying.PlayingPodcast("Der Tag", null, "E", true, Duration.ofSeconds(90), null, limit)).playing,
        )
    }

    @Test
    fun `a podcast that has not started has no progress to show`() {
        assertEquals(
            NowPlayingModel(PlayingKind.PODCAST, "Podcast · A", "E", "0:00", "18:30", null),
            model(WakeUpPlaying.PlayingPodcast("P", "A", "E", false, Duration.ZERO, Duration.ofSeconds(1110), limit)).playing,
        )
    }

    @Test
    fun `progress stays within the bar`() {
        val over = WakeUpPlaying.PlayingPodcast("P", "A", "E", true, Duration.ofMinutes(20), Duration.ofMinutes(18), limit)
        assertEquals(1f, model(over).playing?.progress)
    }

    @Test
    fun `play times`() {
        assertEquals("0:00", formatPlayTime(Duration.ZERO))
        assertEquals("0:00", formatPlayTime(Duration.ofSeconds(-3)))
        assertEquals("4:12", formatPlayTime(Duration.ofSeconds(252)))
        assertEquals("59:59", formatPlayTime(Duration.ofSeconds(3599)))
        assertEquals("1:02:03", formatPlayTime(Duration.ofSeconds(3723)))
    }

    private fun text(playing: WakeUpPlaying?) = wakeUpNotificationText(
        WakeUpStatus(listOf("Gym"), playing, Instant.parse("2026-11-07T05:16:00Z")),
        zone,
    )

    @Test
    fun `the notification of a podcast`() {
        val playing = WakeUpPlaying.PlayingPodcast("Der Tag", "Deutschlandfunk", "E", true, Duration.ZERO, null, limit)
        assertEquals(NotificationText("Playing: Der Tag", "Deutschlandfunk podcast · stops by 06:16 at the latest"), text(playing))
        assertEquals(
            NotificationText("Playing: Der Tag", "Der Tag podcast · stops by 06:16 at the latest"),
            text(playing.copy(author = null)),
        )
    }

    @Test
    fun `the notification of a feed`() {
        assertEquals(
            NotificationText("Playing: tagesschau.de", "Reading the news · stops by 06:16 at the latest"),
            text(WakeUpPlaying.ReadingNews("tagesschau.de", 1, 2, at, limit)),
        )
        assertEquals(
            NotificationText("Getting the news", "tagesschau.de · stops by 06:16 at the latest"),
            text(WakeUpPlaying.GettingNews("tagesschau.de")),
        )
    }

    @Test
    fun `the notification of speech`() {
        assertEquals(NotificationText("Speaking the title", "Gym"), text(WakeUpPlaying.Speaking("Gym", 1, 1, at)))
        assertEquals(NotificationText("Speaking the titles", "Gym"), text(WakeUpPlaying.Speaking("Gym", 1, 2, at)))
    }

    @Test
    fun `the notification between two steps`() {
        assertEquals(WAKE_UP_PLACEHOLDER_TEXT, text(null))
    }

    @Test
    fun `no news means no promise of when it stops`() {
        val status = WakeUpStatus(listOf("Gym"), WakeUpPlaying.GettingNews("x"), null)
        assertEquals(NotificationText("Getting the news", "x · "), wakeUpNotificationText(status, zone))
    }
}
