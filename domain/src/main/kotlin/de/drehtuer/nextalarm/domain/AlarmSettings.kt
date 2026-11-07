package de.drehtuer.nextalarm.domain

import java.net.URI
import java.net.URISyntaxException
import kotlin.math.roundToInt

// What screen 5 says and offers. The wording is specified in docs/design.md.

/** The phone's alarm stream volume, in its own steps. */
data class AlarmVolume(
    val current: Int,
    val max: Int,
)

data class BannerText(
    val title: String,
    val body: String,
)

/** Why a picked sound file was not taken. */
enum class SoundRejection { UNSUPPORTED_TYPE, UNREADABLE, NOT_PLAYABLE }

data class PickerOption<T>(
    val value: T,
    val label: String,
)

/** A setting with a fixed choice of values. [label] is the current one. */
data class ValuePicker<T>(
    val label: String,
    val options: List<PickerOption<T>>,
)

data class AlarmSettingsModel(
    /** Only when the phone's alarm volume is low. */
    val lowVolume: BannerText?,
    val soundName: String,
    /** True if a file is set, which can be given up for the phone's default. */
    val usesFile: Boolean,
    /** Why the last picked file was not taken, if it was not. */
    val soundRejection: String?,
    val volumeRamp: ValuePicker<Int>,
    val maxVolume: ValuePicker<Float?>,
    val vibrate: Boolean,
    val ringFor: ValuePicker<Int>,
    val snoozeLength: ValuePicker<Int>,
    val speakTitle: Boolean,
    val news: String,
)

private const val LOW_ALARM_VOLUME_STEPS = 2
private const val PERCENT = 100
private const val SECONDS_PER_MINUTE = 60
private const val TENTHS = 10f
private const val WWW_PREFIX = "www."

private val RAMP_SECONDS = listOf(0, 15, 30, 60, 120, 300)
private val RING_MINUTES = listOf(1, 2, 3, 5, 10, 15)
private val SNOOZE_MINUTES = listOf(1, 5, 10, 15, 20, 30)

/** No option is silent: an alarm nobody can hear is worse than none. 100% is the same as not setting a value. */
private val MAX_VOLUME_TENTHS = 9 downTo 1

/** The threshold of the low-volume banner. */
fun isAlarmVolumeLow(volume: AlarmVolume): Boolean = volume.current <= LOW_ALARM_VOLUME_STEPS

/** The maximum volume only lowers the player's own gain, so the banner cannot offer it as a way to be louder. */
fun lowVolumeBanner(volume: AlarmVolume): BannerText? = if (isAlarmVolumeLow(volume)) {
    BannerText(
        "Your phone's alarm volume is low (${volume.current} of ${volume.max}).",
        "Alarms may be too quiet to wake you. Turn it up in the phone's sound settings.",
    )
} else {
    null
}

fun soundRejectionText(reason: SoundRejection, fileName: String): String = when (reason) {
    SoundRejection.UNSUPPORTED_TYPE -> "“$fileName” isn't a sound file. Choose an MP3, WAV, OGG or M4A file."
    SoundRejection.UNREADABLE -> "“$fileName” can't be opened. It may have been moved or deleted. Choose it again."
    SoundRejection.NOT_PLAYABLE -> "“$fileName” has no sound NextAlarm can play. Choose another file."
}

/** The value of the "Alarm sound" row. [defaultName] is empty if the phone does not say what its default is called. */
fun soundName(sound: SoundRef, defaultName: String?): String = when (sound) {
    SoundRef.SystemDefault -> if (defaultName != null) "Phone default ($defaultName)" else "Phone default"
    is SoundRef.File -> sound.displayName
}

private fun rampLabel(seconds: Int): String {
    val minutes = seconds / SECONDS_PER_MINUTE
    val rest = seconds % SECONDS_PER_MINUTE
    return when {
        seconds == 0 -> "None"
        minutes == 0 -> "$rest s"
        rest == 0 -> "$minutes min"
        else -> "$minutes min $rest s"
    }
}

internal fun minutesLabel(minutes: Int) = "$minutes min"

private fun volumeLabel(volume: Float?): String =
    if (volume != null) "${(volume * PERCENT).roundToInt()}%" else "Not set"

private fun <T> picker(current: T, values: List<T>, label: (T) -> String) =
    ValuePicker(label(current), values.map { PickerOption(it, label(it)) })

/** The value of the "News" row. What a feed is called is only known once it is read, so this names its address. */
fun newsLabel(news: NewsSource?): String = when (news?.type) {
    null -> "Off"
    NewsType.RSS -> "RSS · ${feedHost(news.url)}"
    NewsType.PODCAST -> "Podcast · ${feedHost(news.url)}"
}

private fun feedHost(url: String): String {
    val host = try {
        URI(url).host
    } catch (_: URISyntaxException) {
        null
    }
    return host?.removePrefix(WWW_PREFIX) ?: url
}

fun buildAlarmSettings(
    settings: Settings,
    defaultSoundName: String?,
    volume: AlarmVolume?,
    soundRejection: String?,
): AlarmSettingsModel = AlarmSettingsModel(
    lowVolume = volume?.let(::lowVolumeBanner),
    soundName = soundName(settings.sound, defaultSoundName),
    usesFile = settings.sound is SoundRef.File,
    soundRejection = soundRejection,
    volumeRamp = picker(settings.volumeRampSeconds, RAMP_SECONDS, ::rampLabel),
    maxVolume = picker(settings.maxVolume, listOf<Float?>(null) + MAX_VOLUME_TENTHS.map { it / TENTHS }, ::volumeLabel),
    vibrate = settings.vibrate,
    ringFor = picker(settings.ringTimeoutMinutes, RING_MINUTES, ::minutesLabel),
    snoozeLength = picker(settings.snoozeMinutes, SNOOZE_MINUTES, ::minutesLabel),
    speakTitle = settings.speakReason,
    news = newsLabel(settings.news),
)
