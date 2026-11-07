package de.drehtuer.nextalarm.domain

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ForegroundGateTest {
    private class Timers : RingTimers {
        val blocks = mutableListOf<() -> Unit>()
        var cancelled = 0

        override fun after(delay: Duration, block: () -> Unit): () -> Unit {
            blocks += block
            return { cancelled++ }
        }
    }

    private val timers = Timers()
    private val gate = ForegroundGate(timers)
    private val log = mutableListOf<String>()

    private val speech = object : SpeechOutput {
        override fun speak(text: String, onDone: (Boolean) -> Unit) {
            log += "speak:$text"
        }

        override fun stop() {
            log += "stopSpeech"
        }
    }
    private val podcast = object : PodcastOutput {
        override fun play(url: String, onStarted: () -> Unit, onEnded: () -> Unit) {
            log += "play:$url"
        }

        override fun position(): Duration = Duration.ofSeconds(7)
        override fun duration(): Duration? = null
        override fun pause() {
            log += "pause"
        }

        override fun resume() {
            log += "resume"
        }

        override fun stop() {
            log += "stopPodcast"
        }
    }

    @Test
    fun `what has to happen before the audio comes first, when the service is up`() {
        val before = ForegroundGate(timers, beforeAudio = { log += "before" })
        GatedSpeech(speech, before).speak("Gym") {}
        assertTrue(log.isEmpty())
        before.open()
        assertEquals(listOf("before", "speak:Gym"), log)
        log.clear()
        GatedSpeech(speech, before).speak("Run") {}
        assertEquals(listOf("before", "speak:Run"), log)
    }

    @Test
    fun `a service that comes up with nothing waiting has nothing to do before it`() {
        val before = ForegroundGate(timers, beforeAudio = { log += "before" })
        before.open()
        assertTrue(log.isEmpty())
    }

    @Test
    fun `pausing and resuming a gated podcast go straight through`() {
        val gated = GatedPodcast(podcast, gate)
        gated.pause()
        gated.resume()
        assertEquals(listOf("pause", "resume"), log)
    }

    @Test
    fun `speech waits until the service is up`() {
        GatedSpeech(speech, gate).speak("Gym") {}
        assertTrue(log.isEmpty())
        gate.open()
        assertEquals(listOf("speak:Gym"), log)
    }

    @Test
    fun `a podcast waits until the service is up, and the rest goes straight through`() {
        val gated = GatedPodcast(podcast, gate)
        gated.play("url", {}, {})
        assertTrue(log.isEmpty())
        assertEquals(Duration.ofSeconds(7), gated.position())
        gate.open()
        assertEquals(listOf("play:url"), log)
    }

    @Test
    fun `with the service up nothing waits`() {
        gate.open()
        GatedSpeech(speech, gate).speak("Gym") {}
        assertEquals(listOf("speak:Gym"), log)
        assertTrue(timers.blocks.isEmpty())
    }

    @Test
    fun `it goes ahead anyway if the service takes too long`() {
        GatedSpeech(speech, gate).speak("Gym") {}
        timers.blocks.single().invoke()
        assertEquals(listOf("speak:Gym"), log)
    }

    @Test
    fun `stopping forgets what waits, for speech and podcasts alike`() {
        val gatedSpeech = GatedSpeech(speech, gate)
        gatedSpeech.speak("Gym") {}
        gatedSpeech.stop()
        val gatedPodcast = GatedPodcast(podcast, gate)
        gatedPodcast.play("url", {}, {})
        gatedPodcast.stop()
        gate.open()
        assertEquals(listOf("stopSpeech", "stopPodcast"), log)
    }

    @Test
    fun `only the latest waits`() {
        val gated = GatedSpeech(speech, gate)
        gated.speak("One") {}
        gated.speak("Two") {}
        gate.open()
        assertEquals(listOf("speak:Two"), log)
    }

    @Test
    fun `once the service has gone, it waits again`() {
        gate.open()
        gate.close()
        GatedSpeech(speech, gate).speak("Gym") {}
        assertTrue(log.isEmpty())
        gate.close()
        gate.open()
        assertTrue(log.isEmpty())
    }
}

class WakeUpDirectorTest {
    private class FakeSpeech : SpeechOutput {
        val said = mutableListOf<String>()
        var pending: ((Boolean) -> Unit)? = null
        var stops = 0

        /** The answer of an engine that reports straight away. Without one, the test answers through [pending]. */
        var immediate: Boolean? = null

        override fun speak(text: String, onDone: (Boolean) -> Unit) {
            said += text
            val answer = immediate
            if (answer != null) onDone(answer) else pending = onDone
        }

        override fun stop() {
            stops++
            pending = null
        }

        fun finish(worked: Boolean = true) {
            val callback = pending ?: error("Nothing is being said")
            pending = null
            callback(worked)
        }
    }

    private class FakePodcast : PodcastOutput {
        val played = mutableListOf<String>()
        var onStarted: () -> Unit = {}
        var onEnded: () -> Unit = {}
        var stops = 0
        var position: Duration = Duration.ZERO
        var duration: Duration? = null
        var pauses = 0
        var resumes = 0

        override fun pause() {
            pauses++
        }

        override fun resume() {
            resumes++
        }

        override fun play(url: String, onStarted: () -> Unit, onEnded: () -> Unit) {
            played += url
            this.onStarted = onStarted
            this.onEnded = onEnded
        }

        override fun position() = position
        override fun duration() = duration

        override fun stop() {
            stops++
        }
    }

    private class FakeFetcher : NewsFetcher {
        val asked = mutableListOf<String>()
        var onResult: (FeedResult) -> Unit = {}
        var cancels = 0

        override fun fetch(url: String, onResult: (FeedResult) -> Unit): () -> Unit {
            asked += url
            this.onResult = onResult
            return { cancels++ }
        }
    }

    /** Timers fire when the test says so. A cancelled one is gone, but its block can still be run, as if it was too late. */
    private class FakeTimers : RingTimers {
        inner class Timer(val delay: Duration, val block: () -> Unit) {
            var cancelled = false
        }

        val all = mutableListOf<Timer>()

        override fun after(delay: Duration, block: () -> Unit): () -> Unit {
            val timer = Timer(delay, block)
            all += timer
            return { timer.cancelled = true }
        }

        fun active(delay: Duration) = all.last { it.delay == delay && !it.cancelled }
        fun last(delay: Duration) = all.last { it.delay == delay }
    }

    private class FakeLock : RingWakeLock {
        val holds = mutableListOf<Duration>()
        var releases = 0

        override fun hold(atMost: Duration) {
            holds += atMost
        }

        override fun release() {
            releases++
        }
    }

    private val speech = FakeSpeech()
    private val podcast = FakePodcast()
    private val fetcher = FakeFetcher()
    private val timers = FakeTimers()
    private val wakeLock = FakeLock()
    private val clock = FakeClock()
    private val reports = mutableListOf<Pair<Long, AlarmEvent>>()
    private val changes = mutableListOf<WakeUpStatus>()
    private val calls = FakeCalls()
    private val focus = FakeFocus()
    private val director = WakeUpDirector(
        speech,
        podcast,
        fetcher,
        timers,
        wakeLock,
        AudioFocusHold(focus) { _, _ -> },
        CallWatch(calls) { _, _ -> },
        clock::now,
        { id, event -> reports += id to event },
        { changes += it },
    )

    private val rssSource = NewsSource(NewsType.RSS, "https://www.news.example/feed", maxMinutes = 15)
    private val podcastSource = NewsSource(NewsType.PODCAST, "https://pod.example/feed", maxMinutes = 15)
    private val limit = Duration.ofMinutes(15)
    private val podcastStartLimit = Duration.ofSeconds(45)

    private val episode = PodcastEpisode("Der Tag, 6 November", "https://cdn.example/a.mp3", Duration.ofMinutes(18), null)
    private val rssFeed = Feed(
        NewsType.RSS,
        "tagesschau.de",
        null,
        listOf(FeedEntry("One", "First", null), FeedEntry("Two", null, null)),
        null,
    )
    private val podcastFeed = Feed(NewsType.PODCAST, "Der Tag", "Deutschlandfunk", emptyList(), episode)

    private fun alarm(id: Long, reason: String? = "Title $id", news: NewsSource? = null) = AlarmInstance(
        id,
        AlarmKey.forEvent(id, NOW),
        NOW,
        AlarmState.Announcing,
        0,
        false,
        snapshot(reason = reason).copy(news = news),
    )

    private fun playing() = director.status().playing

    private fun announced(id: Long): Pair<Long, AlarmEvent> = id to AlarmEvent.AnnouncementDone

    private fun newsDone(id: Long): Pair<Long, AlarmEvent> = id to AlarmEvent.NewsDone

    private fun found(feed: Feed) = FeedResult.Found(feed)

    private fun stoppedByOtherAudio(id: Long): Pair<Long, AlarmEvent> = id to AlarmEvent.StopWakeUp

    private fun startedPodcast() {
        director.startNews(alarm(1, news = podcastSource))
        fetcher.onResult(found(podcastFeed))
        podcast.onStarted()
    }

    // Audio focus.

    @Test
    fun `speech asks for the focus, and the automation gives it up when it ends`() {
        director.startAnnouncement(alarm(1))
        assertEquals(1, focus.requests)
        speech.finish()
        director.leave(1)
        assertEquals(1, focus.abandons)
    }

    @Test
    fun `a podcast asks for the focus before it plays`() {
        director.startNews(alarm(1, news = podcastSource))
        assertEquals(0, focus.requests)
        fetcher.onResult(found(podcastFeed))
        assertEquals(1, focus.requests)
    }

    @Test
    fun `the focus is kept from the titles through the news`() {
        director.startAnnouncement(alarm(1))
        speech.finish()
        director.startNews(alarm(1, news = rssSource))
        fetcher.onResult(found(rssFeed))
        assertEquals(1, focus.requests)
        assertEquals(0, focus.abandons)
    }

    @Test
    fun `an automation with nothing to play asks for nothing`() {
        director.startAnnouncement(alarm(1, reason = null))
        director.startNews(alarm(1))
        director.leave(1)
        assertEquals(0, focus.requests)
        assertEquals(0, focus.abandons)
    }

    @Test
    fun `focus that is denied does not stop the audio`() {
        focus.answer = false
        director.startAnnouncement(alarm(1))
        assertEquals(listOf("Title 1"), speech.said)
    }

    @Test
    fun `losing the focus for good while speaking ends the automation`() {
        director.startAnnouncement(alarm(1))
        director.startAnnouncement(alarm(2))
        focus.onLost()
        assertEquals(1, speech.stops)
        assertEquals(setOf(stoppedByOtherAudio(1), stoppedByOtherAudio(2)), reports.toSet())
        assertNull(playing())
        director.leave(1)
        director.leave(2)
        assertFalse(director.status().running)
    }

    @Test
    fun `losing the focus for good ends a podcast`() {
        startedPodcast()
        focus.onLost()
        assertEquals(1, podcast.stops)
        assertEquals(listOf(stoppedByOtherAudio(1)), reports)
    }

    @Test
    fun `losing the focus to a call is not the end, as the call decides`() {
        startedPodcast()
        calls.isActive = true
        focus.onLost()
        assertEquals(0, podcast.stops)
        assertTrue(reports.isEmpty())
    }

    @Test
    fun `the focus lost after the end means nothing`() {
        director.startAnnouncement(alarm(1))
        val lost = focus.onLost
        director.leave(1)
        lost()
        assertTrue(reports.isEmpty())
    }

    // Calls.

    @Test
    fun `a call at the start skips the title, and the rest goes on to the end`() {
        calls.isActive = true
        director.startAnnouncement(alarm(1))
        assertTrue(speech.said.isEmpty())
        assertEquals(listOf(announced(1)), reports)
        assertEquals(0, focus.requests)
    }

    @Test
    fun `a call at the start skips the news without fetching it`() {
        calls.isActive = true
        director.startNews(alarm(1, news = rssSource))
        assertTrue(fetcher.asked.isEmpty())
        assertEquals(listOf(newsDone(1)), reports)
        assertEquals(0, focus.requests)
    }

    @Test
    fun `a call that starts stops the title, and skips the others and the news`() {
        director.startAnnouncement(alarm(1, news = rssSource))
        director.startAnnouncement(alarm(2))
        director.startNews(alarm(1, news = rssSource))
        calls.change(true)
        assertEquals(1, speech.stops)
        assertEquals(listOf("Title 1"), speech.said)
        assertEquals(listOf(announced(1), announced(2), newsDone(1)), reports)
        assertTrue(fetcher.asked.isEmpty())
        assertEquals(1, focus.abandons)
    }

    @Test
    fun `a call that starts stops the reading of the news, which ends`() {
        director.startNews(alarm(1, news = rssSource))
        fetcher.onResult(found(rssFeed))
        calls.change(true)
        assertEquals(1, speech.stops)
        assertEquals(listOf(newsDone(1)), reports)
    }

    @Test
    fun `a call that starts pauses a podcast and leaves it in the news`() {
        startedPodcast()
        calls.change(true)
        assertEquals(1, podcast.pauses)
        assertEquals(0, podcast.stops)
        assertTrue(reports.isEmpty())
        assertIs<WakeUpPlaying.PlayingPodcast>(playing())
        assertEquals(1, focus.abandons)
    }

    @Test
    fun `when the call ends the podcast goes on, with the focus again`() {
        startedPodcast()
        calls.change(true)
        calls.change(false)
        assertEquals(1, podcast.resumes)
        assertEquals(2, focus.requests)
        assertTrue(reports.isEmpty())
    }

    @Test
    fun `a call that outlasts the limit ends the news, and there is nothing left to resume`() {
        startedPodcast()
        calls.change(true)
        timers.active(limit).block()
        assertEquals(1, podcast.stops)
        assertEquals(listOf(newsDone(1)), reports)
        calls.change(false)
        assertEquals(0, podcast.resumes)
    }

    @Test
    fun `a podcast that doesn't get going again ends the news`() {
        startedPodcast()
        calls.change(true)
        calls.change(false)
        podcast.onEnded()
        assertEquals(listOf(newsDone(1)), reports)
    }

    @Test
    fun `a call before a podcast has started counts as there being nothing to play`() {
        director.startNews(alarm(1, news = podcastSource))
        fetcher.onResult(found(podcastFeed))
        calls.change(true)
        assertEquals(1, podcast.stops)
        assertEquals(listOf(newsDone(1)), reports)
    }

    @Test
    fun `a call while the feed is on its way waits for it, and then nothing plays`() {
        director.startNews(alarm(1, news = podcastSource))
        calls.change(true)
        assertTrue(reports.isEmpty())
        fetcher.onResult(found(podcastFeed))
        assertTrue(podcast.played.isEmpty())
        assertEquals(listOf(newsDone(1)), reports)
    }

    @Test
    fun `a call that started and ended while the feed was on its way changes nothing`() {
        director.startNews(alarm(1, news = rssSource))
        calls.change(true)
        calls.change(false)
        fetcher.onResult(found(rssFeed))
        assertEquals(listOf("One. First"), speech.said)
    }

    @Test
    fun `an alarm that wants to be announced during a paused podcast does not stop it`() {
        startedPodcast()
        calls.change(true)
        director.startAnnouncement(alarm(2))
        assertEquals(0, podcast.stops)
        assertEquals(listOf(announced(2)), reports)
        calls.change(false)
        assertEquals(1, podcast.resumes)
    }

    @Test
    fun `an alarm that wants news during a paused podcast joins it`() {
        startedPodcast()
        calls.change(true)
        director.startNews(alarm(2, news = podcastSource))
        assertTrue(reports.isEmpty())
        calls.change(false)
        podcast.onEnded()
        assertEquals(listOf(newsDone(1), newsDone(2)), reports)
    }

    @Test
    fun `a call that starts with nothing playing has nothing to do`() {
        director.startAnnouncement(alarm(1))
        speech.finish()
        reports.clear()
        calls.change(true)
        assertTrue(reports.isEmpty())
    }

    @Test
    fun `a call that ends with nothing paused has nothing to do`() {
        director.startAnnouncement(alarm(1))
        calls.change(false)
        assertEquals(0, podcast.resumes)
        assertEquals(listOf("Title 1"), speech.said)
    }

    @Test
    fun `a podcast being played, without a call, has nothing to resume`() {
        startedPodcast()
        calls.change(false)
        assertEquals(0, podcast.resumes)
    }

    @Test
    fun `calls are only watched during the automation, and what comes late changes nothing`() {
        director.startAnnouncement(alarm(1))
        val late = calls.watching.single()
        director.leave(1)
        assertTrue(calls.watching.isEmpty())
        late(true)
        assertTrue(reports.isEmpty())
    }

    @Test
    fun `if there's no telling whether a call is active, it plays`() {
        calls.failure = SecurityException("Not allowed")
        director.startAnnouncement(alarm(1))
        assertEquals(listOf("Title 1"), speech.said)
    }

    @Test
    fun `a stored RSS feed that is plain http plays nothing when the alarm comes, and the automation goes on to its end`() {
        director.startNews(alarm(1, news = NewsSource(NewsType.RSS, "http://www.news.example/feed", maxMinutes = 15)))
        fetcher.onResult(FeedResult.Failed(FeedFailure.InsecureFeed))
        assertTrue(speech.said.isEmpty())
        assertEquals(listOf(newsDone(1)), reports)
    }

    private fun speechLimit(text: String) = Duration.ofSeconds(30).plusMillis(text.length * 200L)

    // Titles.

    @Test
    fun `before anything is started it is idle`() {
        assertEquals(WakeUpStatus.IDLE, director.status())
        assertFalse(director.status().running)
    }

    @Test
    fun `the title is said, and then reported as done`() {
        director.startAnnouncement(alarm(1))
        assertEquals(listOf("Title 1"), speech.said)
        assertEquals(WakeUpPlaying.Speaking("Title 1", 1, 1, NOW), playing())
        assertTrue(director.status().running)
        assertEquals(listOf("Title 1"), director.status().titles)
        assertTrue(reports.isEmpty())
        speech.finish()
        assertEquals(listOf(announced(1)), reports)
        assertNull(playing())
    }

    @Test
    fun `a failed title counts as said, so that the automation goes on`() {
        director.startAnnouncement(alarm(1))
        speech.finish(worked = false)
        assertEquals(listOf(announced(1)), reports)
    }

    @Test
    fun `an engine that fails at once does not hold anything up`() {
        speech.immediate = false
        director.startAnnouncement(alarm(1))
        director.startAnnouncement(alarm(2))
        assertEquals(listOf(announced(1), announced(2)), reports)
    }

    @Test
    fun `an engine that never answers is given up on, and a late answer is ignored`() {
        director.startAnnouncement(alarm(1))
        val late = speech.pending!!
        val timer = timers.active(speechLimit("Title 1"))
        timer.block()
        assertEquals(1, speech.stops)
        assertEquals(listOf(announced(1)), reports)
        late(true)
        timer.block()
        assertEquals(1, reports.size)
    }

    @Test
    fun `a limit that fires just as the title ended does nothing`() {
        director.startAnnouncement(alarm(1))
        speech.finish()
        assertTrue(timers.last(speechLimit("Title 1")).cancelled)
        timers.last(speechLimit("Title 1")).block()
        assertEquals(0, speech.stops)
        assertEquals(1, reports.size)
    }

    @Test
    fun `several titles are said in turn, and the screen counts them`() {
        director.startAnnouncement(alarm(1))
        director.startAnnouncement(alarm(2))
        director.startAnnouncement(alarm(3))
        assertEquals(listOf("Title 1"), speech.said)
        assertEquals(WakeUpPlaying.Speaking("Title 1", 1, 3, NOW), playing())
        speech.finish()
        assertEquals(WakeUpPlaying.Speaking("Title 2", 2, 3, NOW), playing())
        speech.finish()
        speech.finish()
        assertEquals(listOf("Title 1", "Title 2", "Title 3"), speech.said)
        assertEquals(listOf(1L, 2L, 3L), reports.map { it.first })
        assertEquals(listOf("Title 1", "Title 2", "Title 3"), director.status().titles)
    }

    @Test
    fun `counting starts again after the titles are through`() {
        director.startAnnouncement(alarm(1))
        speech.finish()
        director.startAnnouncement(alarm(2))
        assertEquals(WakeUpPlaying.Speaking("Title 2", 1, 1, NOW), playing())
    }

    @Test
    fun `an alarm without a title has nothing to say`() {
        director.startAnnouncement(alarm(1, reason = null))
        assertTrue(speech.said.isEmpty())
        assertEquals(listOf(announced(1)), reports)
        assertEquals(listOf<String?>(null), director.status().titles)
    }

    // Leaving.

    @Test
    fun `when the last alarm leaves everything stops and the lock is released`() {
        director.startAnnouncement(alarm(1))
        director.leave(1)
        assertEquals(WakeUpStatus.IDLE, director.status())
        assertEquals(WakeUpStatus.IDLE, changes.last())
        assertTrue(speech.stops > 0)
        assertEquals(1, wakeLock.releases)
    }

    @Test
    fun `an alarm that was not in the automation has nothing to leave`() {
        director.leave(7)
        assertEquals(0, wakeLock.releases)
        assertEquals(0, speech.stops)
        assertTrue(changes.isEmpty())
    }

    @Test
    fun `when the alarm being spoken leaves, the next one is said`() {
        director.startAnnouncement(alarm(1))
        director.startAnnouncement(alarm(2))
        val late = speech.pending!!
        director.leave(1)
        assertEquals(listOf("Title 1", "Title 2"), speech.said)
        late(true)
        assertTrue(reports.isEmpty())
    }

    @Test
    fun `when an alarm that waits for its turn leaves, it is not said and not counted`() {
        director.startAnnouncement(alarm(1))
        director.startAnnouncement(alarm(2))
        director.startAnnouncement(alarm(3))
        director.leave(2)
        assertEquals(WakeUpPlaying.Speaking("Title 1", 1, 2, NOW), playing())
        speech.finish()
        assertEquals(listOf("Title 1", "Title 3"), speech.said)
    }

    @Test
    fun `an alarm that is through leaves the others running`() {
        director.startAnnouncement(alarm(1))
        director.startAnnouncement(alarm(2))
        speech.finish()
        director.leave(1)
        assertEquals(listOf("Title 1", "Title 2"), speech.said)
        assertEquals(0, wakeLock.releases)
        assertEquals(listOf("Title 1", "Title 2"), director.status().titles)
    }

    @Test
    fun `when the only alarm with news leaves, the news stops`() {
        director.startAnnouncement(alarm(1))
        speech.finish()
        director.startNews(alarm(2, news = rssSource))
        val done = changes.size
        director.leave(2)
        assertEquals(1, fetcher.cancels)
        assertNull(playing())
        assertTrue(changes.size > done)
        assertEquals(0, wakeLock.releases)
    }

    @Test
    fun `a stray alarm leaving does not stop news that others are waiting for`() {
        director.startAnnouncement(alarm(1))
        speech.finish()
        director.startNews(alarm(2, news = rssSource))
        director.startNews(alarm(3, news = rssSource))
        director.leave(2)
        assertEquals(0, fetcher.cancels)
        assertIs<WakeUpPlaying.GettingNews>(playing())
    }

    @Test
    fun `the news of the alarm that leaves ends with the alarm`() {
        director.startNews(alarm(1, news = rssSource))
        director.leave(1)
        assertEquals(1, fetcher.cancels)
        assertEquals(WakeUpStatus.IDLE, director.status())
    }

    @Test
    fun `an automation can start again after it ended`() {
        director.startAnnouncement(alarm(1))
        director.startAnnouncement(alarm(2))
        director.leave(1)
        director.leave(2)
        director.startAnnouncement(alarm(3))
        assertEquals(WakeUpPlaying.Speaking("Title 3", 1, 1, NOW), playing())
        assertEquals(2, wakeLock.holds.size)
    }

    // The wake lock.

    @Test
    fun `the lock is held from the first alarm on, and for as long as the news may take`() {
        director.startAnnouncement(alarm(1))
        director.startAnnouncement(alarm(2))
        assertEquals(listOf(Duration.ofMinutes(10)), wakeLock.holds)
        director.startNews(alarm(3, news = rssSource))
        speech.finish()
        speech.finish()
        assertEquals(listOf(Duration.ofMinutes(10), Duration.ofMinutes(16)), wakeLock.holds)
    }

    // RSS.

    @Test
    fun `an RSS feed is fetched and read entry by entry`() {
        director.startNews(alarm(1, news = rssSource))
        assertEquals(listOf(rssSource.url), fetcher.asked)
        assertEquals(WakeUpPlaying.GettingNews("news.example"), playing())
        assertEquals(NOW.plus(limit), director.status().newsStopsAt)

        fetcher.onResult(found(rssFeed))
        assertEquals(WakeUpPlaying.ReadingNews("tagesschau.de", 1, 2, NOW, limit), playing())
        assertEquals(listOf("One. First"), speech.said)
        speech.finish()
        assertEquals(WakeUpPlaying.ReadingNews("tagesschau.de", 2, 2, NOW, limit), playing())
        assertTrue(reports.isEmpty())
        speech.finish()
        assertEquals(listOf(newsDone(1)), reports)
        assertNull(playing())
        assertNull(director.status().newsStopsAt)
    }

    @Test
    fun `no network ends the news`() {
        director.startNews(alarm(1, news = rssSource))
        fetcher.onResult(FeedResult.Failed(FeedFailure.Unreachable))
        assertEquals(listOf(newsDone(1)), reports)
        assertTrue(speech.said.isEmpty())
    }

    @Test
    fun `a feed without entries ends the news`() {
        director.startNews(alarm(1, news = rssSource))
        fetcher.onResult(found(rssFeed.copy(entries = emptyList())))
        assertEquals(listOf(newsDone(1)), reports)
    }

    @Test
    fun `without text to speech an RSS feed ends the news`() {
        director.startNews(alarm(1, news = rssSource))
        fetcher.onResult(found(rssFeed))
        speech.finish(worked = false)
        assertEquals(listOf("One. First"), speech.said)
        assertEquals(listOf(newsDone(1)), reports)
    }

    @Test
    fun `reading stops at the limit`() {
        director.startNews(alarm(1, news = rssSource))
        fetcher.onResult(found(rssFeed))
        val stopsBefore = speech.stops
        timers.active(limit).block()
        assertEquals(stopsBefore + 1, speech.stops)
        assertEquals(listOf(newsDone(1)), reports)
    }

    @Test
    fun `the limit includes the time the feed takes to arrive`() {
        director.startNews(alarm(1, news = rssSource))
        timers.active(limit).block()
        assertEquals(1, fetcher.cancels)
        assertEquals(listOf(newsDone(1)), reports)
        fetcher.onResult(found(rssFeed))
        assertTrue(speech.said.isEmpty())
    }

    @Test
    fun `the limit comes from the news source`() {
        director.startNews(alarm(1, news = rssSource.copy(maxMinutes = 2)))
        assertEquals(NOW.plus(Duration.ofMinutes(2)), director.status().newsStopsAt)
        timers.active(Duration.ofMinutes(2)).block()
        assertEquals(1, reports.size)
    }

    @Test
    fun `a limit that fires after the news ended does nothing`() {
        director.startNews(alarm(1, news = rssSource))
        val timer = timers.active(limit)
        fetcher.onResult(FeedResult.Failed(FeedFailure.Unreachable))
        assertTrue(timer.cancelled)
        timer.block()
        assertEquals(1, reports.size)
    }

    // Podcasts.

    @Test
    fun `the latest episode of a podcast is played`() {
        director.startNews(alarm(1, news = podcastSource))
        fetcher.onResult(found(podcastFeed))
        assertEquals(listOf(episode.audioUrl), podcast.played)
        assertEquals(
            WakeUpPlaying.PlayingPodcast("Der Tag", "Deutschlandfunk", episode.title, false, Duration.ZERO, episode.duration, limit),
            playing(),
        )
        podcast.position = Duration.ofSeconds(90)
        podcast.duration = Duration.ofMinutes(20)
        podcast.onStarted()
        assertEquals(
            WakeUpPlaying.PlayingPodcast("Der Tag", "Deutschlandfunk", episode.title, true, Duration.ofSeconds(90), Duration.ofMinutes(20), limit),
            playing(),
        )
        assertTrue(reports.isEmpty())
        podcast.onEnded()
        assertEquals(listOf(newsDone(1)), reports)
    }

    @Test
    fun `while the player does not know the length, the feed has to do`() {
        director.startNews(alarm(1, news = podcastSource))
        fetcher.onResult(found(podcastFeed))
        podcast.onStarted()
        assertEquals(episode.duration, assertIs<WakeUpPlaying.PlayingPodcast>(playing()).duration)
    }

    @Test
    fun `a failed episode ends the news`() {
        director.startNews(alarm(1, news = podcastSource))
        fetcher.onResult(found(podcastFeed))
        podcast.onEnded()
        assertEquals(listOf(newsDone(1)), reports)
    }

    @Test
    fun `an episode that does not start in time ends the news`() {
        director.startNews(alarm(1, news = podcastSource))
        fetcher.onResult(found(podcastFeed))
        timers.active(podcastStartLimit).block()
        assertEquals(1, podcast.stops)
        assertEquals(listOf(newsDone(1)), reports)
    }

    @Test
    fun `an episode that started is no longer limited by that`() {
        director.startNews(alarm(1, news = podcastSource))
        fetcher.onResult(found(podcastFeed))
        podcast.onStarted()
        assertTrue(timers.last(podcastStartLimit).cancelled)
    }

    @Test
    fun `an episode stops at the limit`() {
        director.startNews(alarm(1, news = podcastSource))
        fetcher.onResult(found(podcastFeed))
        podcast.onStarted()
        timers.active(limit).block()
        assertEquals(1, podcast.stops)
        assertEquals(listOf(newsDone(1)), reports)
    }

    @Test
    fun `a podcast address without audio ends the news`() {
        director.startNews(alarm(1, news = podcastSource))
        fetcher.onResult(found(rssFeed))
        assertEquals(listOf(newsDone(1)), reports)
        assertTrue(podcast.played.isEmpty())
    }

    @Test
    fun `a podcast set up as RSS is read, as that is what the user chose`() {
        director.startNews(alarm(1, news = rssSource))
        fetcher.onResult(found(podcastFeed.copy(entries = rssFeed.entries)))
        assertEquals(listOf("One. First"), speech.said)
        assertTrue(podcast.played.isEmpty())
    }

    @Test
    fun `callbacks of an ended episode do nothing`() {
        director.startNews(alarm(1, news = podcastSource))
        fetcher.onResult(found(podcastFeed))
        val onStarted = podcast.onStarted
        val onEnded = podcast.onEnded
        onEnded()
        onStarted()
        onEnded()
        assertEquals(1, reports.size)
    }

    @Test
    fun `a podcast still plays without text to speech`() {
        speech.immediate = false
        director.startAnnouncement(alarm(1, news = podcastSource))
        assertEquals(listOf(announced(1)), reports)
        director.startNews(alarm(1, news = podcastSource))
        fetcher.onResult(found(podcastFeed))
        assertEquals(listOf(episode.audioUrl), podcast.played)
    }

    // Together.

    @Test
    fun `news waits for the titles`() {
        director.startAnnouncement(alarm(1))
        director.startAnnouncement(alarm(2))
        speech.finish()
        director.startNews(alarm(1, news = rssSource))
        assertTrue(fetcher.asked.isEmpty())
        assertIs<WakeUpPlaying.Speaking>(playing())
        speech.finish()
        assertEquals(listOf(rssSource.url), fetcher.asked)
    }

    @Test
    fun `alarms share one news, and all are done with it`() {
        director.startNews(alarm(1, news = rssSource))
        director.startNews(alarm(2, news = podcastSource))
        assertEquals(listOf(rssSource.url), fetcher.asked)
        fetcher.onResult(FeedResult.Failed(FeedFailure.Unreachable))
        assertEquals(listOf(newsDone(1), newsDone(2)), reports)
    }

    @Test
    fun `a title to say interrupts the news, which then starts again`() {
        director.startNews(alarm(1, news = podcastSource))
        fetcher.onResult(found(podcastFeed))
        val onEnded = podcast.onEnded
        director.startAnnouncement(alarm(2))
        assertEquals(1, podcast.stops)
        assertEquals(listOf("Title 2"), speech.said)
        onEnded()
        assertTrue(reports.isEmpty())
        speech.finish()
        assertEquals(listOf(announced(2)), reports)
        assertEquals(listOf(podcastSource.url, podcastSource.url), fetcher.asked)
    }

    @Test
    fun `what is left of an interrupted news does not touch the news that started again`() {
        director.startNews(alarm(1, news = podcastSource))
        val oldFeed = fetcher.onResult
        fetcher.onResult(found(podcastFeed))
        val onStarted = podcast.onStarted
        val onEnded = podcast.onEnded
        director.startAnnouncement(alarm(2))
        speech.finish()
        assertEquals(WakeUpPlaying.GettingNews("pod.example"), playing())

        oldFeed(found(podcastFeed))
        onStarted()
        onEnded()
        assertEquals(WakeUpPlaying.GettingNews("pod.example"), playing())
        assertEquals(listOf(announced(2)), reports)
    }

    @Test
    fun `a title to say interrupts reading too`() {
        director.startNews(alarm(1, news = rssSource))
        fetcher.onResult(found(rssFeed))
        val late = speech.pending!!
        director.startAnnouncement(alarm(2))
        assertEquals(1, speech.stops)
        late(true)
        assertEquals(listOf("One. First", "Title 2"), speech.said)
    }

    @Test
    fun `a title to say interrupts fetching too, and the old feed is ignored`() {
        director.startNews(alarm(1, news = rssSource))
        val old = fetcher.onResult
        director.startAnnouncement(alarm(2))
        assertEquals(1, fetcher.cancels)
        old(found(rssFeed))
        assertEquals(listOf("Title 2"), speech.said)
    }

    @Test
    fun `when the alarm being spoken leaves, the news of the others starts`() {
        director.startNews(alarm(1, news = rssSource))
        director.startAnnouncement(alarm(2))
        director.leave(2)
        assertEquals(listOf(rssSource.url, rssSource.url), fetcher.asked)
    }

    @Test
    fun `an alarm that wants news but has no source just ends it`() {
        director.startNews(alarm(1))
        assertEquals(listOf(newsDone(1)), reports)
        assertTrue(fetcher.asked.isEmpty())
        assertTrue(director.status().running)
    }

    @Test
    fun `everybody hears about every change`() {
        director.startAnnouncement(alarm(1))
        assertEquals(WakeUpPlaying.Speaking("Title 1", 1, 1, NOW), changes.last().playing)
        speech.finish()
        assertNull(changes.last().playing)
        clock.time = NOW.plusSeconds(5)
        director.startNews(alarm(1, news = rssSource))
        assertEquals(WakeUpPlaying.GettingNews("news.example"), changes.last().playing)
        assertEquals(NOW.plusSeconds(5).plus(limit), changes.last().newsStopsAt)
    }
}
