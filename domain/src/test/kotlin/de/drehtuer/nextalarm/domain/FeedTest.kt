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
        val downloader = FakeDownloader(FeedDownload.Bytes("<rss><channel><title>A</title></channel></rss>".toByteArray()))
        val result = readFeed(" https://www.tagesschau.de/feed ", downloader)
        assertEquals("A", assertIs<FeedResult.Found>(result).feed.name)
        assertEquals(listOf("https://www.tagesschau.de/feed" to MAX_FEED_BYTES), downloader.asked)
    }

    @Test
    fun `a feed without a title is named after its host`() {
        val downloader = FakeDownloader(FeedDownload.Bytes("<rss><channel/></rss>".toByteArray()))
        val result = readFeed("https://www.tagesschau.de/feed", downloader)
        assertEquals("tagesschau.de", assertIs<FeedResult.Found>(result).feed.name)
    }

    @Test
    fun `an address that is not on the web is not even tried`() {
        val downloader = FakeDownloader(FeedDownload.Bytes(ByteArray(0)))
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
        val result = readFeed("https://a.example/", FakeDownloader(FeedDownload.Bytes("<html/>".toByteArray())))
        assertEquals(FeedResult.Failed(FeedFailure.NotAFeed), result)
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
