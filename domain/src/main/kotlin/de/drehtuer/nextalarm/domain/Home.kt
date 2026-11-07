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

/** What the quick settings tile shows. It is highlighted when [active]. */
data class NextAlarmTile(
    val label: String,
    val subtitle: String,
    val active: Boolean,
)

/** The titles of screen 4 and of the last step of onboarding. */
const val ALARM_CALENDARS_TITLE = "Alarm calendars"
const val CHOOSE_CALENDARS_TITLE = "Choose the alarm calendars"

/** The part about other calendars is a warning: ticking a normal one by accident means an alarm for every meeting. */
const val ALARM_CALENDARS_LEAD = "Every timed event in the ticked calendars rings at its start time. Don't tick a " +
    "calendar you use for anything else: every meeting in it would ring too. A calendar hidden in a calendar app " +
    "still rings. No other calendar is read."
const val CHOOSE_CALENDARS_LEAD = "Every timed event in the ticked calendars will ring. Don't tick a calendar you use " +
    "for anything else: every meeting in it would ring too. Use calendars just for alarms. A calendar hidden in a " +
    "calendar app still rings."

const val CALENDAR_EXPLANATION = "Each timed event in the calendars you choose rings at its start time."
const val PERMISSIONS_LEAD = "NextAlarm only reads the calendars you choose next. Nothing leaves your phone."
const val READ_CALENDARS_DESCRIPTION = "To see the events in your alarm calendars. Only those calendars are read."
const val SETUP_SUMMARY = "Explanation, permissions and calendars"

val NO_CALENDAR_CHOSEN_BANNER = BannerText("No calendar chosen.", "Until you choose one, no alarm rings.")

/** The group and the rows of chosen calendars that are gone. Their names went with them. See [missingCalendars]. */
const val MISSING_CALENDARS_HEADER = "No longer on this phone"
const val MISSING_CALENDAR_ROW = "Missing calendar. Untick it to carry on"

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
    calendars: List<DeviceCalendar>,
    notificationsAllowed: Boolean = true,
): HomeModel {
    val waiting = waitingAlarms(alarms, now)
    val today = now.atZone(zone).toLocalDate()
    return HomeModel(
        hero = waiting.firstOrNull()?.let { homeHero(it, waiting, now, zone, today) },
        days = waiting
            .groupBy { it.triggerAt.atZone(zone).toLocalDate() }
            .map { (date, group) -> HomeDay(dayHeader(date, today), group.map { homeRow(it, zone) }) },
        emptyBody = if (waiting.isEmpty()) emptyBody(now, zone, calendars) else null,
        failSafeBody = if (record.state == PlannerState.FailSafe) failSafeBody(record, zone) else null,
        footer = homeFooter(now, zone, record, calendars),
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

private fun emptyBody(now: Instant, zone: ZoneId, calendars: List<DeviceCalendar>): String {
    val until = now.plus(PLANNING_HORIZON).atZone(zone).format(WEEKDAY_AND_TIME)
    val target = when (calendars.size) {
        0 -> "an alarm calendar"
        1 -> "the ${calendars.single().name} calendar"
        else -> "one of your alarm calendars"
    }
    return "Nothing is planned until $until. To set one, add a timed event to $target in your calendar app."
}

private fun failSafeBody(record: PlannerRecord, zone: ZoneId): String {
    val at = record.lastRun?.let { " at ${it.startedAt.atZone(zone).format(TIME)}" }.orEmpty()
    val stillRing = "The alarms below will still ring, but nothing new is added, and after 48 hours nothing rings."
    return when (record.lastRun?.failure) {
        PlanningFailure.CALENDAR_GONE ->
            "An alarm calendar was no longer on this phone$at. $stillRing Untick it under $ALARM_CALENDARS_TITLE in the settings."
        PlanningFailure.NO_CALENDAR -> "No alarm calendar was chosen$at. $stillRing Choose one in the settings."
        else -> "NextAlarm couldn't read your calendar$at. The alarms below will still ring. Anything you add or change " +
            "in the calendar won't, and if this lasts 48 hours, nothing rings."
    }
}

/** Like "“A” (DAVx⁵), “B” and “C”", or "“A”, “B”, “C” and 2 more" if there are too many to name them all. */
private fun quotedCalendars(calendars: List<DeviceCalendar>): String {
    val named = byName(calendars).take(MAX_NAMED_CALENDARS).map { calendar ->
        "“${calendar.name}”${calendarSource(calendar)?.let { " ($it)" }.orEmpty()}"
    }
    val more = calendars.size - named.size
    return when {
        more > 0 -> "${named.joinToString(", ")} and $more more"
        named.size == 1 -> named.single()
        else -> "${named.dropLast(1).joinToString(", ")} and ${named.last()}"
    }
}

private fun homeFooter(now: Instant, zone: ZoneId, record: PlannerRecord, calendars: List<DeviceCalendar>): String? {
    val source = when (calendars.size) {
        0 -> null
        1 -> "From the calendar ${quotedCalendars(calendars)}."
        else -> "From the calendars ${quotedCalendars(calendars)}."
    }
    val checked = record.lastRun?.let {
        "Checked ${relativeDayAndTime(it.startedAt, now.atZone(zone).toLocalDate(), zone)}."
    }
    return listOfNotNull(source, checked).joinToString(" ").ifEmpty { null }
}

/** "today 06:00", "tomorrow 06:00" or "Sunday 06:00". */
private fun nextAlarmDayAndTime(alarm: AlarmInstance, now: Instant, zone: ZoneId): String {
    val today = now.atZone(zone).toLocalDate()
    val date = alarm.triggerAt.atZone(zone).toLocalDate()
    val day = when (date) {
        today -> "today"
        today.plusDays(1) -> "tomorrow"
        else -> date.format(WEEKDAY)
    }
    return "$day ${formatTime(alarm.triggerAt, zone)}"
}

/** The title and text of the "Next alarm" notification. */
fun nextAlarmText(alarm: AlarmInstance, now: Instant, zone: ZoneId): NextAlarmText = NextAlarmText(
    title = "Next alarm: ${nextAlarmDayAndTime(alarm, now, zone)}",
    text = alarm.snapshot.reason ?: "No title",
)

/** The quick settings tile: the same alarm as the notification, in the same words. */
fun nextAlarmTile(alarms: List<AlarmInstance>, now: Instant, zone: ZoneId): NextAlarmTile {
    val next = nextAlarm(alarms, now)
    return NextAlarmTile(
        label = "Next alarm",
        subtitle = next?.let { nextAlarmDayAndTime(it, now, zone) } ?: "None in 48 hours",
        active = next != null,
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

/** The chosen calendars that are on this phone. */
fun chosenCalendars(selected: Set<CalendarId>, calendars: List<DeviceCalendar>): List<DeviceCalendar> =
    calendars.filter { it.id in selected }

/**
 * The chosen calendars that are not on this phone, which the calendar list has to offer for unticking: as long as one of
 * them stays chosen, every planning run fails. An empty [calendars] says nothing, because that is also how a list without
 * the permission looks, and unticking would then lose a choice that is still good.
 */
fun missingCalendars(selected: Set<CalendarId>, calendars: List<DeviceCalendar>): List<CalendarId> =
    if (calendars.isEmpty()) emptyList() else (selected - calendars.map { it.id }.toSet()).sortedBy { it.value }

/** Sorted by name, so that the same calendars always read the same. */
private fun byName(calendars: List<DeviceCalendar>): List<DeviceCalendar> =
    calendars.sortedWith(compareBy({ it.name.lowercase(Locale.ENGLISH) }, { it.id.value }))

/**
 * The line under "Alarm calendars" in the settings, about the chosen calendars that are on this phone: the calendar and
 * its source, or "2 calendars: NextAlarm, Special cases". Long lists are cut short: "5 calendars: A, B, C and 2 more".
 */
fun calendarSummary(calendars: List<DeviceCalendar>): String {
    val only = calendars.singleOrNull()
    if (only != null) {
        val source = calendarSource(only) ?: only.accountName
        return if (source != null) "${only.name} · $source" else only.name
    }
    if (calendars.isEmpty()) return "None chosen. Nothing rings"
    val names = byName(calendars).take(MAX_NAMED_CALENDARS).joinToString(", ") { it.name }
    val more = calendars.size - MAX_NAMED_CALENDARS
    return "${calendars.size} calendars: $names${if (more > 0) " and $more more" else ""}"
}

private const val MAX_NAMED_CALENDARS = 3
private const val SECONDS_PER_MINUTE = 60L
private const val MINUTES_PER_HOUR = 60L
