package de.drehtuer.nextalarm.data

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.CalendarContract
import androidx.test.core.app.ApplicationProvider
import de.drehtuer.nextalarm.domain.CalendarEvent
import de.drehtuer.nextalarm.domain.CalendarGoneException
import de.drehtuer.nextalarm.domain.CalendarId
import de.drehtuer.nextalarm.domain.DeviceCalendar
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AndroidCalendarRepositoryTest {
    /** Stands in for the calendar provider. It never sees real calendar data. */
    class FakeCalendarProvider : ContentProvider() {
        var result: Cursor? = null

        /** What asking for the chosen calendars gives. By default calendar 7 exists. */
        var chosenCalendars: Cursor? = MatrixCursor(arrayOf("_id")).apply { addRow(arrayOf<Any?>(7L)) }
        var calendarSelection: String? = null
        var calendarArgs: List<String>? = null

        /** If set, the queries run for real against these tables, as they do in the calendar provider. */
        var tables: SQLiteDatabase? = null
        var failure: RuntimeException? = null
        var uri: Uri? = null
        var selection: String? = null
        var selectionArgs: List<String>? = null

        override fun onCreate() = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? {
            val askingForChosen = selection?.startsWith("_id IN") == true
            if (askingForChosen) {
                calendarSelection = selection
                calendarArgs = selectionArgs?.toList()
            } else {
                this.uri = uri
                this.selection = selection
                this.selectionArgs = selectionArgs?.toList()
                failure?.let { throw it }
            }
            tables?.let { db ->
                val table = if (uri.pathSegments.first() == "calendars") "calendars" else "instances"
                return db.query(table, projection, selection, selectionArgs?.toList()?.toTypedArray(), null, null, null)
            }
            return if (askingForChosen) chosenCalendars else result
        }

        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }

    private val provider = Robolectric.buildContentProvider(FakeCalendarProvider::class.java)
        .create(CalendarContract.AUTHORITY).get()
    private val repository =
        AndroidCalendarRepository(ApplicationProvider.getApplicationContext<Context>().contentResolver)

    private val from = Instant.ofEpochMilli(1_000)
    private val to = Instant.ofEpochMilli(2_000)
    private val calendar = setOf(CalendarId(7))

    private fun eventCursor(vararg rows: Array<Any?>) =
        MatrixCursor(arrayOf("event_id", "begin", "title", "allDay", "eventStatus")).apply { rows.forEach(::addRow) }

    @Test
    fun `events are read from the instances of the given range and calendar`() = runBlocking<Unit> {
        provider.result = eventCursor()
        repository.events(calendar, from, to)
        assertEquals(listOf("instances", "when", "1000", "2000"), provider.uri?.pathSegments)
        assertEquals("calendar_id IN (?)", provider.selection)
        assertEquals(listOf("7"), provider.selectionArgs)
    }

    @Test
    fun `the query does not depend on the calendar being visible`() = runBlocking<Unit> {
        provider.result = eventCursor()
        repository.events(calendar, from, to)
        assertTrue(provider.selection?.contains("visible") == false)
    }

    @Test
    fun `a timed event is mapped`() = runBlocking {
        provider.result = eventCursor(arrayOf(5L, 1_500L, "Flight", 0, CalendarContract.Events.STATUS_CONFIRMED))
        assertEquals(
            listOf(CalendarEvent(5, Instant.ofEpochMilli(1_500), "Flight", allDay = false, cancelled = false)),
            repository.events(calendar, from, to),
        )
    }

    @Test
    fun `missing title and status are read as no title and not cancelled`() = runBlocking {
        provider.result = eventCursor(arrayOf(5L, 1_500L, null, 0, null))
        assertEquals(
            listOf(CalendarEvent(5, Instant.ofEpochMilli(1_500), null, allDay = false, cancelled = false)),
            repository.events(calendar, from, to),
        )
    }

    @Test
    fun `all-day and cancelled events are marked`() = runBlocking {
        provider.result = eventCursor(
            arrayOf(1L, 1_500L, "Holiday", 1, CalendarContract.Events.STATUS_TENTATIVE),
            arrayOf(2L, 1_600L, "Dropped", 0, CalendarContract.Events.STATUS_CANCELED),
        )
        val events = repository.events(calendar, from, to)
        assertEquals(listOf(true, false), events.map { it.allDay })
        assertEquals(listOf(false, true), events.map { it.cancelled })
    }

    @Test
    fun `an empty calendar gives no events`() = runBlocking {
        provider.result = eventCursor()
        assertEquals(emptyList(), repository.events(calendar, from, to))
    }

    @Test
    fun `a calendar that is gone is a failure, not an empty calendar`() = runBlocking<Unit> {
        provider.chosenCalendars = MatrixCursor(arrayOf("_id"))
        provider.result = eventCursor()
        assertFailsWith<CalendarGoneException> { repository.events(calendar, from, to) }
        assertEquals(listOf("7"), provider.calendarArgs)
        assertEquals(null, provider.uri)
    }

    @Test
    fun `asking for the calendar can fail like any read`() = runBlocking<Unit> {
        provider.chosenCalendars = null
        assertFailsWith<IllegalStateException> { repository.events(calendar, from, to) }
    }

    /**
     * Tables as in the calendar provider, in real SQLite, so that the selections and their arguments really run. Calendar 1
     * is shown in calendar apps and has an event, 2 is hidden and has an event, 3 has no events, and 4 is not chosen.
     */
    private fun providerWithCalendars(): SQLiteDatabase = SQLiteDatabase.create(null).also { db ->
        db.execSQL("CREATE TABLE calendars (_id INTEGER PRIMARY KEY, visible INTEGER NOT NULL)")
        db.execSQL(
            "CREATE TABLE instances (event_id INTEGER, begin INTEGER, title TEXT, allDay INTEGER, eventStatus INTEGER, " +
                "calendar_id INTEGER, visible INTEGER NOT NULL)",
        )
        listOf(1 to 1, 2 to 0, 3 to 1, 4 to 1).forEach { (id, visible) ->
            db.execSQL("INSERT INTO calendars VALUES ($id, $visible)")
        }
        db.execSQL("INSERT INTO instances VALUES (10, 1500, 'Special case', 0, NULL, 1, 1)")
        db.execSQL("INSERT INTO instances VALUES (20, 1600, 'Wake up for work', 0, NULL, 2, 0)")
        db.execSQL("INSERT INTO instances VALUES (40, 1700, 'A meeting', 0, NULL, 4, 1)")
        provider.tables = db
    }

    @Test
    fun `one query reads all the chosen calendars, with their ids only as arguments`() = runBlocking<Unit> {
        provider.chosenCalendars = MatrixCursor(arrayOf("_id")).apply {
            addRow(arrayOf<Any?>(1L))
            addRow(arrayOf<Any?>(2L))
        }
        provider.result = eventCursor()
        repository.events(setOf(CalendarId(1), CalendarId(2)), from, to)
        assertEquals("_id IN (?, ?)", provider.calendarSelection)
        assertEquals(listOf("1", "2"), provider.calendarArgs)
        assertEquals(listOf("instances", "when", "1000", "2000"), provider.uri?.pathSegments)
        assertEquals("calendar_id IN (?, ?)", provider.selection)
        assertEquals(listOf("1", "2"), provider.selectionArgs)
    }

    @Test
    fun `a hidden calendar counts like a visible one, an empty one adds nothing, and others stay out`() = runBlocking {
        providerWithCalendars().use {
            val events = repository.events(setOf(CalendarId(1), CalendarId(2), CalendarId(3)), from, to)
            assertEquals(listOf(10L, 20L), events.map { it.eventId }.sorted())
            assertEquals(setOf("Special case", "Wake up for work"), events.map { it.title }.toSet())
        }
        assertTrue(provider.selection?.contains("visible") == false)
    }

    @Test
    fun `only a hidden calendar still gives its events`() = runBlocking {
        providerWithCalendars().use {
            assertEquals(listOf(20L), repository.events(setOf(CalendarId(2)), from, to).map { it.eventId })
        }
    }

    @Test
    fun `calendars that all exist and are all empty are a valid empty result`() = runBlocking {
        providerWithCalendars().use {
            assertEquals(emptyList(), repository.events(setOf(CalendarId(3)), from, to))
        }
    }

    @Test
    fun `one missing calendar among several fails the read, and says which`() = runBlocking {
        providerWithCalendars().use {
            val failure = assertFailsWith<CalendarGoneException> {
                repository.events(setOf(CalendarId(1), CalendarId(2), CalendarId(9)), from, to)
            }
            assertEquals(setOf(CalendarId(9)), failure.calendars)
            assertEquals(null, provider.uri)
        }
    }

    @Test
    fun `all of several missing calendars are named`() = runBlocking {
        providerWithCalendars().use {
            val failure = assertFailsWith<CalendarGoneException> {
                repository.events(setOf(CalendarId(8), CalendarId(9)), from, to)
            }
            assertEquals(setOf(CalendarId(8), CalendarId(9)), failure.calendars)
            assertTrue("8" in failure.message.orEmpty() && "9" in failure.message.orEmpty(), failure.message)
        }
    }

    @Test
    fun `asking for no calendars is an error, not an empty result`() = runBlocking<Unit> {
        assertFailsWith<IllegalArgumentException> { repository.events(emptySet(), from, to) }
        assertEquals(null, provider.calendarArgs)
        assertEquals(null, provider.uri)
    }

    @Test
    fun `a missing result is a failure, not an empty calendar`() = runBlocking<Unit> {
        provider.result = null
        assertFailsWith<IllegalStateException> { repository.events(calendar, from, to) }
        assertFailsWith<IllegalStateException> { repository.calendars() }
    }

    @Test
    fun `a revoked permission is a failure`() = runBlocking<Unit> {
        provider.failure = SecurityException("no calendar permission")
        assertFailsWith<SecurityException> { repository.events(calendar, from, to) }
    }

    @Test
    fun `calendars are listed, with or without name and account`() = runBlocking {
        provider.result = MatrixCursor(arrayOf("_id", "calendar_displayName", "account_name", "account_type", "calendar_color")).apply {
            addRow(arrayOf<Any?>(1L, "Alarms", "me@example.org", "bitfire.at.davdroid", 123))
            addRow(arrayOf<Any?>(2L, null, null, null, null))
        }
        assertEquals(
            listOf(
                DeviceCalendar(CalendarId(1), "Alarms", "me@example.org", "bitfire.at.davdroid", 123),
                DeviceCalendar(CalendarId(2), "", null, null, null),
            ),
            repository.calendars(),
        )
        assertEquals(listOf("calendars"), provider.uri?.pathSegments)
    }
}
