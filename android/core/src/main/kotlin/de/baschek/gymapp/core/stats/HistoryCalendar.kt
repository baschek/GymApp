package de.baschek.gymapp.core.stats

import de.baschek.gymapp.core.model.Session
import de.baschek.gymapp.core.model.SessionStatus
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/** Completed sessions of a month by day of month (1..31), in chronological order. */
fun sessionsByDay(sessions: List<Session>, month: YearMonth, zone: ZoneId): Map<Int, List<Session>> =
    sessions.filter { it.status == SessionStatus.COMPLETED }
        .mapNotNull { s -> runCatching { Instant.parse(s.effectiveTime) }.getOrNull()?.let { s to it.atZone(zone).toLocalDate() } }
        .filter { (_, d) -> YearMonth.from(d) == month }
        .sortedBy { it.second }
        .groupBy({ it.second.dayOfMonth }, { it.first })

/** Number of blank cells before the 1st in a Monday-first calendar grid. */
fun leadingBlanks(month: YearMonth): Int = month.atDay(1).dayOfWeek.value - 1
