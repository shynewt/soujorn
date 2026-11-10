package app.sojourn.ui.evidence

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.sojourn.AppContainer
import app.sojourn.data.Countries
import app.sojourn.data.CountrySet
import app.sojourn.data.Settings
import app.sojourn.data.db.Document
import app.sojourn.data.db.DocumentKind
import app.sojourn.data.db.EvidenceGroup
import app.sojourn.data.db.Flight
import app.sojourn.data.db.Rule
import app.sojourn.data.db.RuleType
import app.sojourn.domain.DayBook
import app.sojourn.domain.EvidenceCount
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

/** A country counted in the year: its days, and how each is backed up. */
data class Coverage(val country: String, val days: Int, val evidence: EvidenceCount)

/**
 * A document worth having for the whole year, ticked when one is kept. [prominent] when it
 * matters most: a residence certificate from home while another country's rule counts you.
 */
data class CheckItem(
    val kind: DocumentKind,
    val title: String,
    val why: String,
    val country: String?,
    val kept: Boolean,
    val prominent: Boolean = false,
)

/** A month's photos, shown as one row so hundreds of them don't bury the other documents. */
data class PhotoMonth(val month: YearMonth, val photos: List<Document>) {
    val countries: List<String> get() = photos.map { it.country }.distinct()
}

/** A group of the year's documents: Travel, Being there, Home… */
data class DocumentGroup(val group: EvidenceGroup, val kept: List<Kept>, val photos: List<PhotoMonth> = emptyList()) {
    val size: Int get() = kept.size + photos.sumOf { it.photos.size }
}

/** Everything the Evidence tab shows for one year. */
data class EvidenceState(
    val year: Int,
    val years: List<Int>,
    val today: LocalDate,
    val coverage: List<Coverage> = emptyList(),
    val checklist: List<CheckItem> = emptyList(),
    val groups: List<DocumentGroup> = emptyList(),
    val flights: Map<Long, Flight> = emptyMap(),
    val usTaxpayer: Boolean = false,
    val loading: Boolean = false,
) {
    val isEmpty: Boolean get() = coverage.isEmpty() && groups.isEmpty()
    val documentCount: Int get() = groups.sumOf { it.size }
}

/** Whether a document covers any of [from]..[to]: one day in it, or a period overlapping it. */
fun Document.overlaps(from: LocalDate, to: LocalDate): Boolean = day <= to && (until ?: day) >= from

/** The year to open on: last year's evidence is what's asked for until the end of March. */
fun defaultEvidenceYear(today: LocalDate, years: List<Int>): Int =
    if (today.monthValue <= 3 && (today.year - 1) in years) today.year - 1 else today.year

/** The countries whose residency rules count you, other than where you're tax resident. */
fun residentElsewhere(settings: Settings, rules: List<Rule>): List<String> {
    val home = settings.taxResidence ?: return emptyList()
    return rules.filter { it.enabled && it.type == RuleType.RESIDENCY }
        .flatMap { CountrySet.parse(it.countries).codes }.distinct().filter { it != home }
}

/** Builds the tab's state for [year] from the day book, the kept documents, Settings and the rules. */
fun evidenceState(book: DayBook, documents: List<Document>, settings: Settings, rules: List<Rule>, year: Int?): EvidenceState {
    val today = book.today
    val years = (book.years() + documents.map { it.day.year }).filter { it <= today.year }.distinct().sortedDescending().ifEmpty { listOf(today.year) }
    val chosen = year ?: defaultEvidenceYear(today, years)
    val from = LocalDate.of(chosen, 1, 1)
    val to = LocalDate.of(chosen, 12, 31)
    val coverage = book.summary(from, minOf(to, today)).countries.filter { it.days > 0 }.sortedByDescending { it.days }
        .map { Coverage(it.country, it.days, book.evidence(from, to, CountrySet(setOf(it.country)))) }
    val inYear = documents.filter { it.overlaps(from, to) }

    val home = settings.taxResidence ?: coverage.firstOrNull()?.country
    val elsewhere = residentElsewhere(settings, rules)
    fun kept(vararg kinds: DocumentKind) = inYear.any { it.kind in kinds }
    val checklist = listOf(
        CheckItem(
            DocumentKind.RESIDENCE_CERTIFICATE,
            "Tax residence certificate",
            if (elsewhere.isNotEmpty() && home != null) {
                "From ${Countries.name(home)}: the strongest answer if ${elsewhere.joinToString(" or ") { Countries.name(it) }} counts you as resident."
            } else {
                "From the tax office where you're resident, for the year."
            },
            settings.taxResidence,
            kept(DocumentKind.RESIDENCE_CERTIFICATE),
            prominent = elsewhere.isNotEmpty(),
        ),
        CheckItem(DocumentKind.LEASE, "Rental contract or deed", "Shows a home${home?.let { " in ${Countries.name(it)}" }.orEmpty()}.", home, kept(DocumentKind.LEASE)),
        CheckItem(DocumentKind.UTILITY_BILL, "Utility bills", "Electricity, water or internet used at home through the year.", home, kept(DocumentKind.UTILITY_BILL)),
        CheckItem(DocumentKind.WORK, "Work and tax records", "A work contract, payslips or a tax return.", home, kept(DocumentKind.WORK, DocumentKind.TAX_RETURN)),
    ).sortedByDescending { it.prominent }

    val groups = EvidenceGroup.entries.mapNotNull { group ->
        val (photos, others) = inYear.filter { it.kind.group == group }.partition { it.kind == DocumentKind.PHOTO }
        val months = photos.groupBy { YearMonth.from(it.day) }.toSortedMap().map { (month, list) -> PhotoMonth(month, list) }
        val kept = bundle(others).sortedWith(compareBy({ it.first.day }, { it.kind }))
        DocumentGroup(group, kept, months).takeIf { it.size > 0 }
    }
    return EvidenceState(
        year = chosen,
        years = years,
        today = today,
        coverage = coverage,
        checklist = checklist,
        groups = groups,
        flights = book.flights.associateBy { it.id },
        usTaxpayer = settings.usTaxpayer,
    )
}

/** The Evidence tab's year, and what it holds. */
class EvidenceViewModel(container: AppContainer) : ViewModel() {
    private val repository = container.repository
    private val year = MutableStateFlow<Int?>(null)

    val state: StateFlow<EvidenceState> = combine(repository.book, repository.documents, repository.settings, repository.rules, year) { book, documents, settings, rules, year ->
        evidenceState(book, documents, settings, rules, year)
    }.flowOn(Dispatchers.Default).stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        LocalDate.now().let { EvidenceState(it.year, listOf(it.year), it, loading = true) },
    )

    fun choose(year: Int) {
        this.year.value = year
    }
}
