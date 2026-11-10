package app.sojourn.ui.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.sojourn.AppContainer
import app.sojourn.data.CalendarView
import app.sojourn.data.RangeEdit
import app.sojourn.data.Settings
import app.sojourn.data.db.Document
import app.sojourn.domain.DayBook
import app.sojourn.domain.Leg
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Every change the Calendar can make, from any of its views and the day sheet. One
 * implementation serves them all, so the day sheet does the same wherever it's opened from.
 * Each change is recorded by the repository, which is what gives it the app-wide Undo.
 */
interface CalendarActions {
    /** Sets, adds, removes or clears a country on every day of a range, past or planned. */
    fun edit(from: LocalDate, to: LocalDate, edit: RangeEdit, country: String?)

    /** Puts a recorded travel day's countries in the order they were visited. */
    fun reorder(date: LocalDate, path: List<String>) {}

    /** Says a whole stay was somewhere else. */
    fun changeStay(leg: Leg.StayLeg, country: String) {}

    fun setView(view: CalendarView) {}

    /** Shows another of a day's photos as its evidence. */
    fun choosePhoto(document: Document, uri: String) {}

    /** Takes a document out of the evidence. */
    fun deleteDocument(document: Document) {}
}

class CalendarViewModel(container: AppContainer) : ViewModel(), CalendarActions {
    private val repository = container.repository
    val book: StateFlow<DayBook?> = repository.book.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val settings: StateFlow<Settings> = repository.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Settings())
    val documents: StateFlow<List<Document>> = repository.documents.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The list view, worked out whenever the days change. */
    val stays: StateFlow<StayListState> = repository.book.map(::stayList)
        .flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StayListState())

    override fun edit(from: LocalDate, to: LocalDate, edit: RangeEdit, country: String?) {
        viewModelScope.launch { repository.editRange(from, to, edit, country) }
    }

    override fun reorder(date: LocalDate, path: List<String>) {
        viewModelScope.launch { repository.reorderDay(date, path) }
    }

    override fun changeStay(leg: Leg.StayLeg, country: String) {
        viewModelScope.launch { repository.changeStay(leg.start, leg.end, leg.country, country) }
    }

    override fun setView(view: CalendarView) {
        viewModelScope.launch { repository.settingsStore.update { it.copy(calendarView = view) } }
    }

    override fun choosePhoto(document: Document, uri: String) {
        viewModelScope.launch { repository.choosePhoto(document, uri) }
    }

    override fun deleteDocument(document: Document) {
        viewModelScope.launch { repository.deleteDocument(document) }
    }
}
