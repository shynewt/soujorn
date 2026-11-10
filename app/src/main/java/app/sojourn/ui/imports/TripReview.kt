package app.sojourn.ui.imports

import app.sojourn.data.db.Flight
import app.sojourn.data.flights.AirportDirectory
import app.sojourn.data.flights.Airlines
import app.sojourn.data.flights.FlightMatch
import app.sojourn.data.travel.FoundTrip
import app.sojourn.data.travel.Names
import app.sojourn.data.travel.PassengerMatch
import app.sojourn.data.travel.TripKind

/** A found trip with what Sojourn worked out about it. */
data class TripItem(
    val trip: FoundTrip,
    val fromCountry: String?,
    val toCountry: String?,
    val passengerMatch: PassengerMatch,
    val duplicate: Boolean,
    /** The same trip found again in this batch (its screenshot and its PDF), kept as evidence too. */
    val copies: List<FoundTrip> = emptyList(),
    /** Other passengers' passes for this same flight, left out as the user's own was found. */
    val othersLeftOut: Int = 0,
) {
    val usable: Boolean get() = fromCountry != null && toCountry != null
    val selectedByDefault: Boolean get() = usable && !duplicate && passengerMatch != PassengerMatch.DIFFERENT
}

internal fun prepareTripItems(
    trips: List<FoundTrip>,
    airports: AirportDirectory,
    existingFlights: List<Flight>,
    travellerNames: Set<String>,
): List<TripItem> {
    val alike = trips.groupBy { it.key }
    val items = alike.values.map { it.first() }.sortedBy { it.departure }.map { trip ->
        val flight = trip.kind == TripKind.FLIGHT
        val fromCountry = if (flight) airports[trip.from]?.country else trip.fromCountry
        val toCountry = if (flight) airports[trip.to]?.country else trip.toCountry
        val asFlight = trip.asFlight(fromCountry, toCountry)
        TripItem(
            trip = trip,
            fromCountry = fromCountry,
            toCountry = toCountry,
            passengerMatch = Names.match(trip.passenger, travellerNames),
            duplicate = trip.isDuplicateOf(existingFlights) || asFlight != null && existingFlights.any { FlightMatch.same(it, asFlight) },
            copies = alike.getValue(trip.key).drop(1),
        )
    }
    // The same flight found twice in this batch (an event in two calendars, an email and its
    // event): one copy is kept, the user's own booking first, then the one that says most.
    val kept = mutableListOf<TripItem>()
    val merged = HashMap<TripItem, MutableList<FoundTrip>>()
    val order = compareBy<TripItem> { listOf(PassengerMatch.MATCH, PassengerMatch.UNKNOWN, PassengerMatch.DIFFERENT).indexOf(it.passengerMatch) }.thenByDescending { detail(it.trip) }
    for (item in items.sortedWith(order)) {
        val flight = item.trip.asFlight(item.fromCountry, item.toCountry)
        // Copies for different passengers stay apart, so each shows whose it is; saving
        // checks again, so the user's flight is still only saved once.
        val same = if (flight == null) emptyList() else kept.filter { other ->
            other.trip.kind == TripKind.FLIGHT && other.trip.asFlight(other.fromCountry, other.toCountry)?.let { FlightMatch.same(it, flight) } == true
        }
        // A copy without a name (a pass read from its print) joins the one traveller's copy,
        // but stays apart when the flight was found for several people.
        val travellers = if (flight == null) emptySet() else items.filter { other ->
            other.trip.kind == TripKind.FLIGHT && other.trip.asFlight(other.fromCountry, other.toCountry)?.let { FlightMatch.same(it, flight) } == true
        }.mapNotNull { it.trip.passenger?.let(Names::fold) }.toSet()
        val twin = if (item.trip.passenger == null) same.firstOrNull().takeIf { travellers.size == 1 }
        else same.firstOrNull { samePassenger(it.trip, item.trip) }
        if (twin == null) kept += item else merged.getOrPut(twin) { mutableListOf() } += listOf(item.trip) + item.copies
    }
    val result = items.filter { item -> kept.any { it === item } }.map { item ->
        merged[item]?.let { more -> item.copy(copies = item.copies + more) } ?: item
    }
    // A pass in the user's own name settles whose flight it is: the other passengers' passes
    // for it (a family's PDF, a page each) are left out rather than listed.
    fun flightOf(item: TripItem) = item.trip.takeIf { it.kind == TripKind.FLIGHT }?.asFlight(item.fromCountry, item.toCountry)
    val mine = result.filter { it.passengerMatch == PassengerMatch.MATCH }
    val others = result.filter { item ->
        item.passengerMatch == PassengerMatch.DIFFERENT && flightOf(item)?.let { f -> mine.any { m -> flightOf(m)?.let { FlightMatch.same(it, f) } == true } } == true
    }
    return result.filter { it !in others }.map { item ->
        val own = flightOf(item)?.takeIf { item.passengerMatch == PassengerMatch.MATCH } ?: return@map item
        val left = others.count { other -> flightOf(other)?.let { FlightMatch.same(own, it) } == true }
        if (left == 0) item else item.copy(othersLeftOut = left)
    }
}

private fun samePassenger(a: FoundTrip, b: FoundTrip) = a.passenger?.let(Names::fold) == b.passenger?.let(Names::fold)

private fun FoundTrip.isDuplicateOf(flights: List<Flight>) = flights.any(::isDuplicateOf)

private fun detail(trip: FoundTrip): Int =
    (if (trip.number.isNotBlank()) 2 else 0) + (if (trip.departureMinute != null) 1 else 0) + (if (trip.arrivalMinute != null) 1 else 0) +
        (if (trip.passenger != null) 1 else 0)

/** A found flight as the flight it would be saved as, to compare with others; null for other trips. */
internal fun FoundTrip.asFlight(fromCountry: String?, toCountry: String?): Flight? {
    if (kind != TripKind.FLIGHT || fromCountry == null || toCountry == null) return null
    return Flight(
        number = Airlines.format(number),
        origin = from.uppercase(),
        destination = to.uppercase(),
        originCountry = fromCountry,
        destinationCountry = toCountry,
        departureDate = departure,
        arrivalDate = arrival,
        departureMinute = departureMinute,
        arrivalMinute = arrivalMinute,
        note = source.take(80),
    )
}
