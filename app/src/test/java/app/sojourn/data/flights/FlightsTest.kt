package app.sojourn.data.flights

import app.sojourn.data.db.Flight
import app.sojourn.domain.CountingOptions
import app.sojourn.domain.DayBook
import app.sojourn.domain.DayKind
import java.io.File
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FlightsTest {
    private val airports by lazy { File("src/main/assets/airports.tsv").inputStream().use(AirportDirectory::read) }
    private val today = LocalDate.of(2026, 10, 25)

    private fun flight(from: String, to: String, date: LocalDate, arrives: LocalDate = date, departs: Int? = null, lands: Int? = null) =
        Flight(
            number = "TP1",
            origin = from,
            destination = to,
            originCountry = airports[from]!!.country,
            destinationCountry = airports[to]!!.country,
            departureDate = date,
            arrivalDate = arrives,
            departureMinute = departs,
            arrivalMinute = lands,
        )

    @Test
    fun airportSearchFindsCodesCitiesAndNamesWithoutAccents() {
        assertEquals("LIS", airports.search("lisbon").first().code)
        assertEquals("LCA", airports.search("LCA").first().code)
        assertEquals("ZRH", airports.search("zurich").first().code)
        assertEquals("CY", airports["PFO"]!!.country)
        // Bigger airports first: London's large ones before its smaller ones.
        assertEquals(setOf("LHR", "LGW"), airports.search("london").take(2).map { it.code }.toSet())
    }

    @Test
    fun pastFlightsAreTravelDaysInFlightOrder() {
        val book = DayBook.build(emptyList(), emptyList(), CountingOptions(), today, listOf(flight("LIS", "LCA", today.minusDays(3), departs = 600, lands = 1000)))
        val day = book[today.minusDays(3)]!!
        assertEquals(listOf("PT", "CY"), day.countries)
        assertEquals(DayKind.RECORDED, day.kind)
    }

    @Test
    fun futureFlightsPlanTheStayInBetween() {
        val out = flight("LIS", "BKK", today.plusDays(10), arrives = today.plusDays(11))
        val back = flight("BKK", "LIS", today.plusDays(40), arrives = today.plusDays(40))
        val book = DayBook.build(emptyList(), emptyList(), CountingOptions(), today, listOf(back, out))
        assertEquals(listOf("PT"), book[today.plusDays(10)]!!.countries)
        assertEquals(listOf("TH"), book[today.plusDays(11)]!!.countries)
        assertEquals(DayKind.PLANNED, book[today.plusDays(25)]!!.kind)
        assertEquals(listOf("TH"), book[today.plusDays(25)]!!.countries)
        assertEquals(listOf("TH", "PT"), book[today.plusDays(40)]!!.countries)
        assertEquals(listOf(out, back), book.upcomingFlights)
    }

    @Test
    fun youStayWhereYouAreUntilTheFirstFlightLeavesFromThere() {
        val here = listOf(app.sojourn.data.db.Presence(today, "PT", app.sojourn.data.db.Source.LOCATION, 0, 0))
        val book = DayBook.build(here, emptyList(), CountingOptions(), today, listOf(flight("LIS", "LCA", today.plusDays(9))))
        assertEquals(listOf("PT"), book[today.plusDays(4)]!!.countries)
        assertEquals(DayKind.PLANNED, book[today.plusDays(4)]!!.kind)
        // From somewhere else, nothing is assumed about getting to Lisbon.
        val elsewhere = DayBook.build(here, emptyList(), CountingOptions(), today, listOf(flight("MAD", "LCA", today.plusDays(9))))
        assertNull(elsewhere[today.plusDays(4)])
    }

    @Test
    fun noStayIsImpliedWhenTheNextFlightLeavesFromElsewhere() {
        val book = DayBook.build(
            emptyList(),
            emptyList(),
            CountingOptions(),
            today,
            listOf(flight("LIS", "BKK", today.plusDays(10)), flight("SGN", "LIS", today.plusDays(40))),
        )
        assertNull(book[today.plusDays(25)])
    }

    @Test
    fun boardingPassBarcodeText() {
        // Day 316 is 12 November.
        val bcbp = "M1DOE/JANE            EABC123 LISLCATP 1234 316Y012A0001 100"
        val guess = FlightText.parse(bcbp, airports, today)
        assertEquals(FlightGuess("TP 1234", "LIS", "LCA", LocalDate.of(2026, 11, 12)), guess)
    }

    @Test
    fun bookingConfirmationText() {
        val text = """
            Your trip to Larnaca is confirmed! Booking reference: XYZ789
            Flight TP 1234 · Thu, 12 Nov 2026
            Lisbon (LIS) → Larnaca (LCA)
            Departs 10:05 · Arrives 16:40 · 1 bag included for THE trip
        """.trimIndent()
        assertEquals(FlightGuess("TP 1234", "LIS", "LCA", LocalDate.of(2026, 11, 12)), FlightText.parse(text, airports, today))
    }

    @Test
    fun numericDatesReadDayFirst() {
        val guess = FlightText.parse("FR8337 BCN-BGY on 03/12/2026", airports, today)
        assertEquals(FlightGuess("FR 8337", "BCN", "BGY", LocalDate.of(2026, 12, 3)), guess)
    }

    @Test
    fun flightNumbersAreTidied() {
        assertEquals("TP 1234", Airlines.format("tp1234"))
        assertEquals("U2 89", Airlines.format("U2 0089"))
        assertEquals("easyJet", Airlines.name("U2 89"))
    }
}
