package app.sojourn.domain

import app.sojourn.data.Countries
import app.sojourn.data.CountrySet
import app.sojourn.data.db.Presence
import app.sojourn.data.db.sources
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** One row of Form 2555 Part III, line 18: travel during the 12-month period. */
data class TravelRow(val country: String, val arrived: LocalDate, val left: LocalDate, val fullDays: Int)

data class FeieReport(
    val taxYear: Int,
    /** The 12-month period with the most full days abroad that overlaps the tax year. */
    val period: DateRange,
    val fullDaysAbroad: Int,
    val untrackedDays: Int,
    val rows: List<TravelRow>,
) {
    val qualifies: Boolean get() = fullDaysAbroad >= REQUIRED_DAYS

    /** Days of the tax year inside the period, which prorates the exclusion. */
    val qualifyingDaysInYear: Int
        get() {
            val from = maxOf(period.start, LocalDate.of(taxYear, 1, 1))
            val to = minOf(period.end, LocalDate.of(taxYear, 12, 31))
            return if (from > to) 0 else ChronoUnit.DAYS.between(from, to).toInt() + 1
        }

    companion object {
        const val REQUIRED_DAYS = 330
    }
}

object Reports {
    private val usDate = DateTimeFormatter.ofPattern("MM/dd/yyyy")
    private val abroad = CountrySet(setOf("US"), except = true)

    /**
     * Finds the 12-month period for the physical presence test. A full day abroad is a day
     * with no time spent in the US, which is how a travel day through the US is excluded.
     */
    fun feie(book: DayBook, taxYear: Int): FeieReport {
        val yearStart = LocalDate.of(taxYear, 1, 1)
        val yearEnd = LocalDate.of(taxYear, 12, 31)
        val searchFrom = yearStart.minusYears(1).plusDays(1)
        val total = ChronoUnit.DAYS.between(searchFrom, yearEnd.plusYears(1)).toInt() + 1
        val prefix = IntArray(total + 1)
        for (i in 0 until total) {
            prefix[i + 1] = prefix[i] + if (book.counts(searchFrom.plusDays(i.toLong()), abroad, withPlans = false)) 1 else 0
        }
        var best = DateRange(yearStart, yearEnd)
        var bestDays = -1
        var bestOverlap = -1
        var start = searchFrom
        while (start <= yearEnd) {
            val end = start.plusYears(1).minusDays(1)
            val from = ChronoUnit.DAYS.between(searchFrom, start).toInt()
            val to = ChronoUnit.DAYS.between(searchFrom, end).toInt()
            val days = prefix[to + 1] - prefix[from]
            val overlap = ChronoUnit.DAYS.between(maxOf(start, yearStart), minOf(end, yearEnd)).toInt()
            // Once a period qualifies, more abroad days don't help, but covering more of the tax
            // year prorates a larger exclusion. Until one qualifies, show the closest miss.
            val qualifies = days >= FeieReport.REQUIRED_DAYS
            val bestQualifies = bestDays >= FeieReport.REQUIRED_DAYS
            val better = when {
                qualifies != bestQualifies -> qualifies
                qualifies -> overlap > bestOverlap || (overlap == bestOverlap && days > bestDays)
                else -> days > bestDays || (days == bestDays && overlap > bestOverlap)
            }
            if (better) {
                best = DateRange(start, end)
                bestDays = days
                bestOverlap = overlap
            }
            start = start.plusDays(1)
        }
        val rows = book.stays(best.start, best.end).filter { it.kind != DayKind.PLANNED }.map { stay ->
            val full = book.between(stay.start, stay.end).count { it.countries == listOf(stay.country) }
            TravelRow(stay.country, stay.start, stay.end, full)
        }
        val untracked = book.gaps(best.start, best.end).sumOf { it.days }
        return FeieReport(taxYear, best, bestDays.coerceAtLeast(0), untracked, rows)
    }

    fun feieCsv(report: FeieReport): String = buildString {
        appendLine("Country,Date arrived,Date left,Full days present,Days in U.S. on business,Income earned in U.S. on business")
        report.rows.forEach {
            appendLine("${Csv.field(Countries.name(it.country))},${it.arrived.format(usDate)},${it.left.format(usDate)},${it.fullDays},,")
        }
    }

    fun feieText(report: FeieReport): String = buildString {
        appendLine("Form 2555 – physical presence test, tax year ${report.taxYear}")
        appendLine("12-month period: ${report.period.start.format(usDate)} to ${report.period.end.format(usDate)}")
        appendLine("Full days in a foreign country: ${report.fullDaysAbroad} (330 required)")
        if (report.untrackedDays > 0) appendLine("Untracked days in the period: ${report.untrackedDays}")
        appendLine()
        appendLine("Line 18 – travel during the period:")
        report.rows.forEach {
            appendLine("${Countries.name(it.country)}: ${it.arrived.format(usDate)} – ${it.left.format(usDate)}, ${it.fullDays} full days")
        }
    }

    data class VisitedCountry(val country: String, val trips: List<DateRange>) {
        val days: Int get() = trips.sumOf { it.days }
    }

    /** Every country visited from [from] on, with each trip; CBP asks for five years. */
    fun travelHistory(book: DayBook, from: LocalDate, exclude: Set<String> = emptySet()): List<VisitedCountry> =
        book.stays(from, book.today)
            .filter { it.kind != DayKind.PLANNED && it.country !in exclude }
            .groupBy { it.country }
            .map { (country, stays) -> VisitedCountry(country, stays.map { DateRange(it.start, it.end) }.sortedByDescending { it.start }) }
            .sortedByDescending { it.trips.first().start }

    fun travelHistoryText(history: List<VisitedCountry>, from: LocalDate): String = buildString {
        val format = DateTimeFormatter.ofPattern("MMM d, yyyy")
        appendLine("Countries visited since ${from.format(format)}")
        appendLine()
        history.forEach { visited ->
            appendLine("${Countries.name(visited.country)} – ${visited.trips.size} trip(s), ${visited.days} days")
            visited.trips.forEach { appendLine("  ${it.start.format(format)} – ${it.end.format(format)}") }
        }
    }

    fun presenceCsv(rows: List<Presence>): String = buildString {
        appendLine("date,country_code,country,source,also_from")
        rows.sortedWith(compareBy({ it.day }, { it.firstSeen ?: Long.MIN_VALUE })).forEach {
            val also = (it.sources - it.source).joinToString(";") { s -> s.name.lowercase() }
            appendLine("${it.day},${it.country},${Csv.field(Countries.name(it.country))},${it.source.name.lowercase()},$also")
        }
    }

}
