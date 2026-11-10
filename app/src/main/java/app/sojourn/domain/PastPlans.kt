package app.sojourn.domain

import app.sojourn.data.db.Plan
import java.time.temporal.ChronoUnit

/**
 * Stays that were planned for days now past but not all recorded: Today asks whether they
 * happened, so a plan never quietly stands in for days nobody confirmed. Cleared plans only
 * keep flights from implying a stay, so they're never asked about.
 */
fun unconfirmedPlans(book: DayBook, plans: List<Plan>): List<Plan> {
    val yesterday = book.today.minusDays(1)
    return plans.filter { plan ->
        if (plan.cleared || plan.start > yesterday) return@filter false
        val end = minOf(plan.end, yesterday)
        book.between(plan.start, end).size < ChronoUnit.DAYS.between(plan.start, end).toInt() + 1
    }.sortedBy { it.start }
}
