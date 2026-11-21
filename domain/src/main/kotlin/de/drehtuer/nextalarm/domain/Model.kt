package de.drehtuer.nextalarm.domain

import java.time.Duration
import java.time.Instant

@JvmInline
value class CalendarId(val value: Long)

/** The alarm sound: the phone's default alarm sound, or a copy of the file the user picked. */
sealed interface SoundRef {
    data object SystemDefault : SoundRef
    data class File(val name: String) : SoundRef {
        /** What the user knows the file by. The name of the copy has it after a unique part: see [forCopy]. */
        val displayName: String
            get() = COPY_NAME.matchEntire(name)?.groupValues?.get(1) ?: name

        companion object {
            private const val MAX_DISPLAY_NAME = 64
            private val COPY_NAME = Regex("sound-[0-9a-fA-F-]{36}-(.+)")
            private val NOT_IN_FILE_NAMES = Regex("[/\\\\\\p{Cntrl}]")

            /**
             * The sound for a new copy of the file [displayName]. The settings keep only this name, so that is where
             * the file's own name is kept too. At most 64 characters of it fit, within the 255 bytes of a file name.
             */
            fun forCopy(uniqueId: String, displayName: String): File =
                File("sound-$uniqueId-${displayName.replace(NOT_IN_FILE_NAMES, "_").take(MAX_DISPLAY_NAME)}")
        }
    }
}

enum class NewsType { RSS, PODCAST }

data class NewsSource(
    val type: NewsType,
    val url: String,
    val maxMinutes: Int = DEFAULT_NEWS_MINUTES,
    /** The feed's own title when it was found ("Der Tag"). Empty for a source stored before titles were. */
    val title: String? = null,
    /** The silence between the last title and the news, so that the two don't run together. */
    val pauseMillis: Int = DEFAULT_PAUSE_MILLIS,
    /** The language tag to read an RSS feed in. Empty means the language the feed declares, else the phone's. */
    val language: String? = null,
) {
    init {
        require(maxMinutes > 0) { "maxMinutes must be positive: $maxMinutes" }
        require(pauseMillis >= 0) { "pauseMillis must not be negative: $pauseMillis" }
    }

    companion object {
        const val DEFAULT_NEWS_MINUTES = 15
        const val DEFAULT_PAUSE_MILLIS = 1000
    }
}

/**
 * The weather forecast that follows the news. [placeName] is the chosen place, which is also where the forecast is for
 * when [useCurrentLocation] is off or the phone doesn't know where it is.
 */
data class WeatherSource(
    val placeName: String,
    val latitude: Double,
    val longitude: Double,
    val useCurrentLocation: Boolean = false,
    /** The language of the sentence, as a language tag. Empty means the phone's. */
    val language: String? = null,
) {
    init {
        require(latitude in -90.0..90.0) { "latitude must be between -90 and 90: $latitude" }
        require(longitude in -180.0..180.0) { "longitude must be between -180 and 180: $longitude" }
    }
}

/** An event instance of an alarm calendar. Android types are mapped to this in `:data`. */
data class CalendarEvent(
    val eventId: Long,
    val instanceBegin: Instant,
    val title: String?,
    val allDay: Boolean = false,
    val cancelled: Boolean = false,
)

data class Settings(
    /** Every timed event in these calendars is an alarm. Empty if none is chosen. */
    val alarmCalendars: Set<CalendarId>,
    val sound: SoundRef,
    val vibrate: Boolean,
    val volumeRampSeconds: Int,
    /**
     * What the phone's alarm stream is set to while the alarm rings, between 0 and 1 of its steps. Without a value, the
     * stream stays as it is. A value of 0 leaves it alone as well and makes the alarm silent. No setting offers that: it
     * is what the silent device tests use.
     */
    val maxVolume: Float?,
    val ringTimeoutMinutes: Int = DEFAULT_RING_TIMEOUT_MINUTES,
    val snoozeMinutes: Int = DEFAULT_SNOOZE_MINUTES,
    val speakReason: Boolean,
    val news: NewsSource?,
    val weather: WeatherSource? = null,
) {
    init {
        require(volumeRampSeconds >= 0) { "volumeRampSeconds must not be negative: $volumeRampSeconds" }
        require(maxVolume == null || maxVolume in 0f..1f) { "maxVolume must be between 0 and 1: $maxVolume" }
        require(ringTimeoutMinutes > 0) { "ringTimeoutMinutes must be positive: $ringTimeoutMinutes" }
        require(snoozeMinutes > 0) { "snoozeMinutes must be positive: $snoozeMinutes" }
    }

    companion object {
        const val DEFAULT_RING_TIMEOUT_MINUTES = 3
        const val DEFAULT_SNOOZE_MINUTES = 10

        /** The settings before the user has changed anything. See docs/architecture.md#data-model. */
        val DEFAULT = Settings(
            alarmCalendars = emptySet(),
            sound = SoundRef.SystemDefault,
            vibrate = false,
            volumeRampSeconds = 0,
            maxVolume = null,
            speakReason = false,
            news = null,
        )
    }
}

/** These settings with [calendar] ticked or unticked. Doing it twice changes nothing. */
fun Settings.withAlarmCalendar(calendar: CalendarId, ticked: Boolean): Settings =
    copy(alarmCalendars = if (ticked) alarmCalendars + calendar else alarmCalendars - calendar)

/** Everything an alarm needs to ring, so that ringing depends on neither the calendar nor the settings. */
data class AlarmSnapshot(
    val reason: String?,
    val sound: SoundRef,
    val vibrate: Boolean,
    val volumeRampSeconds: Int,
    val maxVolume: Float?,
    val ringTimeoutMinutes: Int,
    val snoozeMinutes: Int,
    val speakReason: Boolean,
    val news: NewsSource?,
    val weather: WeatherSource? = null,
) {
    /** The step after the title plays the feed and then the weather, whichever of them is set. */
    val hasNewsStep: Boolean
        get() = news != null || weather != null
}

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
    weather = weather,
)

data class AlarmInstance(
    val id: Long,
    val key: AlarmKey,
    val triggerAt: Instant,
    val state: AlarmState,
    val snoozeCount: Int,
    val autoSnoozed: Boolean,
    val snapshot: AlarmSnapshot,
    /** How late it rang, if it was late enough to be reported (see [LATE_NOTICE_THRESHOLD]). Empty otherwise. */
    val lateBy: Duration? = null,
)

/** Identifies an alarm across planning runs. It contains the start time, so a moved event is a new alarm. */
@JvmInline
value class AlarmKey(val value: String) {
    /** True if this alarm comes from the calendar and therefore takes part in the plan diff. */
    val isFromCalendar: Boolean
        get() = value.startsWith(EVENT_PREFIX)

    /** True for an alarm that exists only in memory, because its stored one could not be read. See [emergencyAlarm]. */
    val isEmergency: Boolean
        get() = value.startsWith(EMERGENCY_PREFIX)

    /**
     * When the event starts. Unlike the trigger time of an alarm, a snooze does not change it. Empty for an alarm that
     * does not come from the calendar.
     */
    val eventStart: Instant?
        get() = if (isFromCalendar) Instant.ofEpochMilli(value.substringAfterLast(':').toLong()) else null

    companion object {
        private const val EVENT_PREFIX = "event:"
        private const val TEST_PREFIX = "test:"
        private const val EMERGENCY_PREFIX = "emergency:"

        fun forEvent(eventId: Long, instanceBegin: Instant): AlarmKey =
            AlarmKey("$EVENT_PREFIX$eventId:${instanceBegin.toEpochMilli()}")

        fun forTestAlarm(id: Long): AlarmKey = AlarmKey("$TEST_PREFIX$id")

        fun forEmergency(id: Long): AlarmKey = AlarmKey("$EMERGENCY_PREFIX$id")
    }
}
