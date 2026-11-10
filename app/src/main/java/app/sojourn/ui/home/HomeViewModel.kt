package app.sojourn.ui.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.sojourn.AppContainer
import app.sojourn.data.Settings
import app.sojourn.data.db.Flight
import app.sojourn.data.geo.WorldMapData
import app.sojourn.domain.Day
import app.sojourn.domain.DayBook
import app.sojourn.domain.RuleStatus
import app.sojourn.domain.Summary
import app.sojourn.tracking.Tracking
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class HomeState(
    val loaded: Boolean = false,
    val today: LocalDate = LocalDate.now(),
    /** null shows every year at once. */
    val year: Int? = LocalDate.now().year,
    val years: List<Int> = emptyList(),
    val summary: Summary? = null,
    /** Days the summary could have covered so far, to show how complete it is. */
    val elapsedDays: Int = 0,
    val current: Day? = null,
    val streak: Int = 0,
    val untracked: Int = 0,
    val statuses: List<RuleStatus> = emptyList(),
    val recents: List<String> = emptyList(),
    val settings: Settings = Settings(),
    val hasData: Boolean = false,
    /** The next flight within two months, for the card under "now". */
    val nextFlight: Flight? = null,
)

class HomeViewModel(private val container: AppContainer) : ViewModel() {
    private val repository = container.repository
    private val year = MutableStateFlow<Int?>(LocalDate.now().year)
    val map = MutableStateFlow<WorldMapData?>(null)

    val state: StateFlow<HomeState> = combine(repository.book, repository.statuses, repository.settings, year, ::homeState)
        .flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeState())

    init {
        viewModelScope.launch { map.value = withContext(Dispatchers.IO) { container.worldMap } }
    }

    fun selectYear(value: Int?) {
        year.value = value
    }

    fun checkNow(context: Context) = Tracking.checkNow(context)

    fun setToday(country: String) {
        viewModelScope.launch { repository.addToDay(LocalDate.now(), country) }
    }
}

fun homeState(book: DayBook, statuses: List<RuleStatus>, settings: Settings, year: Int?): HomeState {
    val today = book.today
    val first = book.firstRecorded ?: today
    val from = if (year == null) first else LocalDate.of(year, 1, 1)
    val to = if (year == null) today else minOf(LocalDate.of(year, 12, 31), today)
    val summary = book.summary(from, to)
    val current = book.current()
    val country = current?.last
    return HomeState(
        loaded = true,
        today = today,
        year = year,
        years = book.years().filter { it <= today.year },
        summary = summary,
        elapsedDays = if (to < from) 0 else java.time.temporal.ChronoUnit.DAYS.between(from, to).toInt() + 1,
        current = current,
        streak = country?.let { book.streak(it, current.date) } ?: 0,
        untracked = if (book.isEmpty) 0 else book.gaps(maxOf(from, first), to).sumOf { it.days },
        statuses = statuses,
        recents = book.recentCountries(),
        settings = settings,
        hasData = !book.isEmpty,
        nextFlight = book.upcomingFlights.firstOrNull { it.departureDate <= today.plusDays(60) },
    )
}
