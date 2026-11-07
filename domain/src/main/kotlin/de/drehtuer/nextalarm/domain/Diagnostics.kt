package de.drehtuer.nextalarm.domain

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// What screen 7 says, and the log it exports. The wording is specified in docs/design.md.

const val NEXT_CHECK_TEXT = "When the calendar changes, and at least once a day"

/** The title of a test alarm, which is how it shows on the alarm screen, on home and in the history. */
const val TEST_ALARM_TITLE = "Test alarm"
const val TEST_ALARM_NOTE = "“$TEST_ALARM_TITLE” rings in about 5 seconds, with your current settings."
const val LOG_TITLES_NOTE = "The log includes the titles of your alarms, and nothing else personal."
const val LOG_SAVED_NOTE = "Log saved."
const val LOG_FAILED_NOTE = "The log couldn't be saved. Try again."

/** How far ahead a test alarm is set, so that it can be seen coming. See [TEST_ALARM_NOTE]. */
val TEST_ALARM_DELAY: Duration = Duration.ofSeconds(5)

enum class StatusKind { NOT_SET_UP, PLANNING, PLANNED, FAIL_SAFE }

data class StatusCard(
    val kind: StatusKind,
    val title: String,
    val body: String,
)

enum class OutcomeKind { DISMISSED, LATE, UNANSWERED, CANCELLED, MISSED }

data class PastAlarmRow(
    val id: Long,
    val time: String,
    /** Empty if the event has no title. */
    val title: String?,
    val outcome: String,
    val kind: OutcomeKind,
)

data class DiagnosticsModel(
    val status: StatusCard,
    val calendar: String,
    val nextCheck: String,
    val fullScreen: String,
    /** Only while notifications are off. */
    val notificationsOff: BannerText? = null,
    val notifications: String = NOTIFICATIONS_ALLOWED,
    val pastAlarms: List<PastAlarmRow>,
    /** What the exported log is suggested to be called. */
    val logFileName: String,
)

private const val MILLIS_PER_SECOND = 1000.0

private val SHORT_WEEKDAY = DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH)
private val ISO_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH)
private val ISO_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ENGLISH)

/** A test alarm has no event. It goes through the alarm engine like any other, and the plan diff leaves it alone. */
fun testAlarmEntry(now: Instant, settings: Settings): PlanEntry = PlanEntry(
    key = AlarmKey.forTestAlarm(now.toEpochMilli()),
    triggerAt = now.plus(TEST_ALARM_DELAY),
    snapshot = settings.snapshotFor(TEST_ALARM_TITLE),
)

private fun alarmCount(count: Int): String = when (count) {
    0 -> "no alarms"
    1 -> "1 alarm"
    else -> "$count alarms"
}

private fun seconds(duration: Duration): String =
    String.format(Locale.ENGLISH, "%.1f s", duration.toMillis() / MILLIS_PER_SECOND)

private fun failureReason(failure: PlanningFailure): String = when (failure) {
    PlanningFailure.NO_CALENDAR -> "no alarm calendar is chosen"
    PlanningFailure.CALENDAR_GONE -> "the alarm calendar is no longer on this phone"
    PlanningFailure.NO_PERMISSION -> "calendar permission was removed"
    PlanningFailure.READ_FAILED -> "the calendar couldn't be read"
    PlanningFailure.APPLY_FAILED -> "the alarms couldn't be updated"
}

private fun failureFix(failure: PlanningFailure): String = when (failure) {
    PlanningFailure.NO_CALENDAR -> "Choose an alarm calendar in the settings to fix it."
    PlanningFailure.CALENDAR_GONE -> "Choose another alarm calendar in the settings to fix it."
    PlanningFailure.NO_PERMISSION -> "Allow calendar access again to fix it."
    PlanningFailure.READ_FAILED ->
        "NextAlarm keeps trying. If this lasts, check that the calendar is still on this phone."
    PlanningFailure.APPLY_FAILED -> "NextAlarm keeps trying. If this lasts, restart the phone."
}

private fun plannedSentence(waiting: Int): String = when (waiting) {
    0 -> "No alarms are planned, and changes in the calendar are not picked up."
    1 -> "The 1 alarm already planned will still ring, but changes in the calendar are not picked up."
    else -> "The $waiting alarms already planned will still ring, but changes in the calendar are not picked up."
}

private fun plannedBody(run: PlanningRun?, today: LocalDate, zone: ZoneId): String {
    if (run == null) return "No check has finished yet."
    val found = alarmCount(run.alarmCount ?: 0)
    return "Last check ${relativeDayAndTime(run.startedAt, today, zone)} took ${seconds(run.duration)} and found " +
        "$found in the next 48 hours."
}

private fun failSafeBody(run: PlanningRun?, waiting: Int, today: LocalDate, zone: ZoneId): String {
    val failure = run?.failure
    val planned = plannedSentence(waiting)
    if (failure == null) return planned
    val at = relativeDayAndTime(run.startedAt, today, zone)
    val capitalised = at.take(1).uppercase(Locale.ENGLISH) + at.drop(1)
    return "$capitalised: ${failureReason(failure)}. $planned ${failureFix(failure)}"
}

/**
 * The status card. The planner does not store when a running check started, so unlike the design's example this one
 * gives no time for `Planning`.
 */
fun statusCard(record: PlannerRecord, alarms: List<AlarmInstance>, now: Instant, zone: ZoneId): StatusCard {
    val today = now.atZone(zone).toLocalDate()
    return when (record.state) {
        PlannerState.Unconfigured -> StatusCard(StatusKind.NOT_SET_UP, "Not set up yet", "Finish setup to plan alarms.")
        PlannerState.Planning ->
            StatusCard(StatusKind.PLANNING, "Checking the calendar…", "Alarms already planned stay in place.")
        PlannerState.Planned ->
            StatusCard(StatusKind.PLANNED, "Alarms are planned", plannedBody(record.lastRun, today, zone))
        PlannerState.FailSafe -> StatusCard(
            StatusKind.FAIL_SAFE,
            "Last check failed",
            failSafeBody(record.lastRun, waitingAlarms(alarms, now).size, today, zone),
        )
    }
}

/** The line under "Diagnostics & history" in the settings. */
fun diagnosticsSummary(record: PlannerRecord, zone: ZoneId): String {
    val run = record.lastRun
    return when {
        run == null -> "Not checked yet"
        run.failure != null -> "Last check failed"
        else -> "Last check ${formatMinute(run.startedAt, zone)} · all good"
    }
}

/** A chosen calendar that cannot be found is not the same as none chosen: the permission may be gone. */
fun calendarFact(selected: CalendarId?, calendar: DeviceCalendar?, lastFailure: PlanningFailure? = null): String = when {
    selected == null || calendar != null -> calendarSummary(calendar)
    lastFailure == PlanningFailure.CALENDAR_GONE -> "No longer on this phone. Choose another"
    else -> "Can't be read right now"
}

private const val NOTIFICATIONS_ALLOWED = "On"

fun notificationsFact(allowed: Boolean): String =
    if (allowed) NOTIFICATIONS_ALLOWED else "Off: alarms can't show their screen or their buttons"

/** Without notifications a ringing alarm is invisible, and can only be stopped by opening the app. */
fun notificationsOffBanner(allowed: Boolean): BannerText? = if (allowed) {
    null
} else {
    BannerText(
        "Notifications are off",
        "A ringing alarm can't show its screen or its Snooze and Dismiss buttons. Open NextAlarm to stop it. Allow " +
            "notifications in the app settings to fix this.",
    )
}

fun fullScreenFact(allowed: Boolean): String = if (allowed) "Allowed" else "Not allowed: alarms ring as a notification"

private fun snoozes(count: Int) = if (count == 1) "1 snooze" else "$count snoozes"

private fun doneOutcome(alarm: AlarmInstance): Pair<String, OutcomeKind> {
    val late = alarm.lateBy
    return when {
        late != null -> "Rang ${late.toMinutes()} min late (phone was off)" to OutcomeKind.LATE
        alarm.snoozeCount > 0 -> "Dismissed after ${snoozes(alarm.snoozeCount)}" to OutcomeKind.DISMISSED
        else -> "Dismissed" to OutcomeKind.DISMISSED
    }
}

/** Empty for an alarm that has not ended. */
private fun outcome(alarm: AlarmInstance): Pair<String, OutcomeKind>? = when (alarm.state) {
    AlarmState.Done -> doneOutcome(alarm)
    AlarmState.Unanswered -> "Not answered (rang twice)" to OutcomeKind.UNANSWERED
    AlarmState.Cancelled -> "Event moved or deleted before it rang" to OutcomeKind.CANCELLED
    AlarmState.Missed -> "Missed: phone was off" to OutcomeKind.MISSED
    AlarmState.Scheduled, AlarmState.Ringing, AlarmState.Snoozed, AlarmState.Announcing, AlarmState.NewsPlaying -> null
}

/** A snooze moves the trigger time, but the user knows the alarm by the time of the event. */
private fun displayedTime(alarm: AlarmInstance): Instant = alarm.key.eventStart ?: alarm.triggerAt

/** The alarms that have ended, newest first, each with what became of it. */
private fun outcomes(alarms: List<AlarmInstance>): List<Pair<AlarmInstance, Pair<String, OutcomeKind>>> = alarms
    .mapNotNull { alarm -> outcome(alarm)?.let { alarm to it } }
    .sortedWith(compareByDescending<Pair<AlarmInstance, *>> { displayedTime(it.first) }.thenByDescending { it.first.id })

fun pastAlarmRows(alarms: List<AlarmInstance>, zone: ZoneId): List<PastAlarmRow> =
    outcomes(alarms).map { (alarm, outcome) ->
        val time = displayedTime(alarm)
        PastAlarmRow(
            id = alarm.id,
            time = "${time.atZone(zone).format(SHORT_WEEKDAY)} ${formatTime(time, zone)}",
            title = alarm.snapshot.reason,
            outcome = outcome.first,
            kind = outcome.second,
        )
    }

fun logFileName(now: Instant, zone: ZoneId): String = "nextalarm-log-${now.atZone(zone).format(ISO_DATE)}.txt"

fun buildDiagnostics(
    record: PlannerRecord,
    alarms: List<AlarmInstance>,
    now: Instant,
    zone: ZoneId,
    calendar: String,
    fullScreenAllowed: Boolean,
    notificationsAllowed: Boolean = true,
): DiagnosticsModel = DiagnosticsModel(
    status = statusCard(record, alarms, now, zone),
    calendar = calendar,
    nextCheck = NEXT_CHECK_TEXT,
    fullScreen = fullScreenFact(fullScreenAllowed),
    notificationsOff = notificationsOffBanner(notificationsAllowed),
    notifications = notificationsFact(notificationsAllowed),
    pastAlarms = pastAlarmRows(alarms, zone),
    logFileName = logFileName(now, zone),
)

private fun lastRunLines(run: PlanningRun?, zone: ZoneId): List<String> {
    if (run == null) return listOf("Last check: none yet")
    val failure = run.failure
    val result = if (failure != null) "Failed: ${failureReason(failure)}" else "Found ${alarmCount(run.alarmCount ?: 0)}"
    return listOf(
        "Last check: ${run.startedAt.atZone(zone).format(ISO_DATE_TIME)}",
        "Took: ${seconds(run.duration)}",
        "Result: $result",
    )
}

/**
 * The plain text of "Export log": what screen 7 shows of the planner and the past alarms. Calendar and account names
 * stay out on purpose, because a log gets passed on. The only personal data are the event titles, and it says so.
 */
fun buildLogExport(now: Instant, zone: ZoneId, record: PlannerRecord, alarms: List<AlarmInstance>): String {
    val past = outcomes(alarms).map { (alarm, outcome) ->
        val title = alarm.snapshot.reason?.lines()?.joinToString(" ") ?: "No title"
        "${displayedTime(alarm).atZone(zone).format(ISO_DATE_TIME)} | ${outcome.first} | $title"
    }
    val lines = listOf(
        "NextAlarm log",
        "Exported: ${now.atZone(zone).format(ISO_DATE_TIME)} ($zone)",
        LOG_TITLES_NOTE,
        "",
        "Planner state: ${record.state}",
        "Failures in a row: ${record.consecutiveFailures}",
    ) + lastRunLines(record.lastRun, zone) + listOf("", "Past alarms:") + past.ifEmpty { listOf("None") }
    return lines.joinToString("\n", postfix = "\n")
}
