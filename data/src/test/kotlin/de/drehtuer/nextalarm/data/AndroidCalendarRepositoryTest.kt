package de.drehtuer.nextalarm.data

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.CalendarContract
import androidx.test.core.app.ApplicationProvider
import de.drehtuer.nextalarm.domain.CalendarEvent
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
            this.uri = uri
            this.selection = selection
            this.selectionArgs = selectionArgs?.toList()
            failure?.let { throw it }
            return result
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
    private val calendar = CalendarId(7)

    private fun eventCursor(vararg rows: Array<Any?>) =
        MatrixCursor(arrayOf("event_id", "begin", "title", "allDay", "eventStatus")).apply { rows.forEach(::addRow) }

    @Test
    fun `events are read from the instances of the given range and calendar`() = runBlocking<Unit> {
        provider.result = eventCursor()
        repository.events(calendar, from, to)
        assertEquals(listOf("instances", "when", "1000", "2000"), provider.uri?.pathSegments)
        assertEquals("calendar_id = ?", provider.selection)
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
