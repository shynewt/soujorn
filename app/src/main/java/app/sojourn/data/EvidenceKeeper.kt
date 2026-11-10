package app.sojourn.data

import android.net.Uri
import app.sojourn.data.db.Document
import app.sojourn.data.db.DocumentKind
import app.sojourn.data.db.Flight
import app.sojourn.data.flights.Airlines
import app.sojourn.data.travel.FoundTrip
import app.sojourn.data.travel.RawKind
import app.sojourn.data.travel.TravelImporter
import app.sojourn.data.travel.TripKind
import java.time.LocalDate

/**
 * Keeps what a trip was found in as evidence for its days: a boarding pass file, a calendar
 * event, a booking's text. Flights already saved gain evidence the same way as new ones, and
 * the same file or text is never kept twice for a day, however often it's shared.
 */
class EvidenceKeeper(private val repository: Repository, private val store: EvidenceStore, private val travel: TravelImporter) {

    /** The title a flight's evidence goes by: "Boarding pass: W6 4666 BCN→LCA". */
    fun title(kind: DocumentKind, trip: FoundTrip): String {
        val route = if (trip.kind == TripKind.STAY) trip.from else listOf(trip.number, "${trip.from}→${trip.to}").filter { it.isNotBlank() }.joinToString(" ")
        return "${kind.label}: ${route.ifBlank { trip.source }}".take(120)
    }

    /**
     * Keeps [original]'s source on [places] (day and country), titled after [trip]. Files are
     * copied once per source through [copies]. Returns how many documents were new.
     */
    suspend fun keep(trip: FoundTrip, original: FoundTrip, places: List<Pair<LocalDate, String>>, copies: MutableMap<String, String?> = HashMap()): Int {
        val kind = when (original.rawKind) {
            RawKind.CALENDAR_EVENT -> DocumentKind.CALENDAR_EVENT
            RawKind.BOOKING -> DocumentKind.BOOKING
            RawKind.BOARDING_PASS -> DocumentKind.BOARDING_PASS
            null -> return 0
        }
        val title = title(kind, trip)
        if (kind != DocumentKind.BOARDING_PASS) {
            return places.count { (day, country) -> repository.addDocument(Document(day = day, country = country, kind = kind, title = title, text = original.raw.orEmpty())) }
        }
        val source = original.file ?: return 0
        // A file already kept for these days, from an earlier share or import, isn't kept again.
        val hash = store.hash(Uri.parse(source)) ?: return 0
        val fresh = places.filter { (day, country) ->
            repository.documents(day, country, kind).none { existing -> existing.file?.let { store.hashOf(it) } == hash }
        }
        if (fresh.isEmpty()) return 0
        val file = copies.getOrPut(source) { store.keep(Uri.parse(source), original.fileName ?: "boarding-pass") } ?: return 0
        return fresh.count { (day, country) ->
            repository.addDocument(Document(day = day, country = country, kind = kind, title = title, file = file, text = original.raw.orEmpty()))
        }
    }

    /** What adding a boarding pass to a saved flight came to. */
    sealed interface Attached {
        data class Kept(val documents: Int) : Attached
        data object AlreadyKept : Attached
        /** The file is another flight's pass. */
        data class OtherFlight(val found: String) : Attached
    }

    /**
     * Adds a boarding pass (PDF, picture or Wallet pass) to [flight]. A pass read as another
     * flight isn't added; one with nothing readable is, as the user chose it for this flight.
     */
    suspend fun attach(flight: Flight, uri: Uri): Attached {
        val found = travel.files(listOf(uri)).trips
        val own = found.firstOrNull { it.kind == TripKind.FLIGHT && it.isDuplicateOf(flight) }
        if (own == null && found.any { it.kind == TripKind.FLIGHT }) {
            val other = found.first { it.kind == TripKind.FLIGHT }
            return Attached.OtherFlight("${other.number} ${other.from}→${other.to} on ${other.departure}".trim())
        }
        val number = flight.number.takeIf { it.isNotBlank() }?.let(Airlines::format).orEmpty()
        val trip = FoundTrip(TripKind.FLIGHT, flight.departureDate, flight.arrivalDate, number = number, from = flight.origin, to = flight.destination)
        val original = own ?: trip.copy(file = uri.toString(), fileName = travel.displayName(uri), rawKind = RawKind.BOARDING_PASS)
        val places = listOf(flight.departureDate to flight.originCountry, flight.arrivalDate to flight.destinationCountry).distinct()
        val kept = keep(trip, original, places)
        return if (kept > 0) Attached.Kept(kept) else Attached.AlreadyKept
    }

    /** The evidence kept for [flight]: what's titled with its route on its two days, both days' copies. */
    fun forFlight(flight: Flight, documents: List<Document>): List<Document> {
        val route = "${flight.origin}→${flight.destination}"
        return documents.filter { doc ->
            doc.kind != DocumentKind.PHOTO && route in doc.title &&
                (doc.day == flight.departureDate && doc.country == flight.originCountry || doc.day == flight.arrivalDate && doc.country == flight.destinationCountry)
        }
    }
}
