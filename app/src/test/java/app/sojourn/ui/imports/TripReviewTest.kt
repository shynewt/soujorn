package app.sojourn.ui.imports

import app.sojourn.data.db.Flight
import app.sojourn.data.flights.AirportDirectory
import app.sojourn.data.travel.FoundTrip
import app.sojourn.data.travel.Names
import app.sojourn.data.travel.PassengerMatch
import app.sojourn.data.travel.TripKind
import java.io.File
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TripReviewTest {
    private val airports by lazy { File("src/main/assets/airports.tsv").inputStream().use(AirportDirectory::read) }
    private val date = LocalDate.of(2026, 11, 12)
    private val flight = FoundTrip(TripKind.FLIGHT, date, from = "LCA", to = "LIS", departureMinute = 480)

    @Test
    fun passengerStatesHaveExplicitDefaultSelection() {
        val trip = flight.copy(passenger = "Jane Doe")
        PassengerMatch.entries.forEach { match ->
            val item = TripItem(trip, "CY", "PT", match, duplicate = false)
            assertEquals(match != PassengerMatch.DIFFERENT, item.selectedByDefault)
            assertFalse(item.copy(duplicate = true).selectedByDefault)
            assertFalse(item.copy(fromCountry = null).selectedByDefault)
            assertFalse(item.copy(toCountry = null).selectedByDefault)
        }
    }

    @Test
    fun blankOrUnrecognizablePassengerNamesAreUnknown() {
        listOf(null, "", " ", "123", "MR MRS").forEach { passenger ->
            assertEquals(PassengerMatch.UNKNOWN, Names.match(passenger, setOf("Jane Doe")))
        }
    }

    @Test
    fun reviewKeepsEveryConnectionAndOnlyMarksTheExistingLegDuplicate() {
        val connection = flight.copy(from = "LIS", to = "LHR", departureMinute = 900)
        val lastLeg = flight.copy(from = "LHR", to = "JFK", departureMinute = 1100)
        val existing = Flight(
            number = "", origin = "LCA", destination = "LIS", originCountry = "CY", destinationCountry = "PT",
            departureDate = date, arrivalDate = date, departureMinute = 480,
        )
        val items = prepareTripItems(
            listOf(flight, connection, lastLeg, flight.copy(source = "Another calendar")),
            airports, listOf(existing), emptySet(),
        )
        assertEquals(listOf(flight, connection, lastLeg), items.map { it.trip })
        assertEquals(listOf(true, false, false), items.map { it.duplicate })
        assertEquals(listOf(false, true, true), items.map { it.selectedByDefault })
        assertEquals(listOf("CY", "PT", "GB"), items.map { it.fromCountry })
        assertEquals(listOf("PT", "GB", "US"), items.map { it.toCountry })
    }

    @Test
    fun reviewSkipsOtherPassengersButKeepsUnidentifiedBookings() {
        val trips = listOf(
            flight.copy(passenger = "DOE/JANE MRS"),
            flight.copy(passenger = "DOE/ROSA MRS"),
            flight,
        )
        val items = prepareTripItems(trips, airports, emptyList(), setOf("Jane Doe"))
        // The user's own pass is the one listed; another passenger's for the same flight is left out.
        assertEquals(listOf(PassengerMatch.MATCH, PassengerMatch.UNKNOWN), items.map { it.passengerMatch })
        assertEquals(1, items.first().othersLeftOut)
        assertEquals(listOf(true, true), items.map { it.selectedByDefault })
        // Without the user's name, everyone's pass is listed to choose from.
        assertEquals(3, prepareTripItems(trips, airports, emptyList(), emptySet()).size)
    }

    @Test
    fun flightsResolveCountriesFromAirportsAndOtherTripsKeepBookingCountries() {
        val train = FoundTrip(TripKind.TRAIN, date.minusDays(1), from = "Paris", to = "London", fromCountry = "FR", toCountry = "GB")
        val unknown = flight.copy(from = "???", fromCountry = "CY")
        val items = prepareTripItems(listOf(unknown, train), airports, emptyList(), emptySet())
        assertEquals(listOf(train, unknown), items.map { it.trip })
        assertTrue(items.first().usable)
        assertEquals("FR", items.first().fromCountry)
        assertEquals("GB", items.first().toCountry)
        assertFalse(items.last().usable)
        assertFalse(items.last().selectedByDefault)
    }

    @Test
    fun aPassFoundTwiceInABatchIsOneTripKeepingBothFiles() {
        val screenshot = flight.copy(passenger = "DOE/JANE MRS", file = "content://screenshot", rawKind = app.sojourn.data.travel.RawKind.BOARDING_PASS)
        val pdf = flight.copy(file = "content://pdf", rawKind = app.sojourn.data.travel.RawKind.BOARDING_PASS)
        val again = screenshot.copy(file = "content://zip/screenshot")
        val items = prepareTripItems(listOf(pdf, screenshot, again), airports, emptyList(), emptySet())
        val item = items.single()
        assertEquals("DOE/JANE MRS", item.trip.passenger)
        assertEquals(setOf("content://screenshot", "content://pdf", "content://zip/screenshot"), (listOf(item.trip) + item.copies).map { it.file }.toSet())
    }
}
