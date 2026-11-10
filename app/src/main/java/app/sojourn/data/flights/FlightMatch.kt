package app.sojourn.data.flights

import app.sojourn.data.db.Flight
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * Tells when two flights are the same trip found twice: a calendar event and a flight entered
 * by hand, one event in two calendars, or an import run again. Sources disagree in small ways
 * (a city read as another of its airports, a missing flight number, a codeshare's other number,
 * a time zone moving the date by a day), so matching only on identical fields let duplicates in.
 */
object FlightMatch {
    /**
     * Whether [a] and [b] are the same flight. [strict] is for removing duplicates already saved:
     * it only matches what can't be two real flights (the same number, or the same route and day).
     */
    fun same(a: Flight, b: Flight, strict: Boolean = false): Boolean {
        val days = abs(ChronoUnit.DAYS.between(a.departureDate, b.departureDate))
        if (days > 1) return false
        val numberA = number(a.number)
        val numberB = number(b.number)
        val sameNumber = numberA.isNotEmpty() && numberA == numberB
        val sameRoute = a.origin.equals(b.origin, ignoreCase = true) && a.destination.equals(b.destination, ignoreCase = true)
        val sameCountries = a.originCountry == b.originCountry && a.destinationCountry == b.destinationCountry
        val minutes = if (a.departureMinute != null && b.departureMinute != null) abs(a.departureMinute - b.departureMinute) else null
        // A day apart: only a date read in another time zone, which also moves the time. The
        // same number at the same time on the next day is the daily flight taken twice, and
        // never something to delete, so cleaning up saved flights leaves day-apart pairs alone.
        if (days == 1L) return !strict && sameNumber && sameCountries && minutes != 0
        if (sameNumber && (sameCountries || sameRoute)) return true
        // The same route twice in a day is the same flight unless the times say otherwise
        // (an early flight missed, a later one taken: both are kept).
        if (sameRoute) return minutes == null || minutes <= 90 && (numberA.isEmpty() || numberB.isEmpty()) || minutes <= 15
        if (strict) return false
        // Different airports of the same two countries (London read as Heathrow, flown from
        // Gatwick): the same trip when the times agree. A domestic flight needs its airports.
        if (!sameCountries || a.originCountry == a.destinationCountry) return false
        val bothNumbered = numberA.isNotEmpty() && numberB.isNotEmpty()
        return if (bothNumbered) minutes != null && minutes <= 15 else minutes == null || minutes <= 90
    }

    /** "IB 3166", "ib3166", "IB03166" → "IB3166". */
    fun number(text: String): String {
        val clean = text.uppercase().filter { it.isLetterOrDigit() }
        if (clean.length <= 2) return clean
        return clean.take(2) + clean.drop(2).trimStart('0')
    }

    /** How much a flight says, to keep the better of two copies. */
    private fun detail(flight: Flight): Int =
        (if (flight.number.isNotBlank()) 2 else 0) + (if (flight.departureMinute != null) 1 else 0) +
            (if (flight.arrivalMinute != null) 1 else 0) + (if (flight.note.isNotBlank() && !flight.note.startsWith("Calendar")) 2 else 0)

    /** The copy to keep of two duplicates, filled in with what only the other one knew. */
    fun merge(a: Flight, b: Flight): Flight {
        val (keep, other) = if (detail(b) > detail(a)) b to a else a to b
        return keep.copy(
            number = keep.number.ifBlank { other.number },
            departureMinute = keep.departureMinute ?: other.departureMinute,
            arrivalMinute = keep.arrivalMinute ?: other.arrivalMinute,
            note = keep.note.ifBlank { other.note },
        )
    }

    /** What to change so no two of [flights] are duplicates: ids to delete, flights to update. */
    data class Cleanup(val delete: List<Long>, val update: List<Flight>)

    fun duplicates(flights: List<Flight>): Cleanup {
        val kept = mutableListOf<Flight>()
        val delete = mutableListOf<Long>()
        val changed = LinkedHashMap<Long, Flight>()
        for (flight in flights.sortedWith(compareBy<Flight> { it.departureDate }.thenBy { it.id })) {
            val index = kept.indexOfFirst { same(it, flight, strict = true) }
            if (index < 0) {
                kept += flight
                continue
            }
            val existing = kept[index]
            val merged = merge(existing, flight)
            // The merged flight takes the id of the copy it was built from; the other goes.
            val gone = if (merged.id == existing.id) flight else existing
            delete += gone.id
            changed.remove(gone.id)
            kept[index] = merged
            if (merged != (if (merged.id == existing.id) existing else flight)) changed[merged.id] = merged
        }
        return Cleanup(delete, changed.values.toList())
    }
}
