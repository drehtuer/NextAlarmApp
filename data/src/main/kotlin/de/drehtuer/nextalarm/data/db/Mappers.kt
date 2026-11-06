package de.drehtuer.nextalarm.data.db

import de.drehtuer.nextalarm.domain.AlarmInstance
import de.drehtuer.nextalarm.domain.AlarmKey
import de.drehtuer.nextalarm.domain.AlarmSnapshot
import de.drehtuer.nextalarm.domain.AlarmState
import de.drehtuer.nextalarm.domain.CalendarId
import de.drehtuer.nextalarm.domain.NewsSource
import de.drehtuer.nextalarm.domain.NewsType
import de.drehtuer.nextalarm.domain.PlannerRecord
import de.drehtuer.nextalarm.domain.PlannerState
import de.drehtuer.nextalarm.domain.Settings
import de.drehtuer.nextalarm.domain.SoundRef
import java.time.Instant

// The stored names are part of the database format: changing one needs a migration.

internal fun AlarmState.toColumn(): String = when (this) {
    AlarmState.Scheduled -> "SCHEDULED"
    AlarmState.Ringing -> "RINGING"
    AlarmState.Snoozed -> "SNOOZED"
    AlarmState.Announcing -> "ANNOUNCING"
    AlarmState.NewsPlaying -> "NEWS_PLAYING"
    AlarmState.Done -> "DONE"
    AlarmState.Unanswered -> "UNANSWERED"
    AlarmState.Cancelled -> "CANCELLED"
    AlarmState.Missed -> "MISSED"
}

internal fun alarmStateFromColumn(value: String): AlarmState = when (value) {
    "SCHEDULED" -> AlarmState.Scheduled
    "RINGING" -> AlarmState.Ringing
    "SNOOZED" -> AlarmState.Snoozed
    "ANNOUNCING" -> AlarmState.Announcing
    "NEWS_PLAYING" -> AlarmState.NewsPlaying
    "DONE" -> AlarmState.Done
    "UNANSWERED" -> AlarmState.Unanswered
    "CANCELLED" -> AlarmState.Cancelled
    "MISSED" -> AlarmState.Missed
    else -> throw IllegalArgumentException("Unknown alarm state: $value")
}

internal fun PlannerState.toColumn(): String = when (this) {
    PlannerState.Unconfigured -> "UNCONFIGURED"
    PlannerState.Planning -> "PLANNING"
    PlannerState.Planned -> "PLANNED"
    PlannerState.FailSafe -> "FAIL_SAFE"
}

internal fun plannerStateFromColumn(value: String): PlannerState = when (value) {
    "UNCONFIGURED" -> PlannerState.Unconfigured
    "PLANNING" -> PlannerState.Planning
    "PLANNED" -> PlannerState.Planned
    "FAIL_SAFE" -> PlannerState.FailSafe
    else -> throw IllegalArgumentException("Unknown planner state: $value")
}

internal fun SoundRef.toColumn(): String? = when (this) {
    SoundRef.SystemDefault -> null
    is SoundRef.File -> name
}

internal fun soundRefFromColumn(value: String?): SoundRef =
    if (value == null) SoundRef.SystemDefault else SoundRef.File(value)

internal fun NewsSource.toColumns() = NewsColumns(type.name, url, maxMinutes)

internal fun NewsColumns.toDomain() = NewsSource(NewsType.valueOf(type), url, maxMinutes)

internal fun AlarmSnapshot.toConfigColumns() = AlarmConfigColumns(
    soundFile = sound.toColumn(),
    vibrate = vibrate,
    volumeRampSeconds = volumeRampSeconds,
    maxVolume = maxVolume,
    ringTimeoutMinutes = ringTimeoutMinutes,
    snoozeMinutes = snoozeMinutes,
    speakReason = speakReason,
    news = news?.toColumns(),
)

internal fun AlarmInstance.toEntity() = AlarmInstanceEntity(
    id = id,
    alarmKey = key.value,
    triggerAtMillis = triggerAt.toEpochMilli(),
    state = state.toColumn(),
    snoozeCount = snoozeCount,
    autoSnoozed = autoSnoozed,
    reason = snapshot.reason,
    config = snapshot.toConfigColumns(),
)

internal fun AlarmInstanceEntity.toDomain() = AlarmInstance(
    id = id,
    key = AlarmKey(alarmKey),
    triggerAt = Instant.ofEpochMilli(triggerAtMillis),
    state = alarmStateFromColumn(state),
    snoozeCount = snoozeCount,
    autoSnoozed = autoSnoozed,
    snapshot = AlarmSnapshot(
        reason = reason,
        sound = soundRefFromColumn(config.soundFile),
        vibrate = config.vibrate,
        volumeRampSeconds = config.volumeRampSeconds,
        maxVolume = config.maxVolume,
        ringTimeoutMinutes = config.ringTimeoutMinutes,
        snoozeMinutes = config.snoozeMinutes,
        speakReason = config.speakReason,
        news = config.news?.toDomain(),
    ),
)

internal fun Settings.toEntity() = SettingsEntity(
    alarmCalendarId = alarmCalendar?.value,
    config = AlarmConfigColumns(
        soundFile = sound.toColumn(),
        vibrate = vibrate,
        volumeRampSeconds = volumeRampSeconds,
        maxVolume = maxVolume,
        ringTimeoutMinutes = ringTimeoutMinutes,
        snoozeMinutes = snoozeMinutes,
        speakReason = speakReason,
        news = news?.toColumns(),
    ),
)

internal fun SettingsEntity.toDomain() = Settings(
    alarmCalendar = alarmCalendarId?.let(::CalendarId),
    sound = soundRefFromColumn(config.soundFile),
    vibrate = config.vibrate,
    volumeRampSeconds = config.volumeRampSeconds,
    maxVolume = config.maxVolume,
    ringTimeoutMinutes = config.ringTimeoutMinutes,
    snoozeMinutes = config.snoozeMinutes,
    speakReason = config.speakReason,
    news = config.news?.toDomain(),
)

internal fun PlannerRecord.toEntity() = PlannerEntity(state = state.toColumn(), generation = generation)

internal fun PlannerEntity.toDomain() = PlannerRecord(plannerStateFromColumn(state), generation)
