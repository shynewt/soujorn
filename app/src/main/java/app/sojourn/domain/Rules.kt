package app.sojourn.domain

import app.sojourn.data.Countries
import app.sojourn.data.CountryGroup
import app.sojourn.data.CountrySet
import app.sojourn.data.db.Rule
import app.sojourn.data.db.RuleType
import app.sojourn.data.db.RuleWindow
import java.time.LocalDate
import java.time.MonthDay
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max

enum class Level { GOOD, CAUTION, ALERT }

/** What a plan does to a rule, judged over the planned days ahead. */
data class Forecast(val level: Level, val text: String, val date: LocalDate? = null)

data class RuleStatus(
    val rule: Rule,
    val level: Level,
    /** The figure that matters most, e.g. "43 days left". */
    val headline: String,
    /** How the figure came about, e.g. "47 of 90 days used in the last 180 days". */
    val detail: String,
    /** The next useful date or action, if there is one. */
    val note: String?,
    /** 0..1 share of the limit or minimum reached. */
    val progress: Float,
    val forecast: Forecast?,
    val window: DateRange,
    /** True once a minimum is reached, so the card can celebrate instead of nag. */
    val met: Boolean = false,
    /** False for a visitor rule the user is exempt from altogether, e.g. Schengen for EU citizens. */
    val applies: Boolean = true,
    /** Countries of a visitor rule left out because the user may live there. */
    val exempt: Set<String> = emptySet(),
    /** The scale the card draws, with a mark at each threshold. */
    val meter: Meter? = null,
    /** Each test of a residency rule. */
    val tests: List<TestStatus> = emptyList(),
    /** Further lines worth knowing, e.g. Spain's count with sporadic absences. */
    val extras: List<String> = emptyList(),
    val goal: Goal? = null,
    val dayLimit: ResidencyDayLimit? = null,
    /** For residency rules: this tax year's counted days by how well each is backed up. */
    val evidence: EvidenceCount? = null,
)

/** Who is travelling: where they may live decides which visitor limits count at all. */
data class Traveller(
    val citizenships: Set<String> = emptySet(),
    val residencePermits: Set<String> = emptySet(),
    /** Where the user is tax resident (Settings › Tax residence), which changes how some countries count. */
    val taxResidence: String? = null,
) {
    /** A passport that allows living anywhere in the EU, EEA and Switzerland. */
    val freeMovement: String? get() = citizenships.sorted().firstOrNull { it in CountryGroup.FREE_MOVEMENT }

    val mayLiveIn: Set<String>
        get() = citizenships + residencePermits + if (freeMovement != null) CountryGroup.FREE_MOVEMENT else emptySet()
}

object RuleEngine {
    private val dayMonth get() = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
    private val dayMonthYear get() = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())

    /**
     * A visitor rule leaves out the countries the traveller may live in, and doesn't apply at all
     * when that covers every one of them. Tax rules count every day regardless.
     */
    fun evaluate(rule: Rule, book: DayBook, traveller: Traveller = Traveller()): RuleStatus {
        val set = CountrySet.parse(rule.countries)
        if (!rule.forVisitors || set.except) return dispatch(rule, book, set, traveller)
        val exempt = set.codes intersect traveller.mayLiveIn
        if (exempt.isEmpty()) return dispatch(rule, book, set, traveller)
        if (exempt == set.codes) return notForYou(rule, book, set, traveller)
        return dispatch(rule, book, set.copy(codes = set.codes - exempt), traveller).copy(exempt = exempt)
    }

    private fun dispatch(rule: Rule, book: DayBook, set: CountrySet, traveller: Traveller): RuleStatus {
        val c = ResidencyEngine.counting(rule, book, set)
        val status = when (rule.type) {
            RuleType.US_PRESENCE -> usPresence(rule, book, c)
            RuleType.RESIDENCY -> ResidencyEngine.evaluate(rule, book, traveller)
            RuleType.LIMIT -> when (rule.window) {
                RuleWindow.ROLLING -> rollingLimit(rule, book, set, c)
                RuleWindow.YEAR -> yearLimit(rule, book, set, c)
                RuleWindow.STAY -> stayLimit(rule, book, set, c)
            }
            RuleType.MINIMUM -> when (rule.window) {
                RuleWindow.ROLLING -> rollingMinimum(rule, book, set, c)
                RuleWindow.YEAR, RuleWindow.STAY -> yearMinimum(rule, book, set, c)
            }
        }
        if (status.meter != null || rule.type == RuleType.RESIDENCY) return status
        return status.copy(meter = defaultMeter(rule, status))
    }

    /** One mark at the rule's limit or target, the figure as far as it has come. */
    private fun defaultMeter(rule: Rule, status: RuleStatus): Meter? {
        if (!status.applies) return null
        val max = if (rule.type == RuleType.US_PRESENCE) rule.days else rule.days.coerceAtLeast(1)
        val value = (status.progress * max).toInt()
        return Meter(value, 0, max, listOf(Meter.Mark(max, "$max")), minimum = rule.type == RuleType.MINIMUM)
    }

    internal fun plannedTotal(used: Int, planned: Int, period: String): List<String> =
        if (planned > 0) listOf("With plans: ${used + planned} days total ($planned planned) $period") else emptyList()

    /** "the Schengen Area" for a group, a plain name for countries. */
    private fun area(spec: String): String {
        val described = CountrySet.describe(spec)
        return if (spec.split(',').any { CountryGroup.byKey(it.trim()) != null }) "the $described" else described
    }

    private fun notForYou(rule: Rule, book: DayBook, set: CountrySet, traveller: Traveller): RuleStatus {
        val passport = traveller.freeMovement
        val reason = when {
            passport != null && set.codes.all { it in CountryGroup.FREE_MOVEMENT } ->
                "As a citizen of ${Countries.name(passport)}, you can stay in ${area(rule.countries)} as long as you like."
            set.codes.size == 1 -> "You can live in ${Countries.name(set.codes.single())}, so its visitor limit doesn't count."
            else -> "You can live in every country this rule covers."
        }
        return RuleStatus(
            rule = rule,
            level = Level.GOOD,
            headline = "Doesn't apply to you",
            detail = reason,
            note = null,
            progress = 0f,
            forecast = null,
            window = DateRange(book.today, book.today),
            applies = false,
            exempt = set.codes,
        )
    }

    fun yearWindow(date: LocalDate, yearStart: Int): DateRange {
        val start = MonthDay.of(yearStart / 100, yearStart % 100)
        val thisYear = start.atYear(date.year)
        val from = if (thisYear > date) start.atYear(date.year - 1) else thisYear
        return DateRange(from, start.atYear(from.year + 1).minusDays(1))
    }

    fun yearLabel(window: DateRange, yearStart: Int): String =
        if (yearStart == 101) "${window.start.year}" else "${window.start.year}/${(window.end.year % 100).toString().padStart(2, '0')}"

    private fun rollingLimit(rule: Rule, book: DayBook, set: CountrySet, c: DayCounting): RuleStatus {
        val today = book.today
        val width = rule.windowDays.coerceAtLeast(1)
        val limit = rule.days
        val start = today.minusDays(width - 1L)
        val used = book.count(start, today, set, withPlans = false, counting = c)
        val remaining = limit - used
        val level = when {
            used > limit -> Level.ALERT
            remaining <= max(3, limit / 10) -> Level.CAUTION
            else -> Level.GOOD
        }

        // Longest stay starting tomorrow (or continuing today's), assuming every day is spent there.
        val past = BooleanArray(width) { book.counts(start.plusDays(it.toLong()), set, withPlans = false, counting = c) }
        val suffix = IntArray(width + 1)
        for (i in width - 1 downTo 0) suffix[i] = suffix[i + 1] + if (past[i]) 1 else 0
        var stay = 0
        while (stay < width + limit) {
            val k = stay + 1
            // Window for day today+k covers past indices k..width-1 plus the k assumed days.
            val pastInWindow = if (k >= width) 0 else suffix[k]
            if (pastInWindow + k > limit) break
            stay = k
        }
        val presentToday = book.counts(today, set, withPlans = false, counting = c)
        val note = when {
            stay >= width -> "No limit in reach at this pace"
            presentToday && stay > 0 -> "You can stay until ${today.plusDays(stay.toLong()).format(dayMonth)}"
            presentToday -> "Over the limit: leave as soon as possible"
            stay > 0 -> "Arrive tomorrow and you can stay $stay days"
            else -> nextRelease(book, set, today, width, c)?.let { "Next day frees up on ${it.format(dayMonth)}" }
        }

        val forecast = plannedHorizon(book, set, c)?.let { horizon ->
            // A day planned for today, with nothing recorded yet, is in the window too: it's
            // taken off again once it leaves, so it has to be counted in from the start.
            var window = used + if (!book.counts(today, set, withPlans = false, counting = c) && book.counts(today, set, withPlans = true, counting = c)) 1 else 0
            var peak = window
            var breach: LocalDate? = null
            var date = today.plusDays(1)
            while (date <= horizon) {
                if (book.counts(date, set, withPlans = true, counting = c)) window++
                if (book.counts(date.minusDays(width.toLong()), set, withPlans = true, counting = c)) window--
                if (window > limit && breach == null) breach = date
                peak = max(peak, window)
                date = date.plusDays(1)
            }
            if (breach != null) {
                Forecast(Level.ALERT, "Plans break the limit on ${breach.format(dayMonthYear)}, peaking at $peak of $limit", breach)
            } else {
                Forecast(if (limit - peak <= max(3, limit / 10)) Level.CAUTION else Level.GOOD, "Plans stay within the limit, peaking at $peak of $limit")
            }
        }

        return RuleStatus(
            rule = rule,
            level = level,
            headline = if (used > limit) "${used - limit} days over" else plural(remaining, "day") + " left",
            detail = "$used of $limit days used in the last $width days",
            note = note,
            progress = used.toFloat() / limit.coerceAtLeast(1),
            forecast = forecast,
            window = DateRange(start, today),
        )
    }

    private fun nextRelease(book: DayBook, set: CountrySet, today: LocalDate, width: Int, c: DayCounting): LocalDate? {
        var date = today.plusDays(1)
        repeat(width) {
            if (book.counts(date.minusDays(width.toLong()), set, withPlans = false, counting = c)) return date
            date = date.plusDays(1)
        }
        return null
    }

    private fun yearLimit(rule: Rule, book: DayBook, set: CountrySet, c: DayCounting): RuleStatus {
        val today = book.today
        val window = yearWindow(today, rule.yearStart)
        val limit = rule.days
        val used = book.count(window.start, today, set, withPlans = false, counting = c)
        val planned = book.count(today.plusDays(1), window.end, set, withPlans = true, counting = c)
        val projected = used + planned
        val remaining = limit - used
        val level = when {
            used > limit -> Level.ALERT
            remaining <= max(7, limit / 10) -> Level.CAUTION
            else -> Level.GOOD
        }
        val forecast = if (planned == 0) null else if (projected > limit) {
            var running = used
            var breach: LocalDate? = null
            for (day in book.between(today.plusDays(1), window.end)) {
                if (book.counts(day.date, set, withPlans = true, counting = c)) running++
                if (running > limit) {
                    breach = day.date
                    break
                }
            }
            Forecast(Level.ALERT, "Plans pass $limit days on ${breach?.format(dayMonthYear)}; $projected by year end", breach)
        } else {
            Forecast(if (limit - projected <= max(7, limit / 10)) Level.CAUTION else Level.GOOD, "With plans: $projected of $limit days by ${window.end.format(dayMonth)}")
        }
        return RuleStatus(
            rule = rule,
            level = level,
            headline = if (used > limit) "${used - limit} days over" else plural(remaining, "day") + " left",
            detail = "$used of $limit days in ${yearLabel(window, rule.yearStart)}",
            note = "Count resets on ${window.end.plusDays(1).format(dayMonthYear)}",
            progress = used.toFloat() / limit.coerceAtLeast(1),
            forecast = forecast,
            window = window,
            extras = plannedTotal(used, planned, "in ${yearLabel(window, rule.yearStart)}"),
        )
    }

    private fun stayLimit(rule: Rule, book: DayBook, set: CountrySet, c: DayCounting): RuleStatus {
        val today = book.today
        val limit = rule.days
        var run = 0
        var cursor = today
        while (book.counts(cursor, set, withPlans = false, counting = c)) {
            run++
            cursor = cursor.minusDays(1)
        }
        val remaining = limit - run

        val forecast = plannedHorizon(book, set, c)?.let { horizon ->
            var length = run
            var breach: LocalDate? = null
            var longest = run
            var date = today.plusDays(1)
            while (date <= horizon) {
                length = if (book.counts(date, set, withPlans = true, counting = c)) length + 1 else 0
                longest = max(longest, length)
                if (length > limit && breach == null) breach = date
                date = date.plusDays(1)
            }
            if (breach != null) {
                Forecast(Level.ALERT, "${if (set.except) "Planned time away" else "A planned stay"} passes $limit days on ${breach.format(dayMonthYear)}; longest with plans: $longest days", breach)
            } else {
                Forecast(Level.GOOD, "Longest planned ${if (set.except) "time away" else "stay"}: ${plural(longest, "day")} of $limit")
            }
        }
        val level = when {
            run > limit -> Level.ALERT
            run > 0 && remaining <= max(3, limit / 10) -> Level.CAUTION
            else -> Level.GOOD
        }
        return RuleStatus(
            rule = rule,
            level = level,
            // An "anywhere except" set turns a stay limit into a limit on time away from home.
            headline = when {
                run == 0 && set.except -> "In ${home(set)} now"
                run == 0 -> "Not there now"
                run > limit -> "${run - limit} days over"
                else -> plural(remaining, "day") + " left"
            },
            detail = when {
                set.except && run == 0 -> "Away at most $limit days in a row"
                set.except -> "Away for ${plural(run, "day")}, at most $limit in a row"
                run == 0 -> "Up to $limit days per stay"
                else -> "Day $run of $limit on this stay"
            },
            note = when {
                run !in 1..limit -> null
                set.except -> "Be back in ${home(set)} by ${today.plusDays(remaining.toLong()).format(dayMonthYear)}"
                else -> "Leave by ${today.plusDays(remaining.toLong()).format(dayMonthYear)}"
            },
            progress = run.toFloat() / limit.coerceAtLeast(1),
            forecast = forecast,
            window = DateRange(today.minusDays(max(run - 1, 0).toLong()), today),
        )
    }

    private fun yearMinimum(rule: Rule, book: DayBook, set: CountrySet, c: DayCounting): RuleStatus {
        val today = book.today
        val window = yearWindow(today, rule.yearStart)
        val target = rule.days
        val used = book.count(window.start, today, set, withPlans = false, counting = c)
        val planned = book.count(today.plusDays(1), window.end, set, withPlans = true, counting = c)
        val daysLeft = ChronoUnit.DAYS.between(today, window.end).toInt()
        val availableDays = daysLeft + if (book.counts(today, set, withPlans = false, counting = c)) 0 else 1
        val needed = target - used
        val met = used >= target
        val level = when {
            met -> Level.GOOD
            needed > availableDays -> Level.ALERT
            needed > daysLeft - 30 -> Level.CAUTION
            else -> Level.GOOD
        }
        val forecast = if (met || planned == 0) null else if (used + planned >= target) {
            Forecast(Level.GOOD, "On track: plans reach $target days by year end")
        } else {
            Forecast(Level.CAUTION, "Plans fall ${plural(target - used - planned, "day")} short")
        }
        return RuleStatus(
            rule = rule,
            level = level,
            headline = when {
                met -> "Reached"
                needed > availableDays -> "Not reachable this tax year"
                else -> plural(needed, "day") + " to go"
            },
            detail = "$used of $target days in ${yearLabel(window, rule.yearStart)}",
            note = when {
                met -> null
                needed > availableDays -> "Not reachable before ${window.end.format(dayMonthYear)}"
                else -> "Latest start to make it: ${window.end.minusDays(needed - 1L).format(dayMonth)}"
            },
            progress = used.toFloat() / target.coerceAtLeast(1),
            forecast = forecast,
            window = window,
            extras = plannedTotal(used, planned, "in ${yearLabel(window, rule.yearStart)}"),
            met = met,
        )
    }

    private fun rollingMinimum(rule: Rule, book: DayBook, set: CountrySet, c: DayCounting): RuleStatus {
        val today = book.today
        val width = rule.windowDays.coerceAtLeast(1)
        val target = rule.days
        val start = today.minusDays(width - 1L)
        val used = book.count(start, today, set, withPlans = false, counting = c)
        val met = used >= target
        val slack = width - target
        val level = if (met) Level.GOOD else Level.CAUTION
        val forecast = plannedHorizon(book, set, c)?.let { horizon ->
            var window = used + if (!book.counts(today, set, withPlans = false, counting = c) && book.counts(today, set, withPlans = true, counting = c)) 1 else 0
            var date = today.plusDays(1)
            var reached: LocalDate? = null
            while (date <= horizon) {
                if (book.counts(date, set, withPlans = true, counting = c)) window++
                if (book.counts(date.minusDays(width.toLong()), set, withPlans = true, counting = c)) window--
                if (window >= target && reached == null) reached = date
                date = date.plusDays(1)
            }
            if (reached != null) {
                Forecast(Level.GOOD, "Plans reach $target days on ${reached.format(dayMonthYear)}", reached)
            } else {
                Forecast(Level.CAUTION, "Plans don't reach $target days in any $width-day window yet")
            }
        }
        return RuleStatus(
            rule = rule,
            level = level,
            headline = if (met) "Reached" else plural(target - used, "day") + " to go",
            detail = "$used of $target days in the last $width days",
            note = if (met) "Window: ${start.format(dayMonthYear)} – ${today.format(dayMonthYear)}" else "Up to ${plural(slack, "day")} may be spent elsewhere",
            progress = used.toFloat() / target.coerceAtLeast(1),
            forecast = forecast,
            window = DateRange(start, today),
            met = met,
        )
    }

    private fun usPresence(rule: Rule, book: DayBook, c: DayCounting): RuleStatus {
        val us = CountrySet(setOf("US"))
        val today = book.today
        val year = today.year
        fun countYear(y: Int) = book.count(LocalDate.of(y, 1, 1), LocalDate.of(y, 12, 31), us, withPlans = false, counting = c)
        val current = book.count(LocalDate.of(year, 1, 1), today, us, withPlans = false, counting = c)
        val planned = book.count(today.plusDays(1), LocalDate.of(year, 12, 31), us, withPlans = true, counting = c)
        val previous = countYear(year - 1)
        val before = countYear(year - 2)
        val carried = previous / 3.0 + before / 6.0
        val score = current + carried
        val threshold = rule.days.toDouble()
        val meets = current >= 31 && score >= threshold
        // Days still possible this year without meeting the test: the weighted total stays
        // below the threshold, or the year stays under the 31-day floor.
        val belowThreshold = (ceil(threshold - carried).toInt() - 1 - current)
        val allowed = max(max(belowThreshold, 30 - current), 0)
        val projectedMeets = current + planned >= 31 && score + planned >= threshold
        val level = when {
            meets -> Level.ALERT
            projectedMeets || allowed <= 15 -> Level.CAUTION
            else -> Level.GOOD
        }
        val forecast = if (planned == 0) null else Forecast(
            if (projectedMeets) Level.ALERT else Level.GOOD,
            "With plans: ${current + planned} US days this year; ${"%.1f".format(Locale.getDefault(), score + planned)} of ${rule.days} weighted days. " +
                if (projectedMeets) "You'd meet the test" else "You stay under the test",
        )
        return RuleStatus(
            rule = rule,
            level = level,
            headline = if (meets) "Test met" else plural(allowed, "day") + " left",
            detail = "$current + $previous/3 + $before/6 = ${"%.1f".format(Locale.getDefault(), score)} of ${rule.days}",
            note = if (meets) "You count as a US tax resident for $year" else "US days you can still spend in $year",
            progress = (score / threshold).toFloat(),
            forecast = forecast,
            window = DateRange(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31)),
        )
    }

    /** The last planned day, when any planned day falls in [set]. */
    private fun plannedHorizon(book: DayBook, set: CountrySet, c: DayCounting): LocalDate? {
        val last = book.lastPlanned ?: return null
        val any = book.between(book.today.plusDays(1), last).any { it.kind == DayKind.PLANNED && c.counts(it.path, set, it.transit) }
        return if (any) last else null
    }

    private fun home(set: CountrySet) = set.codes.singleOrNull()?.let { Countries.name(it) } ?: "your home countries"

    private fun plural(n: Int, word: String) = "$n $word${if (n == 1) "" else "s"}"
}

/** Ready-made rules; [template] fills in a sensible default for the chosen country. */
enum class RulePreset(val title: String, val description: String, val needsCountry: Boolean, val unitedStates: Boolean = false) {
    SCHENGEN("Schengen 90/180", "For visitors: at most 90 days in any 180 across the Schengen Area", false),
    AVOID_RESIDENCY("Avoid a tax residency", "Stay under every test that would make you tax resident, with the country's own day counting and tax year", true),
    KEEP_RESIDENCY("Keep a tax residency", "Meet one of a country's residency tests each year, so you stay resident there", true),
    CONTINUITY("Residence continuity", "Don't stay away more than 6 months in a row, as EU long-term residence, citizenship and many permits require", true),
    VISA_STAY("Visa-free stay", "Limit the length of a single stay, like 30 or 60 days per entry", true),
    CUSTOM("Custom rule", "Any limit or minimum, rolling or per year", false),
    US_PRESENCE("US substantial presence", "For non-US citizens: stay clear of US tax residency", false, unitedStates = true),
    FEIE("FEIE physical presence", "For US taxpayers abroad: 330 full days outside the US in 12 months", false, unitedStates = true),
    ;

    fun template(country: String? = null): Rule = when (this) {
        SCHENGEN -> Rule(name = title, type = RuleType.LIMIT, countries = "@schengen", days = 90, window = RuleWindow.ROLLING, windowDays = 180, forVisitors = true, counting = DayCounting.ANY_PART.name)
        AVOID_RESIDENCY -> ResidencyEngine.rule(country ?: "ES", Goal.AVOID)
        KEEP_RESIDENCY -> ResidencyEngine.rule(country ?: "PT", Goal.KEEP)
        CONTINUITY -> Rule(
            name = "Residence in ${country?.let { Countries.name(it) } ?: "my country"}",
            type = RuleType.LIMIT,
            countries = "!${country.orEmpty()}",
            days = 182,
            window = RuleWindow.STAY,
        )
        VISA_STAY -> Rule(
            name = "${country?.let { Countries.name(it) } ?: "Visa-free"} stay",
            type = RuleType.LIMIT,
            countries = country.orEmpty(),
            days = country?.let { VISA_FREE_DAYS[it] } ?: 30,
            window = RuleWindow.STAY,
            forVisitors = true,
            // Immigration counts the days you're let in, arrival and departure included.
            counting = DayCounting.ANY_PART.name,
        )
        US_PRESENCE -> Rule(name = title, type = RuleType.US_PRESENCE, countries = "US", days = 183, window = RuleWindow.YEAR)
        FEIE -> Rule(name = title, type = RuleType.MINIMUM, countries = "!US", days = 330, window = RuleWindow.ROLLING, windowDays = 365, counting = DayCounting.FULL_DAYS.name)
        CUSTOM -> Rule(name = "", type = RuleType.LIMIT, countries = country.orEmpty(), days = 90, window = RuleWindow.YEAR)
    }

    companion object {
        /** Common visa-free stay lengths, only as a starting point for the user to adjust. */
        val VISA_FREE_DAYS = mapOf("TH" to 60, "ID" to 30, "VN" to 45, "MY" to 90, "PH" to 30, "MX" to 180, "GB" to 180, "JP" to 90, "KR" to 90, "GE" to 365, "AL" to 90, "RS" to 90, "TR" to 90, "AE" to 90)
    }
}
