package de.drehtuer.nextalarm.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import de.drehtuer.nextalarm.data.NEWS
import de.drehtuer.nextalarm.data.SNAPSHOT
import de.drehtuer.nextalarm.domain.AlarmKey
import de.drehtuer.nextalarm.domain.AlarmState
import de.drehtuer.nextalarm.domain.CalendarId
import de.drehtuer.nextalarm.domain.PlannerState
import de.drehtuer.nextalarm.domain.PlanningFailure
import de.drehtuer.nextalarm.domain.SoundRef
import java.io.File
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NextAlarmDatabaseTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val deviceContext = context.createDeviceProtectedStorageContext()

    private fun schema(version: Int) =
        JSONObject(File("schemas/${NextAlarmDatabase::class.java.name}/$version.json").readText()).getJSONObject("database")

    /** Builds the database exactly as [version] left it on a phone, from the schema exported for that version. */
    private fun createDatabase(version: Int, fill: (SQLiteDatabase) -> Unit) {
        val path = deviceContext.getDatabasePath(NextAlarmDatabase.NAME)
        path.parentFile?.mkdirs()
        val schema = schema(version)
        val db = SQLiteDatabase.openOrCreateDatabase(path, null)
        try {
            val entities = schema.getJSONArray("entities")
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
                val indices = entity.optJSONArray("indices") ?: JSONArray()
                for (indexOfIndex in 0 until indices.length()) {
                    db.execSQL(
                        indices.getJSONObject(indexOfIndex).getString("createSql")
                            .replace("\${TABLE_NAME}", entity.getString("tableName")),
                    )
                }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (index in 0 until setup.length()) db.execSQL(setup.getString(index))
            db.execSQL(
                "INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES (42, ?)",
                arrayOf(schema.getString("identityHash")),
            )
            fill(db)
            db.version = version
        } finally {
            db.close()
        }
    }

    private fun createVersion1() = createDatabase(1) { db ->
        db.execSQL(
            "INSERT INTO alarm_instance (id, alarmKey, triggerAtMillis, state, snoozeCount, autoSnoozed, reason, " +
                "soundFile, vibrate, volumeRampSeconds, maxVolume, ringTimeoutMinutes, snoozeMinutes, speakReason, " +
                "news_type, news_url, news_maxMinutes) " +
                "VALUES (7, 'event:1:1000', 1000, 'SNOOZED', 2, 1, 'Flight', 'sound-1', 0, 12, 0.5, 4, 8, 0, " +
                "'RSS', 'https://example.org/feed.xml', 7)",
        )
        db.execSQL(
            "INSERT INTO settings (id, alarmCalendarId, soundFile, vibrate, volumeRampSeconds, maxVolume, " +
                "ringTimeoutMinutes, snoozeMinutes, speakReason, news_type, news_url, news_maxMinutes) " +
                "VALUES (0, 9, NULL, 1, 0, NULL, 3, 10, 1, NULL, NULL, NULL)",
        )
        db.execSQL(
            "INSERT INTO planner (id, state, generation, consecutiveFailures, lastRun_startedAtMillis, " +
                "lastRun_durationMillis, lastRun_alarmCount, lastRun_failure) " +
                "VALUES (0, 'PLANNED', 5, 0, 2000, 40, 1, NULL)",
        )
    }

    private fun insertVersion2Settings(db: SQLiteDatabase, calendar: Long?) {
        db.execSQL(
            "INSERT INTO settings (id, alarmCalendarId, soundFile, vibrate, volumeRampSeconds, maxVolume, " +
                "ringTimeoutMinutes, snoozeMinutes, speakReason, news_type, news_url, news_maxMinutes) " +
                "VALUES (0, ?, 'sound-1', 1, 12, 0.5, 4, 8, 1, 'RSS', 'https://example.org/feed.xml', 7)",
            arrayOf(calendar),
        )
    }

    private fun insertVersion2Alarm(db: SQLiteDatabase) {
        db.execSQL(
            "INSERT INTO alarm_instance (id, alarmKey, triggerAtMillis, state, snoozeCount, autoSnoozed, reason, " +
                "soundFile, vibrate, volumeRampSeconds, maxVolume, ringTimeoutMinutes, snoozeMinutes, speakReason, " +
                "news_type, news_url, news_maxMinutes, lateBy) " +
                "VALUES (7, 'event:1:1000', 1000, 'SNOOZED', 2, 1, 'Flight', 'sound-1', 0, 12, 0.5, 4, 8, 0, " +
                "'RSS', 'https://example.org/feed.xml', 7, 60000)",
        )
    }

    /** Opens the database as the app does. Room checks the migrated tables against the schema, and fails on a difference. */
    private fun <T> opened(use: suspend (NextAlarmDatabase) -> T): T = runBlocking {
        val database = NextAlarmDatabase.open(context)
        try {
            use(database)
        } finally {
            database.close()
        }
    }

    @Test
    fun `the database is created in device-protected storage`() = runBlocking {
        val database = NextAlarmDatabase.open(context)
        try {
            assertNull(database.settings().load())
        } finally {
            database.close()
        }
        assertTrue(deviceContext.getDatabasePath(NextAlarmDatabase.NAME).isFile)
    }

    @Test
    fun `version 1 data survives the migration to version 2`() = runBlocking {
        createVersion1()
        // Room checks the migrated tables against the current schema when it opens them, and fails on any difference.
        val database = NextAlarmDatabase.open(context)
        try {
            val alarm = database.alarms().find(7)!!.toDomain()
            assertEquals(AlarmKey("event:1:1000"), alarm.key)
            assertEquals(Instant.ofEpochMilli(1000), alarm.triggerAt)
            assertEquals(AlarmState.Snoozed, alarm.state)
            assertEquals(2, alarm.snoozeCount)
            assertEquals(true, alarm.autoSnoozed)
            assertEquals(SNAPSHOT, alarm.snapshot)
            assertNull(alarm.lateBy)

            assertEquals("9", database.settings().load()?.alarmCalendarIds)
            assertEquals("PLANNED", database.planner().load()?.state)
            assertEquals(5L, database.planner().load()?.generation)

            database.alarms().update(alarm.copy(lateBy = Duration.ofMinutes(7)).toEntity())
            assertEquals(Duration.ofMinutes(7), database.alarms().find(7)?.toDomain()?.lateBy)
        } finally {
            database.close()
        }
    }

    @Test
    fun `the calendar chosen in version 2 stays chosen, and so does every other setting`() {
        createDatabase(2) { insertVersion2Settings(it, 9) }
        val settings = opened { it.settings().load()!!.toDomain() }
        assertEquals(setOf(CalendarId(9)), settings.alarmCalendars)
        assertEquals(SoundRef.File("sound-1"), settings.sound)
        assertEquals(true, settings.vibrate)
        assertEquals(12, settings.volumeRampSeconds)
        assertEquals(0.5f, settings.maxVolume)
        assertEquals(4, settings.ringTimeoutMinutes)
        assertEquals(8, settings.snoozeMinutes)
        assertEquals(true, settings.speakReason)
        assertEquals(NEWS, settings.news)
    }

    @Test
    fun `no calendar chosen in version 2 is an empty set in version 3`() {
        createDatabase(2) { insertVersion2Settings(it, null) }
        assertEquals(emptySet(), opened { it.settings().load()!!.toDomain().alarmCalendars })
    }

    @Test
    fun `version 2 without settings stays without settings`() {
        createDatabase(2) {}
        assertNull(opened { it.settings().load() })
    }

    @Test
    fun `the migration to version 3 leaves alarms and the planner alone, so alarms keep their keys`() {
        createDatabase(2) { db ->
            insertVersion2Alarm(db)
            db.execSQL(
                "INSERT INTO planner (id, state, generation, consecutiveFailures, lastRun_startedAtMillis, " +
                    "lastRun_durationMillis, lastRun_alarmCount, lastRun_failure) " +
                    "VALUES (0, 'FAIL_SAFE', 5, 2, 2000, 40, NULL, 'CALENDAR_GONE')",
            )
        }
        opened { database ->
            val alarm = database.alarms().find(7)!!.toDomain()
            assertEquals(AlarmKey("event:1:1000"), alarm.key)
            assertEquals(AlarmState.Snoozed, alarm.state)
            assertEquals(SNAPSHOT, alarm.snapshot)
            assertEquals(Duration.ofMinutes(1), alarm.lateBy)
            val planner = database.planner().load()!!.toDomain()
            assertEquals(PlannerState.FailSafe, planner.state)
            assertEquals(5L, planner.generation)
            assertEquals(2, planner.consecutiveFailures)
            assertEquals(PlanningFailure.CALENDAR_GONE, planner.lastRun?.failure)
        }
    }

    @Test
    fun `after the migration several calendars can be stored, and survive reopening`() {
        createDatabase(2) { insertVersion2Settings(it, 9) }
        val several = setOf(CalendarId(9), CalendarId(3))
        opened { database ->
            val settings = database.settings().load()!!.toDomain()
            database.settings().save(settings.copy(alarmCalendars = several).toEntity())
        }
        assertEquals(several, opened { it.settings().load()!!.toDomain().alarmCalendars })
    }

    @Test
    fun `the migration goes from version 2 to version 3`() {
        assertEquals(2, MIGRATION_2_3.startVersion)
        assertEquals(3, MIGRATION_2_3.endVersion)
    }

    @Test
    fun `the migration goes from version 1 to version 2`() {
        assertEquals(1, MIGRATION_1_2.startVersion)
        assertEquals(2, MIGRATION_1_2.endVersion)
    }

    @Test
    fun `the schema of every version is kept, and version 1 has no lateBy column`() {
        assertEquals(listOf("lateBy"), columns(2, "alarm_instance") - columns(1, "alarm_instance").toSet())
        assertEquals(emptyList(), columns(1, "alarm_instance") - columns(2, "alarm_instance").toSet())
        assertEquals(2, schema(2).getInt("version"))
    }

    @Test
    fun `version 3 only replaces the calendar column of the settings`() {
        assertEquals(listOf("alarmCalendarIds"), columns(3, "settings") - columns(2, "settings").toSet())
        assertEquals(listOf("alarmCalendarId"), columns(2, "settings") - columns(3, "settings").toSet())
        assertEquals(columns(2, "alarm_instance"), columns(3, "alarm_instance"))
        assertEquals(columns(2, "planner"), columns(3, "planner"))
        assertEquals(3, schema(3).getInt("version"))
    }

    private fun columns(version: Int, table: String): List<String> {
        val entities = schema(version).getJSONArray("entities")
        val entity = (0 until entities.length()).map(entities::getJSONObject).single { it.getString("tableName") == table }
        val fields = entity.getJSONArray("fields")
        return (0 until fields.length()).map { fields.getJSONObject(it).getString("columnName") }
    }
}
