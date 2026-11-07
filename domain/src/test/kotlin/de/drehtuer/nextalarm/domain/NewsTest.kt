package de.drehtuer.nextalarm.domain

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val ZONE: ZoneId = ZoneId.of("Europe/Berlin")
private val AT_NOW: Instant = Instant.parse("2026-11-06T19:00:00Z")
private val TODAY: LocalDate = LocalDate.of(2026, 11, 6)
private const val ADDRESS = "https://www.tagesschau.de/feed"

private val ENTRY = FeedEntry("Headline", "Summary", AT_NOW)
private val EPISODE = PodcastEpisode("Friday", "https://example.org/a.mp3", Duration.ofMinutes(18), AT_NOW)
private val RSS = Feed(NewsType.RSS, "tagesschau.de", null, listOf(ENTRY), null)
private val PODCAST = Feed(NewsType.PODCAST, "Der Tag", "Deutschlandfunk", listOf(ENTRY), EPISODE)

class NewsMinutesTest {
    @Test
    fun `longer goes one step up, to the next step from between two`() {
        assertEquals(20, longerNewsMinutes(15))
        assertEquals(10, longerNewsMinutes(7))
        assertEquals(10, longerNewsMinutes(5))
    }

    @Test
    fun `longer stops at the maximum, and takes a stored value outside the limits back into them`() {
        assertEquals(60, longerNewsMinutes(55))
        assertEquals(60, longerNewsMinutes(58))
        assertEquals(60, longerNewsMinutes(60))
        assertEquals(60, longerNewsMinutes(90))
        assertEquals(5, longerNewsMinutes(3))
    }

    @Test
    fun `shorter goes one step down, to the step below from between two`() {
        assertEquals(10, shorterNewsMinutes(15))
        assertEquals(5, shorterNewsMinutes(7))
        assertEquals(10, shorterNewsMinutes(11))
    }

    @Test
    fun `shorter stops at the minimum, and takes a stored value outside the limits back into them`() {
        assertEquals(5, shorterNewsMinutes(10))
        assertEquals(5, shorterNewsMinutes(5))
        assertEquals(5, shorterNewsMinutes(1))
        assertEquals(60, shorterNewsMinutes(90))
    }

    @Test
    fun `every step from the minimum to the maximum can be reached`() {
        val steps = generateSequence(MIN_NEWS_MINUTES) { minutes ->
            longerNewsMinutes(minutes).takeIf { it != minutes }
        }.toList()
        assertEquals((5..60 step 5).toList(), steps)
    }
}

class NewsCheckTest {
    @Test
    fun `a failure stays that failure`() {
        assertEquals(
            NewsCheck.Failed(FeedFailure.Unreachable),
            newsCheck(FeedResult.Failed(FeedFailure.Unreachable)),
        )
    }

    @Test
    fun `a feed with something to play is found`() {
        assertEquals(NewsCheck.Found(RSS), newsCheck(FeedResult.Found(RSS)))
        assertEquals(NewsCheck.Found(PODCAST), newsCheck(FeedResult.Found(PODCAST)))
    }

    @Test
    fun `a feed with nothing to play doesn't count`() {
        assertEquals(NewsCheck.Empty, newsCheck(FeedResult.Found(RSS.copy(entries = emptyList()))))
        assertEquals(NewsCheck.Empty, newsCheck(FeedResult.Found(PODCAST.copy(latestEpisode = null))))
    }

    @Test
    fun `a found feed is stored with its kind, the trimmed address and the duration`() {
        val draft = NewsDraft("  $ADDRESS ", 20)
        assertEquals(NewsSource(NewsType.RSS, ADDRESS, 20), newsSourceToSave(draft, NewsCheck.Found(RSS)))
        assertEquals(NewsSource(NewsType.PODCAST, ADDRESS, 20), newsSourceToSave(draft, NewsCheck.Found(PODCAST)))
    }
}

class NewsWordingTest {
    @Test
    fun `the kind lines`() {
        assertEquals("RSS feed · read aloud", newsKindLine(NewsType.RSS))
        assertEquals("Podcast · latest episode plays", newsKindLine(NewsType.PODCAST))
    }

    @Test
    fun `the helper follows what is known`() {
        assertEquals("Paste the address of an RSS or podcast feed.", newsHelper(NewsCheck.None))
        assertEquals("RSS feed found.", newsHelper(NewsCheck.Found(RSS)))
        assertEquals("Podcast found.", newsHelper(NewsCheck.Found(PODCAST)))
        assertEquals("RSS feed found.", newsHelper(NewsCheck.Unplayable(RSS)))
        assertEquals("Podcast found.", newsHelper(NewsCheck.Unplayable(PODCAST)))
        assertEquals("This address didn't return a feed.", newsHelper(NewsCheck.Failed(FeedFailure.NotAFeed)))
        assertEquals("This address didn't return a feed.", newsHelper(NewsCheck.Empty))
        assertEquals("RSS feeds need an https address.", newsHelper(NewsCheck.Failed(FeedFailure.InsecureFeed)))
    }

    private fun entries(count: Int, published: Instant?) = List(count) { FeedEntry("Headline $it", null, published) }

    @Test
    fun `headlines count those of today, in the phone's zone`() {
        assertEquals("12 headlines today", headlinesDetail(entries(12, AT_NOW) + entries(3, AT_NOW.minus(Duration.ofDays(2))), TODAY, ZONE))
        assertEquals("1 headline today", headlinesDetail(entries(1, AT_NOW) + entries(3, null), TODAY, ZONE))
        // Still the 5th in UTC, but already 00:30 on the 6th in Berlin.
        assertEquals("1 headline today", headlinesDetail(entries(1, Instant.parse("2026-11-05T23:30:00Z")), TODAY, ZONE))
    }

    @Test
    fun `without any of today, headlines just count`() {
        assertEquals("3 headlines", headlinesDetail(entries(3, null), TODAY, ZONE))
        assertEquals("1 headline", headlinesDetail(entries(1, AT_NOW.minus(Duration.ofDays(1))), TODAY, ZONE))
        assertEquals("0 headlines", headlinesDetail(emptyList(), TODAY, ZONE))
    }

    @Test
    fun `a podcast names its publisher, the day and the length`() {
        assertEquals("Deutschlandfunk · today, 18 min", podcastDetail("Deutschlandfunk", EPISODE, TODAY, ZONE))
    }

    @Test
    fun `the day of an episode`() {
        fun day(published: Instant) = podcastDetail(null, EPISODE.copy(published = published, duration = null), TODAY, ZONE)
        assertEquals("today", day(AT_NOW))
        assertEquals("yesterday", day(AT_NOW.minus(Duration.ofDays(1))))
        assertEquals("4 November", day(AT_NOW.minus(Duration.ofDays(2))))
    }

    @Test
    fun `the length of an episode is rounded, and never nothing`() {
        fun length(duration: Duration) = podcastDetail(null, EPISODE.copy(published = null, duration = duration), TODAY, ZONE)
        assertEquals("18 min", length(Duration.ofSeconds(18 * 60 + 29)))
        assertEquals("19 min", length(Duration.ofSeconds(18 * 60 + 30)))
        assertEquals("1 min", length(Duration.ofSeconds(20)))
        assertEquals("72 min", length(Duration.ofMinutes(72)))
    }

    @Test
    fun `a podcast detail leaves out what the feed doesn't give`() {
        assertEquals("Deutschlandfunk", podcastDetail("Deutschlandfunk", null, TODAY, ZONE))
        assertEquals("Deutschlandfunk", podcastDetail("Deutschlandfunk", EPISODE.copy(published = null, duration = null), TODAY, ZONE))
        assertEquals("today, 18 min", podcastDetail(null, EPISODE, TODAY, ZONE))
        assertNull(podcastDetail(null, null, TODAY, ZONE))
    }
}

class NewsBannerTest {
    private val insecure = "This RSS feed uses plain http, which NextAlarm only allows for podcasts. Use its https address."

    @Test
    fun `an RSS feed over plain http says so, and how to fix it`() {
        val banner = newsFailureBanner(NewsDraft(ADDRESS, check = NewsCheck.Failed(FeedFailure.InsecureFeed)))
        assertEquals("This feed can't be used.", banner?.title)
        assertEquals("$insecure Until it works, alarms skip the news.", banner?.body)
    }

    @Test
    fun `an RSS feed over plain http leaves another stored feed in use`() {
        val saved = NewsSource(NewsType.RSS, "https://example.org/feed", 15)
        val banner = newsFailureBanner(NewsDraft(ADDRESS, saved = saved, check = NewsCheck.Failed(FeedFailure.InsecureFeed)))
        assertEquals("$insecure Until it works, alarms keep using example.org.", banner?.body)
    }

    @Test
    fun `an RSS feed over plain http marks the field as failed`() {
        val model = buildNews(NewsDraft(ADDRESS, check = NewsCheck.Failed(FeedFailure.InsecureFeed)), null, AT_NOW, ZONE)
        assertTrue(model.addressFailed)
        assertEquals("RSS feeds need an https address.", model.helper)
    }

    private fun body(check: NewsCheck, saved: NewsSource? = null) =
        newsFailureBanner(NewsDraft(ADDRESS, saved = saved, check = check))?.body

    private fun failed(failure: FeedFailure) = body(NewsCheck.Failed(failure))

    @Test
    fun `there is no banner without a failure`() {
        assertNull(newsFailureBanner(NewsDraft(ADDRESS)))
        assertNull(newsFailureBanner(NewsDraft(ADDRESS, check = NewsCheck.Found(RSS))))
    }

    @Test
    fun `a server's answer is quoted, as in the design`() {
        val banner = newsFailureBanner(NewsDraft(ADDRESS, check = NewsCheck.Failed(FeedFailure.Http(404, "Not Found"))))
        assertEquals("Test failed.", banner?.title)
        assertEquals(
            "The server answered “404 Not Found”. Check the address, then try again. Until it works, alarms skip the news.",
            banner?.body,
        )
    }

    @Test
    fun `a server that gives no message is quoted with its code`() {
        val expected = "The server answered “500”. Check the address, then try again. Until it works, alarms skip the news."
        assertEquals(expected, failed(FeedFailure.Http(500, null)))
        assertEquals(expected, failed(FeedFailure.Http(500, "  ")))
    }

    @Test
    fun `every other failure says what is wrong`() {
        val end = " Check the address, then try again. Until it works, alarms skip the news."
        assertEquals("This isn't a web address: it has to start with https:// or http://.$end", failed(FeedFailure.InvalidAddress))
        assertEquals("The server can't be reached, or the phone has no connection.$end", failed(FeedFailure.Unreachable))
        assertEquals("The answer is too large to be read as a feed.$end", failed(FeedFailure.TooLarge))
        assertEquals("The answer isn't a feed NextAlarm can read.$end", failed(FeedFailure.NotAFeed))
        assertEquals("The feed has nothing in it to play.$end", body(NewsCheck.Empty))
    }

    @Test
    fun `a feed that won't play says so`() {
        val end = "Until it works, alarms skip the news."
        assertEquals(
            "The feed was found, but the phone couldn't read it aloud. Check its text-to-speech settings, then try again. $end",
            body(NewsCheck.Unplayable(RSS)),
        )
        assertEquals(
            "The podcast was found, but its latest episode wouldn't play. Check the connection, then try again. $end",
            body(NewsCheck.Unplayable(PODCAST)),
        )
    }

    @Test
    fun `alarms skip the news if the broken address is the stored one`() {
        val body = body(NewsCheck.Failed(FeedFailure.NotAFeed), NewsSource(NewsType.RSS, ADDRESS))
        assertTrue(assertNotNull(body).endsWith("Until it works, alarms skip the news."))
    }

    @Test
    fun `another stored feed goes on playing, and the banner says so`() {
        val body = body(NewsCheck.Failed(FeedFailure.NotAFeed), NewsSource(NewsType.RSS, "https://www.example.org/feed"))
        assertTrue(assertNotNull(body).endsWith("Until it works, alarms keep using example.org."))
    }
}

class NewsTestLineTest {
    private val limit = Duration.ofMinutes(15)

    private fun podcast(position: Duration, duration: Duration?, started: Boolean = true) = NewsTestProgress.Playing(
        WakeUpPlaying.PlayingPodcast("Der Tag", "Deutschlandfunk", "Friday", started, position, duration, limit),
    )

    @Test
    fun `while the feed is on its way`() {
        assertEquals(
            NewsTestLine("Getting the news · tagesschau.de", null),
            newsTestLine(NewsTestProgress.Getting(ADDRESS), AT_NOW),
        )
    }

    @Test
    fun `between the fetch and the first sound`() {
        assertEquals(NewsTestLine("Getting the news", null), newsTestLine(NewsTestProgress.Playing(null), AT_NOW))
        assertEquals(
            NewsTestLine("Getting the news", null),
            newsTestLine(NewsTestProgress.Playing(WakeUpPlaying.GettingNews("tagesschau.de")), AT_NOW),
        )
    }

    @Test
    fun `reading counts from its start up to the limit`() {
        val playing = WakeUpPlaying.ReadingNews("tagesschau.de", 1, 3, AT_NOW.minusSeconds(90), limit)
        assertEquals(
            NewsTestLine("Playing “tagesschau.de” · 1:30 of 15:00", 0.1f),
            newsTestLine(NewsTestProgress.Playing(playing), AT_NOW),
        )
    }

    @Test
    fun `a podcast longer than the limit counts up to the limit, as in the design`() {
        val line = newsTestLine(podcast(Duration.ofSeconds(42), Duration.ofMinutes(18)), AT_NOW)
        assertEquals("Playing “Der Tag” · 0:42 of 15:00", line.text)
        assertEquals(42f / 900, line.progress)
    }

    @Test
    fun `a shorter podcast counts up to its own end`() {
        val line = newsTestLine(podcast(Duration.ofMinutes(5), Duration.ofMinutes(10)), AT_NOW)
        assertEquals("Playing “Der Tag” · 5:00 of 10:00", line.text)
        assertEquals(0.5f, line.progress)
    }

    @Test
    fun `a podcast of unknown length counts up to the limit`() {
        assertEquals("Playing “Der Tag” · 1:00 of 15:00", newsTestLine(podcast(Duration.ofMinutes(1), null), AT_NOW).text)
    }

    @Test
    fun `a podcast that hasn't started has no progress to show`() {
        val line = newsTestLine(podcast(Duration.ZERO, null, started = false), AT_NOW)
        assertEquals("Playing “Der Tag” · 0:00 of 15:00", line.text)
        assertNull(line.progress)
    }
}

class BuildNewsTest {
    private fun build(draft: NewsDraft, test: NewsTestProgress? = null) = buildNews(draft, test, AT_NOW, ZONE)

    @Test
    fun `an empty screen can't test or turn off anything`() {
        val model = build(NewsDraft())
        assertEquals("", model.address)
        assertEquals("Paste the address of an RSS or podcast feed.", model.helper)
        assertFalse(model.addressFailed)
        assertNull(model.detected)
        assertEquals("15 min", model.minutes)
        assertFalse(model.canTest)
        assertFalse(model.testing)
        assertNull(model.testLine)
        assertNull(model.failure)
        assertFalse(model.canTurnOff)
    }

    @Test
    fun `an address can be tested, and blanks don't count as one`() {
        assertTrue(build(NewsDraft(ADDRESS)).canTest)
        assertFalse(build(NewsDraft("   ")).canTest)
    }

    @Test
    fun `there is something to turn off with an address or with a stored feed`() {
        assertTrue(build(NewsDraft(ADDRESS)).canTurnOff)
        assertTrue(build(NewsDraft(saved = NewsSource(NewsType.RSS, ADDRESS))).canTurnOff)
    }

    @Test
    fun `an RSS feed is described`() {
        val detected = build(NewsDraft(ADDRESS, check = NewsCheck.Found(RSS))).detected
        assertEquals(DetectedFeedModel(NewsType.RSS, "RSS feed · read aloud", "tagesschau.de", "1 headline today"), detected)
    }

    @Test
    fun `a podcast is described`() {
        val detected = build(NewsDraft(ADDRESS, check = NewsCheck.Found(PODCAST))).detected
        assertEquals(
            DetectedFeedModel(NewsType.PODCAST, "Podcast · latest episode plays", "Der Tag", "Deutschlandfunk · today, 18 min"),
            detected,
        )
    }

    @Test
    fun `a feed that won't play stays described, and the field isn't at fault`() {
        val model = build(NewsDraft(ADDRESS, check = NewsCheck.Unplayable(RSS)))
        assertNotNull(model.detected)
        assertFalse(model.addressFailed)
        assertNotNull(model.failure)
    }

    @Test
    fun `a failure marks the field and describes nothing`() {
        listOf(NewsCheck.Failed(FeedFailure.NotAFeed), NewsCheck.Empty).forEach { check ->
            val model = build(NewsDraft(ADDRESS, check = check))
            assertTrue(model.addressFailed)
            assertNull(model.detected)
            assertEquals("This address didn't return a feed.", model.helper)
            assertNotNull(model.failure)
        }
    }

    @Test
    fun `the stepper ends at its limits`() {
        assertFalse(build(NewsDraft(minutes = MIN_NEWS_MINUTES)).canShorten)
        assertTrue(build(NewsDraft(minutes = MIN_NEWS_MINUTES)).canLengthen)
        assertTrue(build(NewsDraft(minutes = MAX_NEWS_MINUTES)).canShorten)
        assertFalse(build(NewsDraft(minutes = MAX_NEWS_MINUTES)).canLengthen)
    }

    @Test
    fun `while a test runs it can be stopped, even without an address, and the duration stays`() {
        val model = build(NewsDraft(), NewsTestProgress.Playing(null))
        assertTrue(model.testing)
        assertTrue(model.canTest)
        assertFalse(model.canShorten)
        assertFalse(model.canLengthen)
        assertEquals("Getting the news", model.testLine?.text)
    }
}

class NewsTestPlayerTest {
    private class FakeSpeech : SpeechOutput {
        val said = mutableListOf<String>()
        var pending: ((Boolean) -> Unit)? = null
        var immediate: Boolean? = null
        var stops = 0

        override fun speak(text: String, onDone: (Boolean) -> Unit) {
            said += text
            val answer = immediate
            if (answer != null) onDone(answer) else pending = onDone
        }

        override fun stop() {
            stops++
            pending = null
        }

        fun finish(worked: Boolean = true) = assertNotNull(pending)(worked)
    }

    private class FakePodcast : PodcastOutput {
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
        override fun pause() = Unit
        override fun resume() = Unit

        override fun stop() {
            stops++
        }
    }

    private class FakeTimers : RingTimers {
        val waiting = linkedMapOf<Duration, () -> Unit>()

        override fun after(delay: Duration, block: () -> Unit): () -> Unit {
            waiting[delay] = block
            return { waiting.remove(delay) }
        }
    }

    private class FakeWakeLock : RingWakeLock {
        var held = false

        override fun hold(atMost: Duration) {
            held = true
        }

        override fun release() {
            held = false
        }
    }

    private val speech = FakeSpeech()
    private val podcast = FakePodcast()
    private val timers = FakeTimers()
    private val wakeLock = FakeWakeLock()
    private var now = AT_NOW
    private val calls = FakeCalls()
    private val focus = FakeFocus()
    private val player =
        NewsTestPlayer(speech, podcast, timers, wakeLock, AudioFocusHold(focus) { _, _ -> }, CallWatch(calls) { _, _ -> }) { now }
    private val ended = mutableListOf<Boolean>()
    private val limit = Duration.ofMinutes(15)

    private fun play(feed: Feed, minutes: Int = 15) =
        player.play(NewsSource(feed.kind, ADDRESS, minutes), feed) { ended += it }

    private val two = RSS.copy(entries = listOf(FeedEntry("One", null, null), FeedEntry("Two", null, null)))

    @Test
    fun `a test asks for the focus and gives it up at the end`() {
        play(RSS)
        assertEquals(1, focus.requests)
        player.stop()
        assertEquals(1, focus.abandons)
    }

    @Test
    fun `a test during a call plays nothing, and that is no failure of the feed`() {
        calls.isActive = true
        play(RSS)
        assertTrue(speech.said.isEmpty())
        assertEquals(listOf(true), ended)
    }

    @Test
    fun `a call that starts before anything was heard ends the test, which is no failure of the feed`() {
        play(RSS)
        calls.change(true)
        assertEquals(listOf(true), ended)
    }

    @Test
    fun `other audio that starts before anything was heard ends the test, which is no failure of the feed`() {
        play(RSS)
        focus.onLost()
        assertEquals(listOf(true), ended)
        assertEquals(1, focus.abandons)
    }

    private fun reachLimit() {
        now = now.plus(limit)
        assertNotNull(timers.waiting[limit])()
    }

    @Test
    fun `an RSS feed is read aloud, entry by entry, to its end`() {
        play(two)
        assertEquals(listOf("One"), speech.said)
        assertTrue(wakeLock.held)
        assertIs<WakeUpPlaying.ReadingNews>(player.playing())
        speech.finish()
        assertEquals(listOf("One", "Two"), speech.said)
        speech.finish()
        assertEquals(listOf(true), ended)
        assertNull(player.playing())
        assertFalse(wakeLock.held)
    }

    @Test
    fun `speech that fails at once is a failure`() {
        speech.immediate = false
        play(two)
        assertEquals(listOf(false), ended)
        assertNull(player.playing())
        assertFalse(wakeLock.held)
    }

    @Test
    fun `speech that fails after something was heard is not`() {
        play(two)
        speech.finish()
        speech.finish(worked = false)
        assertEquals(listOf(true), ended)
    }

    @Test
    fun `a podcast plays its latest episode`() {
        play(PODCAST)
        assertEquals(listOf(EPISODE.audioUrl), podcast.played)
        assertEquals(false, assertIs<WakeUpPlaying.PlayingPodcast>(player.playing()).started)
        podcast.onStarted()
        podcast.position = Duration.ofSeconds(42)
        val playing = assertIs<WakeUpPlaying.PlayingPodcast>(player.playing())
        assertTrue(playing.started)
        assertEquals(Duration.ofSeconds(42), playing.position)
        podcast.onEnded()
        assertEquals(listOf(true), ended)
    }

    @Test
    fun `a podcast that never starts is a failure`() {
        play(PODCAST)
        podcast.onEnded()
        assertEquals(listOf(false), ended)
    }

    @Test
    fun `it stops at the maximum duration, of the source it plays`() {
        play(two)
        speech.finish()
        reachLimit()
        assertEquals(listOf(true), ended)
        assertEquals(2, speech.said.size)
        assertNull(speech.pending)
        assertFalse(wakeLock.held)
    }

    @Test
    fun `a limit reached before the first entry is done is no failure`() {
        play(two)
        reachLimit()
        assertEquals(listOf(true), ended)
    }

    @Test
    fun `a podcast stops at the maximum duration`() {
        play(PODCAST)
        podcast.onStarted()
        reachLimit()
        assertEquals(listOf(true), ended)
        assertEquals(1, podcast.stops)
    }

    @Test
    fun `stop ends it without reporting an end`() {
        play(two)
        player.stop()
        assertTrue(speech.stops > 0)
        assertNull(speech.pending)
        assertNull(player.playing())
        assertFalse(wakeLock.held)
        assertTrue(ended.isEmpty())
    }

    @Test
    fun `stop stops a podcast`() {
        play(PODCAST)
        player.stop()
        assertEquals(1, podcast.stops)
    }

    @Test
    fun `stop with nothing playing does nothing`() {
        player.stop()
        assertEquals(0, speech.stops)
        assertEquals(0, podcast.stops)
    }

    @Test
    fun `playing again starts over, and what was heard before doesn't count`() {
        play(two)
        speech.finish()
        speech.immediate = false
        play(two)
        assertEquals(listOf(false), ended)
    }
}
