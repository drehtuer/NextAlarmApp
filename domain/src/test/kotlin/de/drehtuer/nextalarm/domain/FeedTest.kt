package de.drehtuer.nextalarm.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FeedTest {
    private class FakeDownloader(private val answer: FeedDownload) : FeedDownloader {
        val asked = mutableListOf<Pair<String, Long>>()

        override fun download(url: String, maxBytes: Long): FeedDownload {
            asked += url to maxBytes
            return answer
        }
    }

    @Test
    fun `a feed is downloaded with the size limit, and parsed`() {
        val downloader = FakeDownloader(FeedDownload.Bytes("<rss><channel><title>A</title></channel></rss>".toByteArray(), emptyList()))
        val result = readFeed(" https://www.tagesschau.de/feed ", downloader)
        assertEquals("A", assertIs<FeedResult.Found>(result).feed.name)
        assertEquals(listOf("https://www.tagesschau.de/feed" to MAX_FEED_BYTES), downloader.asked)
    }

    @Test
    fun `a feed without a title is named after its host`() {
        val downloader = FakeDownloader(FeedDownload.Bytes("<rss><channel/></rss>".toByteArray(), emptyList()))
        val result = readFeed("https://www.tagesschau.de/feed", downloader)
        assertEquals("tagesschau.de", assertIs<FeedResult.Found>(result).feed.name)
    }

    @Test
    fun `an address that is not on the web is not even tried`() {
        val downloader = FakeDownloader(FeedDownload.Bytes(ByteArray(0), emptyList()))
        listOf("file:///etc/passwd", "ftp://x.example/a", "tagesschau.de", "", "http://", "https://bad host/").forEach {
            assertEquals(FeedResult.Failed(FeedFailure.InvalidAddress), readFeed(it, downloader), it)
        }
        assertTrue(downloader.asked.isEmpty())
    }

    @Test
    fun `a failed download is passed on`() {
        val failure = FeedFailure.Http(404, "Not Found")
        assertEquals(FeedResult.Failed(failure), readFeed("https://a.example/", FakeDownloader(FeedDownload.Failed(failure))))
    }

    @Test
    fun `an answer that is not a feed is reported as such`() {
        val result = readFeed("https://a.example/", FakeDownloader(FeedDownload.Bytes("<html/>".toByteArray(), emptyList())))
        assertEquals(FeedResult.Failed(FeedFailure.NotAFeed), result)
    }

    private val rss = "<rss><channel><title>A</title><item><title>B</title></item></channel></rss>".toByteArray()
    private val podcast = """<rss><channel><title>A</title><item><title>B</title>
        <enclosure url="http://a.example/a.mp3" type="audio/mpeg"/></item></channel></rss>""".toByteArray()

    private fun read(address: String, bytes: ByteArray, hops: List<String> = listOf(address)) =
        readFeed(address, FakeDownloader(FeedDownload.Bytes(bytes, hops)))

    @Test
    fun `an RSS feed at a plain http address is refused`() {
        assertEquals(FeedResult.Failed(FeedFailure.InsecureFeed), read("http://a.example/", rss))
    }

    @Test
    fun `the scheme counts in any case, and with spaces around the address`() {
        assertEquals(FeedResult.Failed(FeedFailure.InsecureFeed), read(" HTTP://a.example/ ", rss, emptyList()))
    }

    @Test
    fun `an RSS feed that came over plain http at any redirect is refused`() {
        val hops = listOf("https://a.example/", "http://b.example/", "https://c.example/")
        assertEquals(FeedResult.Failed(FeedFailure.InsecureFeed), read("https://a.example/", rss, hops))
    }

    @Test
    fun `an RSS feed that plain http redirected to https is refused too`() {
        val hops = listOf("http://a.example/", "https://a.example/")
        assertEquals(FeedResult.Failed(FeedFailure.InsecureFeed), read("http://a.example/", rss, hops))
    }

    @Test
    fun `an RSS feed over https all the way is fine`() {
        val hops = listOf("https://a.example/", "https://b.example/")
        assertEquals(NewsType.RSS, assertIs<FeedResult.Found>(read("https://a.example/", rss, hops)).feed.kind)
    }

    @Test
    fun `a podcast may use plain http, for the feed and for its audio`() {
        val hops = listOf("http://a.example/", "http://b.example/")
        val feed = assertIs<FeedResult.Found>(read("http://a.example/", podcast, hops)).feed
        assertEquals(NewsType.PODCAST, feed.kind)
        assertEquals("http://a.example/a.mp3", feed.latestEpisode?.audioUrl)
    }

    @Test
    fun `an answer over plain http that is no feed at all stays what it is`() {
        val result = read("http://a.example/", "<html/>".toByteArray())
        assertEquals(FeedResult.Failed(FeedFailure.NotAFeed), result)
    }

    @Test
    fun `only http counts as plain http`() {
        assertTrue(isPlainHttp("http://a.example/"))
        assertTrue(isPlainHttp("HTTP://a.example/"))
        assertFalse(isPlainHttp("https://a.example/"))
        assertFalse(isPlainHttp("httpx://a.example/"))
    }

    @Test
    fun `only http and https count as web addresses`() {
        assertTrue(isWebAddress("http://a.example/x"))
        assertTrue(isWebAddress("HTTPS://a.example/x"))
        assertFalse(isWebAddress("file:///etc/passwd"))
        assertFalse(isWebAddress("/just/a/path"))
        assertFalse(isWebAddress("http:///no-host"))
        assertFalse(isWebAddress("http://a b/"))
    }

    @Test
    fun `the fallback name is the host, or the address if there is none`() {
        assertEquals("tagesschau.de", feedFallbackName("https://www.tagesschau.de/x"))
        assertEquals("a.example", feedFallbackName("https://a.example/x"))
        assertEquals("/path", feedFallbackName("/path"))
        assertEquals("http://a b/", feedFallbackName("http://a b/"))
    }

    @Test
    fun `what is read aloud is the title and the text`() {
        assertEquals("Title", FeedEntry("Title", null, null).spokenText)
        assertEquals("Title", FeedEntry("Title", "Title", null).spokenText)
        assertEquals("Title. Text", FeedEntry("Title", "Text", null).spokenText)
        assertEquals("Title? Text", FeedEntry("Title?", "Text", null).spokenText)
    }
}
