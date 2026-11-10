package app.sojourn.ui.calendar

import app.sojourn.data.CountrySet
import app.sojourn.domain.CountryRules
import app.sojourn.domain.DateRange
import app.sojourn.domain.DayBook
import app.sojourn.domain.DayCounting
import app.sojourn.domain.DayKind
import app.sojourn.domain.Leg
import app.sojourn.domain.RuleEngine
import java.time.LocalDate

/** A stay with what the list says about it. */
data class StayItem(
    val leg: Leg.StayLeg,
    val counting: DayCounting,
    /** "2026/27" and the country's counted days in that tax year up to the end of this stay. */
    val yearLabel: String,
    val yearTotal: Int,
    val now: Boolean,
)

/** One row of the list: a stay, a crossing between countries, or days nobody recorded. */
sealed interface StayListItem {
    val key: String

    data class StayRow(val stay: StayItem) : StayListItem {
        override val key = "s-${stay.leg.country}-${stay.leg.start}"
    }

    data class CrossingRow(val crossing: Leg.Crossing) : StayListItem {
        override val key = "c-${crossing.date}"
    }

    data class GapRow(val range: DateRange) : StayListItem {
        override val key = "g-${range.start}"
    }
}

/** The Calendar's list view: what's ahead, the stay you're in, and the past by year. */
data class StayListState(
    val loaded: Boolean = false,
    val today: LocalDate = LocalDate.now(),
    /** Crossings and stays still to come, latest first, so the whole list reads one way. */
    val ahead: List<StayListItem> = emptyList(),
    /** The stay you're in, or were last seen in. */
    val now: StayItem? = null,
    /** Newest year first, newest first within it. */
    val past: List<Pair<Int, List<StayListItem>>> = emptyList(),
    val recents: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = now == null && past.isEmpty() && ahead.isEmpty()
}

/** Works out the list from the days; heavy enough to keep off the main thread. */
fun stayList(book: DayBook): StayListState {
    val today = book.today
    val legs = book.journey()

    fun item(leg: Leg.StayLeg): StayItem {
        val yearStart = CountryRules[leg.country]?.yearStart ?: 101
        val window = RuleEngine.yearWindow(leg.end, yearStart)
        val counting = book.policies.of(leg.country)
        val total = book.count(window.start, leg.presentEnd.coerceAtMost(window.end), CountrySet(setOf(leg.country)), withPlans = true, counting = counting)
        return StayItem(leg, counting, RuleEngine.yearLabel(window, yearStart), total, now = today in leg.start..leg.presentEnd && leg.kind != DayKind.PLANNED)
    }

    val current = legs.filterIsInstance<Leg.StayLeg>().lastOrNull { it.start <= today && it.kind != DayKind.PLANNED }
    val ahead = legs.filter { leg ->
        when (leg) {
            is Leg.StayLeg -> leg.start > today
            is Leg.Crossing -> leg.date > today
            is Leg.GapLeg -> false
        }
    }.asReversed().map { it.toItem(::item) }
    val past = legs.filter { leg ->
        when (leg) {
            is Leg.StayLeg -> leg.start <= today && leg != current
            is Leg.Crossing -> leg.date <= today
            is Leg.GapLeg -> true
        }
    }.asReversed().map { it.toItem(::item) }
        .groupBy { item ->
            when (item) {
                is StayListItem.StayRow -> item.stay.leg.end.year
                is StayListItem.CrossingRow -> item.crossing.date.year
                is StayListItem.GapRow -> item.range.end.year
            }
        }.toList()

    return StayListState(
        loaded = true,
        today = today,
        ahead = ahead,
        now = current?.let(::item),
        past = past,
        recents = book.recentCountries(),
    )
}

private fun Leg.toItem(stay: (Leg.StayLeg) -> StayItem): StayListItem = when (this) {
    is Leg.StayLeg -> StayListItem.StayRow(stay(this))
    is Leg.Crossing -> StayListItem.CrossingRow(this)
    is Leg.GapLeg -> StayListItem.GapRow(range)
}
