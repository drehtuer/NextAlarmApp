package de.drehtuer.nextalarm.ui

import android.app.Application
import android.os.Looper
import de.drehtuer.nextalarm.domain.FeedFailure
import de.drehtuer.nextalarm.domain.FeedResult
import de.drehtuer.nextalarm.domain.NewsModel
import de.drehtuer.nextalarm.domain.NewsSource
import de.drehtuer.nextalarm.domain.NewsType
import de.drehtuer.nextalarm.domain.Settings
import de.drehtuer.nextalarm.domain.SettingsRepository
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

private const val FEED = "https://www.tagesschau.de/feed"
private const val OTHER = "https://example.org/other"
private val DETECTION_DELAY: Duration = Duration.ofSeconds(1)
private val LIMIT: Duration = Duration.ofMinutes(15)

/** Everything runs on the main thread of the test, whose looper only moves when the test says so. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class NewsControllerTest {
    private class FakeSettings(var stored: NewsSource? = null) : SettingsRepository {
        var loads = 0

        override suspend fun load(): Settings {
            loads++
            return Settings.DEFAULT.copy(news = stored)
        }

        override suspend fun save(settings: Settings) {
            stored = settings.news
        }
    }

    private var now: Instant = Instant.parse("2026-11-06T19:00:00Z")
    private val parts = NewsParts { now }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val settings = FakeSettings()
    private val stored = mutableListOf<NewsSource?>()
    private var model: NewsModel? = null
    private var published = 0
    private val controller = NewsController(
        scope,
        parts.ports,
        settings,
        { now },
        { ZoneId.of("Europe/Berlin") },
        store = {
            stored += it
            settings.stored = it
        },
        publish = {
            model = it
            published++
        },
    )

    @After
    fun tearDown() {
        scope.cancel()
    }

    private val shown: NewsModel
        get() = assertNotNull(model)

    private fun settle() = shadowOf(Looper.getMainLooper()).idle()

    private fun waitFor(time: Duration) = shadowOf(Looper.getMainLooper()).idleFor(time)

    private fun type(address: String) {
        controller.setAddress(address)
        settle()
    }

    private fun typeAndDetect(address: String) {
        type(address)
        waitFor(DETECTION_DELAY)
    }

    private fun toggle() {
        controller.toggleTest()
        settle()
    }

    /** A test of [FEED], played to the point where the first thing sounds. */
    private fun startTest(result: FeedResult = FeedResult.Found(RSS_FEED)) {
        parts.feeds.result = result
        type(FEED)
        toggle()
    }

    @Test
    fun `opening shows what is stored and looks at it again, without storing it twice`() {
        settings.stored = NewsSource(NewsType.RSS, FEED, 20)
        controller.open()
        settle()
        assertEquals(FEED, shown.address)
        assertEquals("20 min", shown.minutes)
        assertEquals(listOf(FEED), parts.feeds.asked)
        assertEquals("RSS feed found.", shown.helper)
        assertEquals("tagesschau.de", shown.detected?.name)
        assertTrue(stored.isEmpty())
    }

    @Test
    fun `opening with nothing stored asks nobody`() {
        controller.open()
        settle()
        assertEquals("", shown.address)
        assertEquals("15 min", shown.minutes)
        assertTrue(parts.feeds.asked.isEmpty())
    }

    @Test
    fun `a stored feed that can't be reached right now stays stored, and nothing complains`() {
        settings.stored = NewsSource(NewsType.RSS, FEED)
        parts.feeds.result = FeedResult.Failed(FeedFailure.Unreachable)
        controller.open()
        settle()
        assertNull(shown.detected)
        assertNull(shown.failure)
        assertTrue(stored.isEmpty())
    }

    @Test
    fun `a stored feed that has become a podcast is stored as one`() {
        settings.stored = NewsSource(NewsType.RSS, FEED, 20)
        parts.feeds.result = FeedResult.Found(PODCAST_FEED)
        controller.open()
        settle()
        assertEquals(listOf<NewsSource?>(NewsSource(NewsType.PODCAST, FEED, 20)), stored)
    }

    @Test
    fun `rebuilding the screen keeps what was typed`() {
        controller.open()
        settle()
        type("https://exam")
        controller.open()
        settle()
        assertEquals("https://exam", shown.address)
        assertEquals(1, settings.loads)
    }

    @Test
    fun `coming back to the screen starts from what is stored`() {
        controller.open()
        settle()
        type("https://exam")
        controller.close()
        controller.open()
        settle()
        assertEquals("", shown.address)
        assertEquals(2, settings.loads)
    }

    @Test
    fun `the same text is no change`() {
        type(FEED)
        val before = published
        type(FEED)
        assertEquals(before, published)
        waitFor(DETECTION_DELAY)
        assertEquals(listOf(FEED), parts.feeds.asked)
    }

    @Test
    fun `an address is looked at once the typing has paused, and a feed is stored`() {
        type(" $FEED ")
        assertEquals(" $FEED ", shown.address)
        assertTrue(parts.feeds.asked.isEmpty())
        waitFor(DETECTION_DELAY)
        assertEquals(listOf(FEED), parts.feeds.asked)
        assertEquals("RSS feed found.", shown.helper)
        assertEquals(listOf<NewsSource?>(NewsSource(NewsType.RSS, FEED, 15)), stored)
    }

    @Test
    fun `a podcast is detected as one`() {
        parts.feeds.result = FeedResult.Found(PODCAST_FEED)
        typeAndDetect(FEED)
        assertEquals("Podcast found.", shown.helper)
        assertEquals("Der Tag", shown.detected?.name)
        assertEquals(NewsSource(NewsType.PODCAST, FEED, 15), stored.single())
    }

    @Test
    fun `what isn't an address isn't looked at`() {
        typeAndDetect("tagesschau")
        assertTrue(parts.feeds.asked.isEmpty())
    }

    @Test
    fun `typing on replaces the address that was going to be looked at`() {
        type(FEED)
        type(OTHER)
        waitFor(DETECTION_DELAY)
        assertEquals(listOf(OTHER), parts.feeds.asked)
    }

    @Test
    fun `typing on drops a look that is on its way`() {
        val answer = CompletableDeferred<FeedResult>()
        parts.feeds.waiting = answer
        typeAndDetect(FEED)
        parts.feeds.waiting = null
        type("https://exam")
        answer.complete(FeedResult.Found(RSS_FEED))
        settle()
        assertNull(shown.detected)
        assertTrue(stored.isEmpty())
    }

    @Test
    fun `a detection that finds nothing doesn't complain and stores nothing`() {
        parts.feeds.result = FeedResult.Failed(FeedFailure.Http(404, "Not Found"))
        typeAndDetect(FEED)
        assertFalse(shown.addressFailed)
        assertNull(shown.failure)
        assertTrue(stored.isEmpty())
    }

    @Test
    fun `a detection that finds an empty feed stores nothing`() {
        parts.feeds.result = FeedResult.Found(RSS_FEED.copy(entries = emptyList()))
        typeAndDetect(FEED)
        assertNull(shown.detected)
        assertTrue(stored.isEmpty())
    }

    @Test
    fun `changing the address forgets the feed of the old one, and keeps it stored`() {
        typeAndDetect(FEED)
        parts.feeds.result = FeedResult.Failed(FeedFailure.NotAFeed)
        typeAndDetect(OTHER)
        assertNull(shown.detected)
        assertEquals(FEED, settings.stored?.url)
    }

    @Test
    fun `a detection on its way still counts after leaving the screen`() {
        type(FEED)
        controller.close()
        waitFor(DETECTION_DELAY)
        assertEquals(FEED, settings.stored?.url)
    }

    @Test
    fun `the duration is only remembered while nothing is stored`() {
        controller.lengthen()
        settle()
        assertEquals("20 min", shown.minutes)
        assertTrue(stored.isEmpty())
        typeAndDetect(FEED)
        assertEquals(20, stored.single()?.maxMinutes)
    }

    @Test
    fun `the duration of what is stored changes at once`() {
        typeAndDetect(FEED)
        controller.lengthen()
        settle()
        assertEquals("20 min", shown.minutes)
        assertEquals(20, settings.stored?.maxMinutes)
        controller.shorten()
        settle()
        assertEquals("15 min", shown.minutes)
        assertEquals(15, settings.stored?.maxMinutes)
    }

    @Test
    fun `the duration counts for what is stored, even with another address in the field`() {
        typeAndDetect(FEED)
        parts.feeds.result = FeedResult.Failed(FeedFailure.NotAFeed)
        type(OTHER)
        controller.lengthen()
        settle()
        assertEquals(NewsSource(NewsType.RSS, FEED, 20), settings.stored)
    }

    @Test
    fun `a step that changes nothing stores nothing`() {
        settings.stored = NewsSource(NewsType.RSS, FEED, 60)
        controller.open()
        settle()
        controller.lengthen()
        settle()
        assertTrue(stored.isEmpty())
    }

    @Test
    fun `a test that fails says why, stores nothing and leaves the stored feed alone`() {
        typeAndDetect(OTHER)
        startTest(FeedResult.Failed(FeedFailure.Http(404, "Not Found")))
        assertFalse(shown.testing)
        assertTrue(shown.addressFailed)
        assertEquals("This address didn't return a feed.", shown.helper)
        assertEquals("Test failed.", shown.failure?.title)
        assertTrue(assertNotNull(shown.failure).body.startsWith("The server answered “404 Not Found”."))
        assertEquals(OTHER, settings.stored?.url)
        assertEquals(1, stored.size)
        assertTrue(parts.speech.said.isEmpty())
    }

    @Test
    fun `a test of an empty feed fails`() {
        startTest(FeedResult.Found(RSS_FEED.copy(entries = emptyList())))
        assertEquals("Test failed.", shown.failure?.title)
        assertTrue(stored.isEmpty())
    }

    @Test
    fun `a failed test goes away when the address changes`() {
        startTest(FeedResult.Failed(FeedFailure.NotAFeed))
        type(OTHER)
        assertNull(shown.failure)
        assertFalse(shown.addressFailed)
    }

    @Test
    fun `a test that fails can be tried again, and then works`() {
        startTest(FeedResult.Failed(FeedFailure.NotAFeed))
        parts.feeds.result = FeedResult.Found(RSS_FEED)
        toggle()
        assertNull(shown.failure)
        assertTrue(shown.testing)
        assertEquals(FEED, settings.stored?.url)
    }

    @Test
    fun `a test takes over from a detection, so that the feed is fetched once`() {
        type(FEED)
        toggle()
        waitFor(DETECTION_DELAY)
        assertEquals(listOf(FEED), parts.feeds.asked)
    }

    @Test
    fun `while the feed is on its way the test can be stopped, and its answer doesn't count`() {
        val answer = CompletableDeferred<FeedResult>()
        parts.feeds.waiting = answer
        type(FEED)
        toggle()
        assertTrue(shown.testing)
        assertEquals("Getting the news · tagesschau.de", shown.testLine?.text)
        assertNull(shown.testLine?.progress)
        toggle()
        assertFalse(shown.testing)
        answer.complete(FeedResult.Found(RSS_FEED))
        settle()
        assertTrue(parts.speech.said.isEmpty())
        assertTrue(stored.isEmpty())
    }

    @Test
    fun `a working test stores the feed and reads it aloud, with the limit it was given`() {
        controller.lengthen()
        startTest()
        assertEquals(NewsSource(NewsType.RSS, FEED, 20), settings.stored)
        assertEquals(listOf("One"), parts.speech.said)
        assertTrue(shown.testing)
        assertTrue(parts.wakeLock.held)
        assertEquals("RSS feed found.", shown.helper)
        assertEquals("Playing “tagesschau.de” · 0:00 of 20:00", shown.testLine?.text)
        assertNotNull(parts.timers.waiting[Duration.ofMinutes(20)])
    }

    @Test
    fun `the line counts up while it plays`() {
        startTest()
        now = now.plusSeconds(42)
        waitFor(Duration.ofSeconds(1))
        assertEquals("Playing “tagesschau.de” · 0:42 of 15:00", shown.testLine?.text)
    }

    @Test
    fun `reading to the end ends the test without a complaint, and counting stops`() {
        startTest()
        assertNotNull(parts.speech.pending)(true)
        assertNotNull(parts.speech.pending)(true)
        settle()
        assertFalse(shown.testing)
        assertNull(shown.testLine)
        assertNull(shown.failure)
        assertFalse(parts.wakeLock.held)
        val before = published
        waitFor(Duration.ofSeconds(5))
        assertEquals(before, published)
    }

    @Test
    fun `a podcast plays its latest episode, up to the end of it`() {
        startTest(FeedResult.Found(PODCAST_FEED))
        assertEquals(listOf("https://example.org/friday.mp3"), parts.podcast.played)
        assertNull(shown.testLine?.progress)
        parts.podcast.onStarted()
        parts.podcast.position = Duration.ofSeconds(42)
        waitFor(Duration.ofSeconds(1))
        assertEquals("Playing “Der Tag” · 0:42 of 15:00", shown.testLine?.text)
        assertNotNull(shown.testLine?.progress)
        parts.podcast.onEnded()
        settle()
        assertFalse(shown.testing)
        assertNull(shown.failure)
    }

    @Test
    fun `an episode that won't play is a failed test, of a feed that stays stored`() {
        startTest(FeedResult.Found(PODCAST_FEED))
        parts.podcast.onEnded()
        settle()
        assertFalse(shown.testing)
        assertEquals("Test failed.", shown.failure?.title)
        assertFalse(shown.addressFailed)
        assertEquals("Der Tag", shown.detected?.name)
        assertEquals(FEED, settings.stored?.url)
    }

    @Test
    fun `speech that fails straight away is a failed test, and nothing goes on counting`() {
        parts.speech.immediate = false
        startTest()
        settle()
        assertFalse(shown.testing)
        assertEquals("Test failed.", shown.failure?.title)
        val before = published
        waitFor(Duration.ofSeconds(5))
        assertEquals(before, published)
    }

    @Test
    fun `the test ends at the maximum duration, without a complaint`() {
        startTest()
        assertNotNull(parts.speech.pending)(true)
        now = now.plus(LIMIT)
        assertNotNull(parts.timers.waiting[LIMIT])()
        settle()
        assertFalse(shown.testing)
        assertNull(shown.failure)
        assertNull(parts.speech.pending)
    }

    @Test
    fun `stop ends the test, and it can be started again`() {
        startTest()
        toggle()
        assertFalse(shown.testing)
        assertNull(shown.failure)
        assertNull(parts.speech.pending)
        assertFalse(parts.wakeLock.held)
        toggle()
        assertTrue(shown.testing)
        assertEquals(listOf("One", "One"), parts.speech.said)
    }

    @Test
    fun `an end that arrives after stop doesn't count`() {
        startTest()
        val finish = assertNotNull(parts.speech.pending)
        // From another thread, as an engine does, so that the end has to wait for the main thread. It would be a failure.
        thread { finish(false) }.join()
        controller.stopTest()
        settle()
        assertFalse(shown.testing)
        assertNull(shown.failure)
    }

    @Test
    fun `an end from another thread reaches the screen`() {
        startTest()
        val finish = assertNotNull(parts.speech.pending)
        thread { finish(false) }.join()
        assertTrue(shown.testing)
        settle()
        assertFalse(shown.testing)
        assertEquals("Test failed.", shown.failure?.title)
    }

    @Test
    fun `leaving the screen stops the test`() {
        startTest()
        controller.close()
        settle()
        assertFalse(shown.testing)
        assertNull(parts.speech.pending)
    }

    @Test
    fun `being hidden stops the test`() {
        startTest()
        controller.stopTest()
        settle()
        assertFalse(shown.testing)
        assertNull(parts.speech.pending)
    }

    @Test
    fun `stopping without a test changes nothing`() {
        type(FEED)
        val before = published
        controller.stopTest()
        assertEquals(before, published)
    }

    @Test
    fun `changing the address stops the test`() {
        startTest()
        type(OTHER)
        assertFalse(shown.testing)
        assertNull(parts.speech.pending)
    }

    @Test
    fun `turning off clears the field, stops the test and stores no news`() {
        startTest()
        controller.turnOff()
        settle()
        assertEquals("", shown.address)
        assertFalse(shown.testing)
        assertNull(shown.detected)
        assertFalse(shown.canTurnOff)
        assertNull(parts.speech.pending)
        assertNull(settings.stored)
        assertEquals(listOf(null), stored.drop(1))
    }

    @Test
    fun `turning off drops a detection on its way`() {
        type(FEED)
        controller.turnOff()
        waitFor(DETECTION_DELAY)
        assertTrue(parts.feeds.asked.isEmpty())
        assertTrue(stored.isEmpty())
    }

    @Test
    fun `turning off with nothing stored has nothing to store`() {
        controller.turnOff()
        settle()
        assertTrue(stored.isEmpty())
        assertFalse(shown.canTurnOff)
    }

    @Test
    fun `what isn't an address can be tested, and the feed source says what is wrong with it`() {
        type("tagesschau")
        parts.feeds.result = FeedResult.Failed(FeedFailure.InvalidAddress)
        toggle()
        assertEquals(listOf("tagesschau"), parts.feeds.asked)
        assertTrue(assertNotNull(shown.failure).body.startsWith("This isn't a web address"))
    }

    @Test
    fun `what was found is handed over for storing before typing on can cancel anything`() {
        parts.feeds.result = FeedResult.Found(RSS_FEED)
        type(FEED)
        toggle()
        assertEquals(1, stored.size)
        type(OTHER)
        assertEquals(1, stored.size)
    }

    @Test
    fun `after turning off the same address can be detected again`() {
        typeAndDetect(FEED)
        controller.turnOff()
        settle()
        typeAndDetect(FEED)
        assertEquals(FEED, settings.stored?.url)
    }
}
