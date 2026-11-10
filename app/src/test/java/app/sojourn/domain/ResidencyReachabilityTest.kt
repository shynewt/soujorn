package app.sojourn.domain

import app.sojourn.data.db.Plan
import app.sojourn.data.db.Presence
import app.sojourn.data.db.RuleType
import app.sojourn.data.db.Source
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResidencyReachabilityTest {
    private val today = LocalDate.of(2026, 11, 3)

    private fun book(country: String, days: Int, date: LocalDate = today, plans: List<Plan> = emptyList()): DayBook {
        val rows = (0 until days).map { Presence(date.minusDays(it.toLong()), country, Source.MANUAL) }
        return DayBook.build(rows, plans, CountingOptions(fillGaps = false), date)
    }

    private fun spain(goal: Goal = Goal.AVOID) = ResidencyEngine.rule("ES", goal)

    @Test
    fun annualDayTestIsOutOfReachEvenWithTheRestOfTheYearThere() {
        val status = RuleEngine.evaluate(spain(), book("ES", 99))
        assertEquals("Not enough days left", status.headline)
        assertEquals(Level.GOOD, status.level)
        assertTrue(status.note, status.note?.contains("31 Dec 2026") == true)
        assertTrue(status.extras.toString(), status.extras.contains("Other residency conditions may still apply"))
        assertEquals(157, status.tests.first { it.test.id == "days" }.maximum)
    }

    @Test
    fun legacyYearlyMinimumAlsoShowsThatItCannotBeReached() {
        val rule = RulePreset.CUSTOM.template("CY").copy(type = RuleType.MINIMUM, days = 183)
        val status = RuleEngine.evaluate(rule, book("CY", 99))
        assertEquals("Not reachable this tax year", status.headline)
        assertEquals(Level.ALERT, status.level)
    }

    @Test
    fun finalTaxYearDayCannotBeCountedTwice() {
        val status = RuleEngine.evaluate(spain(), book("ES", 183, date = LocalDate.of(2026, 12, 31)))
        assertEquals("Not enough days left", status.headline)
        assertEquals(183, status.tests.first { it.test.id == "days" }.maximum)
    }

    @Test
    fun keepGoalMakesAnUnreachableDayTestAnAlert() {
        val status = RuleEngine.evaluate(spain(Goal.KEEP), book("ES", 99))
        assertEquals("Not enough days left", status.headline)
        assertEquals(Level.ALERT, status.level)
    }

    @Test
    fun exactlyEnoughRemainingDaysStillShowsTheTarget() {
        val status = RuleEngine.evaluate(spain(), book("ES", 126))
        assertEquals("57 days left", status.headline)
        assertEquals(184, status.tests.first { it.test.id == "days" }.maximum)
    }

    @Test
    fun todayCanStillCountWhenItHasNotBeenCountedYet() {
        val rows = (1L..125L).map { Presence(today.minusDays(it), "ES", Source.MANUAL) }
        val book = DayBook.build(rows, emptyList(), CountingOptions(fillGaps = false), today)
        val status = RuleEngine.evaluate(spain(), book)
        assertEquals("58 days left", status.headline)
        assertEquals(184, status.tests.first { it.test.id == "days" }.maximum)
    }

    @Test
    fun oneDayShortIsOutOfReach() {
        val status = RuleEngine.evaluate(spain(), book("ES", 125))
        assertEquals("Not enough days left", status.headline)
    }

    @Test
    fun declaredResidencyStillTakesPrecedence() {
        val rule = spain()
        val params = ResidencyParams.decode(rule.params)!!.copy(answers = mapOf("interests" to true))
        val status = RuleEngine.evaluate(rule.copy(params = params.encode()), book("ES", 99))
        assertEquals("Tax resident in 2026", status.headline)
        assertEquals(Level.ALERT, status.level)
    }

    @Test
    fun aLowerReachableTestRemainsTheTarget() {
        val status = RuleEngine.evaluate(ResidencyEngine.rule("CY", Goal.KEEP), book("CY", 20))
        assertEquals("40 days to go", status.headline)
        assertFalse(status.tests.first { it.test.id == "sixty" }.outOfReach)
        assertTrue(status.tests.first { it.test.id == "days" }.outOfReach)
    }

    @Test
    fun alreadyReachedDayTestsStayReached() {
        val status = RuleEngine.evaluate(spain(), book("ES", 184))
        assertEquals("Tax resident in 2026", status.headline)
        assertFalse(status.tests.first { it.test.id == "days" }.outOfReach)
    }

    @Test
    fun maximumUsesTheTaxYearEndRatherThanDecember() {
        val rule = ResidencyEngine.rule("CY", Goal.KEEP).copy(yearStart = 406)
        val status = RuleEngine.evaluate(rule, book("CY", 20))
        assertEquals("40 days to go", status.headline)
        assertEquals(173, status.tests.first { it.test.id == "days" }.maximum)
    }

    @Test
    fun rollingTestsAreNotRuledOutUsingAnAnnualDayBudget() {
        val status = RuleEngine.evaluate(ResidencyEngine.rule("PT", Goal.AVOID), book("PT", 99))
        assertEquals(null, status.dayLimit)
        assertFalse(status.tests.first { it.test.id == "days" }.outOfReach)
    }

    @Test
    fun appliedSporadicAbsencesCannotBeRuledOutByPhysicalDaysAlone() {
        val rule = spain()
        val params = ResidencyParams.decode(rule.params)!!.copy(sporadic = SporadicOptions(apply = true))
        val status = RuleEngine.evaluate(rule.copy(params = params.encode()), book("ES", 99))
        assertFalse(status.tests.first { it.test.id == "days" }.outOfReach)
    }

    @Test
    fun optionalAbsenceCalculationDoesNotOverrideTheChosenDayCount() {
        val status = ResidencySamples.spain(withAbsences = true)
        assertEquals("Not enough days left", status.headline)
        assertEquals(Level.GOOD, status.level)
        val summary = requireNotNull(status.dayLimit)
        assertEquals(99, summary.used)
        assertEquals(134, summary.projected)
        assertEquals(237, summary.withAbsences)
        assertTrue(summary.needsAbsenceReview)
    }

    @Test
    fun enabledAbsenceCountingStillUsesTheHigherCount() {
        val status = ResidencySamples.spain(withAbsences = true, sporadic = SporadicOptions(apply = true))
        assertEquals("Tax resident in 2026", status.headline)
        assertEquals(Level.ALERT, status.level)
        assertEquals(237, status.tests.first { it.test.id == "days" }.used)
        assertEquals(null, status.dayLimit)
    }

    @Test
    fun anAbsenceThatCannotChangeTheOutcomeDoesNotCreateAWarning() {
        val summary = ResidencyDayLimit(used = 99, projected = 134, needed = 184, maximum = 157, withAbsences = 119)
        assertFalse(summary.needsAbsenceReview)
        assertTrue(summary.copy(ongoingAbsence = 70).needsAbsenceReview)
    }

    @Test
    fun keepGoalAlsoUsesTheChosenCountingMethod() {
        val status = ResidencySamples.spain(goal = Goal.KEEP, withAbsences = true)
        assertEquals("Not enough days left", status.headline)
        assertEquals(Level.ALERT, status.level)
    }

    @Test
    fun plannedTotalRemainsVisibleWhenTheTestIsOutOfReach() {
        val plans = listOf(Plan(country = "ES", start = today.plusDays(1), end = today.plusDays(35)))
        val status = RuleEngine.evaluate(spain(), book("ES", 99, plans = plans))
        assertEquals("Not enough days left", status.headline)
        assertTrue(status.extras.toString(), status.extras.contains("With plans: 134 days total (35 planned) in 2026"))
    }

    @Test
    fun aForecastWithoutADateDoesNotSayNull() {
        // The substantial presence test has no single day it's met on.
        val plans = listOf(Plan(country = "US", start = today.plusDays(1), end = LocalDate.of(2026, 12, 31)))
        val status = RuleEngine.evaluate(ResidencyEngine.rule("US", Goal.AVOID), book("US", 150, plans = plans))
        val forecast = requireNotNull(status.forecast)
        assertFalse(forecast.text, forecast.text.contains("null"))
        assertEquals("Plans meet the substantial presence test in 2026", forecast.text)
    }
}
