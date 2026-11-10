package app.sojourn.domain

import app.sojourn.data.CountrySet
import app.sojourn.data.PlanEdits
import app.sojourn.data.db.Flight
import app.sojourn.data.db.Plan
import app.sojourn.data.db.Presence
import app.sojourn.data.db.Source
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CountingTest {
    private val today = LocalDate.of(2026, 10, 25)
    private val cy = CountrySet(setOf("CY"))

    private fun d(s: String) = LocalDate.parse(s)

    private fun stay(country: String, from: String, to: String) =
        generateSequence(d(from)) { it.plusDays(1) }.takeWhile { it <= d(to) }.map { Presence(it, country, Source.MANUAL) }.toList()

    private fun book(rows: List<Presence>, plans: List<Plan> = emptyList(), flights: List<Flight> = emptyList(), fill: Boolean = false) =
        DayBook.build(rows, plans, CountingOptions(fillGaps = fill), today, flights)

    @Test
    fun cyprusCountsArrivalButNotDeparture() {
        val c = DayCounting.ARRIVAL_DAY
        assertTrue("arrival", c.counts(listOf("GR", "CY"), cy))
        assertFalse("departure", c.counts(listOf("CY", "GR"), cy))
        assertTrue("in and out the same day", c.counts(listOf("GR", "CY", "GR"), cy))
        assertFalse("out and back the same day", c.counts(listOf("CY", "GR", "CY"), cy))
        assertTrue("whole day", c.counts(listOf("CY"), cy))
    }

    @Test
    fun policiesDisagreeOnTravelDays() {
        val departure = listOf("CY", "ES")
        assertTrue(DayCounting.ANY_PART.counts(departure, cy))
        assertFalse(DayCounting.MIDNIGHT.counts(departure, cy))
        assertTrue(DayCounting.MORNING.counts(departure, cy))
        assertFalse(DayCounting.FULL_DAYS.counts(departure, cy))
        assertTrue(DayCounting.MIDNIGHT.counts(listOf("CY", "GR", "CY"), cy))
    }

    @Test
    fun handEnteredTravelDaysAreOrderedByTheirNeighbours() {
        // Hand-entered days have no times; the day before was Cyprus and the day after Spain.
        val rows = stay("CY", "2026-03-01", "2026-03-10") + stay("ES", "2026-03-10", "2026-03-12")
        val book = book(rows)
        assertEquals(listOf("CY", "ES"), book[d("2026-03-10")]!!.path)
        // So the departure day isn't a Cyprus day, but is a Spanish one.
        val summary = book.summary(d("2026-03-01"), d("2026-03-12"))
        assertEquals(9, summary.countries.single { it.country == "CY" }.days)
        assertEquals(10, summary.countries.single { it.country == "CY" }.present)
        assertEquals(3, summary.countries.single { it.country == "ES" }.days)
    }

    @Test
    fun aDayTripAwayIsLeavingAndComingBack() {
        val rows = stay("CY", "2026-03-01", "2026-03-05") + Presence(d("2026-03-03"), "GR", Source.MANUAL)
        val day = book(rows)[d("2026-03-03")]!!
        assertEquals(listOf("CY", "GR", "CY"), day.path)
        assertFalse(DayCounting.ARRIVAL_DAY.counts(day.path, cy))
    }

    @Test
    fun airsideTransitDoesNotCount() {
        val flights = listOf(
            Flight(1, "TK1", "LIS", "IST", "PT", "TR", d("2026-05-01"), d("2026-05-01"), 7 * 60, 13 * 60),
            Flight(2, "TK2", "IST", "BKK", "TR", "TH", d("2026-05-01"), d("2026-05-01"), 15 * 60, 23 * 60),
        )
        val book = book(stay("PT", "2026-04-28", "2026-04-30"), flights = flights)
        val day = book[d("2026-05-01")]!!
        assertEquals(listOf("PT", "TR", "TH"), day.path)
        assertEquals(setOf("TR"), day.transit)
        assertFalse(book.counts(day.date, CountrySet(setOf("TR")), withPlans = false))
    }

    @Test
    fun cyprusSixtyDayRuleNeedsItsConditions() {
        val rows = stay("CY", "2026-01-01", "2026-03-31") + stay("PT", "2026-04-01", "2026-06-30")
        val rule = RulePreset.AVOID_RESIDENCY.template("CY")
        val unanswered = RuleEngine.evaluate(rule, book(rows))
        assertEquals(Level.CAUTION, unanswered.level)
        assertTrue(unanswered.headline, unanswered.headline.startsWith("Resident if"))

        fun answered(work: Boolean, home: Boolean): RuleStatus {
            val params = ResidencyParams.decode(rule.params)!!.copy(answers = mapOf("cy_work" to work, "cy_home" to home))
            return RuleEngine.evaluate(rule.copy(params = params.encode()), book(rows))
        }
        assertEquals(Level.ALERT, answered(work = true, home = true).level)
        val noHome = answered(work = true, home = false)
        assertEquals(Level.GOOD, noHome.level)
        assertEquals("Not enough days left", noHome.headline)
        assertTrue(noHome.tests.single { it.test.id == "days" }.outOfReach)
        // The meter marks both thresholds.
        assertEquals(listOf(60, 184), answered(work = true, home = true).meter!!.marks.map { it.at })
    }

    @Test
    fun sixtyDayRuleFailsWhenAnotherCountryHadMoreThan183Days() {
        val rows = stay("CY", "2026-01-01", "2026-03-05") + stay("PT", "2026-03-06", "2026-10-01")
        val rule = RulePreset.AVOID_RESIDENCY.template("CY")
        val params = ResidencyParams.decode(rule.params)!!.copy(answers = mapOf("cy_work" to true, "cy_home" to true))
        val status = RuleEngine.evaluate(rule.copy(params = params.encode()), book(rows))
        val sixty = status.tests.single { it.test.id == "sixty" }
        assertTrue(sixty.daysMet)
        assertFalse(sixty.met)
        assertTrue(sixty.failing.single().startsWith("Portugal had"))
    }

    @Test
    fun spainSporadicAbsencesAddClosedTripsOnly() {
        // Spain, a 20-day trip to Morocco and back, Spain again, then away for good on 1 Sep.
        val rows = stay("ES", "2026-01-01", "2026-03-01") + stay("MA", "2026-03-02", "2026-03-21") +
            stay("ES", "2026-03-22", "2026-04-30") + stay("FR", "2026-05-01", "2026-12-31").filter { it.day <= today }
        val book = book(rows)
        val year = DateRange(d("2026-01-01"), d("2026-12-31"))
        val result = Sporadic.count(book, "ES", year, DayCounting.ANY_PART, SporadicOptions())
        assertEquals(20, result.days)
        // An absence longer than 183 days isn't sporadic.
        assertEquals(0, Sporadic.count(book, "ES", year, DayCounting.ANY_PART, SporadicOptions(maxRun = 10)).days)
        val status = RuleEngine.evaluate(RulePreset.AVOID_RESIDENCY.template("ES"), book)
        assertTrue(status.extras.first(), status.extras.first().startsWith("Counting sporadic absences: 120 days"))
    }

    @Test
    fun spainAddsNoAbsencesWithACertificateButStillCountsThem() {
        // Living in Cyprus with trips to Spain.
        val rows = stay("ES", "2026-01-01", "2026-03-01") + stay("CY", "2026-03-02", "2026-05-31") + stay("ES", "2026-06-01", "2026-07-31")
        val rule = RulePreset.AVOID_RESIDENCY.template("ES")
        val plain = RuleEngine.evaluate(rule, book(rows))
        assertTrue(plain.extras.joinToString(), plain.extras.any { it.startsWith("Counting sporadic absences: 212 days") })
        // Tax resident in Cyprus but no answer about the certificate: added, with a nudge to say so.
        val cypriot = RuleEngine.evaluate(rule, book(rows), Traveller(taxResidence = "CY"))
        assertTrue(cypriot.extras.joinToString(), cypriot.extras.any { it.startsWith("Counting sporadic absences: 212 days") })
        assertTrue(cypriot.extras.joinToString(), cypriot.extras.any { it.startsWith("You're tax resident in Cyprus: with its tax residence certificate") })
        // Applied to the rule, the absences make a Cyprus resident Spanish; a certificate stops that, and the count stays in view.
        val applied = ResidencyParams.fromCatalogue("ES", Goal.AVOID).let { it.copy(sporadic = it.sporadic!!.copy(apply = true)) }
        val strict = rule.copy(params = applied.encode())
        assertEquals("Tax resident in 2026", RuleEngine.evaluate(strict, book(rows), Traveller(taxResidence = "CY")).headline)
        val certified = rule.copy(params = applied.copy(sporadic = applied.sporadic!!.copy(certificate = true)).encode())
        val withCertificate = RuleEngine.evaluate(certified, book(rows), Traveller(taxResidence = "CY"))
        assertFalse(withCertificate.headline.startsWith("Tax resident"))
        assertTrue(withCertificate.extras.joinToString(), withCertificate.extras.any { it.startsWith("Sporadic absences: 91 days abroad, not added: you can prove tax residence in Cyprus") })
        // A tax haven also asks for 183 days there.
        val haven = RuleEngine.evaluate(certified, book(rows), Traveller(taxResidence = "BM"))
        assertTrue(haven.extras.joinToString(), haven.extras.any { it.startsWith("Bermuda is on Spain's list of tax havens") })
    }

    @Test
    fun irelandAddsTwoYears() {
        val rows = stay("IE", "2025-01-01", "2025-05-31") + stay("IE", "2026-01-01", "2026-05-01")
        val status = RuleEngine.evaluate(RulePreset.AVOID_RESIDENCY.template("IE"), book(rows))
        val two = status.tests.single { it.test.id == "two" }
        assertEquals(151 + 121, two.used)
        assertFalse(two.daysMet)
    }

    @Test
    fun ukTiesLowerTheThreshold() {
        assertEquals(16, ResidencyEngine.ukThreshold(4, leaver = true))
        assertEquals(121, ResidencyEngine.ukThreshold(1, leaver = true))
        assertEquals(null, ResidencyEngine.ukThreshold(1, leaver = false))
    }

    @Test
    fun journeyStaysNeverOverlap() {
        // A long stay in Portugal with a week in Spain inside it: three stays, two crossings.
        val rows = stay("PT", "2026-06-01", "2026-06-30") + stay("ES", "2026-06-10", "2026-06-16")
        val journey = book(rows).journey()
        val stays = journey.filterIsInstance<Leg.StayLeg>()
        assertEquals(listOf("PT", "ES", "PT"), stays.map { it.country })
        stays.zipWithNext().forEach { (a, b) -> assertTrue(a.end < b.start) }
        assertEquals(2, journey.filterIsInstance<Leg.Crossing>().size)
    }

    @Test
    fun aSmallerRangeInsideAPlanSplitsItInThree() {
        val big = Plan(1, "TH", d("2026-11-01"), d("2026-11-30"))
        val change = PlanEdits.remove(listOf(big), d("2026-11-10"), d("2026-11-15"), null) +
            PlanEdits.Change(emptyList(), listOf(Plan(country = "VN", start = d("2026-11-10"), end = d("2026-11-15"))))
        assertEquals(listOf(1L), change.removed)
        assertEquals(
            listOf("TH 2026-11-01..2026-11-09", "TH 2026-11-16..2026-11-30", "VN 2026-11-10..2026-11-15"),
            change.added.map { "${it.country} ${it.start}..${it.end}" },
        )
        val book = book(emptyList(), plans = change.added)
        assertEquals(listOf("TH", "VN", "TH"), book.journey().filterIsInstance<Leg.StayLeg>().map { it.country })
    }

    @Test
    fun plannedStaysBeatTheStaysFlightsImply() {
        val flights = listOf(
            Flight(1, "A1", "LIS", "BKK", "PT", "TH", d("2026-11-01"), d("2026-11-01"), 600, 1300),
            Flight(2, "A2", "BKK", "LIS", "TH", "PT", d("2026-11-30"), d("2026-11-30"), 600, 1300),
        )
        val plans = listOf(Plan(1, "LA", d("2026-11-10"), d("2026-11-12")), Plan(2, "", d("2026-11-20"), d("2026-11-21"), cleared = true))
        val book = book(stay("PT", "2026-10-20", "2026-10-25"), plans, flights)
        assertEquals(listOf("TH"), book[d("2026-11-05")]!!.countries)
        assertEquals(listOf("LA"), book[d("2026-11-11")]!!.countries)
        assertEquals(null, book[d("2026-11-20")])
        assertEquals(PlanOrigin.BETWEEN_FLIGHTS, book[d("2026-11-05")]!!.origins["TH"])
    }
}
