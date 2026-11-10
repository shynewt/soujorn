package app.sojourn.data.flights

import app.sojourn.data.db.Flight
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlightMatchTest {
    private val date = LocalDate.of(2026, 3, 4)
    private val manual = Flight(
        id = 1, number = "BA 459", origin = "MAD", destination = "LGW", originCountry = "ES", destinationCountry = "GB",
        departureDate = date, arrivalDate = date, departureMinute = 7 * 60 + 5, note = "Window seat",
    )

    @Test
    fun theSameFlightReadDifferentlyIsADuplicate() {
        // "Vuelo a Londres" reads as Heathrow, without a number, at the same time.
        val calendar = manual.copy(id = 0, number = "", destination = "LHR", note = "Calendar: Vuelo a Londres")
        assertTrue(FlightMatch.same(manual, calendar))
        // The same number a day apart: a date read in another time zone.
        assertTrue(FlightMatch.same(manual, manual.copy(id = 0, departureDate = date.plusDays(1), departureMinute = null)))
        // Number written another way, on the same route.
        assertTrue(FlightMatch.same(manual, manual.copy(id = 0, number = "ba0459", departureMinute = null)))
    }

    @Test
    fun differentFlightsStayApart() {
        // The way back, a later flight on the same route, and a domestic leg are other flights.
        assertFalse(FlightMatch.same(manual, manual.copy(origin = "LGW", destination = "MAD", originCountry = "GB", destinationCountry = "ES")))
        assertFalse(FlightMatch.same(manual, manual.copy(number = "", departureMinute = 18 * 60)))
        assertFalse(FlightMatch.same(manual, manual.copy(departureDate = date.plusDays(7))))
        val domestic = manual.copy(number = "", origin = "MAD", destination = "BCN", originCountry = "ES", destinationCountry = "ES")
        assertFalse(FlightMatch.same(domestic, domestic.copy(origin = "BCN", destination = "PMI")))
        // The daily flight taken on two days in a row, at its usual time, is two flights; and
        // cleaning up saved flights never merges days apart.
        assertFalse(FlightMatch.same(manual, manual.copy(departureDate = date.plusDays(1))))
        assertFalse(FlightMatch.same(manual, manual.copy(departureDate = date.plusDays(1), departureMinute = null), strict = true))
        // Other numbers between the same countries at other times.
        assertFalse(FlightMatch.same(manual, manual.copy(number = "IB 3166", destination = "LHR", departureMinute = 15 * 60)))
    }

    @Test
    fun cleanupKeepsTheCopyThatSaysMostAndFillsItIn() {
        val bare = manual.copy(id = 2, number = "", departureMinute = null, arrivalMinute = 9 * 60, note = "Calendar: Vuelo a Londres")
        val other = manual.copy(id = 3, origin = "LGW", destination = "MAD", originCountry = "GB", destinationCountry = "ES", departureDate = date.plusDays(5))
        val cleanup = FlightMatch.duplicates(listOf(bare, manual, other))
        assertEquals(listOf(2L), cleanup.delete)
        assertEquals(listOf(manual.copy(arrivalMinute = 9 * 60)), cleanup.update)
        // Nothing twice, nothing to do.
        assertEquals(FlightMatch.Cleanup(emptyList(), emptyList()), FlightMatch.duplicates(listOf(manual, other)))
    }
}
