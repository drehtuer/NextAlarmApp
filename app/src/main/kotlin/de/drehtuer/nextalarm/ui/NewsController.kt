package de.drehtuer.nextalarm.ui

import de.drehtuer.nextalarm.domain.Feed
import de.drehtuer.nextalarm.domain.FeedSource
import de.drehtuer.nextalarm.domain.NewsCheck
import de.drehtuer.nextalarm.domain.NewsDraft
import de.drehtuer.nextalarm.domain.NewsModel
import de.drehtuer.nextalarm.domain.NewsSource
import de.drehtuer.nextalarm.domain.NewsTestPlayer
import de.drehtuer.nextalarm.domain.NewsTestProgress
import de.drehtuer.nextalarm.domain.SettingsRepository
import de.drehtuer.nextalarm.domain.buildNews
import de.drehtuer.nextalarm.domain.isWebAddress
import de.drehtuer.nextalarm.domain.longerNewsMinutes
import de.drehtuer.nextalarm.domain.newsCheck
import de.drehtuer.nextalarm.domain.newsSourceToSave
import de.drehtuer.nextalarm.domain.shorterNewsMinutes
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/** What screen 6 needs to find out about a feed and to play it. */
class NewsPorts(
    val feeds: FeedSource,
    val player: NewsTestPlayer,
)

/**
 * Runs screen 6: the address, its detection, the test. A feed that was found is stored at once, also when the user has
 * already left the screen. An address that gave none is only complained about after a test, as it may still be being
 * typed. Everything here runs on the thread of [scope], which has to be a single one.
 */
class NewsController(
    private val scope: CoroutineScope,
    private val ports: NewsPorts,
    private val settings: SettingsRepository,
    private val now: () -> Instant,
    private val zone: () -> ZoneId,
    /** Stores the news source and asks for a new plan. It goes on by itself, whatever happens to the work here. */
    private val store: (NewsSource?) -> Unit,
    private val publish: (NewsModel) -> Unit,
) {
    private var draft = NewsDraft()
    private var opened = false
    private var test: NewsTestProgress? = null

    /** Told apart from each other, because the end of an earlier test can still arrive. */
    private var testRun = 0

    private var detection: Job? = null
    private var testJob: Job? = null
    private var ticker: Job? = null

    /** Starts from what is stored, unless the screen is only being rebuilt. */
    fun open() {
        if (opened) return
        opened = true
        detection?.cancel()
        detection = scope.launch {
            val saved = settings.load().news
            draft = NewsDraft(saved?.url.orEmpty(), saved?.maxMinutes ?: NewsSource.DEFAULT_NEWS_MINUTES, saved)
            show()
            if (saved != null) detect(saved.url)
        }
    }

    /** A detection on its way goes on, so that an address that is pasted just before leaving still counts. */
    fun close() {
        opened = false
        stopTest()
    }

    fun setAddress(address: String) {
        if (address == draft.address) return
        stopTest()
        detection?.cancel()
        draft = draft.copy(address = address, check = NewsCheck.None)
        show()
        val trimmed = address.trim()
        if (isWebAddress(trimmed)) {
            detection = scope.launch {
                delay(DETECTION_DELAY_MILLIS)
                detect(trimmed)
            }
        }
    }

    fun shorten() = setMinutes(shorterNewsMinutes(draft.minutes))

    fun lengthen() = setMinutes(longerNewsMinutes(draft.minutes))

    /** It counts for what is stored right away, also if the address in the field is another one. */
    private fun setMinutes(minutes: Int) {
        val changed = draft.saved?.copy(maxMinutes = minutes)
        draft = draft.copy(minutes = minutes)
        show()
        save(changed)
    }

    fun toggleTest() {
        if (test != null) stopTest() else startTest()
    }

    fun stopTest() {
        testRun++
        testJob?.cancel()
        ticker?.cancel()
        ports.player.stop()
        if (test != null) {
            test = null
            show()
        }
    }

    fun turnOff() {
        stopTest()
        detection?.cancel()
        draft = draft.copy(address = "", check = NewsCheck.None)
        show()
        save(null)
    }

    private suspend fun detect(address: String) {
        val check = newsCheck(ports.feeds.fetchFeed(address))
        if (check is NewsCheck.Found) saveFound(check)
    }

    private fun saveFound(check: NewsCheck.Found): NewsSource {
        draft = draft.copy(check = check)
        show()
        val source = newsSourceToSave(draft, check)
        save(source)
        return source
    }

    private fun save(source: NewsSource?) {
        if (source == draft.saved) return
        draft = draft.copy(saved = source)
        show()
        store(source)
    }

    private fun startTest() {
        detection?.cancel()
        val run = ++testRun
        val address = draft.address.trim()
        test = NewsTestProgress.Getting(address)
        draft = draft.copy(check = NewsCheck.None)
        show()
        testJob = scope.launch {
            when (val check = newsCheck(ports.feeds.fetchFeed(address))) {
                is NewsCheck.Found -> play(run, saveFound(check), check.feed)
                else -> {
                    draft = draft.copy(check = check)
                    test = null
                    show()
                }
            }
        }
    }

    private fun play(run: Int, source: NewsSource, feed: Feed) {
        test = NewsTestProgress.Playing(null)
        ticker = scope.launch {
            // From the next turn on: what plays is only known after the call below, which can also already end it.
            yield()
            while (true) {
                test = NewsTestProgress.Playing(ports.player.playing())
                show()
                delay(TICK_MILLIS)
            }
        }
        ports.player.play(source, feed) { worked -> scope.launch { testEnded(run, feed, worked) } }
    }

    private fun testEnded(run: Int, feed: Feed, worked: Boolean) {
        if (run != testRun) return
        ticker?.cancel()
        test = null
        if (!worked) draft = draft.copy(check = NewsCheck.Unplayable(feed))
        show()
    }

    private fun show() = publish(buildNews(draft, test, now(), zone()))

    private companion object {
        /** Long enough to tell a paste from typing, which does not need an answer for every letter. */
        const val DETECTION_DELAY_MILLIS = 800L
        const val TICK_MILLIS = 1000L
    }
}
