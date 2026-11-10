package app.sojourn.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.sojourn.AppContainer
import app.sojourn.data.Change
import app.sojourn.data.db.DocumentKind
import app.sojourn.data.db.Flight
import app.sojourn.data.db.Plan
import app.sojourn.domain.RuleStatus
import app.sojourn.ui.flights.FlightEditorSheet
import app.sojourn.ui.plan.PlanEditorSheet
import app.sojourn.ui.theme.CountryPalette
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The four places the app is organised in; see docs/ui-redesign.md. */
enum class AppTab { TODAY, CALENDAR, PLAN, EVIDENCE }

/** A part of Settings to open at. */
enum class SettingsSection { TRACKING, COUNTING, ABOUT_YOU, NOTIFICATIONS, APPEARANCE, DATA }

/** What a new piece of evidence starts with when added from somewhere that knows. */
data class EvidenceDraft(
    val day: LocalDate? = null,
    val until: LocalDate? = null,
    val country: String? = null,
    val kind: DocumentKind? = null,
    val flightId: Long? = null,
)

/** Where the Calendar tab should go when another screen sends you there. */
data class CalendarRequest(val day: LocalDate? = null, val list: Boolean = false, val at: Long = System.nanoTime())

/**
 * Everything a screen can open, wherever it is: other tabs, pushed screens, and the editors
 * shared by the whole app. Screens use this instead of being handed callbacks, so any text that
 * names a place in the app can link to it.
 */
interface AppActions {
    fun tab(tab: AppTab)
    fun calendar(day: LocalDate? = null, list: Boolean = false)
    fun settings(section: SettingsSection? = null)
    fun rules(ruleId: Long? = null)
    fun import()
    fun history()
    fun feie()
    fun evidencePack(year: Int)

    /** Opens the flight editor: [flight] to edit, or a new flight, on [date] if given. */
    fun flight(flight: Flight? = null, date: LocalDate? = null)

    /** Opens the stay editor: [plan] to edit, or a new stay from [start]. */
    fun planStay(plan: Plan? = null, start: LocalDate? = null)

    fun addEvidence(draft: EvidenceDraft = EvidenceDraft())
}

val LocalAppActions = staticCompositionLocalOf<AppActions> { NoActions }

/** For previews and screenshot tests, where nothing navigates. */
object NoActions : AppActions {
    override fun tab(tab: AppTab) = Unit
    override fun calendar(day: LocalDate?, list: Boolean) = Unit
    override fun settings(section: SettingsSection?) = Unit
    override fun rules(ruleId: Long?) = Unit
    override fun import() = Unit
    override fun history() = Unit
    override fun feie() = Unit
    override fun evidencePack(year: Int) = Unit
    override fun flight(flight: Flight?, date: LocalDate?) = Unit
    override fun planStay(plan: Plan?, start: LocalDate?) = Unit
    override fun addEvidence(draft: EvidenceDraft) = Unit
}

/** Every country's colour, the same on every screen: ranked by all the days spent there. */
val LocalCountryColors = staticCompositionLocalOf<Map<String, Color>> { emptyMap() }

/** A country's colour, or the colour for "other" when it has none. */
@Composable
@ReadOnlyComposable
fun countryColor(code: String): Color = LocalCountryColors.current[code] ?: MaterialTheme.colorScheme.outline

/** The editors open over the whole app, and what they need. */
sealed interface Editor {
    data class FlightEdit(val flight: Flight?, val date: LocalDate?) : Editor
    data class StayEdit(val plan: Plan) : Editor
    data class EvidenceAdd(val draft: EvidenceDraft) : Editor
}

/**
 * State the whole app shares: the editor open over everything, the country colours, the
 * airports and countries to offer first, and Undo for every change, wherever it was made.
 */
class ShellViewModel(val container: AppContainer) : ViewModel() {
    private val repository = container.repository
    private val _editor = MutableStateFlow<Editor?>(null)
    val editor: StateFlow<Editor?> = _editor.asStateFlow()

    private val _calendar = MutableStateFlow<CalendarRequest?>(null)
    val calendarRequests: StateFlow<CalendarRequest?> = _calendar.asStateFlow()

    val colors: StateFlow<Map<String, Color>> = repository.book.map { book ->
        val ranked = book.years().takeIf { it.isNotEmpty() }?.let { years ->
            book.summary(LocalDate.of(years.min(), 1, 1), LocalDate.of(years.max(), 12, 31)).countries.sortedByDescending { it.total }.map { it.country }
        }.orEmpty()
        CountryPalette.assign(ranked)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    data class Picks(val airports: List<String> = emptyList(), val countries: List<String> = emptyList())

    val picks: StateFlow<Picks> = combine(repository.flights, repository.book) { flights, book ->
        Picks(
            airports = flights.sortedByDescending { it.departureDate }.flatMap { listOf(it.destination, it.origin) }.distinct().take(8),
            countries = book.recentCountries(),
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Picks())

    fun open(editor: Editor) {
        _editor.value = editor
    }

    fun close() {
        _editor.value = null
    }

    fun showCalendar(request: CalendarRequest) {
        _calendar.value = request
    }

    fun calendarHandled() {
        _calendar.value = null
    }

    fun saveFlight(flight: Flight) = launchAndClose { repository.saveFlight(flight) }

    fun deleteFlight(flight: Flight) = launchAndClose { repository.deleteFlight(flight.id) }

    suspend fun previewFlight(flight: Flight): List<RuleStatus> = repository.previewFlight(flight)

    fun savePlan(plan: Plan) = launchAndClose { repository.savePlan(plan) }

    fun deletePlan(plan: Plan) = launchAndClose { repository.deletePlan(plan.id) }

    suspend fun previewPlan(plan: Plan): List<RuleStatus> = repository.previewPlan(plan)

    private fun launchAndClose(block: suspend () -> Unit) {
        close()
        viewModelScope.launch { block() }
    }

    /** Each change made from now on, to offer Undo for it. */
    val newChanges = repository.changes.drop(1).map { it.firstOrNull() }

    /** The change before this session's newest, so the first emission isn't taken as new. */
    suspend fun latestId(): Long? = repository.changes.first().firstOrNull()?.id

    fun undo(change: Change) {
        viewModelScope.launch { repository.revert(change) }
    }
}

/**
 * Shows "Set 1–3 Mar to Spain · Undo" after every change to days, plans and flights, from any
 * screen, and reverts it on Undo. Reverting is itself a change, so it gets no Undo of its own.
 */
@Composable
fun UndoChanges(shell: ShellViewModel, snackbar: SnackbarHostState) {
    LaunchedEffect(shell) {
        var seen = shell.latestId()
        shell.newChanges.collect { change ->
            if (change == null || change.id == seen) return@collect
            seen = change.id
            if (change.label.startsWith("Reverted") || change.isEmpty) return@collect
            launch {
                val result = snackbar.showSnackbar(change.label, actionLabel = "Undo", withDismissAction = true, duration = SnackbarDuration.Long)
                if (result == SnackbarResult.ActionPerformed) shell.undo(change)
            }
        }
    }
}

/** The flight and stay editors and the evidence form, open over whichever screen asked. */
@Composable
fun SharedEditors(shell: ShellViewModel) {
    val editor by shell.editor.collectAsStateWithLifecycle()
    val picks by shell.picks.collectAsStateWithLifecycle()
    when (val open = editor) {
        is Editor.FlightEdit -> FlightEditorSheet(
            initial = open.flight ?: open.date?.let {
                Flight(number = "", origin = "", destination = "", originCountry = "", destinationCountry = "", departureDate = it, arrivalDate = it)
            },
            recentAirports = picks.airports,
            preview = shell::previewFlight,
            onDismiss = shell::close,
            onSave = shell::saveFlight,
            onDelete = shell::deleteFlight,
        )
        is Editor.StayEdit -> PlanEditorSheet(
            initial = open.plan,
            recents = picks.countries,
            preview = shell::previewPlan,
            onDismiss = shell::close,
            onSave = shell::savePlan,
            onDelete = shell::deletePlan,
        )
        is Editor.EvidenceAdd -> app.sojourn.ui.evidence.AddEvidenceSheet(open.draft, onDismiss = shell::close)
        null -> Unit
    }
}
