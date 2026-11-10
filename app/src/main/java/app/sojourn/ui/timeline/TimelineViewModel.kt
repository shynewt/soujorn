package app.sojourn.ui.timeline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.sojourn.AppContainer
import app.sojourn.data.CountrySet
import app.sojourn.data.RangeEdit
import app.sojourn.data.db.Flight
import app.sojourn.domain.CountryRules
import app.sojourn.domain.DateRange
import app.sojourn.domain.DayBook
import app.sojourn.domain.DayCounting
import app.sojourn.domain.DayKind
import app.sojourn.domain.Leg
import app.sojourn.domain.RuleEngine
import app.sojourn.domain.RuleStatus
import app.sojourn.ui.calendar.CalendarActions
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A stay with what the timeline says about it. */
data class StayItem(
    val leg: Leg.StayLeg,
    val counting: DayCounting,
    /** "2026/27" and the country's counted days in that tax year up to the end of this stay. */
    val yearLabel: String,
    val yearTotal: Int,
    val now: Boolean,
)

sealed interface TimelineItem {
    val key: String

    data class StayRow(val stay: StayItem) : TimelineItem {
        override val key = "s-${stay.leg.country}-${stay.leg.start}"
    }

    data class CrossingRow(val crossing: Leg.Crossing) : TimelineItem {
        override val key = "c-${crossing.date}"
    }

    data class GapRow(val range: DateRange) : TimelineItem {
        override val key = "g-${range.start}"
    }
}

data class TimelineState(
    val loaded: Boolean = false,
    val today: LocalDate = LocalDate.now(),
    /** Crossings and stays still to come, latest first, so the whole timeline reads one way. */
    val ahead: List<TimelineItem> = emptyList(),
    /** The stay you're in, or were last seen in. */
    val now: StayItem? = null,
    /** Newest year first, newest first within it. */
    val past: List<Pair<Int, List<TimelineItem>>> = emptyList(),
    val recents: List<String> = emptyList(),
    val recentAirports: List<String> = emptyList(),
    val book: DayBook? = null,
)

class TimelineViewModel(container: AppContainer) : ViewModel(), CalendarActions {
    private val repository = container.repository

    val state: StateFlow<TimelineState> = repository.book.map(::timelineState)
        .flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TimelineState())

    fun fill(gap: DateRange, country: String) {
        viewModelScope.launch { repository.setRange(gap.start, gap.end, country) }
    }

    fun change(leg: Leg.StayLeg, country: String) {
        viewModelScope.launch { repository.changeStay(leg.start, leg.end, leg.country, country) }
    }

    fun remove(leg: Leg.StayLeg) {
        viewModelScope.launch { repository.removeStay(leg.start, leg.end, leg.country) }
    }

    override fun edit(from: LocalDate, to: LocalDate, edit: RangeEdit, country: String?) {
        viewModelScope.launch { repository.editRange(from, to, edit, country) }
    }

    override fun reorder(date: LocalDate, path: List<String>) {
        viewModelScope.launch { repository.reorderDay(date, path) }
    }

    override fun saveFlight(flight: Flight) {
        viewModelScope.launch { repository.saveFlight(flight) }
    }

    override fun deleteFlight(flight: Flight) {
        viewModelScope.launch { repository.deleteFlight(flight.id) }
    }

    override suspend fun previewFlight(flight: Flight): List<RuleStatus> = repository.previewFlight(flight)
}

fun timelineState(book: DayBook): TimelineState {
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
                is TimelineItem.StayRow -> item.stay.leg.end.year
                is TimelineItem.CrossingRow -> item.crossing.date.year
                is TimelineItem.GapRow -> item.range.end.year
            }
        }.toList()

    return TimelineState(
        loaded = true,
        today = today,
        ahead = ahead,
        now = current?.let(::item),
        past = past,
        recents = book.recentCountries(),
        recentAirports = book.flights.asReversed().flatMap { listOf(it.destination, it.origin) }.distinct().take(8),
        book = book,
    )
}

private fun Leg.toItem(stay: (Leg.StayLeg) -> StayItem): TimelineItem = when (this) {
    is Leg.StayLeg -> TimelineItem.StayRow(stay(this))
    is Leg.Crossing -> TimelineItem.CrossingRow(this)
    is Leg.GapLeg -> TimelineItem.GapRow(range)
}
