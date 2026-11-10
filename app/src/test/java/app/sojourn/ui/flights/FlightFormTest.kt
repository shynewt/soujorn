package app.sojourn.ui.flights

import app.sojourn.data.db.Flight
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class FlightFormTest {
    private val day = LocalDate.of(2026, 1, 12)
    private val flight = Flight(7, "W64666", "BCN", "LCA", "ES", "CY", day, day)

    @Test
    fun anOpenFormTakesWhatAPassFilledInWhereNothingWasTyped() {
        val form = FlightForm(flight)
        form.seat = "1A"
        form.follow(flight.copy(reference = "NGJY6Y", passenger = "DOE/JANE", seat = "13B", departureMinute = 600))
        assertEquals("NGJY6Y", form.reference)
        assertEquals("DOE/JANE", form.passenger)
        assertEquals("1A", form.seat)
        assertEquals(600, form.departs)
    }

    @Test
    fun followingTheSameFlightAgainChangesNothing() {
        val form = FlightForm(flight)
        form.number = "W6 9999"
        form.follow(flight)
        assertEquals("W6 9999", form.number)
    }
}
