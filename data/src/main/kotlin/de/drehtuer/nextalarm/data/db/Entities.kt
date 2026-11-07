package de.drehtuer.nextalarm.data.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** The columns shared by the settings and the snapshot of every alarm. */
data class AlarmConfigColumns(
    val soundFile: String?,
    val vibrate: Boolean,
    val volumeRampSeconds: Int,
    val maxVolume: Float?,
    val ringTimeoutMinutes: Int,
    val snoozeMinutes: Int,
    val speakReason: Boolean,
    @Embedded(prefix = "news_") val news: NewsColumns?,
)

data class NewsColumns(
    val type: String,
    val url: String,
    val maxMinutes: Int,
)

@Entity(tableName = "alarm_instance", indices = [Index("alarmKey")])
data class AlarmInstanceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long,
    val alarmKey: String,
    val triggerAtMillis: Long,
    val state: String,
    val snoozeCount: Int,
    val autoSnoozed: Boolean,
    val reason: String?,
    @Embedded val config: AlarmConfigColumns,
    /** In milliseconds. Empty unless the alarm rang late. */
    @ColumnInfo(name = "lateBy") val lateByMillis: Long?,
)

/** The only row of its table. */
@Entity(tableName = "settings")
data class SettingsEntity(
    @PrimaryKey val id: Int = SINGLE_ROW_ID,
    val alarmCalendarId: Long?,
    @Embedded val config: AlarmConfigColumns,
)

/** The only row of its table. */
@Entity(tableName = "planner")
data class PlannerEntity(
    @PrimaryKey val id: Int = SINGLE_ROW_ID,
    @ColumnInfo(name = "state") val state: String,
    val generation: Long,
    val consecutiveFailures: Int,
    @Embedded(prefix = "lastRun_") val lastRun: PlanningRunColumns?,
)

data class PlanningRunColumns(
    val startedAtMillis: Long,
    val durationMillis: Long,
    val alarmCount: Int?,
    val failure: String?,
)

internal const val SINGLE_ROW_ID = 0
