package app.sojourn.data

import app.sojourn.data.travel.FoundTrip
import androidx.room.withTransaction
import app.sojourn.data.BackupCodec.flightEntities
import app.sojourn.data.BackupCodec.planEntities
import app.sojourn.data.BackupCodec.presence
import app.sojourn.data.BackupCodec.ruleEntities
import app.sojourn.data.db.Document
import app.sojourn.data.db.DocumentKind
import app.sojourn.data.db.Flight
import app.sojourn.data.db.alternatives
import java.io.File
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.StateFlow
import app.sojourn.data.flights.FlightMatch
import app.sojourn.data.flights.Airlines
import app.sojourn.data.db.Plan
import app.sojourn.data.db.Presence
import app.sojourn.data.db.Rule
import app.sojourn.data.db.SojournDatabase
import app.sojourn.data.db.Source
import app.sojourn.domain.CountingOptions
import app.sojourn.domain.DayBook
import app.sojourn.domain.PlanOrigin
import app.sojourn.domain.RuleEngine
import app.sojourn.domain.RuleStatus
import app.sojourn.domain.Traveller
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn

class Repository(
    private val db: SojournDatabase,
    val settingsStore: SettingsStore,
    scope: CoroutineScope,
    /** The last changes, so one found wrong later can still be reverted. */
    private val changeLog: ChangeLog = ChangeLog(null),
    /** Where evidence files (boarding passes) are kept; null keeps none. */
    val evidenceDir: File? = null,
    /** Runs after every change to recorded days, to refresh the home screen widget. */
    private val onDaysChanged: suspend () -> Unit,
) {
    val settings: Flow<Settings> = settingsStore.settings
    val changes: StateFlow<List<Change>> = changeLog.changes
    val documents: Flow<List<Document>> = db.documents().observeAll()
    val plans: Flow<List<Plan>> = db.plans().observeAll()
    val rules: Flow<List<Rule>> = db.rules().observeAll()
    val flights: Flow<List<Flight>> = db.flights().observeAll()

    /**
     * Today's date, ticking over at midnight so open screens move on to the new day. It looks
     * again at least every 15 minutes: a flight into another time zone, or the phone sleeping
     * through midnight, would otherwise leave the book on yesterday while edits use today.
     */
    val today: Flow<LocalDate> = flow {
        while (true) {
            val now = LocalDateTime.now()
            emit(now.toLocalDate())
            val midnight = now.toLocalDate().plusDays(1).atStartOfDay()
            delay(minOf(java.time.Duration.between(now, midnight).toMillis() + 1_000, 15 * 60_000L))
        }
    }.distinctUntilChanged()

    val book: Flow<DayBook> = combine(
        db.presence().observeAll(),
        plans,
        flights,
        settings.map { CountingOptions(it.fillGaps, it.policies) }.distinctUntilChanged(),
        today,
    ) { presence, plans, flights, options, today -> DayBook.build(presence, plans, options, today, flights) }
        .flowOn(Dispatchers.Default)
        .shareIn(scope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    val traveller: Flow<Traveller> = settings.map { Traveller(it.citizenships, it.residencePermits, it.taxResidence) }.distinctUntilChanged()

    /** Enabled rules, the ones that apply to this traveller first. */
    val statuses: Flow<List<RuleStatus>> = combine(book, rules, traveller) { book, rules, traveller ->
        rules.filter { it.enabled }.map { RuleEngine.evaluate(it, book, traveller) }.sortedBy { !it.applies }
    }.flowOn(Dispatchers.Default).shareIn(scope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    suspend fun currentBook(): DayBook = book.first()

    /** Marks a coroutine already inside [recordChange], so nested changes log as one. */
    private object RecordingKey : CoroutineContext.Key<Recording>

    private class Recording : AbstractCoroutineContextElement(RecordingKey)

    private suspend fun tables(): Tables = db.withTransaction { Tables(db.presence().all(), db.plans().all(), db.flights().all()) }

    /**
     * Runs [block] and logs what it changed in days, plans and flights as one change called
     * [label], so it can be reverted later. Changes made inside it log as part of it.
     */
    suspend fun <T> recordChange(label: String, block: suspend () -> T): T {
        if (currentCoroutineContext()[RecordingKey] != null) return block()
        val before = tables()
        val result = withContext(Recording()) { block() }
        val change = Change.between(changeLog.nextId(), System.currentTimeMillis(), label, before, tables())
        if (!change.isEmpty) changeLog.add(change)
        return result
    }

    /**
     * Reverts one logged change: what it added goes, what it removed comes back. Changes made
     * after it stay, unless they changed the same days, which go back to how this one found them.
     */
    suspend fun revert(change: Change) {
        recordChange("Reverted: ${change.label}") {
            db.withTransaction {
                change.daysAdded.forEach { db.presence().remove(LocalDate.parse(it.date), it.country) }
                db.presence().upsert(change.daysRemoved.map { it.toPresence() })
                change.plansAdded.map { it.id }.takeIf { it.isNotEmpty() }?.let { db.plans().deleteAll(it) }
                db.plans().upsertAll(change.plansRemoved.map { it.toPlan() })
                change.flightsAdded.forEach { db.flights().delete(it.id) }
                db.flights().upsertAll(change.flightsRemoved.map { it.toFlight() })
            }
            onDaysChanged()
        }
        changeLog.markReverted(change.id)
    }

    /** Notes being in [country] at [at], widening the day's first and last sighting. */
    suspend fun record(country: String, source: Source, at: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()) {
        val day = at.atZone(zone).toLocalDate()
        val millis = at.toEpochMilli()
        db.withTransaction {
            val existing = db.presence().onDay(day).firstOrNull { it.country == country }
            val row = if (existing == null) {
                Presence(day, country, source, millis, millis)
            } else {
                existing.copy(
                    // A day the user entered by hand stays theirs; tracking only adds times.
                    source = if (existing.source == Source.MANUAL) Source.MANUAL else source,
                    firstSeen = minOf(existing.firstSeen ?: millis, millis),
                    lastSeen = maxOf(existing.lastSeen ?: millis, millis),
                )
            }
            db.presence().upsert(listOf(row))
        }
        onDaysChanged()
    }

    suspend fun addToDay(day: LocalDate, country: String) {
        db.presence().insertMissing(listOf(Presence(day, country, Source.MANUAL)))
        onDaysChanged()
    }

    suspend fun removeFromDay(day: LocalDate, country: String) {
        db.presence().remove(day, country)
        onDaysChanged()
    }

    /** Sets every day from [from] to [to] to [country] alone, replacing what was there. */
    suspend fun setRange(from: LocalDate, to: LocalDate, country: String) = editRange(from, to, RangeEdit.SET, country)

    /** Changes a stay's country, leaving the other country of its travel days alone. */
    suspend fun changeStay(from: LocalDate, to: LocalDate, old: String, new: String) =
        recordChange("Changed ${Change.range(from, to)} from ${Countries.name(old)} to ${Countries.name(new)}") { changeStayNow(from, to, old, new) }

    private suspend fun changeStayNow(from: LocalDate, to: LocalDate, old: String, new: String) {
        val today = LocalDate.now()
        if (from <= today) {
            val end = minOf(to, today)
            val rows = generateSequence(from) { it.plusDays(1) }.takeWhile { it <= end }.map { Presence(it, new, Source.MANUAL) }.toList()
            db.presence().swapCountry(from, end, old, rows)
        }
        if (to > today) {
            val start = maxOf(from, today.plusDays(1))
            rewritePlans { plans -> PlanEdits.remove(plans, start, to, old) + PlanEdits.add(plans, start, to, new) }
        }
        onDaysChanged()
    }

    suspend fun removeStay(from: LocalDate, to: LocalDate, country: String) = editRange(from, to, RangeEdit.REMOVE, country)

    suspend fun clearRange(from: LocalDate, to: LocalDate) = editRange(from, to, RangeEdit.CLEAR, null)

    /**
     * What a range edit changed, so it can be undone: the recorded rows of its days and the
     * plans, before and after it.
     */
    data class EditSnapshot(
        val from: LocalDate,
        val to: LocalDate,
        val presence: List<Presence>,
        val plans: List<Plan>,
        val presenceAfter: List<Presence> = emptyList(),
        val plansAfter: List<Plan> = emptyList(),
    )

    /**
     * Undoes the edit [snapshot] was taken for, and only it: rows and plans it wrote go, the
     * ones it removed come back, and anything written since (a check today found, a plan saved
     * elsewhere) stays.
     */
    suspend fun undo(snapshot: EditSnapshot) = recordChange("Undid an edit of ${Change.range(snapshot.from, snapshot.to)}") {
        db.withTransaction {
            val keys = snapshot.presenceAfter.map { it.day to it.country }.toSet()
            val since = db.presence().between(snapshot.from, snapshot.to).filter { (it.day to it.country) !in keys }
            db.presence().restoreRange(snapshot.from, snapshot.to, snapshot.presence)
            db.presence().upsert(since)
            val before = snapshot.plans.map { it.id }.toSet()
            val after = snapshot.plansAfter.map { it.id }.toSet()
            val added = after - before
            val removed = snapshot.plans.filter { it.id !in after }
            if (added.isNotEmpty()) db.plans().deleteAll(added.toList())
            db.plans().upsertAll(removed)
        }
        onDaysChanged()
    }

    /**
     * Changes the days from [from] to [to], one day at a time: [RangeEdit.SET] leaves [country]
     * alone on them, [RangeEdit.ADD] adds it, [RangeEdit.REMOVE] takes it off and
     * [RangeEdit.CLEAR] empties them. Days up to today are records; later days are the plan,
     * which is rewritten so no two plans ever cover the same days for the same country, and a
     * smaller range inside a bigger one splits it into three instead of overlapping it.
     */
    suspend fun editRange(from: LocalDate, to: LocalDate, edit: RangeEdit, country: String?, source: Source = Source.MANUAL): EditSnapshot {
        val span = Change.range(from, to)
        val name = country?.let { Countries.name(it) }
        val label = when (edit) {
            RangeEdit.SET -> "Set $span to $name"
            RangeEdit.ADD -> "Added $name to $span"
            RangeEdit.REMOVE -> "Took $name off $span"
            RangeEdit.CLEAR -> "Cleared $span"
        }
        return recordChange(label) { editRangeNow(from, to, edit, country, source) }
    }

    private suspend fun editRangeNow(from: LocalDate, to: LocalDate, edit: RangeEdit, country: String?, source: Source): EditSnapshot {
        require(edit == RangeEdit.CLEAR || country != null)
        val today = LocalDate.now()
        val snapshot = db.withTransaction { EditSnapshot(from, to, db.presence().between(from, to), db.plans().all()) }
        if (from <= today) {
            val end = minOf(to, today)
            val days = generateSequence(from) { it.plusDays(1) }.takeWhile { it <= end }.toList()
            when (edit) {
                // Days already recorded in the country keep how they were recorded.
                RangeEdit.SET -> db.presence().setCountry(from, end, country!!, days.map { Presence(it, country, source) })
                // New days are added; days already there gain this as supporting evidence.
                RangeEdit.ADD -> db.presence().addFound(days.map { Presence(it, country!!, source) }, onlyEmpty = false)
                RangeEdit.REMOVE -> db.presence().removeCountry(from, end, country!!)
                RangeEdit.CLEAR -> db.presence().clear(from, end)
            }
        }
        if (to > today) {
            val start = maxOf(from, today.plusDays(1))
            // Days where flights alone imply a stay need a cleared plan to stay empty.
            val book = currentBook()
            val implied = generateSequence(start) { it.plusDays(1) }.takeWhile { it <= to }.filter { date ->
                val day = book[date] ?: return@filter false
                day.origins.any { (c, origin) -> origin == PlanOrigin.BETWEEN_FLIGHTS && (edit == RangeEdit.CLEAR || c == country) }
            }.toList()
            rewritePlans { plans ->
                when (edit) {
                    RangeEdit.SET -> PlanEdits.remove(plans, start, to, null) + PlanEdits.Change(emptyList(), listOf(Plan(country = country!!, start = start, end = to)))
                    RangeEdit.ADD -> PlanEdits.add(plans, start, to, country!!)
                    RangeEdit.REMOVE -> PlanEdits.remove(plans, start, to, country!!) + PlanEdits.clear(implied)
                    RangeEdit.CLEAR -> PlanEdits.remove(plans, start, to, null) + PlanEdits.clear(implied)
                }
            }
        }
        onDaysChanged()
        return db.withTransaction { snapshot.copy(presenceAfter = db.presence().between(from, to), plansAfter = db.plans().all()) }
    }

    /** Applies plan edits worked out against the current plans, then joins plans that now touch. */
    private suspend fun rewritePlans(edits: (List<Plan>) -> PlanEdits.Change) {
        db.withTransaction {
            val change = edits(db.plans().all())
            db.plans().rewrite(change.removed, change.added)
            val merged = PlanEdits.merge(db.plans().all())
            db.plans().rewrite(merged.removed, merged.added)
        }
    }

    /**
     * Puts the countries of a recorded day in [path] order (a country twice when the day left
     * it and came back), by giving the day's rows times spread across it. Flights keep their own.
     */
    suspend fun reorderDay(date: LocalDate, path: List<String>) = recordChange("Reordered the countries on ${Change.range(date, date)}") {
        val start = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val step = 86_400_000L / (path.size + 1)
        db.withTransaction {
            val rows = db.presence().onDay(date)
            val updated = rows.mapNotNull { row ->
                val first = path.indexOf(row.country).takeIf { it >= 0 } ?: return@mapNotNull null
                val last = path.lastIndexOf(row.country)
                row.copy(firstSeen = start + (first + 1) * step, lastSeen = start + (last + 1) * step)
            }
            db.presence().upsert(updated)
        }
        onDaysChanged()
    }

    /**
     * Adds days found elsewhere (photos, location exports): days already recorded in the same
     * country gain them as supporting evidence; new days are added where nothing is known, or
     * anywhere unless [onlyEmpty]. Returns how many days were added and how many backed up.
     */
    suspend fun addFound(rows: List<Presence>, onlyEmpty: Boolean, label: String): Pair<Int, Int> {
        val result = recordChange(label) { db.presence().addFound(rows, onlyEmpty) }
        onDaysChanged()
        return result
    }

    /** Day and country pairs already recorded between [from] and [to]. */
    suspend fun recordedPairs(from: LocalDate, to: LocalDate): Set<Pair<LocalDate, String>> =
        db.presence().between(from, to).mapTo(HashSet()) { it.day to it.country }

    // ---- Evidence documents ----

    /**
     * Keeps [document]. A day and country has one photo: another found for it joins the
     * choices, and the one chosen stays.
     */
    /** Keeps [document]; false when it was there already (a photo joins the day's others). */
    suspend fun addDocument(document: Document): Boolean {
        if (document.kind == DocumentKind.PHOTO) {
            val existing = db.documents().find(document.day, document.country, DocumentKind.PHOTO).firstOrNull()
            if (existing != null) {
                val choices = (existing.alternatives + listOfNotNull(document.uri) + document.alternatives).distinct().filter { it != existing.uri }
                db.documents().upsert(existing.copy(candidates = choices.joinToString("\n")))
                return false
            }
        } else if (document.file == null && db.documents().find(document.day, document.country, document.kind).any { it.title == document.title && it.text == document.text }) {
            return false
        }
        db.documents().upsert(document)
        return true
    }

    suspend fun documents(day: LocalDate, country: String, kind: DocumentKind): List<Document> = db.documents().find(day, country, kind)

    /** Makes [uri], one of its other photos, the photo [document] shows. */
    suspend fun choosePhoto(document: Document, uri: String) {
        val choices = (document.alternatives + listOfNotNull(document.uri)).distinct().filter { it != uri }
        db.documents().upsert(document.copy(uri = uri, candidates = choices.joinToString("\n")))
    }

    suspend fun deleteDocument(document: Document) {
        db.documents().delete(document.id)
        // One boarding pass backs both its days; its file goes with the last of them.
        document.file?.takeIf { db.documents().usingFile(it) == 0 }?.let { name -> evidenceDir?.let { File(it, name).delete() } }
    }

    suspend fun documentsBetween(from: LocalDate, to: LocalDate): List<Document> = db.documents().between(from, to)

    suspend fun recordedDays(from: LocalDate, to: LocalDate): Set<LocalDate> = db.presence().daysBetween(from, to).toSet()

    /**
     * Saves a planned stay. Other plans give way on its days, except a shared first or last
     * day, which stays a travel day between the two.
     */
    suspend fun savePlan(plan: Plan) = recordChange("Planned ${Countries.name(plan.country)}, ${Change.range(plan.start, plan.end)}") {
        rewritePlans { plans ->
            val others = plans.filter { it.id != plan.id }
            val removed = plans.filter { it.id == plan.id && plan.id != 0L }.map { it.id }
            PlanEdits.Change(removed, emptyList()) +
                PlanEdits.remove(others, plan.start, plan.end, plan.country) +
                PlanEdits.remove(others, plan.start.plusDays(1), plan.end.minusDays(1), null, except = plan.country) +
                PlanEdits.Change(emptyList(), listOf(plan.copy(id = 0)))
        }
        onDaysChanged()
    }

    suspend fun deletePlan(id: Long) = recordChange("Deleted a plan") {
        db.plans().delete(id)
        onDaysChanged()
    }

    /** Rules as they would stand with [draft] saved, so the editor can show a plan's effect. */
    suspend fun previewPlan(draft: Plan): List<RuleStatus> =
        preview(plans = db.plans().all().filter { it.id != draft.id || draft.id == 0L } + draft, flights = db.flights().all())

    /** The same for a flight, including the stays it implies until the next one. */
    suspend fun previewFlight(draft: Flight): List<RuleStatus> =
        preview(plans = db.plans().all(), flights = db.flights().all().filter { it.id != draft.id || draft.id == 0L } + draft)

    private suspend fun preview(plans: List<Plan>, flights: List<Flight>): List<RuleStatus> = kotlinx.coroutines.withContext(Dispatchers.Default) {
        val settings = settingsStore.current()
        val book = DayBook.build(db.presence().all(), plans, CountingOptions(settings.fillGaps, settings.policies), LocalDate.now(), flights)
        val traveller = Traveller(settings.citizenships, settings.residencePermits, settings.taxResidence)
        db.rules().all().filter { it.enabled }.map { RuleEngine.evaluate(it, book, traveller) }
    }

    suspend fun saveFlight(flight: Flight) = recordChange("Saved flight ${flight.number.ifBlank { "" }} ${flight.origin}→${flight.destination}".replace("  ", " ")) {
        db.flights().upsert(flight)
        onDaysChanged()
    }

    data class FlightImport(val added: Int, val skipped: Int, val duplicatesRemoved: Int)

    /**
     * Saves imported flights, leaving out any that is already saved (or twice in [flights]),
     * however differently it was written, then merges duplicates earlier imports left behind.
     */
    suspend fun importFlights(flights: List<Flight>): FlightImport = recordChange("Imported ${flights.size} ${if (flights.size == 1) "flight" else "flights"}") { importFlightsNow(flights) }

    private suspend fun importFlightsNow(flights: List<Flight>): FlightImport {
        var added = 0
        var removed = 0
        db.withTransaction {
            val known = db.flights().all().toMutableList()
            for (flight in flights) {
                // A flight already saved keeps what this copy adds: its booking reference,
                // passenger, seat, number or times.
                val existing = known.indexOfFirst { FlightMatch.same(it, flight) }
                if (existing >= 0) {
                    val merged = FlightMatch.merge(known[existing], flight).copy(id = known[existing].id)
                    if (merged != known[existing]) {
                        db.flights().upsert(merged)
                        known[existing] = merged
                    }
                    continue
                }
                db.flights().upsert(flight)
                known += flight
                added++
            }
            removed = mergeDuplicateFlights()
        }
        onDaysChanged()
        return FlightImport(added, flights.size - added, removed)
    }

    /** Fills in what a boarding pass or booking tells about a saved flight and it didn't know. */
    suspend fun enrichFlight(flight: Flight, found: FoundTrip) {
        val current = db.flights().all().firstOrNull { it.id == flight.id } ?: return
        val updated = current.copy(
            number = current.number.ifBlank { found.number.filter { it.isLetterOrDigit() } },
            reference = current.reference.ifBlank { found.reference.orEmpty() },
            passenger = current.passenger.ifBlank { found.passenger.orEmpty() },
            seat = current.seat.ifBlank { found.seat.orEmpty() },
        )
        if (updated != current) recordChange("Filled in flight ${Airlines.format(updated.number)}".trim()) { db.flights().upsert(updated) }
    }

    /** The saved flight [flight] is the same as, if any. */
    suspend fun savedFlight(flight: Flight): Flight? = db.flights().all().firstOrNull { FlightMatch.same(it, flight) }

    /** Removes saved flights that are the same flight twice, keeping what each copy knew. */
    suspend fun removeDuplicateFlights(): Int = recordChange("Merged duplicate flights") {
        val removed = db.withTransaction { mergeDuplicateFlights() }
        if (removed > 0) onDaysChanged()
        removed
    }

    private suspend fun mergeDuplicateFlights(): Int {
        val cleanup = FlightMatch.duplicates(db.flights().all())
        cleanup.delete.forEach { db.flights().delete(it) }
        db.flights().upsertAll(cleanup.update)
        return cleanup.delete.size
    }

    suspend fun deleteFlight(id: Long) = recordChange("Deleted a flight") {
        db.flights().delete(id)
        onDaysChanged()
    }

    /** Turns a plan whose dates have passed into recorded days, for when tracking was off. */
    suspend fun confirmPlan(plan: Plan) = recordChange("Confirmed ${Countries.name(plan.country)}, ${Change.range(plan.start, minOf(plan.end, LocalDate.now()))}") {
        val end = minOf(plan.end, LocalDate.now())
        val rows = generateSequence(plan.start) { it.plusDays(1) }.takeWhile { it <= end }.map { Presence(it, plan.country, Source.MANUAL) }.toList()
        db.withTransaction {
            db.presence().insertMissing(rows)
            if (plan.end > end) db.plans().upsert(plan.copy(start = end.plusDays(1))) else db.plans().delete(plan.id)
        }
        onDaysChanged()
    }

    suspend fun saveRule(rule: Rule): Long {
        val position = if (rule.id == 0L) (db.rules().all().maxOfOrNull { it.position } ?: -1) + 1 else rule.position
        return db.rules().upsert(rule.copy(position = position))
    }

    suspend fun deleteRule(id: Long) = db.rules().delete(id)

    suspend fun presenceRows(): List<Presence> = db.presence().all()

    suspend fun exportJson(): String =
        BackupCodec.encode(db.presence().all(), db.plans().all(), db.rules().all(), settingsStore.current(), db.flights().all())

    data class RestoreResult(val days: Int, val plans: Int, val rules: Int, val flights: Int = 0)

    /** Restores a backup. Merging keeps existing data and adds what's missing. */
    suspend fun restore(json: String, replace: Boolean): RestoreResult =
        recordChange(if (replace) "Restored a backup, replacing everything" else "Merged a backup in") { restoreNow(json, replace) }

    private suspend fun restoreNow(json: String, replace: Boolean): RestoreResult {
        val file = BackupCodec.decode(json)
        val presence = file.presence()
        val plans = file.planEntities()
        val rules = file.ruleEntities()
        val flights = file.flightEntities()
        db.withTransaction {
            if (replace) {
                db.presence().clearAll()
                db.plans().clearAll()
                db.rules().clearAll()
                db.flights().clearAll()
                db.presence().upsert(presence)
                db.plans().upsertAll(plans)
                db.rules().upsertAll(rules)
                db.flights().upsertAll(flights)
            } else {
                // The same rule as imports, so "IB 3166" here and "IB3166" in the backup is one flight.
                val known = db.flights().all().toMutableList()
                flights.forEach { flight ->
                    if (known.none { FlightMatch.same(it, flight) }) {
                        db.flights().upsert(flight.copy(id = 0))
                        known += flight
                    }
                }
                db.presence().insertMissing(presence)
                val existingPlans = db.plans().all().map { Triple(it.country, it.start, it.end) }.toSet()
                db.plans().upsertAll(plans.filter { Triple(it.country, it.start, it.end) !in existingPlans })
                val existingRules = db.rules().all().map { it.name to it.countries }.toSet()
                db.rules().upsertAll(rules.filter { (it.name to it.countries) !in existingRules })
            }
        }
        if (replace) {
            file.settings?.let { restored ->
                settingsStore.update { current ->
                    restored.copy(
                        backupFolder = current.backupFolder,
                        lastBackup = current.lastBackup,
                        lastCheck = current.lastCheck,
                        lastCheckCountry = current.lastCheckCountry,
                        lastCheckSource = current.lastCheckSource,
                        onboarded = true,
                    )
                }
            }
        }
        onDaysChanged()
        return RestoreResult(presence.size, plans.size, rules.size, flights.size)
    }

    suspend fun eraseEverything() = recordChange("Erased all data") {
        db.withTransaction {
            db.presence().clearAll()
            db.plans().clearAll()
            db.rules().clearAll()
            db.flights().clearAll()
            db.documents().clearAll()
        }
        evidenceDir?.listFiles()?.forEach { it.delete() }
        onDaysChanged()
    }
}
