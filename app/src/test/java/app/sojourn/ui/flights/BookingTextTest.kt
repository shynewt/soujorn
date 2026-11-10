package app.sojourn.ui.flights

import app.sojourn.data.db.Flight
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BookingTextTest {
    private val today = LocalDate.of(2026, 10, 25)

    @Test
    fun boardingPassHasEverything() {
        val bcbp = "M1DOE/JANE            EABC123 LISLCATP 1234 316Y012A0001 100"
        assertEquals(BookingDetails("ABC123", "DOE/JANE", "12A"), BookingText.read(bcbp, today))
    }

    @Test
    fun bookingEmailNamesReferenceAndSeat() {
        val text = """
            Your trip to Larnaca is confirmed. Booking reference: XYZ789
            Flight TP 1234 · Thu, 12 Nov 2026 · Seat 14c
        """.trimIndent()
        assertEquals(BookingDetails(reference = "XYZ789", seat = "14C"), BookingText.read(text, today))
    }

    @Test
    fun wordsAfterTheLabelAreNotAReference() {
        assertTrue(BookingText.read("Your booking reference number is below", today).isEmpty)
    }

    @Test
    fun passengerAsWritten() {
        assertEquals("Jane Doe", passengerName("DOE/JANE MRS"))
        assertEquals("Ana Maria Garcia-Lopez", passengerName("GARCIA-LOPEZ/ANA MARIA MS"))
        assertEquals("Jane Doe", passengerName("Jane Doe"))
    }

    @Test
    fun bookingLineOnlyWhenKnown() {
        val flight = Flight(number = "TP1", origin = "LIS", destination = "LCA", originCountry = "PT", destinationCountry = "CY", departureDate = today, arrivalDate = today)
        assertNull(bookingLine(flight))
        assertEquals("Seat 12A · Ref ABC123 · Jane Doe", bookingLine(flight.copy(reference = "ABC123", passenger = "DOE/JANE MRS", seat = "12A")))
    }
}
