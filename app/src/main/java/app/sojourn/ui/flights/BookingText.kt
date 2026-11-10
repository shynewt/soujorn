package app.sojourn.ui.flights

import app.sojourn.data.flights.FlightText
import app.sojourn.data.travel.Bcbp
import java.time.LocalDate

/** The booking details found in a pasted text; blank where nothing was found. */
data class BookingDetails(val reference: String = "", val passenger: String = "", val seat: String = "") {
    val isEmpty: Boolean get() = reference.isEmpty() && passenger.isEmpty() && seat.isEmpty()
}

/**
 * Reads the booking reference, passenger and seat from pasted text, alongside the flight that
 * [FlightText] reads: a boarding pass barcode has all three; a booking email names the
 * reference ("Booking reference: ABC123") and sometimes the seat.
 */
object BookingText {
    // Labels in any case, the code itself in capitals, so "Booking reference number" isn't read as one.
    private val REFERENCE = Regex(
        """(?i:booking\s+(?:reference|ref\.?|code)|reservation\s+(?:code|number)|confirmation\s+(?:code|number)|record\s+locator|pnr|localizador|c[oó]digo\s+de\s+reserva)\s*(?:is|:|#)?\s*([A-Z0-9]{6})\b""",
    )
    private val SEAT = Regex("""(?i:\bseat|\basiento)\s*(?::|#)?\s*(\d{1,2}[A-Ka-k])\b""")

    fun read(text: String, today: LocalDate = LocalDate.now()): BookingDetails {
        Bcbp.parse(text, today, FlightText.dates(text)).firstOrNull()?.let { pass ->
            return BookingDetails(pass.reference.orEmpty(), pass.passenger.orEmpty(), pass.seat.orEmpty())
        }
        return BookingDetails(
            reference = REFERENCE.find(text)?.groupValues?.get(1).orEmpty(),
            seat = SEAT.find(text)?.groupValues?.get(1)?.uppercase().orEmpty(),
        )
    }
}
