package app.sojourn.ui.imports

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.sojourn.AppContainer
import app.sojourn.data.db.Document
import app.sojourn.data.db.DocumentKind
import app.sojourn.data.db.Presence
import app.sojourn.data.db.Source
import app.sojourn.data.RangeEdit
import app.sojourn.data.travel.TravelImporter
import app.sojourn.data.travel.RawKind
import app.sojourn.data.travel.TripKind
import app.sojourn.data.travel.TripScan
import app.sojourn.data.travel.UnreadEvent
import app.sojourn.tracking.FoundDay
import app.sojourn.tracking.LocationFileImporter
import app.sojourn.tracking.PhotoScanner
import java.time.LocalDate
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A run of consecutive days a source places in one country. [backed] days are already recorded
 * there, so the source becomes their supporting evidence; [photos] link the photos of each day.
 */
data class FoundStay(
    val country: String,
    val start: LocalDate,
    val end: LocalDate,
    val days: List<LocalDate>,
    val newDays: Int,
    val backed: Int = 0,
    val photos: Map<LocalDate, List<String>> = emptyMap(),
)

sealed interface ImportPhase {
    data class Idle(val error: String? = null) : ImportPhase

    /** [progress] is null while the size of the work isn't known yet. */
    data class Scanning(val title: String, val detail: String, val progress: Float?) : ImportPhase

    data class Review(
        val stays: List<FoundStay>,
        /** Where the stays came from, e.g. "From 1,204 photos with a location". */
        val origin: String,
        val source: Source,
        val selected: Set<Int>,
        val onlyEmpty: Boolean = true,
        val known: Set<LocalDate>,
        /** Day and country pairs already recorded, which the found days back up. */
        val knownPairs: Set<Pair<LocalDate, String>> = emptySet(),
    ) : ImportPhase

    /** Trips found in calendars, passes or emails, waiting for approval. */
    data class Trips(
        val items: List<TripItem>,
        val origin: String,
        val selected: Set<Int>,
        /** True when the user has no names set, so other people's bookings can't be told apart. */
        val namesUnknown: Boolean,
        /** Flight events whose airports couldn't be read. */
        val unread: List<UnreadEvent> = emptyList(),
        /** Copies of the same flight already saved, merged before this review. */
        val duplicatesRemoved: Int = 0,
    ) : ImportPhase

    data class Done(val added: Int, val what: String = "days", val skipped: Int = 0, val duplicatesRemoved: Int = 0, val backedUp: Int = 0, val evidence: Int = 0) : ImportPhase
}

class ImportViewModel(private val container: AppContainer) : ViewModel() {
    private val photos = PhotoScanner(container.context) { container.locator }
    private val files = LocationFileImporter(container.context) { container.locator }
    private val travel = TravelImporter(container.context) { container.airports }
    private val _phase = MutableStateFlow<ImportPhase>(ImportPhase.Idle())
    val phase: StateFlow<ImportPhase> = _phase
    private var job: Job? = null

    fun defaultRange(): Pair<LocalDate, LocalDate> {
        val today = LocalDate.now()
        return today.minusYears(2) to today.minusDays(1)
    }

    fun denied() {
        _phase.value = ImportPhase.Idle("Sojourn needs access to photos and their locations to do this.")
    }

    fun reset() {
        job?.cancel()
        _phase.value = ImportPhase.Idle()
    }

    fun scanPhotos(from: LocalDate, to: LocalDate) = run("Reading photos") {
        val days = photos.scan(from, to) { done, total ->
            _phase.value = ImportPhase.Scanning("Reading photos", if (total == 0) "Looking through your library…" else "$done of $total", if (total == 0) null else done / total.toFloat())
        }
        review(days, "From ${count(days.sumOf { it.countries.values.sum() })} photos with a location", Source.PHOTO)
    }

    /** Flight events in the phone's calendars, like those Gmail adds from booking emails. */
    fun scanCalendar(from: LocalDate, to: LocalDate) = run("Reading your calendars") {
        val scan = travel.calendar(from, to) { _phase.value = ImportPhase.Scanning("Reading your calendars", "${(it * 100).toInt()}%", it) }
        reviewTrips(scan)
    }

    fun calendarDenied() {
        _phase.value = ImportPhase.Idle("Sojourn needs to read your calendars to find flights in them. Nothing leaves the phone.")
    }

    /** Text shared from another app, e.g. a booking email's text. */
    fun scanText(text: String, html: String?) = run("Reading") {
        reviewTrips(TripScan(travel.text(text, html), 1, "shared text"))
    }

    /** Every file in a chosen folder, as if each were chosen: a folder of boarding passes. */
    fun scanFolder(tree: Uri?) {
        tree ?: return
        run("Reading the folder") {
            val uris = withContext(Dispatchers.IO) { travel.folder(tree) }
            if (uris.isEmpty()) throw java.io.IOException("That folder is empty")
            readFiles(uris, "Reading ${uris.size} files")
        }
    }

    fun scanFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val title = if (uris.size == 1) "Reading the file" else "Reading ${uris.size} files"
        run(title) { readFiles(uris, title) }
    }

    private suspend fun readFiles(uris: List<Uri>, title: String) {
        // Passes, calendars and emails hold trips; tracks and Timeline exports hold locations.
        // A mixed batch reads its trips, and says how many location files to import alone.
        val trips = withContext(Dispatchers.IO) { uris.filter { travel.isTravel(it) } }
        if (trips.isNotEmpty()) {
            val scan = travel.files(trips) { _phase.value = ImportPhase.Scanning(title, "${(it * 100).toInt()}%", it) }
            val others = uris.size - trips.size
            reviewTrips(if (others == 0) scan else scan.copy(what = scan.what + " (${count(others)} location ${if (others == 1) "file" else "files"} left out: import those on their own)"))
        } else {
            val result = files.read(uris) { _phase.value = ImportPhase.Scanning(title, "${(it * 100).toInt()}%", it) }
            val from = if (result.files == 1) "" else " in ${result.files} files"
            review(result.days, "From ${count(result.points)} location points$from", Source.IMPORT)
        }
    }

    private fun run(title: String, work: suspend () -> Unit) {
        job?.cancel()
        _phase.value = ImportPhase.Scanning(title, "Getting started…", null)
        job = viewModelScope.launch {
            try {
                work()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _phase.value = ImportPhase.Idle(error.message ?: "Couldn't read that")
            }
        }
    }

    private suspend fun review(days: List<FoundDay>, origin: String, source: Source) {
        // Only the past is history; a camera with a wrong clock shouldn't add future days.
        val today = LocalDate.now()
        val past = days.filter { it.date <= today }
        val known = if (past.isEmpty()) emptySet() else container.repository.recordedDays(past.first().date, past.last().date)
        val pairs = if (past.isEmpty()) emptySet() else container.repository.recordedPairs(past.first().date, past.last().date)
        val stays = group(past, known, pairs)
        _phase.value = ImportPhase.Review(
            stays = stays,
            origin = origin,
            source = source,
            selected = stays.indices.filter { stays[it].newDays > 0 || stays[it].backed > 0 }.toSet(),
            known = known,
            knownPairs = pairs,
        )
    }

    val settings = container.repository.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), app.sojourn.data.Settings())
    private var lastScan: TripScan? = null

    /** Saves the user's names and checks the found trips against them again. */
    fun setNames(names: Set<String>) {
        viewModelScope.launch {
            container.repository.settingsStore.update { it.copy(travellerNames = names) }
            lastScan?.let { reviewTrips(it) }
        }
    }

    private suspend fun reviewTrips(scan: TripScan) {
        lastScan = scan
        // Earlier imports could save a flight twice; those copies are merged before comparing.
        val cleaned = container.repository.removeDuplicateFlights()
        val settings = container.repository.settingsStore.current()
        val items = prepareTripItems(
            trips = scan.trips,
            airports = container.airports,
            existingFlights = container.repository.currentBook().flights,
            travellerNames = settings.travellerNames,
        )
        _phase.value = ImportPhase.Trips(
            items = items,
            origin = "From ${count(scan.looked)} ${scan.what}",
            selected = items.indices.filter { items[it].selectedByDefault }.toSet(),
            namesUnknown = settings.travellerNames.isEmpty() && items.any { it.trip.passenger != null },
            unread = scan.unread,
            duplicatesRemoved = cleaned + ((_phase.value as? ImportPhase.Trips)?.duplicatesRemoved ?: 0),
        )
    }

    fun toggleTrip(index: Int) {
        val trips = _phase.value as? ImportPhase.Trips ?: return
        if (!trips.items[index].usable) return
        _phase.value = trips.copy(selected = if (index in trips.selected) trips.selected - index else trips.selected + index)
    }

    /** Flights become flights; other trips and stays become days or plans in their countries. */
    fun importTrips() {
        val trips = _phase.value as? ImportPhase.Trips ?: return
        val chosen = trips.items.filterIndexed { i, item -> i in trips.selected && item.usable }
        val label = "Imported ${chosen.size} ${if (chosen.size == 1) "trip" else "trips"}"
        viewModelScope.launch { container.repository.recordChange(label) { addTrips(trips, chosen) } }
    }

    private suspend fun addTrips(trips: ImportPhase.Trips, chosen: List<TripItem>) {
        val repository = container.repository
        val flights = chosen.mapNotNull { it.trip.asFlight(it.fromCountry, it.toCountry) }
        chosen.filter { it.trip.kind != TripKind.FLIGHT }.forEach { item ->
            val trip = item.trip
            when (trip.kind) {
                TripKind.STAY -> repository.editRange(trip.departure, trip.arrival, RangeEdit.ADD, item.toCountry!!, Source.IMPORT)
                else -> {
                    repository.editRange(trip.departure, trip.departure, RangeEdit.ADD, item.fromCountry!!, Source.IMPORT)
                    repository.editRange(trip.arrival, trip.arrival, RangeEdit.ADD, item.toCountry!!, Source.IMPORT)
                }
            }
        }
        // Flights are checked against the saved ones once more as they're written, so an
        // import run twice, or a flight already entered by hand, never ends up there twice.
        // Flights already saved are passed too: they only gain what these copies add (booking
        // reference, passenger, seat), never a second copy.
        val known = trips.items.filter { it.duplicate && it.usable }.mapNotNull { it.trip.asFlight(it.fromCountry, it.toCountry) }
        val result = repository.importFlights(flights + known).let { it.copy(skipped = it.skipped - known.size) }
        // Keep what each trip was found in as evidence, for the trips added and for those
        // already there: a calendar event, a booking's text, a boarding pass file.
        val kept = keepEvidence(chosen + trips.items.filter { it.duplicate && it.usable })
        val added = chosen.size - flights.size + result.added
        _phase.value = ImportPhase.Done(added, if (added == 1) "trip" else "trips", skipped = result.skipped, duplicatesRemoved = result.duplicatesRemoved, evidence = kept)
    }

    /**
     * Keeps each trip's own original, and those of its copies found in the same batch (a
     * flight's PDF and its screenshot), as evidence on its days. Returns how many were new.
     */
    private suspend fun keepEvidence(items: List<TripItem>): Int {
        val copies = HashMap<String, String?>()
        var kept = 0
        for (item in items.distinctBy { it.trip.key }) {
            val places = listOfNotNull(
                item.fromCountry?.takeIf { item.trip.kind != TripKind.STAY }?.let { item.trip.departure to it },
                item.toCountry?.let { item.trip.arrival to it },
            ).distinct()
            val flightId = item.trip.asFlight(item.fromCountry, item.toCountry)?.let { container.repository.savedFlight(it)?.id }
            for (original in (listOf(item.trip) + item.copies).distinctBy { listOf(it.rawKind, it.file ?: it.raw) }) {
                kept += container.keeper.keep(item.trip, original, places, copies, flightId)
            }
        }
        return kept
    }

    fun toggle(index: Int) {
        val review = _phase.value as? ImportPhase.Review ?: return
        _phase.value = review.copy(selected = if (index in review.selected) review.selected - index else review.selected + index)
    }

    fun setOnlyEmpty(value: Boolean) {
        val review = _phase.value as? ImportPhase.Review ?: return
        _phase.value = review.copy(onlyEmpty = value)
    }

    /** New days the chosen stays would add; days already recorded in the same country are only backed up. */
    fun daysToAdd(stays: List<FoundStay>, onlyEmpty: Boolean): Int {
        val review = _phase.value as? ImportPhase.Review ?: return 0
        return stays.sumOf { stay ->
            stay.days.count { day -> (day to stay.country) !in review.knownPairs && (!onlyEmpty || day !in review.known) }
        }
    }

    /** Days already recorded in the same country that the chosen stays back up. */
    fun daysToBack(stays: List<FoundStay>): Int = stays.sumOf { it.backed }

    /**
     * Adds the chosen stays: new days where allowed, supporting evidence on days already
     * recorded there, and for photos, a link to one photo per day to show for it.
     */
    fun import(stays: List<FoundStay>, onlyEmpty: Boolean) {
        val review = _phase.value as? ImportPhase.Review ?: return
        val rows = stays.flatMap { stay -> stay.days.map { Presence(it, stay.country, review.source) } }
        val label = if (review.source == Source.PHOTO) "Imported days from photos" else "Imported a location history"
        viewModelScope.launch {
            val repository = container.repository
            val (added, backed) = repository.addFound(rows, onlyEmpty, label)
            if (review.source == Source.PHOTO && rows.isNotEmpty()) {
                val recorded = repository.recordedPairs(rows.minOf { it.day }, rows.maxOf { it.day })
                stays.forEach { stay ->
                    stay.photos.forEach { (day, links) ->
                        if ((day to stay.country) !in recorded || links.isEmpty()) return@forEach
                        // The middle photo of the day is the default; the others can be chosen instead.
                        val chosen = links[links.size / 2]
                        repository.addDocument(
                            Document(
                                day = day,
                                country = stay.country,
                                kind = DocumentKind.PHOTO,
                                title = "Photo",
                                uri = chosen,
                                candidates = links.filter { it != chosen }.joinToString("\n"),
                            ),
                        )
                    }
                }
            }
            _phase.value = ImportPhase.Done(added, backedUp = backed)
        }
    }

    private fun count(n: Int) = String.format(Locale.getDefault(), "%,d", n)

    companion object {
        /** Consecutive days per country; a few days without data in between don't break a stay. */
        fun group(days: List<FoundDay>, known: Set<LocalDate>, pairs: Set<Pair<LocalDate, String>> = emptySet()): List<FoundStay> {
            val open = HashMap<String, MutableList<LocalDate>>()
            val photos = days.associate { it.date to it.photos }
            val result = mutableListOf<FoundStay>()
            fun close(country: String) {
                val run = open.remove(country) ?: return
                result += FoundStay(
                    country, run.first(), run.last(), run.toList(),
                    newDays = run.count { it !in known },
                    backed = run.count { (it to country) in pairs },
                    photos = run.mapNotNull { day -> photos[day]?.get(country)?.let { day to it } }.toMap(),
                )
            }
            var previous: LocalDate? = null
            for (day in days.sortedBy { it.date }) {
                if (previous != null && day.date > previous.plusDays(3)) open.keys.toList().forEach(::close)
                open.keys.filter { it !in day.countries }.forEach(::close)
                for (country in day.countries.keys) {
                    val run = open.getOrPut(country) { mutableListOf() }
                    val last = run.lastOrNull()
                    if (last != null) {
                        var fill = last.plusDays(1)
                        while (fill < day.date) {
                            run += fill
                            fill = fill.plusDays(1)
                        }
                    }
                    run += day.date
                }
                previous = day.date
            }
            open.keys.toList().forEach(::close)
            return result.sortedByDescending { it.start }
        }
    }
}
