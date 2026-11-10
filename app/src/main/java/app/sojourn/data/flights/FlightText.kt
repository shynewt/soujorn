package app.sojourn.data.flights

import java.time.LocalDate
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale

/** What could be read from a pasted text; anything not found stays null. */
data class FlightGuess(val number: String? = null, val origin: String? = null, val destination: String? = null, val date: LocalDate? = null) {
    val isEmpty: Boolean get() = number == null && origin == null && destination == null && date == null
}

/**
 * Reads a flight out of pasted text: the data string of a boarding pass barcode (IATA BCBP),
 * which many wallet and airline apps let you copy, or a booking confirmation in plain words.
 */
object FlightText {
    // M1 + passenger (20) + e-ticket flag + booking reference (7) + from + to + carrier + flight + day of year.
    private val BOARDING_PASS = Regex("""M[1-4].{20}.(.{7})([A-Z]{3})([A-Z]{3})([A-Z0-9]{2}[A-Z ]?)\s*([0-9]{1,4}[A-Z]?)\s*([0-9]{3})""")
    private val FLIGHT = Regex("""\b([A-Z][A-Z0-9]|[0-9][A-Z])\s?([0-9]{1,4})\b""")
    private val ICAO_FLIGHT = Regex("""\b([A-Z]{3})\s?([0-9]{2,4})\b""")
    private val ROUTE = Regex("""\b([A-Z]{3})\s*(?:→|->|–|—|-|>|to|TO)\s*([A-Z]{3})\b""")
    private val BRACKETED = Regex("""\(([A-Z]{3})\)""")
    private val CODE = Regex("""\b([A-Z]{3})\b""")

    // Capitalised words that happen to be airport codes too (FOR is Fortaleza, THE is Teresina).
    private val WORDS = setOf(
        "THE", "AND", "FOR", "YOU", "ARE", "NOT", "ALL", "NEW", "ONE", "OUT", "GET", "OUR", "MAY", "PER", "VIA",
        "TAX", "FEE", "ETA", "ETD", "UTC", "GMT", "PNR", "REF", "SEAT", "BAG", "CHK", "MON", "TUE", "WED", "THU",
        "FRI", "SAT", "SUN", "JAN", "FEB", "MAR", "APR", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC", "EUR", "USD", "GBP",
        // On boarding passes: titles, passenger types, sequence numbers, bus gates.
        "MRS", "MSS", "DOB", "ADT", "CHD", "INF", "PRM", "SEQ", "BUS", "VAT",
    )

    fun parse(text: String, airports: AirportDirectory, today: LocalDate = LocalDate.now()): FlightGuess {
        boardingPass(text, airports, today)?.let { return it }
        val upper = text.uppercase()
        val number = FLIGHT.findAll(upper)
            .map { it.groupValues[1] to it.groupValues[2] }
            .sortedByDescending { Airlines.isKnown(it.first) }
            .filter { (airline, digits) -> airline.any(Char::isLetter) && digits.length >= 2 }
            .let { found ->
                found.firstOrNull { Airlines.isKnown(it.first) }?.let { (airline, digits) -> Airlines.format(airline + digits) }
                    ?: ICAO_FLIGHT.findAll(upper).firstNotNullOfOrNull { Airlines.fromIcao(it.groupValues[1], it.groupValues[2]) }
                    ?: found.firstOrNull()?.let { (airline, digits) -> Airlines.format(airline + digits) }
            }
        val codes = sequence {
            ROUTE.findAll(text).forEach { yield(it.groupValues[1]); yield(it.groupValues[2]) }
            BRACKETED.findAll(text).forEach { yield(it.groupValues[1]) }
            CODE.findAll(text).forEach { yield(it.groupValues[1]) }
        }.filter { it !in WORDS && airports[it] != null }.distinct().take(2).toList()
        return FlightGuess(number, codes.getOrNull(0), codes.getOrNull(1), date(text, today))
    }

    private fun boardingPass(text: String, airports: AirportDirectory, today: LocalDate): FlightGuess? {
        val match = BOARDING_PASS.find(text) ?: return null
        val (_, from, to, carrier, flight, julian) = match.destructured
        if (airports[from] == null || airports[to] == null) return null
        val day = julian.toIntOrNull()?.takeIf { it in 1..366 } ?: return null
        // The pass only has the day of the year.
        val date = app.sojourn.data.travel.Bcbp.date(day, today, dates(text))
        return FlightGuess(Airlines.format(carrier.trim() + flight.trimStart('0')), from, to, date)
    }

    private val NUMERIC = listOf(
        Regex("""\b(\d{4})-(\d{2})-(\d{2})\b""") to { m: MatchResult -> LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) },
        // Day first, as in most of the world.
        Regex("""\b(\d{1,2})[./](\d{1,2})[./](\d{4})\b""") to { m: MatchResult -> LocalDate.of(m.groupValues[3].toInt(), m.groupValues[2].toInt(), m.groupValues[1].toInt()) },
    )

    private fun date(text: String, today: LocalDate): LocalDate? {
        for ((regex, build) in NUMERIC) {
            regex.find(text)?.let { match -> runCatching { build(match) }.getOrNull()?.let { return it } }
        }
        for (locale in listOf(Locale.ENGLISH, Locale.getDefault()).distinct()) {
            for (pattern in listOf("d MMMM yyyy", "d MMM yyyy", "MMMM d, yyyy", "MMM d, yyyy", "MMMM d yyyy", "MMM d yyyy")) {
                val formatter = DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(pattern).toFormatter(locale)
                DATE_WORDS.findAll(text).forEach { match ->
                    runCatching { LocalDate.parse(match.value.replace(Regex("\\s+"), " ").replace(".", ""), formatter) }.getOrNull()?.let { return it }
                }
            }
            // "Thu 12 Nov" without a year: the next time that date comes round.
            val short = DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("d MMM").parseDefaulting(java.time.temporal.ChronoField.YEAR, today.year.toLong()).toFormatter(locale)
            Regex("""\b\d{1,2}\s+[A-Za-z]{3}\b""").findAll(text).forEach { match ->
                runCatching { LocalDate.parse(match.value.replace(Regex("\\s+"), " "), short) }.getOrNull()?.let {
                    return if (it < today.minusDays(14)) it.plusYears(1) else it
                }
            }
        }
        return null
    }

    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    /**
     * Every full date written in [text]: "2026-01-12", "12/01/2026", "12 Jan 2026" and
     * "12 / JAN / 2026", as boarding passes print them.
     */
    fun dates(text: String): List<LocalDate> {
        val found = mutableListOf<LocalDate>()
        for ((regex, build) in NUMERIC) regex.findAll(text).forEach { m -> runCatching { build(m) }.getOrNull()?.let(found::add) }
        Regex("""\b(\d{1,2})\s*[/.\- ]\s*([A-Za-z]{3,9})\.?\s*[/.\- ]\s*(\d{4})\b""").findAll(text).forEach { m ->
            val month = MONTHS.indexOf(m.groupValues[2].take(3).lowercase()) + 1
            if (month > 0) runCatching { LocalDate.of(m.groupValues[3].toInt(), month, m.groupValues[1].toInt()) }.getOrNull()?.let(found::add)
        }
        return found
    }

    private val DATE_WORDS = Regex("""\b(\d{1,2}\s+[A-Za-zÀ-ÿ.]{3,10}\s+\d{4}|[A-Za-zÀ-ÿ.]{3,10}\s+\d{1,2},?\s+\d{4})\b""")
}
