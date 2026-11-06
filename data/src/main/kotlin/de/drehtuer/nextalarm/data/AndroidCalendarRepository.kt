package de.drehtuer.nextalarm.data

import android.content.ContentResolver
import android.content.ContentUris
import android.database.Cursor
import android.net.Uri
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import de.drehtuer.nextalarm.domain.CalendarEvent
import de.drehtuer.nextalarm.domain.CalendarId
import de.drehtuer.nextalarm.domain.CalendarRepository
import de.drehtuer.nextalarm.domain.DeviceCalendar
import java.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads the calendar provider. It queries the instances URI directly, because `Instances.query()` only returns
 * calendars marked as visible: hiding the alarm calendar in a calendar app must not silence its alarms.
 */
class AndroidCalendarRepository(
    private val resolver: ContentResolver,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : CalendarRepository {

    override suspend fun calendars(): List<DeviceCalendar> = withContext(ioDispatcher) {
        val projection = arrayOf(Calendars._ID, Calendars.CALENDAR_DISPLAY_NAME, Calendars.ACCOUNT_NAME)
        query(Calendars.CONTENT_URI, projection, null, null) { cursor ->
            DeviceCalendar(
                id = CalendarId(cursor.getLong(0)),
                name = cursor.getString(1).orEmpty(),
                accountName = cursor.getStringOrNull(2),
            )
        }
    }

    override suspend fun events(calendar: CalendarId, from: Instant, to: Instant): List<CalendarEvent> =
        withContext(ioDispatcher) {
            val uri = Instances.CONTENT_URI.buildUpon().also {
                ContentUris.appendId(it, from.toEpochMilli())
                ContentUris.appendId(it, to.toEpochMilli())
            }.build()
            val projection = arrayOf(
                Instances.EVENT_ID,
                Instances.BEGIN,
                Instances.TITLE,
                Instances.ALL_DAY,
                Instances.STATUS,
            )
            val selection = "${Instances.CALENDAR_ID} = ?"
            query(uri, projection, selection, arrayOf(calendar.value.toString())) { cursor ->
                CalendarEvent(
                    eventId = cursor.getLong(0),
                    instanceBegin = Instant.ofEpochMilli(cursor.getLong(1)),
                    title = cursor.getStringOrNull(2),
                    allDay = cursor.getInt(3) == 1,
                    cancelled = !cursor.isNull(4) && cursor.getInt(4) == Events.STATUS_CANCELED,
                )
            }
        }

    /** A missing cursor means that the read failed. Treating it as empty would remove every planned alarm. */
    private fun <T> query(
        uri: Uri,
        projection: Array<String>,
        selection: String?,
        selectionArgs: Array<String>?,
        map: (Cursor) -> T,
    ): List<T> {
        val cursor = resolver.query(uri, projection, selection, selectionArgs, null)
            ?: throw IllegalStateException("The calendar provider returned no result for $uri")
        return cursor.use {
            buildList {
                while (it.moveToNext()) add(map(it))
            }
        }
    }

    private fun Cursor.getStringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)
}
