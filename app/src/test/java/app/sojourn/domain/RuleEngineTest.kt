package app.sojourn.domain

import app.sojourn.data.db.Plan
import app.sojourn.data.db.Presence
import app.sojourn.data.db.Rule
import app.sojourn.data.db.RuleType
import app.sojourn.data.db.RuleWindow
import app.sojourn.data.db.Source
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleEngineTest {
    private val today = LocalDate.of(2026, 10, 25)

    private fun stay(country: String, from: LocalDate, to: LocalDate) =
        generateSequence(from) { it.plusDays(1) }.takeWhile { it <= to }.map { Presence(it, country, Source.MANUAL) }.toList()

    private fun book(rows: List<Presence>, plans: List<Plan> = emptyList()) = DayBook.build(rows, plans, CountingOptions(fillGaps = false), today)

    private val schengen = RulePreset.SCHENGEN.template()

    @Test
    fun keepingCyprusShowsPlannedTotalAfterSixtyDayTestIsMet() {
        val original = RulePreset.KEEP_RESIDENCY.template("CY")
        val params = ResidencyParams.decode(original.params)!!
        val answers = params.questions().associate { it.id to true }
        val rule = original.copy(params = params.copy(answers = answers).encode())
        val status = RuleEngine.evaluate(rule, book(
            stay("CY", today.minusDays(79), today),
            listOf(Plan(country = "CY", start = today.plusDays(1), end = today.plusDays(30))),
        ))
        assertTrue(status.met)
        assertTrue(status.extras.toString(), status.extras.any { it == "With plans: 110 days total (30 planned) in 2026" })
    }

    @Test
    fun reachedYearMinimumStillShowsPlannedTotal() {
        val rule = Rule(name = "Minimum", type = RuleType.MINIMUM, countries = "CY", days = 60, window = RuleWindow.YEAR)
        val status = RuleEngine.evaluate(rule, book(
            stay("CY", today.minusDays(79), today),
            listOf(Plan(country = "CY", start = today.plusDays(1), end = today.plusDays(30))),
        ))
        assertTrue(status.met)
        assertTrue(status.extras.toString(), status.extras.any { it == "With plans: 110 days total (30 planned) in 2026" })
    }

    @Test
    fun schengenCountsTheLast180DaysAcrossMemberStates() {
        // 30 days in France ending 100 days ago, then 20 in Spain up to today.
        val rows = stay("FR", today.minusDays(129), today.minusDays(100)) + stay("ES", today.minusDays(19), today) + stay("GB", today.minusDays(60), today.minusDays(40))
        val status = RuleEngine.evaluate(schengen, book(rows))
        assertEquals("40 days left", status.headline)
        assertEquals(Level.GOOD, status.level)
    }

    @Test
    fun schengenDaysOutsideTheWindowNoLongerCount() {
        val rows = stay("IT", today.minusDays(300), today.minusDays(211))
        val status = RuleEngine.evaluate(schengen, book(rows))
        assertEquals("90 days left", status.headline)
    }

    @Test
    fun schengenOverstayIsAnAlert() {
        val rows = stay("DE", today.minusDays(94), today)
        val status = RuleEngine.evaluate(schengen, book(rows))
        assertEquals(Level.ALERT, status.level)
        assertEquals("5 days over", status.headline)
    }

    @Test
    fun schengenMaxStayAccountsForDaysFallingOut() {
        // 80 days used, all long enough ago that they start dropping out soon.
        val rows = stay("NL", today.minusDays(179), today.minusDays(100))
        val status = RuleEngine.evaluate(schengen, book(rows))
        assertEquals("10 days left", status.headline)
        // Entering tomorrow you can stay more than 10 days because old days expire meanwhile.
        val note = status.note!!
        assertTrue(note, note.startsWith("Arrive tomorrow and you can stay"))
        val days = note.filter(Char::isDigit).toInt()
        assertTrue("stay of $days days", days > 10)
    }

    @Test
    fun schengenForecastFindsTheDayAPlanBreaksTheLimit() {
        val rows = stay("PT", today.minusDays(59), today)
        val plan = Plan(1, "ES", today.plusDays(1), today.plusDays(40))
        val status = RuleEngine.evaluate(schengen, book(rows, listOf(plan)))
        val forecast = assertNotNullAndGet(status.forecast)
        assertEquals(Level.ALERT, forecast.level)
        assertEquals(today.plusDays(31), forecast.date)
    }

    @Test
    fun taxYearUsesItsOwnStartDate() {
        val rule = RulePreset.AVOID_RESIDENCY.template("GB")
        assertEquals(406, rule.yearStart)
        // UK tax year 2026/27 runs from 6 April 2026.
        val rows = stay("GB", LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30))
        val status = RuleEngine.evaluate(rule, book(rows))
        assertTrue(status.detail, status.detail.startsWith("25 days in United Kingdom in 2026/27"))
        assertEquals(25, status.tests.single { it.test.id == "days" }.used)
    }

    @Test
    fun yearLimitForecastWarnsWhenPlansPassTheThreshold() {
        val rule = Rule(name = "PT", type = RuleType.LIMIT, countries = "PT", days = 183, window = RuleWindow.YEAR)
        val rows = stay("PT", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 15))
        val status = RuleEngine.evaluate(rule, book(rows, listOf(Plan(1, "PT", today.plusDays(1), today.plusDays(30)))))
        assertEquals(Level.ALERT, status.forecast!!.level)
    }

    @Test
    fun perStayLimitCountsTheCurrentRun() {
        val rule = RulePreset.VISA_STAY.template("TH")
        assertEquals(60, rule.days)
        val rows = stay("TH", today.minusDays(49), today)
        val status = RuleEngine.evaluate(rule, book(rows))
        assertEquals("10 days left", status.headline)
        assertEquals("Day 50 of 60 on this stay", status.detail)
    }

    @Test
    fun minimumIsMetOnceEnoughDaysAreIn() {
        val rule = RulePreset.KEEP_RESIDENCY.template("PT")
        val rows = stay("PT", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 7, 31))
        val status = RuleEngine.evaluate(rule, book(rows))
        assertTrue(status.met)
        assertEquals("Resident in 2026", status.headline)
    }

    @Test
    fun substantialPresenceWeighsEarlierYears() {
        val rule = RulePreset.US_PRESENCE.template()
        // 120 days this year, 120 last year (40 weighted), 120 the year before (20 weighted).
        val rows = stay("US", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 30)) +
            stay("US", LocalDate.of(2025, 1, 1), LocalDate.of(2025, 4, 30)) +
            stay("US", LocalDate.of(2024, 1, 1), LocalDate.of(2024, 4, 29))
        val status = RuleEngine.evaluate(rule, book(rows))
        assertFalse(status.headline, status.headline == "Test met")
        // 183 - 40 - 20 = 123 needed this year; 120 used, so 2 more days stay below it.
        assertEquals("2 days left", status.headline)
    }

    @Test
    fun feieMinimumCountsOnlyFullDaysAbroad() {
        val rule = RulePreset.FEIE.template()
        val rows = stay("MX", today.minusDays(364), today) + Presence(today.minusDays(10), "US", Source.MANUAL)
        val status = RuleEngine.evaluate(rule, book(rows))
        assertEquals("364 of 330 days in the last 365 days", status.detail)
        assertTrue(status.met)
    }

    @Test
    fun yearWindowHandlesStartsLaterInTheYear() {
        val window = RuleEngine.yearWindow(LocalDate.of(2026, 3, 1), 406)
        assertEquals(LocalDate.of(2025, 4, 6), window.start)
        assertEquals(LocalDate.of(2026, 4, 5), window.end)
    }

    @Test
    fun euCitizensAreExemptFromSchengen() {
        val rows = stay("ES", today.minusDays(99), today) + stay("PL", today.minusDays(120), today.minusDays(118))
        val status = RuleEngine.evaluate(schengen, book(rows), Traveller(citizenships = setOf("ES")))
        assertFalse(status.applies)
        assertEquals("Doesn't apply to you", status.headline)
        // Without the passport the same days are well over the limit.
        assertEquals(Level.ALERT, RuleEngine.evaluate(schengen, book(rows)).level)
    }

    @Test
    fun aResidencePermitOnlyExemptsItsOwnCountry() {
        // An American living in Portugal: Portuguese days are home, a Spanish trip still counts.
        val rows = stay("PT", today.minusDays(150), today.minusDays(11)) + stay("ES", today.minusDays(10), today)
        val traveller = Traveller(citizenships = setOf("US"), residencePermits = setOf("PT"))
        val status = RuleEngine.evaluate(schengen, book(rows), traveller)
        assertTrue(status.applies)
        assertEquals("79 days left", status.headline)
        assertEquals(setOf("PT"), status.exempt)
    }

    @Test
    fun taxRulesCountDaysAtHomeRegardlessOfPassport() {
        val rule = RulePreset.AVOID_RESIDENCY.template("ES")
        val rows = stay("ES", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31))
        val status = RuleEngine.evaluate(rule, book(rows), Traveller(citizenships = setOf("ES")))
        assertTrue(status.applies)
        assertTrue(status.detail, status.detail.startsWith("31 days in Spain in 2026"))
        assertEquals("Not enough days left", status.headline)
        assertEquals(31, status.tests.single { it.test.id == "days" }.used)
    }

    @Test
    fun visaStayInYourOwnCountryDoesNotApply() {
        val rule = RulePreset.VISA_STAY.template("TH")
        val rows = stay("TH", today.minusDays(70), today)
        assertFalse(RuleEngine.evaluate(rule, book(rows), Traveller(residencePermits = setOf("TH"))).applies)
        assertEquals(Level.ALERT, RuleEngine.evaluate(rule, book(rows), Traveller(citizenships = setOf("DE"))).level)
    }

    @Test
    fun taxThresholdsFollowEachCountry() {
        fun tests(country: String) = ResidencyParams.decode(RulePreset.AVOID_RESIDENCY.template(country).params)!!.tests
        assertEquals(180, tests("TH").first().days)
        assertEquals(182, tests("IN").first().days)
        assertEquals(401, RulePreset.AVOID_RESIDENCY.template("IN").yearStart)
        assertEquals(365, tests("NZ").first().windowDays)
        assertTrue(tests("FR").single { it.kind == TestKind.DAYS }.indicative)
        assertEquals(listOf(184, 60), tests("CY").map { it.days })
    }

    @Test
    fun residenceContinuityCountsTimeAwayFromHome() {
        val rule = RulePreset.CONTINUITY.template("PT")
        // Home until 40 days ago, then away in Thailand and Vietnam, with a travel day between.
        val rows = stay("PT", today.minusDays(200), today.minusDays(40)) + stay("TH", today.minusDays(39), today.minusDays(10)) +
            stay("VN", today.minusDays(10), today)
        val away = RuleEngine.evaluate(rule, book(rows))
        assertEquals("Away for 40 days, at most 182 in a row", away.detail)
        assertEquals("142 days left", away.headline)
        assertTrue(away.note!!.startsWith("Be back in Portugal by"))
        val home = RuleEngine.evaluate(rule, book(stay("PT", today.minusDays(5), today)))
        assertEquals("In Portugal now", home.headline)
    }

    private fun <T> assertNotNullAndGet(value: T?): T {
        assertNotNull(value)
        return value!!
    }
}
