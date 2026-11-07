package de.drehtuer.nextalarm.data

import de.drehtuer.nextalarm.domain.FeedDownload
import de.drehtuer.nextalarm.domain.FeedDownloader
import de.drehtuer.nextalarm.domain.FeedFailure
import de.drehtuer.nextalarm.domain.FeedResult
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** Nothing here goes near a network: the connections are fakes. */
class HttpFeedDownloaderTest {
    private class FakeConnection(
        url: URL,
        private val code: Int = 200,
        private val message: String? = "OK",
        private val location: String? = null,
        private val length: Long = -1,
        private val body: InputStream = ByteArrayInputStream(ByteArray(0)),
        private val failure: Exception? = null,
    ) : HttpURLConnection(url) {
        var disconnected = false

        override fun connect() = Unit
        override fun usingProxy() = false

        override fun disconnect() {
            disconnected = true
        }

        override fun getResponseCode(): Int {
            failure?.let { throw it }
            return code
        }

        override fun getResponseMessage() = message
        override fun getHeaderField(name: String?) = if (name == "Location") location else null
        override fun getContentLengthLong() = length
        override fun getInputStream() = body
    }

    private val opened = mutableListOf<FakeConnection>()

    private fun downloader(
        nanoTime: () -> Long = { 0 },
        answer: (URL) -> FakeConnection,
    ) = HttpFeedDownloader(open = { url -> answer(url).also { opened += it } }, nanoTime = nanoTime)

    private fun body(size: Int) = ByteArrayInputStream(ByteArray(size) { 1 })

    @Test
    fun `the answer is returned, and the connection closed`() {
        val result = downloader { FakeConnection(it, body = ByteArrayInputStream("feed".toByteArray())) }
            .download("https://a.example/feed", 100)
        assertContentEquals("feed".toByteArray(), assertIs<FeedDownload.Bytes>(result).bytes)
        assertEquals("https://a.example/feed", opened.single().url.toString())
        assertTrue(opened.single().disconnected)
    }

    @Test
    fun `the connection has timeouts, and does not follow redirects on its own`() {
        downloader { FakeConnection(it) }.download("https://a.example/", 100)
        val connection = opened.single()
        assertFalse(connection.instanceFollowRedirects)
        assertEquals(10_000, connection.connectTimeout)
        assertEquals(15_000, connection.readTimeout)
        assertEquals("NextAlarm", connection.getRequestProperty("User-Agent"))
        assertTrue("application/rss+xml" in connection.getRequestProperty("Accept").orEmpty())
    }

    @Test
    fun `an address that is not on the web is not opened`() {
        val result = downloader { FakeConnection(it) }.download("file:///etc/passwd", 100)
        assertEquals(FeedDownload.Failed(FeedFailure.InvalidAddress), result)
        assertTrue(opened.isEmpty())
    }

    @Test
    fun `an error of the server is reported with what it said`() {
        val result = downloader { FakeConnection(it, code = 404, message = "Not Found") }.download("https://a.example/", 100)
        assertEquals(FeedDownload.Failed(FeedFailure.Http(404, "Not Found")), result)
    }

    @Test
    fun `other answers than success are errors too`() {
        val below = downloader { FakeConnection(it, code = 100) }.download("https://a.example/", 100)
        assertEquals(FeedDownload.Failed(FeedFailure.Http(100, "OK")), below)
        val result = downloader { FakeConnection(it, code = 304, location = "https://b.example/") }.download("https://a.example/", 100)
        assertEquals(FeedDownload.Failed(FeedFailure.Http(304, "OK")), result)
    }

    @Test
    fun `redirects are followed, also between http and https, and relative ones too`() {
        val answers = mapOf(
            "http://a.example/old" to FakeAnswer(301, "https://b.example/x/new"),
            "https://b.example/x/new" to FakeAnswer(302, "newer"),
            "https://b.example/x/newer" to FakeAnswer(303, "/top"),
            "https://b.example/top" to FakeAnswer(307, "http://c.example/"),
            "http://c.example/" to FakeAnswer(308, "https://d.example/"),
            "https://d.example/" to FakeAnswer(200, null),
        )
        val result = downloader { url ->
            val answer = answers.getValue(url.toString())
            FakeConnection(url, code = answer.code, location = answer.location, body = ByteArrayInputStream(byteArrayOf(7)))
        }.download("http://a.example/old", 100)
        assertContentEquals(byteArrayOf(7), assertIs<FeedDownload.Bytes>(result).bytes)
        assertEquals(6, opened.size)
        assertTrue(opened.all { it.disconnected })
        assertEquals(answers.keys.toList(), assertIs<FeedDownload.Bytes>(result).addresses)
    }

    private class FakeAnswer(val code: Int, val location: String?)

    @Test
    fun `a redirect off the web is refused`() {
        val result = downloader { FakeConnection(it, code = 302, location = "file:///etc/passwd") }.download("https://a.example/", 100)
        assertEquals(FeedDownload.Failed(FeedFailure.InvalidAddress), result)
        assertEquals(1, opened.size)
    }

    @Test
    fun `a redirect that does not say where to is an error`() {
        val result = downloader { FakeConnection(it, code = 302, message = "Found") }.download("https://a.example/", 100)
        assertEquals(FeedDownload.Failed(FeedFailure.Http(302, "Found")), result)
    }

    @Test
    fun `endless redirects are given up on`() {
        val result = downloader { FakeConnection(it, code = 302, location = "/again") }.download("https://a.example/", 100)
        assertEquals(FeedDownload.Failed(FeedFailure.Unreachable), result)
        assertEquals(6, opened.size)
    }

    @Test
    fun `a file that says it is too large is not read`() {
        val body = object : InputStream() {
            override fun read(): Int = error("It must not be read")
        }
        val result = downloader { FakeConnection(it, length = 101, body = body) }.download("https://a.example/", 100)
        assertEquals(FeedDownload.Failed(FeedFailure.TooLarge), result)
    }

    @Test
    fun `a file that turns out too large is stopped, whatever it said`() {
        val result = downloader { FakeConnection(it, length = 10, body = body(101)) }.download("https://a.example/", 100)
        assertEquals(FeedDownload.Failed(FeedFailure.TooLarge), result)
    }

    @Test
    fun `a file of exactly the limit is fine`() {
        val result = downloader { FakeConnection(it, length = 100, body = body(100)) }.download("https://a.example/", 100)
        assertEquals(100, assertIs<FeedDownload.Bytes>(result).bytes.size)
    }

    @Test
    fun `no network is reported as unreachable`() {
        val result = downloader { FakeConnection(it, failure = IOException("No route")) }.download("https://a.example/", 100)
        assertEquals(FeedDownload.Failed(FeedFailure.Unreachable), result)
    }

    @Test
    fun `a missing permission is reported as unreachable`() {
        val result = downloader { FakeConnection(it, failure = SecurityException("No permission")) }.download("https://a.example/", 100)
        assertEquals(FeedDownload.Failed(FeedFailure.Unreachable), result)
    }

    @Test
    fun `a server that sends too slowly runs into the limit for the whole download`() {
        var time = 0L
        val result = downloader(nanoTime = { time }) {
            time = 61_000_000_000
            FakeConnection(it, body = body(5))
        }.download("https://a.example/", 100)
        assertEquals(FeedDownload.Failed(FeedFailure.Unreachable), result)
    }

    @Test
    fun `the real connections are http connections, and opening one does not connect`() {
        assertIs<HttpURLConnection>(openHttpConnection(URL("https://a.example/")))
        assertIs<FeedDownload.Failed>(HttpFeedDownloader().download("ftp://a.example/", 100))
    }
}

class HttpFeedSourceTest {
    private val rss = "<rss><channel><title>A</title></channel></rss>"

    @Test
    fun `a feed is downloaded and parsed`(): Unit = runBlocking {
        val source = HttpFeedSource(FeedDownloader { _, _ -> FeedDownload.Bytes(rss.toByteArray(), emptyList()) }, Dispatchers.Unconfined)
        assertEquals("A", assertIs<FeedResult.Found>(source.fetchFeed("https://a.example/")).feed.name)
    }

    @Test
    fun `failures are in the result`(): Unit = runBlocking {
        val failure = FeedDownload.Failed(FeedFailure.Unreachable)
        val source = HttpFeedSource(FeedDownloader { _, _ -> failure }, Dispatchers.Unconfined)
        assertEquals(FeedResult.Failed(FeedFailure.Unreachable), source.fetchFeed("https://a.example/"))
    }

    @Test
    fun `an RSS feed that was redirected over plain http is refused where the feed is fetched`(): Unit = runBlocking {
        val hops = listOf("https://a.example/", "http://b.example/")
        val source = HttpFeedSource(FeedDownloader { _, _ -> FeedDownload.Bytes(rss.toByteArray(), hops) }, Dispatchers.Unconfined)
        assertEquals(FeedResult.Failed(FeedFailure.InsecureFeed), source.fetchFeed("https://a.example/"))
    }

    @Test
    fun `by default it needs no network to refuse an address`(): Unit = runBlocking {
        assertEquals(FeedResult.Failed(FeedFailure.InvalidAddress), HttpFeedSource().fetchFeed("not an address"))
    }
}
