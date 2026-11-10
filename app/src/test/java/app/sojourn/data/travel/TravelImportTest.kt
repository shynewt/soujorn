package app.sojourn.data.travel

import app.sojourn.data.flights.AirportDirectory
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TravelImportTest {
    private val airports: AirportDirectory by lazy { File("src/main/assets/airports.tsv").inputStream().use(AirportDirectory::read) }

    @Test
    fun boardingPassBarcodeGivesTheFlightAndPassenger() {
        // IATA's own example: one leg, Mr Desmond Smith, Lisbon to Larnaca on day 312.
        val data = "M1SMITH/DESMOND MR    EABC123 LISLCATP 0123 312Y012A0045 100"
        val trips = Bcbp.parse(data, LocalDate.of(2026, 10, 28))
        val trip = trips.single()
        assertEquals("LIS", trip.from)
        assertEquals("LCA", trip.to)
        assertEquals("TP 123", trip.number)
        assertEquals(LocalDate.ofYearDay(2026, 312), trip.departure)
        assertEquals("SMITH/DESMOND MR", trip.passenger)
        assertEquals("ABC123", trip.reference)
    }

    @Test
    fun twoLegBoardingPass() {
        // Each leg ends with the hex length of its variable part, which the next leg follows.
        val variable = ">5180      B  "
        val data = "M2DOE/JANE            EXYZ987 MADISTTK 1858 120Y022C0012 1" + "%02X".format(variable.length) + variable +
            "XYZ987 ISTBKKTK 0068 120Y033K0013 100"
        val trips = Bcbp.parse(data, LocalDate.of(2026, 4, 1))
        assertEquals(listOf("MAD" to "IST", "IST" to "BKK"), trips.map { it.from to it.to })
    }

    @Test
    fun namesMatchWhateverTheOrderAndTitles() {
        val mine = setOf("Jane Doe")
        assertEquals(PassengerMatch.MATCH, Names.match("DOE/JANE MRS", mine))
        assertEquals(PassengerMatch.MATCH, Names.match("Jane María Doe", mine))
        assertEquals(PassengerMatch.DIFFERENT, Names.match("DOE/JOHN MR", mine))
        assertEquals(PassengerMatch.DIFFERENT, Names.match("SMITH/ANNA", mine))
        assertEquals(PassengerMatch.UNKNOWN, Names.match("DOE/JOHN", emptySet()))
        assertEquals(PassengerMatch.UNKNOWN, Names.match(null, mine))
    }

    @Test
    fun jsonLdReservationsKeepEachPassenger() {
        val html = """
            <html><head><script type="application/ld+json">
            [{"@context":"http://schema.org","@type":"FlightReservation","reservationNumber":"RXJ34P",
              "underName":{"@type":"Person","name":"Jane Doe"},
              "reservationFor":{"@type":"Flight","flightNumber":"117","airline":{"@type":"Airline","iataCode":"BA"},
                "departureAirport":{"@type":"Airport","iataCode":"LHR"},"departureTime":"2027-03-04T20:15:00+00:00",
                "arrivalAirport":{"@type":"Airport","iataCode":"JFK"},"arrivalTime":"2027-03-04T23:05:00-05:00"}},
             {"@context":"http://schema.org","@type":"FlightReservation","reservationNumber":"RXJ34P",
              "underName":{"@type":"Person","name":"Rosa Doe"},
              "reservationFor":{"@type":"Flight","flightNumber":"117","airline":{"@type":"Airline","iataCode":"BA"},
                "departureAirport":{"@type":"Airport","iataCode":"LHR"},"departureTime":"2027-03-04T20:15:00+00:00",
                "arrivalAirport":{"@type":"Airport","iataCode":"JFK"},"arrivalTime":"2027-03-04T23:05:00-05:00"}},
             {"@type":"TrainReservation","underName":"Jane Doe","reservationFor":{"@type":"TrainTrip","trainNumber":"9014",
                "departureStation":{"@type":"TrainStation","name":"Paris Gare du Nord","address":{"addressCountry":"FR"}},
                "departureTime":"2027-03-10T08:13:00+01:00",
                "arrivalStation":{"@type":"TrainStation","name":"London St Pancras","address":{"addressCountry":"United Kingdom"}},
                "arrivalTime":"2027-03-10T09:30:00+00:00"}}]
            </script></head><body>Your trip</body></html>
        """.trimIndent()
        val trips = Reservations.fromHtml(html, "Your booking")
        assertEquals(3, trips.size)
        val flight = trips.first()
        assertEquals("BA 117", flight.number)
        assertEquals(LocalDate.of(2027, 3, 4), flight.departure)
        assertEquals(20 * 60 + 15, flight.departureMinute)
        assertEquals(23 * 60 + 5, flight.arrivalMinute)
        assertEquals(listOf("Jane Doe", "Rosa Doe"), trips.take(2).map { it.passenger })
        val train = trips.last()
        assertEquals(TripKind.TRAIN, train.kind)
        assertEquals("FR", train.fromCountry)
        assertEquals("GB", train.toCountry)
    }

    @Test
    fun microdataReservationsAreReadToo() {
        val html = """
            <div itemscope itemtype="http://schema.org/FlightReservation">
              <meta itemprop="reservationNumber" content="RXJ34P"/>
              <div itemprop="underName" itemscope itemtype="http://schema.org/Person"><meta itemprop="name" content="Eva Green"/></div>
              <div itemprop="reservationFor" itemscope itemtype="http://schema.org/Flight">
                <meta itemprop="flightNumber" content="1234"/>
                <div itemprop="airline" itemscope itemtype="http://schema.org/Airline"><meta itemprop="iataCode" content="TP"/></div>
                <div itemprop="departureAirport" itemscope itemtype="http://schema.org/Airport"><meta itemprop="iataCode" content="LIS"/></div>
                <meta itemprop="departureTime" content="2026-12-01T10:05:00+00:00"/>
                <div itemprop="arrivalAirport" itemscope itemtype="http://schema.org/Airport"><meta itemprop="iataCode" content="LCA"/></div>
                <meta itemprop="arrivalTime" content="2026-12-01T16:40:00+02:00"/>
              </div>
            </div>
        """.trimIndent()
        val trip = Reservations.fromHtml(html).single()
        assertEquals("LIS", trip.from)
        assertEquals("LCA", trip.to)
        assertEquals("Eva Green", trip.passenger)
        assertEquals("TP 1234", trip.number)
    }

    @Test
    fun gmailFlightEventsBecomeFlights() {
        val event = CalendarEvent(
            title = "Flight to Lisbon (TP 1351)",
            location = "Larnaca LCA",
            start = Instant.parse("2026-11-12T08:05:00Z"),
            end = Instant.parse("2026-11-12T13:40:00Z"),
            startZone = ZoneId.of("Asia/Nicosia"),
            endZone = ZoneId.of("Europe/Lisbon"),
        )
        val trip = CalendarTrips.parse(event, airports)!!
        assertEquals("LCA", trip.from)
        assertEquals("LIS", trip.to)
        assertEquals("TP 1351", trip.number)
        assertEquals(10 * 60 + 5, trip.departureMinute)
        assertEquals(13 * 60 + 40, trip.arrivalMinute)
    }

    @Test
    fun everySameDayConnectionIsParsedRegardlessOfRouteSeparator() {
        val start = Instant.parse("2026-11-12T08:00:00Z")
        val events = listOf("LCA-LIS", "LIS -> LHR", "LHR — JFK").mapIndexed { index, route ->
            CalendarEvent(title = route, start = start.plusSeconds(index * 14400L), startZone = ZoneId.of("UTC"))
        }
        assertEquals(
            listOf("LCA" to "LIS", "LIS" to "LHR", "LHR" to "JFK"),
            events.mapNotNull { CalendarTrips.parse(it, airports) }.map { it.from to it.to },
        )
    }

    @Test
    fun differentSameDayFlightsAreNotDuplicates() {
        val date = LocalDate.of(2026, 11, 12)
        val existing = app.sojourn.data.db.Flight(
            number = "", origin = "LCA", destination = "LIS", originCountry = "CY", destinationCountry = "PT",
            departureDate = date, arrivalDate = date, departureMinute = 480,
        )
        val connection = FoundTrip(TripKind.FLIGHT, date, from = "LIS", to = "LHR", departureMinute = 900)
        assertFalse(connection.isDuplicateOf(existing))
        assertTrue(connection.copy(from = "LCA", to = "LIS", departureMinute = 480).isDuplicateOf(existing))
        assertFalse(connection.copy(from = "LCA", to = "LIS", departureMinute = 1100).isDuplicateOf(existing))
        assertTrue(connection.copy(from = "lca", to = "lis", number = "TP 1351", departureMinute = 480)
            .isDuplicateOf(existing.copy(number = "TP1351")))
        assertFalse(connection.copy(number = "TP1351").isDuplicateOf(existing.copy(number = "TP1351")))
    }

    @Test
    fun scanDeduplicationKeepsSeparateSameDayDepartures() {
        val trip = FoundTrip(TripKind.FLIGHT, LocalDate.of(2026, 11, 12), from = "LCA", to = "LIS", departureMinute = 480)
        val later = trip.copy(departureMinute = 900)
        val connection = trip.copy(from = "LIS", to = "LHR", departureMinute = 1100)
        val scan = listOf(trip, trip.copy(source = "Another calendar"), later, connection).distinctBy { it.key }
        assertEquals(listOf(trip, later, connection), scan)
    }

    @Test
    fun otherEventsAreNotFlights() {
        val event = CalendarEvent(title = "Dentist", start = Instant.parse("2026-11-12T08:05:00Z"))
        assertFalse(CalendarTrips.isFlight(event))
    }

    @Test
    fun icsEventsKeepTheirTimeZones() {
        val ics = "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:1\r\nSUMMARY:BA0117 LHR-JFK\r\nDTSTART;TZID=Europe/London:20270304T201500\r\n" +
            "DTEND;TZID=America/New_York:20270304T230500\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
        val event = Ics.parse(ics).single()
        val trip = CalendarTrips.parse(event, airports)!!
        assertEquals("LHR", trip.from)
        assertEquals("JFK", trip.to)
        assertEquals(20 * 60 + 15, trip.departureMinute)
        assertEquals(23 * 60 + 5, trip.arrivalMinute)
    }

    @Test
    fun flightEventsInOtherLanguagesFindTheirAirports() {
        val start = Instant.parse("2026-03-04T07:05:00Z")
        val gmail = CalendarEvent(
            title = "Vuelo a Londres (IB 3166)",
            location = "Aeropuerto Adolfo Suárez Madrid-Barajas",
            start = start,
            end = start.plusSeconds(2 * 3600),
            startZone = ZoneId.of("Europe/Madrid"),
            endZone = ZoneId.of("Europe/London"),
        )
        val trip = CalendarTrips.parse(gmail, airports)!!
        assertEquals("MAD" to "LHR", trip.from to trip.to)
        assertEquals("IB 3166", trip.number)
        val fromTo = CalendarEvent(title = "Vuelo de Lisboa a Múnich", start = start, startZone = ZoneId.of("Europe/Lisbon"), endZone = ZoneId.of("Europe/Berlin"))
        assertEquals("LIS" to "MUC", CalendarTrips.parse(fromTo, airports)!!.let { it.from to it.to })
        val arrow = CalendarEvent(title = "✈ Atenas → Varsovia", start = start, startZone = ZoneId.of("Europe/Athens"))
        assertEquals("ATH" to "WAW", CalendarTrips.parse(arrow, airports)!!.let { it.from to it.to })
        val german = CalendarEvent(title = "Flug nach Mailand", location = "Köln", start = start, startZone = ZoneId.of("Europe/Berlin"))
        assertEquals("CGN" to "MXP", CalendarTrips.parse(german, airports)!!.let { it.from to it.to })
    }

    @Test
    fun timeZonesGiveTheAirportsWhenNothingElseDoes() {
        val event = CalendarEvent(
            title = "Flight to Somewhere (TP 1351)",
            location = "Larnaca LCA",
            start = Instant.parse("2026-11-12T08:05:00Z"),
            end = Instant.parse("2026-11-12T13:40:00Z"),
            startZone = ZoneId.of("Asia/Nicosia"),
            endZone = ZoneId.of("Europe/Lisbon"),
        )
        val trip = CalendarTrips.parse(event, airports)!!
        assertEquals("LCA" to "LIS", trip.from to trip.to)
        assertTrue(trip.source.contains("time zone"))
    }

    @Test
    fun calendarEventsWrittenOutReadBackTheSame() {
        val event = CalendarEvent(
            title = "Vuelo a Londres (IB 3166)",
            location = "Madrid, Barajas; T4",
            description = "Seat 12A\nGate K",
            start = Instant.parse("2026-03-04T07:05:00Z"),
            end = Instant.parse("2026-03-04T09:20:00Z"),
            startZone = ZoneId.of("Europe/Madrid"),
            endZone = ZoneId.of("Europe/London"),
            id = "abc",
        )
        val back = Ics.parse("BEGIN:VCALENDAR\r\n${Ics.write(event)}END:VCALENDAR\r\n").single()
        assertEquals(event.copy(allDay = false), back)
    }

    @Test
    fun boardingPassPdfTextGivesTheFlightWhenItsBarcodeCant() {
        // A PDF page's text comes out in pieces, labels apart from what they label.
        val text = """
            BOARDING PASS
            MRS JANE DOE
            Flight
            W4 2271
            From
            Barcelona El Prat (BCN) T1
            To
            Budapest (BUD) T2B
            Date
            14/11/2026
            Departure 06:00
            Gate closes 05:30 · Seat 12A · Seq 045
        """.trimIndent()
        val trip = PrintedPass.flights(text, airports, LocalDate.of(2026, 11, 9)).single()
        assertEquals("W4 2271", trip.number)
        assertEquals("BCN", trip.from)
        assertEquals("BUD", trip.to)
        assertEquals(LocalDate.of(2026, 11, 14), trip.departure)
    }

    @Test
    fun printedPassWithoutAFlightGivesNothing() {
        assertTrue(PrintedPass.flights("Your booking ABC123 · Terms and conditions · MRS JANE DOE", airports, LocalDate.of(2026, 11, 9)).isEmpty())
    }

    /** Events people type themselves: the flight number in the title, the route in the description. */
    @Test
    fun handMadeFlightEventsReadTheirDescription() {
        val start = Instant.parse("2026-06-05T03:50:00Z")
        val zone = ZoneId.of("Europe/Madrid")
        fun read(title: String, description: String) =
            CalendarTrips.parse(CalendarEvent(title = title, description = description, start = start, end = start.plusSeconds(12600), startZone = zone), airports)
                ?.let { Triple(it.number, it.from, it.to) }
        assertEquals(Triple("FR 4178", "PFO", "WMI"), read("Flight FR 4178 to Warsaw Modlin", "Flight from Paphos to Warsaw Modlin"))
        assertEquals(Triple("FR 5881", "WAW", "PFO"), read("Flight FR 5881 to Paphos", "Flight from Warsaw Chopin to Paphos"))
        assertEquals(Triple("FR 6777", "PFO", "MLA"), read("Flight FR 6777 to Malta", "Flight from Paphos to Malta"))
        // An airline's own reminder: ICAO airline code, destination before origin.
        assertEquals(
            Triple("U2 8653", "LGW", "PFO"),
            read("Flight to Cyprus Paphos KDBRSTH", "Hi,\nYou have a flight EZY8653 to Cyprus Paphos from London Gatwick on Sun 11 October - 12:50 (local time)\n\nWe look forward to seeing you on board!"),
        )
    }

    @Test
    fun airportNamesBeatTheirCitysMainAirport() {
        assertEquals("WMI", Places.airport("Warsaw Modlin", airports))
        assertEquals("WAW", Places.airport("Warsaw", airports))
        assertEquals("LGW", Places.airport("London Gatwick", airports))
        assertEquals("PFO", Places.airport("Cyprus Paphos", airports))
        assertEquals("OKA", Places.airport("Okinawa", airports))
    }

    @Test
    fun goingToTheAirportIsNotAFlight() {
        val start = Instant.parse("2026-06-16T16:00:00Z")
        assertFalse(CalendarTrips.isFlight(CalendarEvent(title = "Take transit from Malta International Airport to Corner Hostel", start = start)))
        assertFalse(CalendarTrips.isFlight(CalendarEvent(title = "Travel to airport", start = start)))
        assertTrue(CalendarTrips.isFlight(CalendarEvent(title = "Vol", start = start)))
    }

    /** A Wizz Air pass for 12 Jan, read in November: the barcode has only "day 12". */
    @Test
    fun boardingPassYearIsThePastOneOrThePrintedOne() {
        val data = "M1DOE/JANE            ENGJY6Y BCNLCAW6 4666 012Y013B0202 100"
        val november = LocalDate.of(2026, 11, 10)
        assertEquals(LocalDate.of(2026, 1, 12), Bcbp.parse(data, november).single().departure)
        // Checked in during December for January: next year's.
        assertEquals(LocalDate.of(2027, 1, 12), Bcbp.parse(data, LocalDate.of(2026, 12, 20)).single().departure)
        // The date printed on the pass wins.
        assertEquals(LocalDate.of(2025, 1, 12), Bcbp.parse(data, november, listOf(LocalDate.of(2025, 1, 12))).single().departure)
        val page = "BOARDING PASS\n$data\nFLIGHT DATE: 12 / JAN / 2026\nBCN - LCA\nW6 4666"
        assertEquals(listOf(LocalDate.of(2026, 1, 12)), app.sojourn.data.flights.FlightText.dates(page))
        assertEquals(LocalDate.of(2026, 1, 12), PrintedPass.flights(page, airports, LocalDate.of(2027, 6, 1)).single().departure)
    }
}
