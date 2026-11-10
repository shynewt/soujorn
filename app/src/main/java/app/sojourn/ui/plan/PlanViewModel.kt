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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
    /** Plans whose dates passed without being recorded, waiting for a yes or no. */
    val unconfirmed: List<Plan> = emptyList(),
    val statuses: List<RuleStatus> = emptyList(),
    val recents: List<String> = emptyList(),
    val flights: List<Flight> = emptyList(),
    /** Airports of past and planned flights, latest first, for quick picks. */
    val recentAirports: List<String> = emptyList(),
)

class PlanViewModel(container: AppContainer) : ViewModel() {
    private val repository = container.repository
    private val year = MutableStateFlow(LocalDate.now().year)

    val state: StateFlow<PlanState> = combine(repository.book, repository.plans, repository.statuses, year, ::planState)
        .flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlanState())

    fun selectYear(value: Int) {
        year.value = value
    }

    fun save(plan: Plan) {
        viewModelScope.launch { repository.savePlan(plan) }
    }

    fun delete(plan: Plan) {
        viewModelScope.launch { repository.deletePlan(plan.id) }
    }

    fun confirm(plan: Plan) {
        viewModelScope.launch { repository.confirmPlan(plan) }
    }

    suspend fun preview(plan: Plan): List<RuleStatus> = repository.previewPlan(plan)

    fun saveFlight(flight: Flight) {
        viewModelScope.launch { repository.saveFlight(flight) }
    }

    fun deleteFlight(flight: Flight) {
        viewModelScope.launch { repository.deleteFlight(flight.id) }
    }

    suspend fun previewFlight(flight: Flight): List<RuleStatus> = repository.previewFlight(flight)
}

fun planState(book: DayBook, allPlans: List<Plan>, statuses: List<RuleStatus>, year: Int): PlanState {
    // Cleared plans only keep flights from implying a stay; they aren't stays to show.
    val plans = allPlans.filter { !it.cleared }
    val today = book.today
    val from = LocalDate.of(year, 1, 1)
    val to = LocalDate.of(year, 12, 31)
    val summary = book.summary(from, to)
    val future = book.between(maxOf(from, today.plusDays(1)), to)
    val futureDays = if (today >= to) 0 else java.time.temporal.ChronoUnit.DAYS.between(maxOf(from, today.plusDays(1)), to).toInt() + 1
    val untracked = if (book.isEmpty || today < from) 0 else book.gaps(maxOf(from, book.firstRecorded ?: today), minOf(to, today)).sumOf { it.days }
    return PlanState(
        loaded = true,
        today = today,
        year = year,
        summary = summary,
        open = futureDays - future.count { it.kind == DayKind.PLANNED },
        untracked = untracked,
        upcoming = plans.filter { it.end >= today }.sortedBy { it.start },
        unconfirmed = plans.filter { plan ->
            plan.start < today && book.between(plan.start, minOf(plan.end, today.minusDays(1))).let { days ->
                days.size < java.time.temporal.ChronoUnit.DAYS.between(plan.start, minOf(plan.end, today.minusDays(1))).toInt() + 1
            }
        },
        statuses = statuses.filter { it.applies },
        recents = book.recentCountries(),
        flights = book.upcomingFlights,
        recentAirports = book.flights.asReversed().flatMap { listOf(it.destination, it.origin) }.distinct().take(8),
    )
}
