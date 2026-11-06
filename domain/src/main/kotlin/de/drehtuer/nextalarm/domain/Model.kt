package de.drehtuer.nextalarm.domain

import java.time.Instant

@JvmInline
value class CalendarId(val value: Long)

/** The alarm sound: a copy of the file the user picked, or the sound bundled with the app. */
sealed interface SoundRef {
    data object Fallback : SoundRef
    data class File(val name: String) : SoundRef
}

enum class NewsType { RSS, PODCAST }

data class NewsSource(
    val type: NewsType,
    val url: String,
    val maxMinutes: Int = DEFAULT_NEWS_MINUTES,
) {
    init {
        require(maxMinutes > 0) { "maxMinutes must be positive: $maxMinutes" }
    }

    companion object {
        const val DEFAULT_NEWS_MINUTES = 15
    }
}

/** An event instance of the alarm calendar. Android types are mapped to this in `:data`. */
data class CalendarEvent(
    val eventId: Long,
    val instanceBegin: Instant,
    val title: String?,
    val allDay: Boolean = false,
    val cancelled: Boolean = false,
)

data class Settings(
    val alarmCalendar: CalendarId?,
    val sound: SoundRef,
    val vibrate: Boolean,
    val volumeRampSeconds: Int,
    val maxVolume: Float,
    val ringTimeoutMinutes: Int = DEFAULT_RING_TIMEOUT_MINUTES,
    val snoozeMinutes: Int = DEFAULT_SNOOZE_MINUTES,
    val speakReason: Boolean,
    val news: NewsSource?,
) {
    init {
        require(volumeRampSeconds >= 0) { "volumeRampSeconds must not be negative: $volumeRampSeconds" }
        require(maxVolume in 0f..1f) { "maxVolume must be between 0 and 1: $maxVolume" }
        require(ringTimeoutMinutes > 0) { "ringTimeoutMinutes must be positive: $ringTimeoutMinutes" }
        require(snoozeMinutes > 0) { "snoozeMinutes must be positive: $snoozeMinutes" }
    }

    companion object {
        const val DEFAULT_RING_TIMEOUT_MINUTES = 3
        const val DEFAULT_SNOOZE_MINUTES = 10
    }
}

/** Everything an alarm needs to ring, so that ringing depends on neither the calendar nor the settings. */
data class AlarmSnapshot(
    val reason: String?,
    val sound: SoundRef,
    val vibrate: Boolean,
    val volumeRampSeconds: Int,
    val maxVolume: Float,
    val ringTimeoutMinutes: Int,
    val snoozeMinutes: Int,
    val speakReason: Boolean,
    val news: NewsSource?,
)

/** Builds the snapshot of an alarm with this [title]. A missing or blank title gives an alarm without a reason. */
fun Settings.snapshotFor(title: String?): AlarmSnapshot = AlarmSnapshot(
    reason = title?.takeIf { it.isNotBlank() },
    sound = sound,
    vibrate = vibrate,
    volumeRampSeconds = volumeRampSeconds,
    maxVolume = maxVolume,
    ringTimeoutMinutes = ringTimeoutMinutes,
    snoozeMinutes = snoozeMinutes,
    speakReason = speakReason,
    news = news,
)

data class AlarmInstance(
    val id: Long,
    val key: AlarmKey,
    val triggerAt: Instant,
    val state: AlarmState,
    val snoozeCount: Int,
    val autoSnoozed: Boolean,
    val snapshot: AlarmSnapshot,
)

/** Identifies an alarm across planning runs. It contains the start time, so a moved event is a new alarm. */
@JvmInline
value class AlarmKey(val value: String) {
    /** True if this alarm comes from the calendar and therefore takes part in the plan diff. */
    val isFromCalendar: Boolean
        get() = value.startsWith(EVENT_PREFIX)

    companion object {
        private const val EVENT_PREFIX = "event:"
        private const val TEST_PREFIX = "test:"

        fun forEvent(eventId: Long, instanceBegin: Instant): AlarmKey =
            AlarmKey("$EVENT_PREFIX$eventId:${instanceBegin.toEpochMilli()}")

        fun forTestAlarm(id: Long): AlarmKey = AlarmKey("$TEST_PREFIX$id")
    }
}
