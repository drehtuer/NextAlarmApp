package de.drehtuer.nextalarm.domain

import java.time.Duration
import java.time.Instant
import java.time.ZoneId

private val FIRST_RETRY: Duration = Duration.ofMinutes(1)
private val LONGEST_RETRY: Duration = Duration.ofHours(1)

/** How long to wait after this many failed runs in a row: one minute, doubling, up to one hour. */
fun retryDelay(consecutiveFailures: Int): Duration {
    val doublings = (consecutiveFailures - 1).coerceIn(0, MAX_DOUBLINGS)
    return FIRST_RETRY.multipliedBy(1L shl doublings).coerceAtMost(LONGEST_RETRY)
}

/** Enough to reach the longest retry, without overflowing the shift above. */
private const val MAX_DOUBLINGS = 6

/** The daily re-plan runs five minutes after the next local midnight, and moves the 48 h window forward. */
fun delayUntilAfterMidnight(now: Instant, zone: ZoneId): Duration {
    val runAt = now.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).plusMinutes(5)
    return Duration.between(now, runAt.toInstant())
}
