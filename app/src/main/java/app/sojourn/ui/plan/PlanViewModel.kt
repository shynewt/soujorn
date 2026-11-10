package app.sojourn.ui.plan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.sojourn.AppContainer
import app.sojourn.data.db.Flight
import app.sojourn.data.db.Plan
import app.sojourn.domain.DayBook
import app.sojourn.domain.DayKind
import app.sojourn.domain.RuleStatus
import app.sojourn.domain.Summary
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

data class PlanState(
    val loaded: Boolean = false,
    val today: LocalDate = LocalDate.now(),
    val year: Int = LocalDate.now().year,
    val summary: Summary? = null,
    /** Days of the year that are neither behind us nor planned. */
    val open: Int = 0,
    /** Past days of the year with nothing recorded. */
    val untracked: Int = 0,
    val upcoming: List<Plan> = emptyList(),
    val statuses: List<RuleStatus> = emptyList(),
    val flights: List<Flight> = emptyList(),
)

/**
 * The year ahead. Editing happens in the shell's shared editors, so this only reads; past plans
 * waiting for a yes or no are Today's to ask about.
 */
class PlanViewModel(container: AppContainer) : ViewModel() {
    private val repository = container.repository
    private val year = MutableStateFlow(LocalDate.now().year)

    val state: StateFlow<PlanState> = combine(repository.book, repository.plans, repository.statuses, year, ::planState)
        .flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlanState())

    fun selectYear(value: Int) {
        year.value = value
    }
}

fun planState(book: DayBook, allPlans: List<Plan>, statuses: List<RuleStatus>, year: Int): PlanState {
    // Cleared plans only keep flights from implying a stay; they aren't stays to show.
    val plans = allPlans.filter { !it.cleared }
    val today = book.today
    val from = LocalDate.of(year, 1, 1)
    val to = LocalDate.of(year, 12, 31)
    val summary = book.summary(from, to)
    val future = book.between(maxOf(from, today.plusDays(1)), to)
    val futureDays = if (today >= to) 0 else ChronoUnit.DAYS.between(maxOf(from, today.plusDays(1)), to).toInt() + 1
    val untracked = if (book.isEmpty || today < from) 0 else book.gaps(maxOf(from, book.firstRecorded ?: today), minOf(to, today)).sumOf { it.days }
    return PlanState(
        loaded = true,
        today = today,
        year = year,
        summary = summary,
        open = futureDays - future.count { it.kind == DayKind.PLANNED },
        untracked = untracked,
        upcoming = plans.filter { it.end >= today }.sortedBy { it.start },
        statuses = statuses.filter { it.applies },
        flights = book.upcomingFlights,
    )
}
