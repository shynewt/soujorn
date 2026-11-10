package app.sojourn.ui.components

import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

object Format {
    // Built on each use, so a language change while the app runs is picked up.
    private val dayMonth get() = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
    private val dayMonthYear get() = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())
    private val weekdayDate get() = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.getDefault())
    private val monthYear get() = DateTimeFormatter.ofPattern("LLLL yyyy", Locale.getDefault())
    private val medium get() = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

    fun short(date: LocalDate, today: LocalDate = LocalDate.now()): String =
        if (date.year == today.year) date.format(dayMonth) else date.format(dayMonthYear)

    fun long(date: LocalDate): String = date.format(weekdayDate)

    fun medium(date: LocalDate): String = date.format(medium)

    fun month(date: LocalDate): String = date.format(monthYear).replaceFirstChar { it.titlecase(Locale.getDefault()) }

    /** "3 – 9 Mar", "28 Feb – 4 Mar", or with years when they differ from today's. */
    fun range(start: LocalDate, end: LocalDate, today: LocalDate = LocalDate.now()): String = when {
        start == end -> short(start, today)
        start.year != end.year -> "${start.format(dayMonthYear)} – ${end.format(dayMonthYear)}"
        start.month == end.month -> "${start.dayOfMonth} – ${short(end, today)}"
        else -> "${start.format(dayMonth)} – ${short(end, today)}"
    }

    fun days(n: Int) = if (n == 1) "1 day" else "$n days"

    fun ago(millis: Long, now: Long = System.currentTimeMillis()): String {
        val minutes = (now - millis) / 60_000
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "$minutes min ago"
            minutes < 60 * 24 -> "${minutes / 60} h ago"
            else -> "${minutes / (60 * 24)} d ago"
        }
    }

    /** Material date pickers speak UTC midnight milliseconds. */
    fun toPickerMillis(date: LocalDate): Long = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    fun fromPickerMillis(millis: Long): LocalDate = java.time.Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
}
