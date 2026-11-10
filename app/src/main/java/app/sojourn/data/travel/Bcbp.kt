package app.sojourn.data.travel

import app.sojourn.data.flights.AirportDirectory
import app.sojourn.data.flights.FlightText
import java.time.LocalDate

/**
 * The data in a boarding pass barcode (IATA Resolution 792, "BCBP"), as printed on paper,
 * shown in airline apps and Google Wallet, or kept in an Apple Wallet pass. It has the
 * passenger, each leg's airports, carrier, flight number and day of the year, but no year and
 * no times: see [date] for how the year is told.
 */
object Bcbp {
    /**
     * The date of day [julian] of some year. A pass is issued at most about a month before
     * its flight, so it's the latest such day up to 30 days after [near], when the file was
     * made. A full date in [printed] (a PDF's text) on that day of the year settles it.
     */
    fun date(julian: Int, near: LocalDate, printed: List<LocalDate> = emptyList()): LocalDate? {
        printed.firstOrNull { it.dayOfYear == julian }?.let { return it }
        val latest = near.plusDays(30)
        return (latest.year downTo latest.year - 2)
            .mapNotNull { year -> runCatching { LocalDate.ofYearDay(year, julian) }.getOrNull()?.takeIf { it.year == year } }
            .firstOrNull { it <= latest }
    }

    fun parse(data: String, near: LocalDate = LocalDate.now(), printed: List<LocalDate> = emptyList()): List<FoundTrip> {
        val start = Regex("M[1-4]").findAll(data).map { it.range.first }.firstOrNull { data.length - it >= 58 } ?: return emptyList()
        val text = data.substring(start)
        val legs = text[1].digitToIntOrNull() ?: return emptyList()
        val passenger = text.substring(2, 22).trim().takeIf { it.isNotEmpty() }
        val trips = mutableListOf<FoundTrip>()
        var offset = 23
        repeat(legs) { leg ->
            if (text.length < offset + 37) return trips
            val reference = text.substring(offset, offset + 7).trim()
            val from = text.substring(offset + 7, offset + 10)
            val to = text.substring(offset + 10, offset + 13)
            val carrier = text.substring(offset + 13, offset + 16).trim()
            val flight = text.substring(offset + 16, offset + 21).trim().trimStart('0').ifEmpty { "0" }
            val julian = text.substring(offset + 21, offset + 24).trim().toIntOrNull()
            if (!from.all(Char::isLetter) || !to.all(Char::isLetter) || julian == null || julian !in 1..366) return trips
            val date = date(julian, near, printed) ?: return trips
            trips += FoundTrip(
                kind = TripKind.FLIGHT,
                departure = date,
                number = "$carrier $flight",
                from = from,
                to = to,
                passenger = passenger,
                source = "Boarding pass",
                reference = reference.takeIf { it.isNotEmpty() },
            )
            // Each leg's mandatory part is 37 characters, then a variable part whose length is
            // the two hex digits at its end; later legs follow it.
            if (leg < legs - 1) {
                val size = text.substring(offset + 35, offset + 37).toIntOrNull(16) ?: return trips
                offset += 37 + size
                // The second leg starts again from its PNR, without the passenger.
            }
        }
        return trips
    }
}

/**
 * The flight printed on a boarding pass, read from a PDF's text when its barcode can't be:
 * the barcode's data if it's printed too, else the flight number, airports and date written
 * out. The airports must be real ones, so a pass's other words aren't taken for them.
 */
object PrintedPass {
    fun flights(text: String, airports: AirportDirectory, near: LocalDate): List<FoundTrip> {
        Bcbp.parse(text, near, FlightText.dates(text)).filter { airports[it.from] != null && airports[it.to] != null }.takeIf { it.isNotEmpty() }?.let { return it }
        val guess = FlightText.parse(text, airports, near)
        val number = guess.number ?: return emptyList()
        val from = guess.origin ?: return emptyList()
        val to = guess.destination?.takeIf { it != from } ?: return emptyList()
        val date = guess.date ?: return emptyList()
        return listOf(FoundTrip(TripKind.FLIGHT, date, number = number, from = from, to = to, source = "Boarding pass"))
    }
}
