package de.drehtuer.nextalarm.domain

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

// What the screens and notifications say. The wording is specified in docs/design.md.

private val TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
private val TIME_WITH_SECONDS = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ENGLISH)
private val DATE = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH)
private val WEEKDAY = DateTimeFormatter.ofPattern("EEEE", Locale.ENGLISH)
private val WEEKDAY_AND_TIME = DateTimeFormatter.ofPattern("EEEE HH:mm", Locale.ENGLISH)

private const val DAVX_ACCOUNT_TYPE = "bitfire.at.davdroid"
private const val ICSX_ACCOUNT_TYPE = "at.bitfire.icsdroid"
private const val LOCAL_ACCOUNT_TYPE = "LOCAL"

data class HomeHero(
    val label: String,
    val time: String,
    val relativeDay: String,
    /** Empty if the event has no title. */
    val title: String?,
)

data class HomeRow(
    val time: String,
    val title: String?,
    val snoozedUntil: String?,
)

data class HomeDay(
    val header: String,
    val rows: List<HomeRow>,
)

data class HomeModel(
    val hero: HomeHero?,
    val days: List<HomeDay>,
    /** Only when no alarm is waiting. */
    val emptyBody: String?,
    /** Only in `FailSafe`. */
    val failSafeBody: String?,
    val footer: String?,
    /** Only while notifications are off. */
    val notificationsOff: BannerText? = null,
)

data class NextAlarmText(
    val title: String,
    val text: String,
)

data class CalendarGroup(
    val label: String,
    val calendars: List<DeviceCalendar>,
)

/** A time, with seconds only if they are not zero. */
fun formatTime(instant: Instant, zone: ZoneId): String {
    val time = instant.atZone(zone)
    return time.format(if (time.second == 0) TIME else TIME_WITH_SECONDS)
}

/** A time without its seconds, for when something happened rather than when something rings. */
internal fun formatMinute(instant: Instant, zone: ZoneId): String = instant.atZone(zone).format(TIME)

/** "today at 19:58", "yesterday at 19:58" or "Thursday at 19:58". */
internal fun relativeDayAndTime(instant: Instant, today: LocalDate, zone: ZoneId): String {
    val time = instant.atZone(zone)
    val day = when (time.toLocalDate()) {
        today -> "today"
        today.minusDays(1) -> "yesterday"
        else -> time.format(WEEKDAY)
    }
    return "$day at ${time.format(TIME)}"
}

/** The alarms that are still to ring, in order. */
fun waitingAlarms(alarms: List<AlarmInstance>, now: Instant): List<AlarmInstance> = alarms
    .filter { (it.state == AlarmState.Scheduled || it.state == AlarmState.Snoozed) && it.triggerAt > now }
    .sortedWith(compareBy({ it.triggerAt }, { it.id }))

fun nextAlarm(alarms: List<AlarmInstance>, now: Instant): AlarmInstance? = waitingAlarms(alarms, now).firstOrNull()

fun buildHome(
    now: Instant,
    zone: ZoneId,
    alarms: List<AlarmInstance>,
    record: PlannerRecord,
    calendar: DeviceCalendar?,
    notificationsAllowed: Boolean = true,
): HomeModel {
    val waiting = waitingAlarms(alarms, now)
    val today = now.atZone(zone).toLocalDate()
    return HomeModel(
        hero = waiting.firstOrNull()?.let { homeHero(it, waiting, now, zone, today) },
        days = waiting
            .groupBy { it.triggerAt.atZone(zone).toLocalDate() }
            .map { (date, group) -> HomeDay(dayHeader(date, today), group.map { homeRow(it, zone) }) },
        emptyBody = if (waiting.isEmpty()) emptyBody(now, zone, calendar) else null,
        failSafeBody = if (record.state == PlannerState.FailSafe) failSafeBody(record, zone) else null,
        footer = homeFooter(now, zone, record, calendar),
        notificationsOff = notificationsOffBanner(notificationsAllowed),
    )
}

private fun homeHero(
    next: AlarmInstance,
    waiting: List<AlarmInstance>,
    now: Instant,
    zone: ZoneId,
    today: LocalDate,
): HomeHero {
    val minute = next.triggerAt.truncatedTo(ChronoUnit.MINUTES)
    val atSameMinute = waiting.count { it.triggerAt.truncatedTo(ChronoUnit.MINUTES) == minute }
    val snoozed = next.state == AlarmState.Snoozed
    val until = Duration.between(now, next.triggerAt)
    return HomeHero(
        label = when {
            snoozed -> "Snoozed"
            atSameMinute > 1 -> "Next alarm · $atSameMinute alarms at ${next.triggerAt.atZone(zone).format(TIME)}"
            else -> "Next alarm"
        },
        time = formatTime(next.triggerAt, zone),
        relativeDay = if (snoozed) {
            "Rings again in ${formatDuration(until)}"
        } else {
            "${relativeDayPrefix(next.triggerAt.atZone(zone).toLocalDate(), today)} · in ${formatDuration(until)}"
        },
        title = next.snapshot.reason,
    )
}

private fun homeRow(alarm: AlarmInstance, zone: ZoneId) = HomeRow(
    time = formatTime(alarm.triggerAt, zone),
    title = alarm.snapshot.reason,
    snoozedUntil = if (alarm.state == AlarmState.Snoozed) "Snoozed until ${formatTime(alarm.triggerAt, zone)}" else null,
)

private fun dayHeader(date: LocalDate, today: LocalDate): String = when (date) {
    today -> "Today · ${date.format(DATE)}"
    today.plusDays(1) -> "Tomorrow · ${date.format(DATE)}"
    else -> date.format(DATE)
}

private fun relativeDayPrefix(date: LocalDate, today: LocalDate): String = when (date) {
    today -> "Today"
    today.plusDays(1) -> "Tomorrow, ${date.format(WEEKDAY)}"
    else -> date.format(WEEKDAY)
}

/** Rounded up, so that an alarm never seems further away than it is said to be. */
private fun formatDuration(duration: Duration): String {
    val minutes = duration.plusSeconds(SECONDS_PER_MINUTE - 1).toMinutes()
    val hours = minutes / MINUTES_PER_HOUR
    val rest = minutes % MINUTES_PER_HOUR
    return when {
        hours == 0L -> "$rest min"
        rest == 0L -> "$hours h"
        else -> "$hours h $rest min"
    }
}

private fun emptyBody(now: Instant, zone: ZoneId, calendar: DeviceCalendar?): String {
    val until = now.plus(PLANNING_HORIZON).atZone(zone).format(WEEKDAY_AND_TIME)
    val target = if (calendar != null) "the ${calendar.name} calendar" else "your alarm calendar"
    return "Nothing is planned until $until. To set one, add a timed event to $target in your calendar app."
}

private fun failSafeBody(record: PlannerRecord, zone: ZoneId): String {
    val at = record.lastRun?.let { " at ${it.startedAt.atZone(zone).format(TIME)}" }.orEmpty()
    if (record.lastRun?.failure == PlanningFailure.CALENDAR_GONE) {
        return "Your alarm calendar was no longer on this phone$at. The alarms below will still ring, but nothing new is " +
            "added, and after 48 hours nothing rings. Choose another calendar in the settings."
    }
    return "NextAlarm couldn't read your calendar$at. The alarms below will still ring. Anything you add or change " +
        "in the calendar won't, and if this lasts 48 hours, nothing rings."
}

private fun homeFooter(now: Instant, zone: ZoneId, record: PlannerRecord, calendar: DeviceCalendar?): String? {
    val source = calendar?.let { "From the calendar “${it.name}”${calendarSource(it)?.let { name -> " ($name)" }.orEmpty()}." }
    val checked = record.lastRun?.let {
        "Checked ${relativeDayAndTime(it.startedAt, now.atZone(zone).toLocalDate(), zone)}."
    }
    return listOfNotNull(source, checked).joinToString(" ").ifEmpty { null }
}

/** The title and text of the "Next alarm" notification. */
fun nextAlarmText(alarm: AlarmInstance, now: Instant, zone: ZoneId): NextAlarmText {
    val today = now.atZone(zone).toLocalDate()
    val date = alarm.triggerAt.atZone(zone).toLocalDate()
    val day = when (date) {
        today -> "today"
        today.plusDays(1) -> "tomorrow"
        else -> date.format(WEEKDAY)
    }
    return NextAlarmText(
        title = "Next alarm: $day ${formatTime(alarm.triggerAt, zone)}",
        text = alarm.snapshot.reason ?: "No title",
    )
}

/** The name of the app that syncs this calendar, if it is one this app knows. */
private fun calendarSource(calendar: DeviceCalendar): String? = when (calendar.accountType) {
    DAVX_ACCOUNT_TYPE -> "DAVx⁵"
    ICSX_ACCOUNT_TYPE -> "ICSx⁵"
    else -> null
}

private fun calendarGroupLabel(calendar: DeviceCalendar): String {
    val source = calendarSource(calendar)
    return when {
        calendar.accountType == LOCAL_ACCOUNT_TYPE -> "On this phone"
        source != null && calendar.accountName != null -> "$source · ${calendar.accountName}"
        source != null -> source
        else -> calendar.accountName ?: "On this phone"
    }
}

/** The calendars by account, as the calendar list shows them. */
fun groupCalendars(calendars: List<DeviceCalendar>): List<CalendarGroup> = calendars
    .groupBy(::calendarGroupLabel)
    .map { (label, group) -> CalendarGroup(label, group.sortedBy { it.name.lowercase(Locale.ENGLISH) }) }
    .sortedBy { it.label.lowercase(Locale.ENGLISH) }

/** The line under "Alarm calendar" in the settings. */
fun calendarSummary(calendar: DeviceCalendar?): String {
    if (calendar == null) return "None chosen. Nothing rings"
    val source = calendarSource(calendar) ?: calendar.accountName
    return if (source != null) "${calendar.name} · $source" else calendar.name
}

private const val SECONDS_PER_MINUTE = 60L
private const val MINUTES_PER_HOUR = 60L
