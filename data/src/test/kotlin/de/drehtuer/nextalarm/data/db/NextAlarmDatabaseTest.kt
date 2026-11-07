package de.drehtuer.nextalarm.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import de.drehtuer.nextalarm.data.SNAPSHOT
import de.drehtuer.nextalarm.domain.AlarmKey
import de.drehtuer.nextalarm.domain.AlarmState
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

    /** Builds the database exactly as version 1 left it on a phone, from the schema exported for that version. */
    private fun createVersion1() {
        val path = deviceContext.getDatabasePath(NextAlarmDatabase.NAME)
        path.parentFile?.mkdirs()
        val schema = schema(1)
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
            db.version = 1
        } finally {
            db.close()
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

            assertEquals(9L, database.settings().load()?.alarmCalendarId)
            assertEquals("PLANNED", database.planner().load()?.state)
            assertEquals(5L, database.planner().load()?.generation)

            database.alarms().update(alarm.copy(lateBy = Duration.ofMinutes(7)).toEntity())
            assertEquals(Duration.ofMinutes(7), database.alarms().find(7)?.toDomain()?.lateBy)
        } finally {
            database.close()
        }
    }

    @Test
    fun `the migration goes from version 1 to version 2`() {
        assertEquals(1, MIGRATION_1_2.startVersion)
        assertEquals(2, MIGRATION_1_2.endVersion)
    }

    @Test
    fun `the schema of every version is kept, and only version 2 has the lateBy column`() {
        fun columns(version: Int): List<String> {
            val entities = schema(version).getJSONArray("entities")
            val alarms = (0 until entities.length()).map(entities::getJSONObject).single { it.getString("tableName") == "alarm_instance" }
            val fields = alarms.getJSONArray("fields")
            return (0 until fields.length()).map { fields.getJSONObject(it).getString("columnName") }
        }
        assertEquals(listOf("lateBy"), columns(2) - columns(1).toSet())
        assertEquals(emptyList(), columns(1) - columns(2).toSet())
        assertEquals(2, schema(2).getInt("version"))
    }
}
