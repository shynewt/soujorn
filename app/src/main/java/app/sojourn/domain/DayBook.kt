package app.sojourn.domain

import app.sojourn.data.CountrySet
import app.sojourn.data.db.Flight
import app.sojourn.data.db.Plan
import app.sojourn.data.db.Presence
import app.sojourn.data.db.Source
import app.sojourn.data.db.sources
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.TreeMap

enum class DayKind {
    /** Seen there by tracking, a photo, or entered by hand. */
    RECORDED,

    /** Untracked, but recorded in the same country on both sides of the gap. */
    INFERRED,

    /** A future day covered by a planned stay. */
    PLANNED,
}

/**
 * How well a day in a country is backed up, best first. Something you can show (a photo, a
 * ticket, a booking, an exported location history) comes before the app's own tracking, which
 * comes before a day entered by hand; an assumed day has nothing behind it.
 */
enum class Evidence(val label: String) {
    DOCUMENTED("Documented"),
    TRACKED("Tracked"),
    MANUAL("By hand"),
    ASSUMED("Assumed");

    companion object {
        fun of(source: Source): Evidence = when (source) {
            Source.PHOTO, Source.FLIGHT, Source.IMPORT -> DOCUMENTED
            Source.LOCATION, Source.NETWORK -> TRACKED
            Source.MANUAL -> MANUAL
        }
    }
}

/** Days counted for a rule, by how well each is backed up; [corroborated] have two kinds of source or more. */
data class EvidenceCount(val documented: Int = 0, val tracked: Int = 0, val manual: Int = 0, val assumed: Int = 0, val corroborated: Int = 0) {
    val total: Int get() = documented + tracked + manual + assumed

    operator fun get(evidence: Evidence): Int = when (evidence) {
        Evidence.DOCUMENTED -> documented
        Evidence.TRACKED -> tracked
        Evidence.MANUAL -> manual
        Evidence.ASSUMED -> assumed
    }
}

/** Where a planned country of a day comes from, so the day sheet knows how to change it. */
enum class PlanOrigin {
    /** A stay the user planned. */
    PLAN,

    /** The departure or arrival of a flight. */
    FLIGHT,

    /** Between landing somewhere and the next flight out of there. */
    BETWEEN_FLIGHTS,
}

/**
 * The countries of one day. [path] is the order they were visited, with a country repeated
 * when the day left it and came back (CY, GR, CY); [countries] lists each once. Countries only
 * passed through airside, between two flights of the day, are in [transit] and never count.
 */
data class Day(
    val date: LocalDate,
    val path: List<String>,
    val kind: DayKind,
    val transit: Set<String> = emptySet(),
    val origins: Map<String, PlanOrigin> = emptyMap(),
    /** The sources behind each recorded country of the day (flights included). */
    val sources: Map<String, Set<Source>> = emptyMap(),
    /**
     * Countries the day is proven in by kept boarding passes on both sides: the flight in and
     * the flight out of that country. Three days in Poland between two passes are documented
     * whatever else recorded them.
     */
    val between: Set<String> = emptySet(),
) {
    val countries: List<String> = path.distinct()

    /** How well [country] is backed up on this day. */
    fun evidenceOf(country: String): Evidence = when {
        kind != DayKind.PLANNED && country in between -> Evidence.DOCUMENTED
        else -> plainEvidenceOf(country)
    }

    private fun plainEvidenceOf(country: String): Evidence = when (kind) {
        DayKind.INFERRED -> Evidence.ASSUMED
        DayKind.PLANNED -> Evidence.ASSUMED
        DayKind.RECORDED -> sources[country].orEmpty().map(Evidence::of).minOrNull() ?: Evidence.MANUAL
    }

    /** The country the day ended in. */
    val last: String get() = path.last()

    val isTravel: Boolean get() = countries.size > 1
}

data class CountingOptions(
    val fillGaps: Boolean = true,
    val policies: CountingPolicies = CountingPolicies(),
)

/**
 * Every known day, past and planned, after the counting options have been applied. It is the
 * single source the summaries, calendar, timeline and rules all count from, so they always agree.
 */
class DayBook private constructor(
    private val days: TreeMap<LocalDate, Day>,
    val today: LocalDate,
    /** Every flight, earliest first. */
    val flights: List<Flight> = emptyList(),
    val policies: CountingPolicies = CountingPolicies(),
) {
    fun flightsOn(date: LocalDate): List<Flight> = flights.filter { it.departureDate == date || it.arrivalDate == date }

    /** Flights still to come, including today's. */
    val upcomingFlights: List<Flight> get() = flights.filter { it.departureDate >= today }

    operator fun get(date: LocalDate): Day? = days[date]

    val isEmpty: Boolean get() = days.isEmpty()

    /** The first and last day with anything recorded, plans excluded. */
    val firstRecorded: LocalDate? get() = days.values.firstOrNull { it.kind != DayKind.PLANNED }?.date
    val lastRecorded: LocalDate? get() = days.descendingMap().values.firstOrNull { it.kind != DayKind.PLANNED }?.date
    val lastPlanned: LocalDate? get() = days.lastEntry()?.value?.takeIf { it.kind == DayKind.PLANNED }?.date
    val firstDay: LocalDate? get() = days.firstEntry()?.key

    fun between(from: LocalDate, to: LocalDate): Collection<Day> =
        if (from > to) emptyList() else days.subMap(from, true, to, true).values

    /**
     * Whether a day counts towards [set] under [counting] (by default, how the set's countries
     * count their own days); planned days only count when [withPlans] is set.
     */
    fun counts(date: LocalDate, set: CountrySet, withPlans: Boolean, counting: DayCounting = policies.forSet(set)): Boolean {
        val day = days[date] ?: return false
        if (day.kind == DayKind.PLANNED && !withPlans) return false
        return counting.counts(day.path, set, day.transit)
    }

    fun count(from: LocalDate, to: LocalDate, set: CountrySet, withPlans: Boolean, counting: DayCounting = policies.forSet(set)): Int =
        between(from, to).count { (it.kind != DayKind.PLANNED || withPlans) && counting.counts(it.path, set, it.transit) }

    /**
     * The lived days from [from] to [to] that count towards [set], by how well each is backed
     * up: a day takes the best evidence of the set's countries on it.
     */
    fun evidence(from: LocalDate, to: LocalDate, set: CountrySet, counting: DayCounting = policies.forSet(set)): EvidenceCount {
        val counts = IntArray(Evidence.entries.size)
        var corroborated = 0
        for (day in between(from, minOf(to, today))) {
            if (day.kind == DayKind.PLANNED || !counting.counts(day.path, set, day.transit)) continue
            val here = day.countries.filter { set.contains(it) && it !in day.transit }.ifEmpty { day.countries }
            counts[here.minOf { day.evidenceOf(it) }.ordinal]++
            if (here.any { c -> day.sources[c].orEmpty().map(Evidence::of).toSet().size > 1 }) corroborated++
        }
        return EvidenceCount(counts[0], counts[1], counts[2], counts[3], corroborated)
    }

    /** Whether [country] counts [date] as one of its days, by its own counting. */
    fun countsFor(country: String, day: Day): Boolean = policies.of(country).counts(day.path, CountrySet(setOf(country)), day.transit)

    /**
     * Days per country in the range, each counted the way that country counts its own days, so a
     * departure day from Cyprus isn't a Cyprus day while it is a Spanish day for Spain.
     */
    fun summary(from: LocalDate, to: LocalDate): Summary {
        val actual = HashMap<String, Int>()
        val planned = HashMap<String, Int>()
        val present = HashMap<String, Int>()
        var tracked = 0
        var plannedDays = 0
        for (day in between(from, to)) {
            val isPlanned = day.kind == DayKind.PLANNED
            val target = if (isPlanned) planned else actual
            if (isPlanned) plannedDays++ else tracked++
            for (country in day.countries) {
                if (country in day.transit) continue
                if (!isPlanned) present[country] = (present[country] ?: 0) + 1
                if (countsFor(country, day)) target[country] = (target[country] ?: 0) + 1 else target.putIfAbsent(country, 0)
            }
        }
        val countries = (actual.keys + planned.keys).map { CountryDays(it, actual[it] ?: 0, planned[it] ?: 0, present[it] ?: 0) }
            .filter { it.total > 0 || it.present > 0 }
            .sortedWith(compareByDescending<CountryDays> { it.days }.thenByDescending { it.planned }.thenBy { it.country })
        val span = ChronoUnit.DAYS.between(from, to).toInt() + 1
        return Summary(from, to, countries, tracked, plannedDays, span)
    }

    /**
     * Ranges of past days with nothing known about them. Today isn't over, so a plan for it is
     * enough: the calendar shows that day with its planned country, not as untracked.
     */
    fun gaps(from: LocalDate, to: LocalDate): List<DateRange> {
        val end = minOf(to, today)
        if (from > end) return emptyList()
        val gaps = mutableListOf<DateRange>()
        var cursor = from
        for (day in between(from, end)) {
            if (day.kind == DayKind.PLANNED && day.date != today) continue
            if (day.date > cursor) gaps += DateRange(cursor, day.date.minusDays(1))
            cursor = day.date.plusDays(1)
        }
        if (cursor <= end) gaps += DateRange(cursor, end)
        return gaps
    }

    /** Uninterrupted runs in each country; on travel days both countries' runs include the day. */
    fun stays(from: LocalDate = days.firstEntry()?.key ?: today, to: LocalDate = days.lastEntry()?.key ?: today): List<Stay> {
        val open = LinkedHashMap<String, Stay>()
        val done = mutableListOf<Stay>()
        var previous: LocalDate? = null
        for (day in between(from, to)) {
            if (previous != null && day.date != previous.plusDays(1)) {
                done += open.values
                open.clear()
            }
            val here = day.countries.filter { it !in day.transit }
            val ended = open.keys - here.toSet()
            ended.forEach { done += open.remove(it)!! }
            for (country in here) {
                val stay = open[country]
                open[country] = if (stay == null || (stay.kind == DayKind.PLANNED) != (day.kind == DayKind.PLANNED)) {
                    stay?.let { done += it }
                    Stay(country, day.date, day.date, day.kind)
                } else {
                    stay.copy(end = day.date, kind = if (day.kind == DayKind.RECORDED) DayKind.RECORDED else stay.kind)
                }
            }
            previous = day.date
        }
        done += open.values
        return done.sortedWith(compareBy<Stay> { it.start }.thenBy { it.end })
    }

    /**
     * The journey as alternating stays and border crossings, oldest first. Each day belongs to
     * the stay of the country it ended in, so stays never overlap however days were entered;
     * a travel day starts the next stay and is also the last day present in the one before.
     */
    fun journey(from: LocalDate = days.firstEntry()?.key ?: today, to: LocalDate = days.lastEntry()?.key ?: today): List<Leg> {
        val legs = mutableListOf<Leg>()
        var current: MutableList<Day>? = null
        var previous: Day? = null

        fun close(next: Day?) {
            val run = current ?: return
            val country = run.first().last
            // The day after, if it starts here, is the day this stay was left.
            val left = next?.takeIf { it.date == run.last().date.plusDays(1) && country in it.countries && it.last != country }
            val presentEnd = left?.date ?: run.last().date
            val all = run + listOfNotNull(left)
            val kind = when {
                run.all { it.kind == DayKind.PLANNED } -> DayKind.PLANNED
                run.any { it.kind == DayKind.RECORDED } -> DayKind.RECORDED
                else -> DayKind.INFERRED
            }
            val set = CountrySet(setOf(country))
            val counting = policies.of(country)
            legs += Leg.StayLeg(
                country = country,
                start = run.first().date,
                end = run.last().date,
                presentEnd = presentEnd,
                kind = kind,
                counted = all.count { counting.counts(it.path, set, it.transit) },
                planned = run.count { it.kind == DayKind.PLANNED } + if (left?.kind == DayKind.PLANNED) 1 else 0,
                arrivedOnTravelDay = run.first().isTravel,
            )
            current = null
        }

        for (day in between(from, to)) {
            val prev = previous
            val contiguous = prev != null && day.date == prev.date.plusDays(1)
            if (prev != null && !contiguous) {
                close(null)
                if (prev.date < today) legs += Leg.GapLeg(DateRange(prev.date.plusDays(1), minOf(day.date.minusDays(1), today)))
            }
            val run = current
            // Same country at the end of the day, a day trip away and back included: same stay.
            if (run != null && run.first().last == day.last) {
                run += day
            } else {
                close(day)
                if (contiguous && prev != null) {
                    legs += Leg.Crossing(day.date, crossingPath(prev, day), day.transit, flightsOn(day.date), day.kind == DayKind.PLANNED)
                }
                current = mutableListOf(day)
            }
            previous = day
        }
        close(null)
        return legs
    }

    /** The countries passed through from where [before] ended to where [day] ended. */
    private fun crossingPath(before: Day, day: Day): List<String> {
        val path = if (day.path.first() == before.last) day.path else listOf(before.last) + day.path
        return path.fold(mutableListOf()) { acc, c -> if (acc.lastOrNull() != c) acc += c; acc }
    }

    /** The day that describes "now": today if known, else the latest recorded day. */
    fun current(): Day? = days[today]?.takeIf { it.kind != DayKind.PLANNED }
        ?: days.headMap(today, true).descendingMap().values.firstOrNull { it.kind != DayKind.PLANNED }

    /** How many days in a row up to [date] include [country]. */
    fun streak(country: String, date: LocalDate = today): Int {
        var count = 0
        var cursor = date
        while (days[cursor]?.takeIf { it.kind != DayKind.PLANNED }?.countries?.contains(country) == true) {
            count++
            cursor = cursor.minusDays(1)
        }
        return count
    }

    /** Recorded years, newest first, including the current one. */
    fun years(): List<Int> {
        val first = firstRecorded?.year ?: today.year
        val last = maxOf(today.year, lastPlanned?.year ?: today.year)
        return (last downTo first).toList()
    }

    /** Countries most recently visited, for quick picks. */
    fun recentCountries(limit: Int = 6): List<String> {
        val seen = LinkedHashSet<String>()
        for (day in days.headMap(today, true).descendingMap().values) {
            day.path.asReversed().forEach { seen += it }
            if (seen.size >= limit) break
        }
        return seen.take(limit)
    }

    /** One country of one day, before the day's order is worked out; times are epoch millis. */
    private data class Visit(val country: String, val first: Long?, val last: Long?, val origin: PlanOrigin? = null, val sources: Set<Source> = emptySet())

    companion object {
        fun build(
            presence: List<Presence>,
            plans: List<Plan>,
            options: CountingOptions,
            today: LocalDate,
            flights: List<Flight> = emptyList(),
            /** Flights with a boarding pass kept: the days between two of them are proven. */
            passes: Set<Long> = emptySet(),
        ): DayBook {
            val sortedFlights = flights.sortedWith(compareBy<Flight> { it.departureDate }.thenBy { it.departureMinute ?: 0 })
            val days = TreeMap<LocalDate, Day>()

            // A flight that has happened is as good as a record: departure in one country, arrival
            // in another, at their times so a travel day lists the countries in order.
            val recorded = TreeMap<LocalDate, MutableList<Visit>>()
            for (row in presence) recorded.getOrPut(row.day) { mutableListOf() } += Visit(row.country, row.firstSeen, row.lastSeen, sources = row.sources)
            for (flight in sortedFlights) {
                if (flight.departureDate <= today) {
                    val at = moment(flight.departureDate, flight.departureMinute ?: 0)
                    recorded.getOrPut(flight.departureDate) { mutableListOf() } += Visit(flight.originCountry, at, at, sources = setOf(Source.FLIGHT))
                }
                if (flight.arrivalDate <= today) {
                    val at = moment(flight.arrivalDate, flight.arrivalMinute ?: LAST_MINUTE)
                    recorded.getOrPut(flight.arrivalDate) { mutableListOf() } += Visit(flight.destinationCountry, at, at, sources = setOf(Source.FLIGHT))
                }
            }
            order(recorded, days, DayKind.RECORDED, sortedFlights)

            if (options.fillGaps) {
                val known = days.values.toList()
                for (i in 1 until known.size) {
                    val before = known[i - 1]
                    val after = known[i]
                    val stayed = before.last
                    if (after.date > before.date.plusDays(1) && after.path.first() == stayed) {
                        var date = before.date.plusDays(1)
                        while (date < after.date) {
                            days[date] = Day(date, listOf(stayed), DayKind.INFERRED)
                            date = date.plusDays(1)
                        }
                    }
                }
            }

            // Plans: stays the user planned win over the stays flights imply, and "cleared" plans
            // say nothing is planned there even though flights would imply a stay.
            val planned = TreeMap<LocalDate, MutableList<Visit>>()
            val explicit = HashSet<LocalDate>()
            val cleared = HashSet<LocalDate>()
            fun add(date: LocalDate, visit: Visit) {
                if (date < today || days[date] != null) return
                val list = planned.getOrPut(date) { mutableListOf() }
                if (list.none { it.country == visit.country && it.first == visit.first }) list += visit
            }
            for (plan in plans) {
                var date = maxOf(plan.start, today)
                while (date <= plan.end) {
                    if (plan.cleared) cleared += date else {
                        explicit += date
                        add(date, Visit(plan.country, null, null, PlanOrigin.PLAN))
                    }
                    date = date.plusDays(1)
                }
            }
            fun fill(date: LocalDate, country: String) {
                if (date !in explicit && date !in cleared) add(date, Visit(country, null, null, PlanOrigin.BETWEEN_FLIGHTS))
            }

            // Flights still to come are planned travel days, and between landing somewhere and
            // the next flight out of there, the plan is to stay: flights alone fill the plan.
            // That includes staying where you are now until the first flight leaves from there.
            val here = days.headMap(today, true).descendingMap().values.firstOrNull()?.last
            val first = sortedFlights.firstOrNull { it.departureDate > today }
            if (first != null && first.originCountry == here) {
                var date = today.plusDays(1)
                while (date < first.departureDate) {
                    fill(date, here)
                    date = date.plusDays(1)
                }
            }
            sortedFlights.forEachIndexed { index, flight ->
                if (flight.departureDate > today) add(flight.departureDate, Visit(flight.originCountry, moment(flight.departureDate, flight.departureMinute ?: 0), null, PlanOrigin.FLIGHT))
                if (flight.arrivalDate > today) add(flight.arrivalDate, Visit(flight.destinationCountry, moment(flight.arrivalDate, flight.arrivalMinute ?: LAST_MINUTE), null, PlanOrigin.FLIGHT))
                val next = sortedFlights.getOrNull(index + 1) ?: return@forEachIndexed
                if (next.originCountry != flight.destinationCountry) return@forEachIndexed
                var date = maxOf(flight.arrivalDate.plusDays(1), today.plusDays(1))
                while (date < next.departureDate) {
                    fill(date, flight.destinationCountry)
                    date = date.plusDays(1)
                }
            }
            order(planned, days, DayKind.PLANNED, sortedFlights)
            bracket(days, sortedFlights.filter { it.departureDate <= today }, passes)
            return DayBook(days, today, sortedFlights, options.policies)
        }

        /**
         * Marks the days between a kept pass into a country and the next kept pass out of it,
         * when no other flight comes between, as proven there: the passes show both ends.
         */
        private fun bracket(days: TreeMap<LocalDate, Day>, flown: List<Flight>, passes: Set<Long>) {
            if (passes.isEmpty()) return
            for (i in 0 until flown.size - 1) {
                val inbound = flown[i]
                val outbound = flown[i + 1]
                if (inbound.id !in passes || outbound.id !in passes) continue
                val country = inbound.destinationCountry
                if (outbound.originCountry != country || outbound.departureDate < inbound.arrivalDate) continue
                var date = inbound.arrivalDate
                while (date <= outbound.departureDate) {
                    val day = days[date]
                    if (day != null && country in day.countries) days[date] = day.copy(between = day.between + country)
                    date = date.plusDays(1)
                }
            }
        }

        /**
         * Turns each date's visits into a day with its countries in the order visited. Times
         * decide where there are any; days entered by hand have none, so the neighbours decide:
         * the country of the day before comes first, the one of the day after comes last, and a
         * country on both sides with another in between was left and come back to.
         */
        private fun order(visits: TreeMap<LocalDate, MutableList<Visit>>, days: TreeMap<LocalDate, Day>, kind: DayKind, flights: List<Flight>) {
            for ((date, rows) in visits) {
                val byCountry = rows.groupBy { it.country }.map { (country, list) ->
                    Visit(country, list.mapNotNull { it.first }.minOrNull(), list.mapNotNull { it.last ?: it.first }.maxOrNull(), list.firstNotNullOfOrNull { it.origin }, list.flatMapTo(LinkedHashSet()) { it.sources })
                }
                val previous = days[date.minusDays(1)]
                val next = days[date.plusDays(1)]?.countries?.toSet() ?: visits[date.plusDays(1)]?.map { it.country }?.toSet().orEmpty()
                val path = if (byCountry.size == 1) {
                    listOf(byCountry[0].country)
                } else if (byCountry.all { it.first != null }) {
                    val sorted = byCountry.sortedBy { it.first }
                    val opener = sorted.first()
                    // Seen first and also last: the day left it and came back.
                    if (opener.last != null && sorted.drop(1).all { (it.last ?: it.first!!) < opener.last }) {
                        sorted.map { it.country } + opener.country
                    } else {
                        sorted.map { it.country }
                    }
                } else {
                    val countries = byCountry.map { it.country }.sorted()
                    // The same countries as the day before: a range marked with both, so it goes on as it was.
                    if (previous != null && previous.countries.toSet() == countries.toSet()) {
                        previous.path
                    } else {
                        byNeighbours(countries, previous?.last, previous?.countries.orEmpty().toSet(), next)
                    }
                }
                days[date] = Day(
                    date = date,
                    path = path,
                    kind = kind,
                    transit = transit(date, path, flights),
                    origins = if (kind == DayKind.PLANNED) byCountry.mapNotNull { v -> v.origin?.let { v.country to it } }.toMap() else emptyMap(),
                    sources = if (kind == DayKind.RECORDED) byCountry.associate { it.country to it.sources } else emptyMap(),
                )
            }
        }

        private fun byNeighbours(countries: List<String>, previousLast: String?, previous: Set<String>, next: Set<String>): List<String> {
            val before = countries.filter { it == previousLast || it in previous }
            val after = countries.filter { it in next }
            val bracket = countries.filter { it == previousLast && it in next }
            if (bracket.size == 1 && countries.size > 1 && (countries - bracket.toSet()).none { it in next }) {
                val home = bracket.single()
                return listOf(home) + countries.filter { it != home } + home
            }
            val start = before.sortedByDescending { it == previousLast }.filter { it !in after || after.size > 1 && it == previousLast }
            val end = after.filter { it !in start }
            val middle = countries.filter { it !in start && it !in end }
            return (start + middle + end).distinct()
        }

        /** Countries landed in and flown out of on the same day, in the middle of it. */
        private fun transit(date: LocalDate, path: List<String>, flights: List<Flight>): Set<String> {
            if (path.size < 3) return emptySet()
            val middle = path.subList(1, path.size - 1).toSet() - path.first() - path.last()
            return middle.filter { country ->
                val landed = flights.filter { it.arrivalDate == date && it.destinationCountry == country }.mapNotNull { it.arrivalMinute ?: 0 }
                val left = flights.filter { it.departureDate == date && it.originCountry == country }.mapNotNull { it.departureMinute ?: LAST_MINUTE }
                landed.isNotEmpty() && left.isNotEmpty() && landed.min() <= left.max()
            }.toSet()
        }

        private const val LAST_MINUTE = 23 * 60 + 59

        private fun moment(date: LocalDate, minute: Int): Long =
            date.atStartOfDay(java.time.ZoneId.systemDefault()).plusMinutes(minute.toLong()).toInstant().toEpochMilli()
    }
}

data class DateRange(val start: LocalDate, val end: LocalDate) {
    val days: Int get() = ChronoUnit.DAYS.between(start, end).toInt() + 1

    operator fun contains(date: LocalDate) = date in start..end
}

data class Stay(val country: String, val start: LocalDate, val end: LocalDate, val kind: DayKind) {
    val days: Int get() = ChronoUnit.DAYS.between(start, end).toInt() + 1
}

/** A piece of the journey: a stay in one country, the crossing between two, or a gap. */
sealed interface Leg {
    val start: LocalDate
    val end: LocalDate

    /**
     * The days that ended in [country], from [start] to [end]. [presentEnd] is the day it was
     * left, when that was a travel day ending elsewhere. [counted] is how many of the days
     * present the country itself counts, by its own counting.
     */
    data class StayLeg(
        val country: String,
        override val start: LocalDate,
        override val end: LocalDate,
        val presentEnd: LocalDate,
        val kind: DayKind,
        val counted: Int,
        val planned: Int,
        val arrivedOnTravelDay: Boolean,
    ) : Leg {
        /** Days with any presence, including the travel days at either end. */
        val present: Int get() = ChronoUnit.DAYS.between(start, presentEnd).toInt() + 1
    }

    /** Going from one country to another on [date], through [path]. */
    data class Crossing(
        val date: LocalDate,
        val path: List<String>,
        val transit: Set<String>,
        val flights: List<Flight>,
        val planned: Boolean,
    ) : Leg {
        override val start: LocalDate get() = date
        override val end: LocalDate get() = date
        val from: String get() = path.first()
        val to: String get() = path.last()
    }

    data class GapLeg(val range: DateRange) : Leg {
        override val start: LocalDate get() = range.start
        override val end: LocalDate get() = range.end
    }
}

data class CountryDays(
    val country: String,
    /** Days lived there, counted the way the country counts them. */
    val days: Int,
    val planned: Int,
    /** Days with any presence at all, travel days included. */
    val present: Int = days,
) {
    val total: Int get() = days + planned
}

data class Summary(
    val from: LocalDate,
    val to: LocalDate,
    val countries: List<CountryDays>,
    /** Days with at least one country, so a travel day counts once here. */
    val trackedDays: Int,
    val plannedDays: Int,
    val spanDays: Int,
)
