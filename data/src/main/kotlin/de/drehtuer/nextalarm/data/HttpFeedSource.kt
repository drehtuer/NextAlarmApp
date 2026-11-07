package de.drehtuer.nextalarm.data

import de.drehtuer.nextalarm.domain.FeedDownload
import de.drehtuer.nextalarm.domain.FeedDownloader
import de.drehtuer.nextalarm.domain.FeedFailure
import de.drehtuer.nextalarm.domain.FeedResult
import de.drehtuer.nextalarm.domain.FeedSource
import de.drehtuer.nextalarm.domain.isWebAddress
import de.drehtuer.nextalarm.domain.readFeed
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.Duration
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Fetches and parses feeds on a background thread. */
class HttpFeedSource(
    private val downloader: FeedDownloader = HttpFeedDownloader(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : FeedSource {
    override suspend fun fetchFeed(url: String): FeedResult = withContext(ioDispatcher) { readFeed(url, downloader) }
}

/** The address has been checked to be http or https. This does not connect yet. */
internal fun openHttpConnection(url: URL): HttpURLConnection = url.openConnection() as HttpURLConnection

/**
 * Downloads with `HttpURLConnection`, http and https only. The connection does not follow redirects itself: it refuses
 * those between http and https, which are common, and every hop has to be checked anyway. A server could send a
 * compressed file that is far larger than it looks, so what counts is what comes out of the stream.
 */
class HttpFeedDownloader(
    private val open: (URL) -> HttpURLConnection = ::openHttpConnection,
    private val nanoTime: () -> Long = System::nanoTime,
) : FeedDownloader {
    override fun download(url: String, maxBytes: Long): FeedDownload {
        val deadline = nanoTime() + TOTAL_LIMIT.toNanos()
        return try {
            follow(url, maxBytes, deadline)
        } catch (_: IOException) {
            FeedDownload.Failed(FeedFailure.Unreachable)
        } catch (_: SecurityException) {
            FeedDownload.Failed(FeedFailure.Unreachable)
        }
    }

    private fun follow(url: String, maxBytes: Long, deadline: Long): FeedDownload {
        var address = url
        val addresses = mutableListOf<String>()
        repeat(MAX_REDIRECTS + 1) {
            if (!isWebAddress(address)) return FeedDownload.Failed(FeedFailure.InvalidAddress)
            addresses += address
            val connection = open(URL(address))
            try {
                configure(connection)
                val code = connection.responseCode
                val location = if (code in REDIRECTS) connection.getHeaderField("Location") else null
                if (location == null) return answer(connection, code, maxBytes, deadline, addresses)
                address = URL(URL(address), location).toString()
            } finally {
                connection.disconnect()
            }
        }
        return FeedDownload.Failed(FeedFailure.Unreachable)
    }

    private fun configure(connection: HttpURLConnection) {
        connection.instanceFollowRedirects = false
        connection.connectTimeout = CONNECT_TIMEOUT.toMillis().toInt()
        connection.readTimeout = READ_TIMEOUT.toMillis().toInt()
        connection.setRequestProperty("User-Agent", "NextAlarm")
        connection.setRequestProperty("Accept", ACCEPT)
    }

    private fun answer(
        connection: HttpURLConnection,
        code: Int,
        maxBytes: Long,
        deadline: Long,
        addresses: List<String>,
    ): FeedDownload = when {
        code !in SUCCESS -> FeedDownload.Failed(FeedFailure.Http(code, connection.responseMessage))
        connection.contentLengthLong > maxBytes -> FeedDownload.Failed(FeedFailure.TooLarge)
        else -> connection.inputStream.use { read(it, maxBytes, deadline, addresses) }
    }

    /** The limit for the whole download stops a server that keeps sending just enough not to run into the timeout. */
    private fun read(stream: InputStream, maxBytes: Long, deadline: Long, addresses: List<String>): FeedDownload {
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_BYTES)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) return FeedDownload.Bytes(bytes.toByteArray(), addresses)
            if (bytes.size() + count > maxBytes) return FeedDownload.Failed(FeedFailure.TooLarge)
            if (nanoTime() > deadline) return FeedDownload.Failed(FeedFailure.Unreachable)
            bytes.write(buffer, 0, count)
        }
    }

    private companion object {
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(10)
        val READ_TIMEOUT: Duration = Duration.ofSeconds(15)
        val TOTAL_LIMIT: Duration = Duration.ofSeconds(60)
        const val MAX_REDIRECTS = 5
        const val BUFFER_BYTES = 16 * 1024
        const val ACCEPT = "application/rss+xml, application/atom+xml, application/xml, text/xml;q=0.9, */*;q=0.1"
        val SUCCESS = 200..299
        val REDIRECTS = setOf(301, 302, 303, 307, 308)
    }
}
