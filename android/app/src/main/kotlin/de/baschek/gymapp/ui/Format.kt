package de.baschek.gymapp.ui

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** German number format without trailing zeros: 57.5 -> "57,5", 60.0 -> "60". */
fun fmtNum(value: Double): String =
    BigDecimal(value).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString().replace('.', ',')

fun fmtKg(value: Double): String = "${fmtNum(value)} kg"

/** Parses "57,5" or "57.5". */
fun parseNum(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()

private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
private val dayFormat = DateTimeFormatter.ofPattern("dd.MM.")
private val dayLongFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy")
private val weekdayFormat = DateTimeFormatter.ofPattern("EEE, dd.MM.", Locale.GERMANY)
private val monthFormat = DateTimeFormatter.ofPattern("LLLL yyyy", Locale.GERMANY)

fun parseInstant(text: String?): Instant? = text?.let { runCatching { Instant.parse(it) }.getOrNull() }

/** "heute 17:42", "gestern 09:10" or "28.10. 17:42". */
fun fmtWhen(instant: Instant, zone: ZoneId = ZoneId.systemDefault(), today: LocalDate = LocalDate.now(zone)): String {
    val dt = instant.atZone(zone)
    val time = timeFormat.format(dt)
    return when (dt.toLocalDate()) {
        today -> "heute $time"
        today.minusDays(1) -> "gestern $time"
        else -> "${dayFormat.format(dt)} $time"
    }
}

fun fmtDay(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): String = dayFormat.format(instant.atZone(zone))
fun fmtDayLong(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): String = dayLongFormat.format(instant.atZone(zone))
fun fmtWeekday(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): String = weekdayFormat.format(instant.atZone(zone))
fun fmtMonth(month: java.time.YearMonth): String = monthFormat.format(month).replaceFirstChar { it.uppercase() }

/** "zuletzt vor 5 Tagen", "zuletzt heute", "noch nie gemacht". */
fun fmtDaysAgo(days: Long?): String = when (days) {
    null -> "noch nie gemacht"
    0L -> "zuletzt heute"
    1L -> "zuletzt gestern"
    else -> "zuletzt vor $days Tagen"
}

fun fmtMinutes(minutes: Int): String = if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"

/** Integer with a German thousands separator: 6420 -> "6.420". */
fun fmtInt(value: Long): String = "%,d".format(Locale.GERMANY, value)

/** "aus 4 Trainings" (learned duration estimate). */
fun fmtLearnedFrom(count: Int): String = if (count == 1) "aus 1 Training" else "aus $count Trainings"
