package de.drehtuer.nextalarm.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Dao
interface AlarmDao {
    @Query("SELECT * FROM alarm_instance ORDER BY triggerAtMillis, id")
    suspend fun all(): List<AlarmInstanceEntity>

    @Query("SELECT * FROM alarm_instance WHERE id = :id")
    suspend fun find(id: Long): AlarmInstanceEntity?

    @Insert
    suspend fun insert(entity: AlarmInstanceEntity): Long

    @Update
    suspend fun update(entity: AlarmInstanceEntity)

    @Query("DELETE FROM alarm_instance WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface SettingsDao {
    @Query("SELECT * FROM settings WHERE id = $SINGLE_ROW_ID")
    suspend fun load(): SettingsEntity?

    @Upsert
    suspend fun save(entity: SettingsEntity)
}

@Dao
interface PlannerDao {
    @Query("SELECT * FROM planner WHERE id = $SINGLE_ROW_ID")
    suspend fun load(): PlannerEntity?

    @Upsert
    suspend fun save(entity: PlannerEntity)
}

/** Version 2 records how late an alarm rang. Alarms from before that are not known to have been late. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE alarm_instance ADD COLUMN lateBy INTEGER")
    }
}

/**
 * Version 3 stores a set of alarm calendars instead of one, and whichever calendar was chosen stays chosen. SQLite can only
 * change a column by building the table again, and the new table has to match the schema exactly: see 3.json.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE `settings_new` (`id` INTEGER NOT NULL, `alarmCalendarIds` TEXT NOT NULL, `soundFile` TEXT, " +
                "`vibrate` INTEGER NOT NULL, `volumeRampSeconds` INTEGER NOT NULL, `maxVolume` REAL, " +
                "`ringTimeoutMinutes` INTEGER NOT NULL, `snoozeMinutes` INTEGER NOT NULL, " +
                "`speakReason` INTEGER NOT NULL, `news_type` TEXT, `news_url` TEXT, `news_maxMinutes` INTEGER, " +
                "PRIMARY KEY(`id`))",
        )
        db.execSQL(
            "INSERT INTO `settings_new` (`id`, `alarmCalendarIds`, `soundFile`, `vibrate`, `volumeRampSeconds`, " +
                "`maxVolume`, `ringTimeoutMinutes`, `snoozeMinutes`, `speakReason`, `news_type`, `news_url`, " +
                "`news_maxMinutes`) " +
                "SELECT `id`, COALESCE(CAST(`alarmCalendarId` AS TEXT), ''), `soundFile`, `vibrate`, " +
                "`volumeRampSeconds`, `maxVolume`, `ringTimeoutMinutes`, `snoozeMinutes`, `speakReason`, `news_type`, " +
                "`news_url`, `news_maxMinutes` FROM `settings`",
        )
        db.execSQL("DROP TABLE `settings`")
        db.execSQL("ALTER TABLE `settings_new` RENAME TO `settings`")
    }
}

/**
 * All app data. The database lives in device-protected storage, so that alarms can ring before the first unlock
 * after a reboot. See docs/architecture.md#direct-boot.
 */
@Database(
    entities = [AlarmInstanceEntity::class, SettingsEntity::class, PlannerEntity::class],
    version = 3,
)
abstract class NextAlarmDatabase : RoomDatabase() {
    abstract fun alarms(): AlarmDao
    abstract fun settings(): SettingsDao
    abstract fun planner(): PlannerDao

    companion object {
        const val NAME = "nextalarm.db"

        fun open(context: Context): NextAlarmDatabase = Room
            .databaseBuilder(context.createDeviceProtectedStorageContext(), NextAlarmDatabase::class.java, NAME)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .build()
    }
}
