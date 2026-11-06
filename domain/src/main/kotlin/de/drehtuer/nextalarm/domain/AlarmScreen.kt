package de.drehtuer.nextalarm.domain

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val ALARM_DATE = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH)

/** About three lines at the size of a single title on a 411 dp wide screen. */
private const val LONG_TITLE_LENGTH = 60

/** What the alarm screen (screen 8) shows. The wording is specified in docs/design.md. */
data class AlarmScreenModel(
    val date: String,
    val time: String,
    /** "3 alarms" when several ring together. Empty for one alarm. */
    val countLine: String?,
    /** The titles of the ringing alarms. An entry is empty if the event has no title. */
    val titles: List<String?>,
    /** "Rings again at 06:10". Blank if nothing is ringing, because then nothing would ring again. */
    val ringsAgain: String,
)

/**
 * The alarm screen at [now]. Each alarm snoozes by the length in its own snapshot, so if these differ, "Rings again"
 * gives the earliest of them: that is when the phone next rings.
 */
fun alarmScreenModel(now: Instant, zone: ZoneId, ringing: List<AlarmInstance>): AlarmScreenModel = AlarmScreenModel(
    date = now.atZone(zone).format(ALARM_DATE),
    time = formatTime(now, zone),
    countLine = if (ringing.size > 1) "${ringing.size} alarms" else null,
    titles = ringing.sortedWith(compareBy({ it.triggerAt }, { it.id })).map { it.snapshot.reason },
    ringsAgain = ringing.minOfOrNull { it.snapshot.snoozeMinutes }
        ?.let { minutes -> "Rings again at ${formatTime(now.plus(Duration.ofMinutes(minutes.toLong())), zone)}" }
        .orEmpty(),
)

/** A long title is shown smaller, so that more of it is visible without scrolling. */
fun isLongAlarmTitle(title: String): Boolean = title.length > LONG_TITLE_LENGTH
