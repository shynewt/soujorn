package app.sojourn.data

import app.sojourn.data.db.Flight
import app.sojourn.data.db.Plan
import app.sojourn.data.db.Presence
import app.sojourn.data.db.Source
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Days, plans and flights as they stood at one moment, to compare before and after a change. */
data class Tables(val presence: List<Presence>, val plans: List<Plan>, val flights: List<Flight>)

@Serializable
data class LoggedDay(val date: String, val country: String, val source: String, val firstSeen: Long? = null, val lastSeen: Long? = null, val evidence: Int = 0) {
    fun toPresence() = Presence(LocalDate.parse(date), country, runCatching { Source.valueOf(source) }.getOrDefault(Source.MANUAL), firstSeen, lastSeen, evidence)

    companion object {
        fun of(p: Presence) = LoggedDay(p.day.toString(), p.country, p.source.name, p.firstSeen, p.lastSeen, p.evidence)
    }
}

@Serializable
data class LoggedPlan(val id: Long, val country: String, val start: String, val end: String, val note: String = "", val cleared: Boolean = false) {
    fun toPlan() = Plan(id, country, LocalDate.parse(start), LocalDate.parse(end), note, cleared)

    companion object {
        fun of(p: Plan) = LoggedPlan(p.id, p.country, p.start.toString(), p.end.toString(), p.note, p.cleared)
    }
}

@Serializable
data class LoggedFlight(
    val id: Long,
    val number: String,
    val origin: String,
    val destination: String,
    val originCountry: String,
    val destinationCountry: String,
    val departureDate: String,
    val arrivalDate: String,
    val departureMinute: Int? = null,
    val arrivalMinute: Int? = null,
    val note: String = "",
) {
    fun toFlight() = Flight(
        id, number, origin, destination, originCountry, destinationCountry,
        LocalDate.parse(departureDate), LocalDate.parse(arrivalDate), departureMinute, arrivalMinute, note,
    )

    companion object {
        fun of(f: Flight) = LoggedFlight(
            f.id, f.number, f.origin, f.destination, f.originCountry, f.destinationCountry,
            f.departureDate.toString(), f.arrivalDate.toString(), f.departureMinute, f.arrivalMinute, f.note,
        )
    }
}

/**
 * One change to days, plans or flights, as what it added and what it removed, so it can be
 * reverted on its own later, leaving the changes made after it in place.
 */
@Serializable
data class Change(
    val id: Long,
    val at: Long,
    val label: String,
    val daysAdded: List<LoggedDay> = emptyList(),
    val daysRemoved: List<LoggedDay> = emptyList(),
    val plansAdded: List<LoggedPlan> = emptyList(),
    val plansRemoved: List<LoggedPlan> = emptyList(),
    val flightsAdded: List<LoggedFlight> = emptyList(),
    val flightsRemoved: List<LoggedFlight> = emptyList(),
    val reverted: Boolean = false,
) {
    /** "31 days, 2 plans, 1 flight": what it touched. */
    val summary: String
        get() {
            val days = (daysAdded + daysRemoved).map { it.date }.distinct().size
            val plans = (plansAdded.map { it.id } + plansRemoved.map { it.id }).distinct().size
            val flights = (flightsAdded.map { it.id } + flightsRemoved.map { it.id }).distinct().size
            return listOfNotNull(
                days.takeIf { it > 0 }?.let { "$it ${if (it == 1) "day" else "days"}" },
                plans.takeIf { it > 0 }?.let { "$it ${if (it == 1) "plan" else "plans"}" },
                flights.takeIf { it > 0 }?.let { "$it ${if (it == 1) "flight" else "flights"}" },
            ).joinToString(", ")
        }

    val isEmpty: Boolean
        get() = daysAdded.isEmpty() && daysRemoved.isEmpty() && plansAdded.isEmpty() && plansRemoved.isEmpty() && flightsAdded.isEmpty() && flightsRemoved.isEmpty()

    companion object {
        /** What changed between [before] and [after]; a row that changed counts as removed and added. */
        fun between(id: Long, at: Long, label: String, before: Tables, after: Tables): Change {
            val daysBefore = before.presence.associateBy { it.day to it.country }
            val daysAfter = after.presence.associateBy { it.day to it.country }
            val plansBefore = before.plans.associateBy { it.id }
            val plansAfter = after.plans.associateBy { it.id }
            val flightsBefore = before.flights.associateBy { it.id }
            val flightsAfter = after.flights.associateBy { it.id }
            return Change(
                id = id,
                at = at,
                label = label,
                daysAdded = daysAfter.filter { (k, v) -> daysBefore[k] != v }.values.map(LoggedDay::of),
                daysRemoved = daysBefore.filter { (k, v) -> daysAfter[k] != v }.values.map(LoggedDay::of),
                plansAdded = plansAfter.filter { (k, v) -> plansBefore[k] != v }.values.map(LoggedPlan::of),
                plansRemoved = plansBefore.filter { (k, v) -> plansAfter[k] != v }.values.map(LoggedPlan::of),
                flightsAdded = flightsAfter.filter { (k, v) -> flightsBefore[k] != v }.values.map(LoggedFlight::of),
                flightsRemoved = flightsBefore.filter { (k, v) -> flightsAfter[k] != v }.values.map(LoggedFlight::of),
            )
        }

        private val day = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())
        private val dayMonth = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())

        /** "4 Mar 2026", "4–9 Mar 2026" or "4 Mar – 9 Apr 2026", for labels. */
        fun range(from: LocalDate, to: LocalDate): String = when {
            from == to -> from.format(day)
            from.year == to.year && from.month == to.month -> "${from.dayOfMonth}–${to.format(day)}"
            from.year == to.year -> "${from.format(dayMonth)} – ${to.format(day)}"
            else -> "${from.format(day)} – ${to.format(day)}"
        }
    }
}

/**
 * The last [limit] changes, newest first, kept in a small file so a change found to be wrong
 * days later can still be reverted. Older ones are dropped: it's a safety net, not a history.
 */
class ChangeLog(private val file: File?, private val limit: Int = LIMIT) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val _changes = MutableStateFlow(load())
    val changes: StateFlow<List<Change>> = _changes

    private fun load(): List<Change> = runCatching {
        file?.takeIf { it.exists() }?.readText()?.let { json.decodeFromString(ListSerializer(Change.serializer()), it) }
    }.getOrNull().orEmpty()

    fun nextId(): Long = (_changes.value.maxOfOrNull { it.id } ?: 0) + 1

    suspend fun add(change: Change) = update { listOf(change) + it }

    suspend fun markReverted(id: Long) = update { list -> list.map { if (it.id == id) it.copy(reverted = true) else it } }

    private suspend fun update(transform: (List<Change>) -> List<Change>) = mutex.withLock {
        val next = transform(_changes.value).take(limit)
        _changes.value = next
        file?.let { f -> withContext(Dispatchers.IO) { runCatching { f.writeText(json.encodeToString(ListSerializer(Change.serializer()), next)) } } }
    }

    companion object {
        const val LIMIT = 20
    }
}
