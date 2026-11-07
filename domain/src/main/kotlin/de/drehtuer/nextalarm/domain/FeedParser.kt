package de.drehtuer.nextalarm.domain

import java.io.ByteArrayInputStream
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import javax.xml.XMLConstants
import javax.xml.parsers.ParserConfigurationException
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.ext.DefaultHandler2

// Feeds come from servers the user does not control, so the XML is hostile until proven otherwise.

private const val MAX_ENTRIES = 100
private const val MAX_RAW_TEXT = 32_000
private const val MAX_TITLE = 500
private const val MAX_SUMMARY = 3_000
private const val MAX_NAME = 200
private const val LEXICAL_HANDLER = "http://xml.org/sax/properties/lexical-handler"

private const val ATOM = "http://www.w3.org/2005/Atom"
private const val RSS_1 = "http://purl.org/rss/1.0/"
private const val RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
private const val ITUNES = "http://www.itunes.com/dtds/podcast-1.0.dtd"
private const val CONTENT = "http://purl.org/rss/1.0/modules/content/"
private const val DUBLIN_CORE = "http://purl.org/dc/elements/1.1/"
private const val SECONDS_PER_PART = 60L
private const val MAX_DURATION_PARTS = 3

private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "flac")
private val GENERIC_TYPES = setOf("", "application/octet-stream", "binary/octet-stream")
private val ITEMS = setOf("item", "entry")
private val FEED_LEVEL = setOf("channel", "feed")

/** Features that are not known to every parser. What decides is [HostileXmlGuard], which works on all of them. */
private val STRICT_FEATURES = listOf(
    XMLConstants.FEATURE_SECURE_PROCESSING to true,
    "http://apache.org/xml/features/disallow-doctype-decl" to true,
    "http://xml.org/sax/features/external-general-entities" to false,
    "http://xml.org/sax/features/external-parameter-entities" to false,
    "http://apache.org/xml/features/nonvalidating/load-external-dtd" to false,
)

/**
 * The feed in [bytes], or nothing if it is not a feed or not safe to read. RSS 2.0, RSS 1.0 and Atom are understood.
 *
 * A document with a DOCTYPE is refused: a feed has no use for one, and all entity attacks need one. Android's parser
 * does not know the feature that forbids it, so the handler itself refuses when the DOCTYPE starts, before any
 * declaration in it has been looked at. That is the same on the JVM and on the phone.
 */
fun parseFeed(bytes: ByteArray, fallbackName: String): Feed? = parseFeed(bytes, fallbackName, STRICT_FEATURES)

/** The [features] can be left out, to show that the feed is safe to read on a parser that knows none of them. */
internal fun parseFeed(
    bytes: ByteArray,
    fallbackName: String,
    features: List<Pair<String, Boolean>>,
    factory: SAXParserFactory = SAXParserFactory.newInstance(),
): Feed? = try {
    factory.isNamespaceAware = true
    features.forEach { (name, value) -> factory.setSupported(name, value) }
    val handler = FeedHandler()
    val reader = factory.newSAXParser().xmlReader
    reader.contentHandler = handler
    reader.errorHandler = handler
    reader.entityResolver = handler
    // Without this the DOCTYPE would not be noticed, so a parser that refuses it does not get to read the feed.
    reader.setProperty(LEXICAL_HANDLER, handler)
    reader.parse(InputSource(ByteArrayInputStream(bytes)))
    handler.feed(fallbackName)
} catch (_: SAXException) {
    null
} catch (_: IOException) {
    null
} catch (_: ParserConfigurationException) {
    null
}

private fun SAXParserFactory.setSupported(name: String, value: Boolean) {
    try {
        setFeature(name, value)
    } catch (_: SAXException) {
        // This parser does not know the feature. See parseFeed for what protects it then.
    } catch (_: ParserConfigurationException) {
        // The same, reported differently.
    }
}

/** What refuses a DOCTYPE and any external entity. */
internal open class HostileXmlGuard : DefaultHandler2() {
    override fun startDTD(name: String?, publicId: String?, systemId: String?): Unit =
        throw SAXException("A feed must not have a DOCTYPE")

    override fun resolveEntity(name: String?, publicId: String?, baseURI: String?, systemId: String?): InputSource =
        throw SAXException("A feed must not refer to external entities")
}

private class ItemBuilder {
    var title = ""
    var summary = ""
    var content = ""
    var published: Instant? = null
    var duration: Duration? = null
    var audioUrl: String? = null

    fun entry(): FeedEntry? {
        val text = plainText(summary).ifEmpty { plainText(content) }
        val heading = plainText(title).take(MAX_TITLE)
        return when {
            heading.isNotEmpty() -> FeedEntry(heading, text.take(MAX_SUMMARY).ifEmpty { null }, published)
            text.isNotEmpty() -> FeedEntry(text.take(MAX_TITLE), null, published)
            else -> null
        }
    }

    fun episode(): PodcastEpisode? =
        audioUrl?.let { PodcastEpisode(plainText(title).take(MAX_TITLE), it, duration, published) }
}

private class FeedHandler : HostileXmlGuard() {
    private val path = ArrayDeque<String>()
    private val text = StringBuilder()
    private val entries = mutableListOf<FeedEntry>()
    private var latest: PodcastEpisode? = null
    private var current: ItemBuilder? = null
    private var title = ""
    private var author: String? = null

    fun feed(fallbackName: String): Feed {
        val name = plainText(title).take(MAX_NAME).ifEmpty { fallbackName }
        val episode = latest?.let { it.copy(title = it.title.ifEmpty { name }) }
        return Feed(
            kind = if (episode != null) NewsType.PODCAST else NewsType.RSS,
            name = name,
            author = author?.let(::plainText)?.take(MAX_NAME)?.ifEmpty { null },
            entries = entries,
            latestEpisode = episode,
        )
    }

    override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
        val name = elementName(uri, localName)
        if (path.isEmpty()) checkRoot(uri, localName)
        path.addLast(name)
        text.clear()
        if (name in ITEMS && current == null) current = ItemBuilder()
        current?.let { readEnclosure(name, attributes, it) }
    }

    override fun characters(ch: CharArray, start: Int, length: Int) {
        val room = MAX_RAW_TEXT - text.length
        if (room > 0) text.appendRange(ch, start, start + minOf(room, length))
    }

    override fun endElement(uri: String, localName: String, qName: String) {
        val name = path.removeLast()
        val parent = path.lastOrNull()
        val item = current
        when {
            item != null && parent in ITEMS -> readItemField(item, name)
            parent in FEED_LEVEL -> readFeedField(name)
        }
        if (name in ITEMS && item != null) finishItem(item)
        text.clear()
    }

    private fun elementName(uri: String, localName: String): String = when (uri) {
        "", ATOM, RSS_1 -> localName
        ITUNES -> "itunes:$localName"
        CONTENT -> "content:$localName"
        DUBLIN_CORE -> "dc:$localName"
        else -> "other:$localName"
    }

    /** Stops at once for anything else, such as a web page that happens to be well-formed. */
    private fun checkRoot(uri: String, localName: String) {
        val isFeed = (uri.isEmpty() && localName == "rss") || (uri == ATOM && localName == "feed") ||
            (uri == RDF && localName == "RDF")
        if (!isFeed) throw SAXException("Not a feed: $localName")
    }

    private fun readEnclosure(name: String, attributes: Attributes, item: ItemBuilder) {
        if (item.audioUrl != null) return
        val url = when {
            name == "enclosure" -> attributes.plain("url")
            name == "link" && attributes.plain("rel") == "enclosure" -> attributes.plain("href")
            else -> null
        } ?: return
        val address = url.trim()
        if (isWebAddress(address) && isAudio(address, attributes.plain("type"))) item.audioUrl = address
    }

    private fun readItemField(item: ItemBuilder, name: String) {
        val value = text.toString().trim()
        when (name) {
            "title" -> item.title = value
            "description", "summary" -> item.summary = value
            "content:encoded", "content" -> item.content = value
            "pubDate", "published", "dc:date" -> item.published = parseFeedDate(value)
            // Atom's date of the last change, which only counts if there is no date of publication.
            "updated" -> if (item.published == null) item.published = parseFeedDate(value)
            "itunes:duration" -> item.duration = parseFeedDuration(value)
        }
    }

    private fun readFeedField(name: String) {
        val value = text.toString().trim()
        when (name) {
            "title" -> title = value
            "itunes:author" -> author = value
        }
    }

    /** The first of equally new episodes wins, so a feed without dates gets its first one: feeds put the newest first. */
    private fun finishItem(item: ItemBuilder) {
        current = null
        if (entries.size < MAX_ENTRIES) item.entry()?.let { entries += it }
        val episode = item.episode() ?: return
        val best = latest
        if (best == null || (episode.published ?: Instant.MIN) > (best.published ?: Instant.MIN)) latest = episode
    }
}

/** Looks through the attributes by local name, because parsers differ in what they give as the qualified name. */
private fun Attributes.plain(name: String): String? =
    (0 until length).firstOrNull { getLocalName(it) == name && getURI(it).isEmpty() }?.let { getValue(it) }

/** Video enclosures are of no use. Some servers do not say what a file is, and then its name has to do. */
private fun isAudio(url: String, type: String?): Boolean {
    val mime = type.orEmpty().trim().lowercase()
    if (mime.startsWith("audio/")) return true
    if (mime !in GENERIC_TYPES) return false
    return url.substringBefore('?').substringBefore('#').substringAfterLast('.').lowercase() in AUDIO_EXTENSIONS
}

/** RSS uses the date format of mails and Atom ISO 8601. A date in neither counts as no date. */
internal fun parseFeedDate(text: String): Instant? = try {
    OffsetDateTime.parse(text.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
} catch (_: DateTimeParseException) {
    try {
        OffsetDateTime.parse(text.trim(), DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant()
    } catch (_: DateTimeParseException) {
        null
    }
}

/** `itunes:duration`: "1110", "18:30" or "1:02:03". */
internal fun parseFeedDuration(text: String): Duration? {
    val parts = text.trim().split(':').map { it.toLongOrNull() }
    if (parts.size > MAX_DURATION_PARTS || parts.any { it == null || it < 0 }) return null
    val seconds = parts.filterNotNull().fold(0L) { total, part -> total * SECONDS_PER_PART + part }
    return if (seconds > 0) Duration.ofSeconds(seconds) else null
}

private val HIDDEN_ELEMENT = Regex("<(script|style)\\b[^>]*>.*?</\\1\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val TAG = Regex("<[^>]*>")
private val ENTITY = Regex("&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|amp|lt|gt|quot|apos|nbsp);")
private val SPACES = Regex("\\s+")
private const val HEX = 16

private val NAMED_ENTITIES = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ")

/** Feeds often carry HTML inside their text, and neither the tags nor the entities are meant to be read aloud. */
internal fun plainText(html: String): String = html
    .replace(HIDDEN_ELEMENT, " ")
    .replace(TAG, " ")
    .replace(ENTITY) { decodeEntity(it.groupValues[1]) }
    .replace(SPACES, " ")
    .trim()

private fun decodeEntity(name: String): String {
    val code = when {
        name.startsWith("#x", ignoreCase = true) -> name.drop(2).toInt(HEX)
        name.startsWith("#") -> name.drop(1).toInt()
        else -> return NAMED_ENTITIES.getValue(name)
    }
    return if (Character.isValidCodePoint(code)) String(Character.toChars(code)) else " "
}
