package de.drehtuer.nextalarm.domain

import java.time.Duration
import java.time.ZoneId

// What the alarm notifications say. The wording is specified in docs/design.md.

data class NotificationText(
    val title: String,
    val text: String,
)

/** The "Fail-safe warning" notification. */
fun failSafeWarningText(failure: PlanningFailure?): NotificationText = NotificationText(
    "New alarms aren't being added",
    when (failure) {
        PlanningFailure.CALENDAR_GONE ->
            "An alarm calendar is no longer on this phone. Alarms already planned will still ring. Untick it under " +
                "$ALARM_CALENDARS_TITLE."
        PlanningFailure.NO_CALENDAR ->
            "No alarm calendar is chosen. Alarms already planned will still ring. Choose one in the settings."
        else -> "NextAlarm can't read your calendar. Alarms already planned will still ring."
    },
)

private const val NO_TITLE = "No title"
private const val NO_TITLE_ALARM = "Alarm (event has no title)"

fun ringingText(alarm: AlarmInstance): NotificationText =
    NotificationText(alarm.snapshot.reason ?: NO_TITLE_ALARM, "Ringing now")

/** For a notification that cannot be about any alarm, because Android demands one before there is one to show. */
val RINGING_PLACEHOLDER_TEXT = NotificationText("Alarm", "Ringing now")

/** Android refused the service. A sound without it is likely silenced, with no error to go by. */
val FOREGROUND_REFUSED_TEXT = NotificationText(
    "This alarm may be silent",
    "Android didn't let NextAlarm run in the background, so its sound may not be heard. Tap to open the alarm.",
)

val NO_SOUND_TEXT = NotificationText(
    "An alarm couldn't play a sound",
    "NextAlarm tried all its sounds and none would play. Tap to open the alarm.",
)

fun snoozedText(alarm: AlarmInstance, zone: ZoneId): NotificationText = NotificationText(
    "Snoozed until ${formatTime(alarm.triggerAt, zone)}",
    alarm.snapshot.reason ?: NO_TITLE,
)

/** The trigger time is when it was due, which after a snooze is when the snooze ended. */
fun lateText(alarm: AlarmInstance, zone: ZoneId): NotificationText {
    val at = formatTime(alarm.triggerAt, zone)
    return NotificationText(
        "An alarm rang ${(alarm.lateBy ?: Duration.ZERO).toMinutes()} min late",
        if (alarm.snapshot.reason != null) {
            "“${alarm.snapshot.reason}” was due at $at, but the phone was off."
        } else {
            "An alarm was due at $at, but the phone was off."
        },
    )
}

fun missedText(alarm: AlarmInstance, zone: ZoneId): NotificationText = NotificationText(
    "An alarm didn't ring",
    "${subject(alarm)} at ${originalTime(alarm, zone)} was missed because the phone was off.",
)

fun unansweredText(alarm: AlarmInstance, zone: ZoneId): NotificationText = NotificationText(
    "An alarm wasn't answered",
    "${subject(alarm)} at ${originalTime(alarm, zone)} rang twice with no response. It won't ring again.",
)

/** The ringer stopped an alarm on its own, because it could not be ended the normal way. */
fun ringLimitText(alarm: AlarmInstance, zone: ZoneId): NotificationText = NotificationText(
    "An alarm was stopped",
    "${subject(alarm)} at ${originalTime(alarm, zone)} rang past its time and NextAlarm couldn't end it the normal way.",
)

private fun subject(alarm: AlarmInstance): String = alarm.snapshot.reason?.let { "“$it”" } ?: "An alarm"

/** A snooze moves the trigger time, but the user knows the alarm by the time of the event. */
private fun originalTime(alarm: AlarmInstance, zone: ZoneId): String =
    formatTime(alarm.key.eventStart ?: alarm.triggerAt, zone)
