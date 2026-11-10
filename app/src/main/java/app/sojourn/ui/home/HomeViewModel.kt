package app.sojourn.ui.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.sojourn.AppContainer
import app.sojourn.data.Settings
import app.sojourn.data.TrackingMode
import app.sojourn.data.db.Document
import app.sojourn.data.db.DocumentKind
import app.sojourn.data.db.Flight
import app.sojourn.data.db.Plan
import app.sojourn.data.db.RuleType
import app.sojourn.data.geo.WorldMapData
import app.sojourn.domain.Day
import app.sojourn.domain.DayBook
import app.sojourn.domain.Level
import app.sojourn.domain.ResidencyParams
import app.sojourn.domain.RuleStatus
import app.sojourn.domain.Summary
import app.sojourn.domain.unconfirmedPlans
import app.sojourn.tracking.Tracking
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Something on Today that needs the user, each fixed from its own row. [key] names it for
 * dismissing, and changes when the thing itself changes, so a dismissed item comes back if
 * it gets worse (more untracked days, a rule from caution to alert).
 */
sealed interface Attention {
    val key: String

    /** Whether it can be put away without acting on it; past plans need a yes or no. */
    val dismissible: Boolean get() = true

    data class Untracked(val days: Int, val year: Int) : Attention {
        override val key = "untracked-$year-$days"
    }

    data class PastPlan(val plan: Plan) : Attention {
        override val key = "plan-${plan.id}"
        override val dismissible = false
    }

    data class RuleNear(val status: RuleStatus) : Attention {
        override val key = "rule-${status.rule.id}-${status.level}"
    }

    /** No tax residence certificate kept for [year], though one is what proves [country] is home. */
    data class Certificate(val country: String, val year: Int) : Attention {
        override val key = "certificate-$year-$country"
    }

    data object TrackingOff : Attention {
        override val key = "tracking-off"
    }
}

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
    val statuses: List<RuleStatus> = emptyList(),
    val recents: List<String> = emptyList(),
    val settings: Settings = Settings(),
    val hasData: Boolean = false,
    /** The next flight within two months. */
    val nextFlight: Flight? = null,
    /** Everything that needs the user, most pressing first, before any is dismissed. */
    val attention: List<Attention> = emptyList(),
)

class HomeViewModel(private val container: AppContainer) : ViewModel() {
    private val repository = container.repository
    private val year = MutableStateFlow<Int?>(LocalDate.now().year)
    val map = MutableStateFlow<WorldMapData?>(null)

    // Dismissed items live on this phone only, beside the app's other small preferences.
    private val prefs = container.context.getSharedPreferences("today", Context.MODE_PRIVATE)
    private val _dismissed = MutableStateFlow(prefs.getStringSet(DISMISSED, emptySet()).orEmpty())
    val dismissed: StateFlow<Set<String>> = _dismissed.asStateFlow()

    private val inputs = combine(repository.book, repository.statuses, repository.settings, repository.plans, repository.documents, ::Inputs)

    val state: StateFlow<HomeState> = combine(inputs, year) { i, year -> homeState(i.book, i.statuses, i.settings, year, i.plans, i.documents) }
        .flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeState())

    private data class Inputs(val book: DayBook, val statuses: List<RuleStatus>, val settings: Settings, val plans: List<Plan>, val documents: List<Document>)

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

    fun confirmPlan(plan: Plan) {
        viewModelScope.launch { repository.confirmPlan(plan) }
    }

    fun deletePlan(plan: Plan) {
        viewModelScope.launch { repository.deletePlan(plan.id) }
    }

    fun dismiss(item: Attention) {
        _dismissed.value += item.key
        prefs.edit().putStringSet(DISMISSED, _dismissed.value).apply()
    }

    private companion object {
        const val DISMISSED = "dismissed"
    }
}

fun homeState(
    book: DayBook,
    statuses: List<RuleStatus>,
    settings: Settings,
    year: Int?,
    plans: List<Plan> = emptyList(),
    documents: List<Document> = emptyList(),
): HomeState {
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
        elapsedDays = if (to < from) 0 else ChronoUnit.DAYS.between(from, to).toInt() + 1,
        current = current,
        streak = country?.let { book.streak(it, current.date) } ?: 0,
        statuses = statuses,
        recents = book.recentCountries(),
        settings = settings,
        hasData = !book.isEmpty,
        nextFlight = book.upcomingFlights.firstOrNull { it.departureDate <= today.plusDays(60) },
        attention = attention(book, statuses, settings, plans, documents),
    )
}

/** What needs the user this year, most pressing first: rules at risk, then the record, then evidence. */
private fun attention(book: DayBook, statuses: List<RuleStatus>, settings: Settings, plans: List<Plan>, documents: List<Document>): List<Attention> = buildList {
    val today = book.today
    val year = today.year
    statuses.filter { it.applies && it.level != Level.GOOD }.sortedByDescending { it.level }.forEach { add(Attention.RuleNear(it)) }
    unconfirmedPlans(book, plans).forEach { add(Attention.PastPlan(it)) }
    // Up to yesterday: today has its own question in the hero.
    val firstRecorded = book.firstRecorded
    if (firstRecorded != null) {
        val start = maxOf(LocalDate.of(year, 1, 1), firstRecorded)
        val untracked = if (start < today) book.gaps(start, today.minusDays(1)).sumOf { it.days } else 0
        if (untracked > 0) add(Attention.Untracked(untracked, year))
    }
    missingCertificate(book, statuses, settings, documents)?.let(::add)
    if (settings.tracking == TrackingMode.OFF && !book.isEmpty) add(Attention.TrackingOff)
}

/**
 * Spain (and others) presume you resident on their days, and add trips abroad, unless you can
 * prove tax residence elsewhere, usually with that country's certificate. Asked for when your
 * tax residence is one country but a residency rule watches another, or most of this year's
 * days were spent somewhere else.
 */
private fun missingCertificate(book: DayBook, statuses: List<RuleStatus>, settings: Settings, documents: List<Document>): Attention.Certificate? {
    val home = settings.taxResidence ?: return null
    val year = book.today.year
    val start = LocalDate.of(year, 1, 1)
    val end = LocalDate.of(year, 12, 31)
    val kept = documents.any { it.kind == DocumentKind.RESIDENCE_CERTIFICATE && it.day <= end && (it.until ?: it.day) >= start }
    if (kept) return null
    val watchedElsewhere = statuses.any { status ->
        status.rule.type == RuleType.RESIDENCY && ResidencyParams.decode(status.rule.params)?.country.let { it != null && it != home }
    }
    val mostDays = book.summary(start, minOf(end, book.today)).countries.maxByOrNull { it.days }?.country
    return if (watchedElsewhere || (mostDays != null && mostDays != home)) Attention.Certificate(home, year) else null
}
