package de.drehtuer.nextalarm.domain

import java.time.Duration
import java.time.Instant

/** How long an alarm that has ended stays in the history. */
val ALARM_RETENTION: Duration = Duration.ofDays(7)

/**
 * The alarms that have ended and are older than [ALARM_RETENTION], to be deleted. An alarm that has not ended is never
 * among them, however old it is. An alarm cancelled ahead of its time counts from that time.
 */
fun expiredAlarms(alarms: List<AlarmInstance>, now: Instant): List<AlarmInstance> =
    alarms.filter { it.state.isTerminal && it.triggerAt.plus(ALARM_RETENTION) < now }
