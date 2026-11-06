package de.drehtuer.nextalarm.domain

import java.time.Instant
import java.time.ZoneId

/** What the alarm screen (screen 8) shows. The wording is specified in docs/design.md. */
data class AlarmScreenModel(
    val date: String,
    val time: String,
    /** "3 alarms" when several ring together. Empty for one alarm. */
    val countLine: String?,
    /** The titles of the ringing alarms. An entry is empty if the event has no title. */
    val titles: List<String?>,
    /** "Rings again at 06:10". */
    val ringsAgain: String,
)

fun alarmScreenModel(now: Instant, zone: ZoneId, ringing: List<AlarmInstance>): AlarmScreenModel =
    TODO("Step 6.3: $now $zone $ringing")
