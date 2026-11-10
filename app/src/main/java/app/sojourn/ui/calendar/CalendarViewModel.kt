package app.sojourn.ui.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.sojourn.AppContainer
import app.sojourn.data.CalendarView
import app.sojourn.data.RangeEdit
import app.sojourn.data.Settings
import app.sojourn.data.db.Document
import app.sojourn.data.db.Flight
import app.sojourn.domain.DayBook
import app.sojourn.domain.RuleStatus
import java.time.LocalDate
import app.sojourn.data.Countries
import app.sojourn.data.Repository
import app.sojourn.ui.components.Format
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

interface CalendarActions {
    /** Sets, adds, removes or clears a country on every day of a range, past or planned. */
    fun edit(from: LocalDate, to: LocalDate, edit: RangeEdit, country: String?)

    /** Puts a recorded travel day's countries in the order they were visited. */
    fun reorder(date: LocalDate, path: List<String>) {}

    fun setView(view: CalendarView) {}

    /** Puts back what the last range edit changed. */
    fun undo() {}

    /** Shows another of a day's photos as its evidence. */
    fun choosePhoto(document: Document, uri: String) {}

    /** Takes a document out of the evidence. */
    fun deleteDocument(document: Document) {}

    /** Forgets the last edit, once its undo is no longer offered. */
    fun dismissUndo() {}

    fun saveFlight(flight: Flight) {}

    fun deleteFlight(flight: Flight) {}

    suspend fun previewFlight(flight: Flight): List<RuleStatus> = emptyList()
}

class CalendarViewModel(container: AppContainer) : ViewModel(), CalendarActions {
    private val repository = container.repository
    val book: StateFlow<DayBook?> = repository.book.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val settings: StateFlow<Settings> = repository.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Settings())

    val documents: StateFlow<List<Document>> = repository.documents.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    override fun choosePhoto(document: Document, uri: String) {
        viewModelScope.launch { repository.choosePhoto(document, uri) }
    }

    override fun deleteDocument(document: Document) {
        viewModelScope.launch { repository.deleteDocument(document) }
    }

    /** The last range edit, with what it replaced, while it can still be undone. */
    private val _lastEdit = MutableStateFlow<LastEdit?>(null)
    val lastEdit: StateFlow<LastEdit?> = _lastEdit

    override fun edit(from: LocalDate, to: LocalDate, edit: RangeEdit, country: String?) {
        viewModelScope.launch {
            val snapshot = repository.editRange(from, to, edit, country)
            _lastEdit.value = LastEdit(describeEdit(from, to, edit, country), snapshot)
        }
    }

    override fun undo() {
        val last = _lastEdit.value ?: return
        _lastEdit.value = null
        viewModelScope.launch { repository.undo(last.snapshot) }
    }

    override fun dismissUndo() {
        _lastEdit.value = null
    }

    override fun reorder(date: LocalDate, path: List<String>) {
        viewModelScope.launch { repository.reorderDay(date, path) }
    }

    override fun setView(view: CalendarView) {
        viewModelScope.launch { repository.settingsStore.update { it.copy(calendarView = view) } }
    }

    override fun saveFlight(flight: Flight) {
        viewModelScope.launch { repository.saveFlight(flight) }
    }

    override fun deleteFlight(flight: Flight) {
        viewModelScope.launch { repository.deleteFlight(flight.id) }
    }

    override suspend fun previewFlight(flight: Flight): List<RuleStatus> = repository.previewFlight(flight)
}

/** A range edit that can still be undone, and how to say what it did. */
data class LastEdit(val message: String, val snapshot: Repository.EditSnapshot)

fun describeEdit(from: LocalDate, to: LocalDate, edit: RangeEdit, country: String?): String {
    val days = Format.days(ChronoUnit.DAYS.between(from, to).toInt() + 1)
    val name = country?.let { "${Countries.flag(it)} ${Countries.name(it)}" }.orEmpty()
    return when (edit) {
        RangeEdit.SET -> "$days set to $name"
        RangeEdit.ADD -> "$name added to $days"
        RangeEdit.REMOVE -> "$name taken off $days"
        RangeEdit.CLEAR -> "$days cleared"
    }
}
