package app.sojourn.data.travel

import app.sojourn.data.db.Flight
import app.sojourn.data.flights.Airlines
import java.text.Normalizer
import java.time.LocalDate

enum class TripKind(val label: String) { FLIGHT("Flight"), TRAIN("Train"), BUS("Bus"), FERRY("Ferry"), CAR("Car rental"), STAY("Stay") }

enum class PassengerMatch { MATCH, DIFFERENT, UNKNOWN }

/** What a found trip's original is, for keeping it as evidence. */
enum class RawKind { CALENDAR_EVENT, BOOKING, BOARDING_PASS }

/**
 * A trip found in a calendar, a boarding pass or a booking email, before the user approves it.
 * Flights name their airports by IATA code; other trips name places, with countries when the
 * booking says them. Times are minutes after local midnight where the trip starts or ends.
 */
data class FoundTrip(
    val kind: TripKind,
    val departure: LocalDate,
    val arrival: LocalDate = departure,
    val number: String = "",
    val from: String = "",
    val to: String = "",
    val fromCountry: String? = null,
    val toCountry: String? = null,
    val departureMinute: Int? = null,
    val arrivalMinute: Int? = null,
    /** The traveller named on the ticket, when it says. */
    val passenger: String? = null,
    /** Where it was found, e.g. "Boarding pass" or the email's subject. */
    val source: String = "",
    val reference: String? = null,
    /** What it was found in, to keep as evidence: a calendar event as iCalendar, or a booking's text. */
    val raw: String? = null,
    val rawKind: RawKind? = null,
    /** The file it was read from (a boarding pass picture, PDF or Wallet pass), to keep a copy of. */
    val file: String? = null,
    val fileName: String? = null,
) {
    fun isDuplicateOf(flight: Flight): Boolean =
        kind == TripKind.FLIGHT && departure == flight.departureDate &&
            from.equals(flight.origin, ignoreCase = true) && to.equals(flight.destination, ignoreCase = true) &&
            (number.isBlank() || flight.number.isBlank() || Airlines.format(number) == Airlines.format(flight.number)) &&
            (departureMinute == null || flight.departureMinute == null || departureMinute == flight.departureMinute)

    /** The same trip found twice (an email and its calendar event) has the same key. */
    val key: String
        get() = listOf(
            kind,
            number.filter { it.isLetterOrDigit() }.uppercase(),
            departure,
            from.uppercase(),
            to.uppercase(),
            passenger?.let(Names::fold),
            departureMinute,
        ).joinToString("|")
}

/**
 * Matches passenger names against the names the user travels under, so a flight bought for
 * someone else isn't taken as the user's own. Airlines write "DOE/JANE MRS" or "Jane Doe";
 * order, titles, accents and middle names are ignored.
 */
object Names {
    private val TITLES = setOf("MR", "MRS", "MS", "MISS", "MSTR", "DR", "PROF", "SR", "SRA", "HERR", "FRAU", "M", "MME", "CHD", "INF")

    fun fold(name: String): String = Normalizer.normalize(name, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").uppercase()

    fun tokens(name: String): Set<String> = fold(name).split(Regex("[^A-Z]+")).filter { it.length > 1 && it !in TITLES }.toSet()

    fun match(passenger: String?, mine: Set<String>): PassengerMatch {
        if (passenger.isNullOrBlank() || mine.isEmpty()) return PassengerMatch.UNKNOWN
        val theirs = tokens(passenger)
        if (theirs.isEmpty()) return PassengerMatch.UNKNOWN
        val matches = mine.any { own ->
            val ours = tokens(own)
            val shared = ours intersect theirs
            // A surname and a first name in common, or every word when there's only one.
            shared.size >= 2 || (ours.size == 1 && shared.size == 1) || (theirs.size == 1 && shared.size == 1)
        }
        return if (matches) PassengerMatch.MATCH else PassengerMatch.DIFFERENT
    }
}
