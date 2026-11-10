package app.sojourn.domain

import app.sojourn.data.db.Plan
import app.sojourn.data.db.Presence
import app.sojourn.data.db.Source
import java.time.LocalDate

object ResidencySamples {
    fun spain(goal: Goal = Goal.AVOID, withAbsences: Boolean = false, sporadic: SporadicOptions = SporadicOptions()): RuleStatus {
        val today = LocalDate.of(2026, 11, 3)
        fun stay(country: String, from: LocalDate, to: LocalDate) =
            generateSequence(from) { it.plusDays(1) }.takeWhile { it <= to }.map { Presence(it, country, Source.MANUAL) }.toList()

        val rows = if (withAbsences) {
            stay("ES", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 19)) +
                stay("CY", LocalDate.of(2026, 2, 20), LocalDate.of(2026, 7, 7)) +
                stay("ES", LocalDate.of(2026, 7, 8), LocalDate.of(2026, 8, 25)) +
                stay("CY", LocalDate.of(2026, 8, 26), today)
        } else {
            stay("ES", today.minusDays(98), today)
        }
        val plan = Plan(country = "ES", start = today.plusDays(1), end = today.plusDays(35))
        val book = DayBook.build(rows, listOf(plan), CountingOptions(fillGaps = false), today)
        val rule = ResidencyEngine.rule("ES", goal)
        val params = requireNotNull(ResidencyParams.decode(rule.params)).copy(sporadic = sporadic)
        return RuleEngine.evaluate(rule.copy(params = params.encode()), book)
    }
}
