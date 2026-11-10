package app.sojourn.domain

import app.sojourn.data.Countries
import app.sojourn.data.CountrySet
import app.sojourn.data.db.Rule
import app.sojourn.data.db.RuleType
import app.sojourn.data.db.RuleWindow
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.max
import kotlin.math.roundToInt

/** Whether the user wants to stay clear of a country's tax residency or hold on to it. */
@Serializable
enum class Goal { AVOID, KEEP }

@Serializable
enum class TestKind {
    /** At least [ResidencyTest.days] days in the tax year, or in any [ResidencyTest.windowDays] when rolling. */
    DAYS,

    /** A stay of at least [ResidencyTest.days] days in a row, where breaks up to [ResidencyTest.maxBreak] days don't interrupt it. */
    CONTINUOUS,

    /**
     * Several tax years weighed together: the current year times weights[0], the year before
     * times weights[1] and so on, reaching [ResidencyTest.days] (Ireland 280 over two years, the
     * US 183 with a third and a sixth, India 365 over the four years before).
     */
    MULTI_YEAR,

    /** The UK's sufficient ties test: the more ties, the fewer days make you resident. */
    UK_TIES,

    /** No days involved: met when the user says so (a home, the centre of their interests). */
    DECLARED,
}

@Serializable
enum class ConditionKind {
    /** A yes or no fact only the user knows. */
    DECLARED,

    /** No other single country had more than [Condition.days] days in the tax year. */
    NO_OTHER_COUNTRY_OVER,
}

@Serializable
data class Condition(val id: String, val text: String, val kind: ConditionKind = ConditionKind.DECLARED, val days: Int = 0)

/**
 * One way to become (or, with [below], to automatically not be) tax resident in a country.
 * [days] is the least number of days that meets the test; [strict] tells it was written as
 * "more than [days] - 1", so it reads the way the law says it.
 */
@Serializable
data class ResidencyTest(
    val id: String,
    val name: String,
    val kind: TestKind = TestKind.DAYS,
    val days: Int = 0,
    val strict: Boolean = false,
    /** Null for the tax year; otherwise any window of this many days. */
    val windowDays: Int? = null,
    val maxBreak: Int = 0,
    val weights: List<Double> = emptyList(),
    /** Least days needed in the current year itself, for multi-year tests. */
    val currentMin: Int = 0,
    /** Least days needed in every year of a multi-year test. */
    val yearMin: Int = 0,
    /** Years with fewer days than this count as none in a multi-year test (Ireland ignores 30 or fewer). */
    val ignoreBelow: Int = 0,
    /** A test that makes you not resident while you stay below it, whatever else applies (UK). */
    val below: Boolean = false,
    /** A rule of thumb rather than law, e.g. France has no day test, only 183 days as a sign. */
    val indicative: Boolean = false,
    val conditions: List<Condition> = emptyList(),
    val note: String = "",
    val enabled: Boolean = true,
) {
    /** "more than 183 days" or "183 days or more". */
    fun threshold(): String = if (strict) "more than ${days - 1} days" else "$days days or more"
}

/** Spain's sporadic absences: days abroad on trips from Spain and back count as days in Spain. */
@Serializable
data class SporadicOptions(
    /** Use the count with absences for the rule; off shows it beside the plain count only. */
    val apply: Boolean = false,
    /** Absences longer than this aren't sporadic (Supreme Court, 28 Nov 2017). */
    val maxRun: Int = 183,
    /**
     * Whether you can prove tax residence in another country, usually with its tax residence
     * certificate. Then no absence is added at all (art. 9.1.a LIRPF). Null is not answered
     * yet, and counts as no: the law presumes absences sporadic until residence is proven.
     */
    val certificate: Boolean? = null,
) {
    /** Whether the absences are left out: only with proof of tax residence elsewhere. */
    val provenElsewhere: Boolean get() = certificate == true
}

/**
 * Spain's tax havens, its "jurisdicciones no cooperativas" (Orden HFP/115/2023 as amended by
 * Orden HAC/649/2026, in force from 28 Jun 2026, which took off Barbados, Dominica, Gibraltar,
 * Samoa, Seychelles and Trinidad and Tobago). For a tax residence in one of them, Hacienda can
 * ask you to prove 183 days spent there in the year (art. 9.1.a LIRPF).
 */
val SPAIN_TAX_HAVENS = setOf(
    "AI", "BH", "BM", "FJ", "GU", "GG", "IM", "KY", "FK", "MP", "SB", "TC", "VG", "VI", "JE", "PW", "AS", "VU",
)

@Serializable
data class ResidencyParams(
    val country: String,
    val goal: Goal = Goal.AVOID,
    val tests: List<ResidencyTest> = emptyList(),
    /** Answers to declared conditions and tests, by id; missing means not answered yet. */
    val answers: Map<String, Boolean> = emptyMap(),
    val sporadic: SporadicOptions? = null,
) {
    fun encode(): String = json.encodeToString(serializer(), this)

    fun answer(id: String): Boolean? = if (id.startsWith("!")) answers[id.drop(1)]?.not() else answers[id]

    /** Every question the tests ask, once each, negated ones as their plain question. */
    fun questions(): List<Condition> = (
        tests.filter { it.enabled }.flatMap { test ->
            test.conditions.filter { it.kind == ConditionKind.DECLARED }.map { if (it.id.startsWith("!")) it.copy(id = it.id.drop(1), text = QUESTION_TEXT[it.id.drop(1)] ?: it.text) else it } +
                listOfNotNull(Condition(test.id, test.name).takeIf { test.kind == TestKind.DECLARED }) +
                if (test.kind == TestKind.UK_TIES) ResidencyEngine.UK_TIE_QUESTIONS + Condition(ResidencyEngine.UK_PRIOR, QUESTION_TEXT.getValue(ResidencyEngine.UK_PRIOR)) else emptyList()
        }
        ).distinctBy { it.id }

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }

        private val QUESTION_TEXT = mapOf(ResidencyEngine.UK_PRIOR to "UK resident in any of the last 3 tax years")

        fun decode(text: String): ResidencyParams? = if (text.isBlank()) null else runCatching { json.decodeFromString(serializer(), text) }.getOrNull()

        /** Sojourn's rules for [country], for a new rule or to reset an edited one. */
        fun fromCatalogue(country: String, goal: Goal): ResidencyParams {
            val known = CountryRules[country]
            return ResidencyParams(
                country = country,
                goal = goal,
                tests = known?.tests ?: listOf(ResidencyTest("days", "183-day rule", days = 184, strict = true)),
                sporadic = if (known?.sporadicAbsences == true) SporadicOptions() else null,
            )
        }
    }
}

/** How one test stands today and, with plans, by the end of the tax year. */
data class TestStatus(
    val test: ResidencyTest,
    /** The figure the test measures: days this year, the current stay, or a weighted total. */
    val used: Int,
    val projected: Int,
    /** Days needed to meet the test, which for the UK ties test depends on the ties. */
    val needed: Int,
    val daysMet: Boolean,
    val projectedMet: Boolean,
    /** When plans first meet it. */
    val reachedOn: LocalDate?,
    /** Conditions known to fail (answered no, or computed false). */
    val failing: List<String>,
    /** Declared conditions not answered yet. */
    val pending: List<Condition>,
    val detail: String,
    /** Upper bound by tax-year end; null when this test has no calculated bound. */
    val maximum: Int? = null,
) {
    val met: Boolean get() = daysMet && failing.isEmpty() && pending.isEmpty()
    val possible: Boolean get() = failing.isEmpty() && test.enabled
    val left: Int get() = max(needed - used, 0)
    val outOfReach: Boolean get() = !test.below && !daysMet && maximum?.let { it < needed } == true
}

data class ResidencyDayLimit(
    val used: Int,
    val projected: Int,
    val needed: Int,
    val maximum: Int,
    val withAbsences: Int? = null,
    val ongoingAbsence: Int = 0,
) {
    val needsAbsenceReview: Boolean
        get() = maximum + ((withAbsences ?: used) - used) + ongoingAbsence >= needed
}

/** A static scale with the day figure, what plans add, and a mark at each threshold. */
data class Meter(val value: Int, val planned: Int, val max: Int, val marks: List<Mark>, val minimum: Boolean = false) {
    data class Mark(val at: Int, val label: String)
}

object ResidencyEngine {
    private val dayMonth get() = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
    private val dayMonthYear get() = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())

    fun counting(rule: Rule, book: DayBook, set: CountrySet): DayCounting =
        rule.counting?.let { runCatching { DayCounting.valueOf(it) }.getOrNull() } ?: book.policies.forSet(set)

    fun evaluate(rule: Rule, book: DayBook, traveller: Traveller = Traveller()): RuleStatus {
        val params = ResidencyParams.decode(rule.params) ?: ResidencyParams.fromCatalogue(rule.countries, Goal.AVOID)
        val country = params.country
        val set = CountrySet(setOf(country))
        val counting = counting(rule, book, set)
        val today = book.today
        val year = RuleEngine.yearWindow(today, rule.yearStart)
        val label = RuleEngine.yearLabel(year, rule.yearStart)
        // Sporadic absences are only added while you can't prove tax residence elsewhere.
        val residentElsewhere = traveller.taxResidence?.takeIf { it != country }
        val absencesExcluded = params.sporadic?.provenElsewhere == true
        // Counted either way, so the number is there to see; added only without the proof.
        val absences = params.sporadic?.let { Sporadic.count(book, country, year, counting, it) }
        val sporadic = absences?.takeIf { !absencesExcluded }
        val extraNow = if (params.sporadic?.apply == true && sporadic != null) sporadic.days else 0
        val extraPlanned = if (params.sporadic?.apply == true && sporadic != null) sporadic.withPlans - sporadic.days else 0

        val remainingDays = ChronoUnit.DAYS.between(today, year.end).toInt()
        val availableDays = remainingDays + if (book.counts(today, set, withPlans = false, counting = counting)) 0 else 1
        val tests = params.tests.filter { it.enabled }.map { test ->
            val status = evaluateTest(test, params, book, set, counting, year, label, extraNow, extraPlanned)
            // Applied sporadic absences can add past days when an ongoing trip ends.
            val boundedByYear = test.kind == TestKind.DAYS && test.windowDays == null && (params.sporadic?.apply != true || absencesExcluded)
            if (boundedByYear) status.copy(maximum = status.used + availableDays) else status
        }
        val resident = tests.filter { !it.test.below }
        val safe = tests.firstOrNull { it.test.below && it.met }
        val metNow = resident.filter { it.met && !it.test.indicative }
        val possible = resident.filter { it.possible && it.test.kind != TestKind.DECLARED }
        val conditional = possible.filter { it.daysMet && it.pending.isNotEmpty() }
        val outOfReach = possible.filter { it.outOfReach }
        val closest = possible.filter { !it.daysMet && !it.outOfReach }.minByOrNull { it.left }
        val name = Countries.name(country)

        val yearTests = possible.filter { it.test.kind == TestKind.DAYS && it.test.windowDays == null || it.test.kind == TestKind.UK_TIES }
        val firstDays = tests.firstOrNull { it.test.kind == TestKind.DAYS || it.test.kind == TestKind.UK_TIES }
        val shown = yearTests.firstOrNull() ?: firstDays
        val meter = shown?.let { primary ->
            val marks = (yearTests.ifEmpty { listOf(primary) }).map { Meter.Mark(it.needed, it.test.name) }.distinctBy { it.at }.sortedBy { it.at }
            Meter(primary.used, max(primary.projected - primary.used, 0), marks.maxOf { it.at }.coerceAtLeast(1), marks, minimum = params.goal == Goal.KEEP)
        }
        val lived = book.count(year.start, today, set, withPlans = false, counting = counting)
        val dayLimit = possible.takeIf { safe == null && metNow.isEmpty() && it.isNotEmpty() && it.all { test -> test.outOfReach } }
            ?.minByOrNull { it.needed }?.let { test ->
                ResidencyDayLimit(
                    used = test.used,
                    projected = test.projected,
                    needed = test.needed,
                    maximum = requireNotNull(test.maximum),
                    withAbsences = sporadic?.takeIf { it.days > 0 }?.let { lived + it.days },
                    ongoingAbsence = sporadic?.ongoing?.takeIf { it in 1..params.sporadic.maxRun } ?: 0,
                )
            }

        val dayLimitNote = dayLimit?.let {
            "Even if you stay in $name until ${year.end.format(dayMonthYear)}, you won't reach ${it.needed} days there."
        }
        val (level, headline, note) = when (params.goal) {
            Goal.AVOID -> when {
                safe != null && metNow.none { it.test.kind == TestKind.DECLARED } -> Triple(Level.GOOD, "Not resident", safe.detail)
                metNow.isNotEmpty() -> Triple(Level.ALERT, "Tax resident in $label", "Met: ${metNow.joinToString { it.test.name }}")
                conditional.isNotEmpty() -> Triple(
                    Level.CAUTION,
                    "Resident if ${conditional.first().pending.joinToString(" and ") { it.text.replaceFirstChar(Char::lowercaseChar) }}",
                    "${conditional.first().test.name}: days reached; answer its questions in the rule",
                )
                dayLimit != null -> Triple(Level.GOOD, "Not enough days left", dayLimitNote)
                closest != null -> {
                    val left = closest.left - 1
                    val caution = left <= max(7, closest.needed / 10) || possible.any { it.projectedMet }
                    Triple(
                        if (caution) Level.CAUTION else Level.GOOD,
                        "${plural(max(left, 0), "day")} left",
                        "Before the ${closest.test.name.replaceFirstChar(Char::lowercaseChar)}${if (closest.pending.isNotEmpty()) " (if ${closest.pending.joinToString(" and ") { it.text.replaceFirstChar(Char::lowercaseChar) }})" else ""}",
                    )
                }
                else -> Triple(Level.GOOD, "No test in reach", "None of $name's tests can be met with your answers")
            }
            Goal.KEEP -> {
                val reached = metNow.firstOrNull()
                val daysLeft = ChronoUnit.DAYS.between(today, year.end).toInt()
                when {
                    reached != null -> Triple(Level.GOOD, "Resident in $label", "Through the ${reached.test.name.replaceFirstChar(Char::lowercaseChar)}")
                    conditional.isNotEmpty() -> Triple(Level.CAUTION, "Days reached", "Resident if ${conditional.first().pending.joinToString(" and ") { it.text.replaceFirstChar(Char::lowercaseChar) }}")
                    dayLimit != null -> Triple(Level.ALERT, "Not enough days left", dayLimitNote)
                    closest == null -> Triple(Level.ALERT, "No test reachable", "With your answers, none of $name's tests can be met")
                    closest.left > availableDays -> Triple(Level.ALERT, "${plural(closest.left, "day")} to go", "Not reachable before ${year.end.format(dayMonthYear)}")
                    else -> Triple(
                        if (closest.left > daysLeft - 30) Level.CAUTION else Level.GOOD,
                        "${plural(closest.left, "day")} to go",
                        "For the ${closest.test.name.replaceFirstChar(Char::lowercaseChar)}; latest start ${year.end.minusDays(closest.left - 1L).format(dayMonth)}",
                    )
                }
            }
        }

        val forecast = run {
            val lastPlanned = book.lastPlanned ?: return@run null
            if (lastPlanned <= today || possible.isNotEmpty() && possible.all { it.outOfReach }) return@run null
            val breach = possible.filter { !it.daysMet && it.projectedMet }.minByOrNull { it.reachedOn ?: LocalDate.MAX }
            when (params.goal) {
                Goal.AVOID -> if (breach != null) {
                    Forecast(Level.ALERT, "Plans meet the ${breach.test.name.replaceFirstChar(Char::lowercaseChar)}${breach.reachedOn?.let { " on ${it.format(dayMonthYear)}" } ?: " in $label"}", breach.reachedOn)
                } else shown?.let { Forecast(Level.GOOD, "With plans: ${it.projected} of ${it.needed} days by ${year.end.format(dayMonth)}") }
                Goal.KEEP -> if (breach != null) {
                    Forecast(Level.GOOD, "Plans reach the ${breach.test.name.replaceFirstChar(Char::lowercaseChar)}${breach.reachedOn?.let { " on ${it.format(dayMonthYear)}" } ?: " in $label"}", breach.reachedOn)
                } else if (metNow.isEmpty()) shown?.let { Forecast(Level.CAUTION, "Plans fall ${plural(max(it.needed - it.projected, 0), "day")} short") } else null
            }
        }

        val extras = buildList {
            val planned = book.count(today.plusDays(1), year.end, set, withPlans = true, counting = counting)
            addAll(RuleEngine.plannedTotal(lived, planned, "in $label"))
            if (sporadic != null) {
                add(
                    if (params.sporadic.apply) "Includes ${plural(sporadic.days, "day")} of sporadic absences"
                    else "Counting sporadic absences: ${lived + sporadic.days} days (${plural(sporadic.days, "day")} abroad)",
                )
                if (sporadic.ongoing > 0) add("${plural(sporadic.ongoing, "day")} away so far would be added if you come back within ${params.sporadic.maxRun} days")
                if (sporadic.days > lived) add("More days abroad than in Spain: courts may not accept counting these as Spanish days")
                if (params.sporadic.certificate == null && residentElsewhere != null) {
                    add("You're tax resident in ${Countries.name(residentElsewhere)}: with its tax residence certificate for $label, no absences are added. Say so in this rule")
                }
            } else if (params.sporadic != null && absences != null) {
                add(
                    "Sporadic absences: ${plural(absences.days, "day")} abroad, not added: you can prove tax residence " +
                        (residentElsewhere?.let { "in ${Countries.name(it)}. Keep its certificate for $label" } ?: "in another country"),
                )
                if (residentElsewhere != null && residentElsewhere in SPAIN_TAX_HAVENS) {
                    val there = book.count(year.start, today, CountrySet(setOf(residentElsewhere)), withPlans = false)
                    add("${Countries.name(residentElsewhere)} is on Spain's list of tax havens: Hacienda can ask you to prove 183 days there in $label. So far: ${plural(there, "day")}")
                }
            }
            if (outOfReach.isNotEmpty() && closest == null && metNow.isEmpty() &&
                resident.any { it.test.kind == TestKind.DECLARED && it.possible }
            ) {
                add("Other residency conditions may still apply")
            }
        }

        return RuleStatus(
            rule = rule,
            level = level,
            headline = headline,
            detail = "$lived days in $name in $label · ${counting.title.replaceFirstChar(Char::lowercaseChar)}",
            note = note,
            progress = meter?.let { it.value.toFloat() / it.max } ?: 0f,
            forecast = forecast,
            window = year,
            met = params.goal == Goal.KEEP && metNow.isNotEmpty(),
            meter = meter,
            tests = tests,
            extras = extras,
            goal = params.goal,
            dayLimit = dayLimit,
            evidence = book.evidence(year.start, today, set, counting).takeIf { it.total > 0 },
        )
    }

    private fun evaluateTest(
        test: ResidencyTest,
        params: ResidencyParams,
        book: DayBook,
        set: CountrySet,
        counting: DayCounting,
        year: DateRange,
        label: String,
        extraNow: Int,
        extraPlanned: Int,
    ): TestStatus {
        val today = book.today
        val failing = mutableListOf<String>()
        val pending = mutableListOf<Condition>()
        for (condition in test.conditions) {
            when (condition.kind) {
                // "!id" asks the opposite of the question "id", so one answer serves both.
                ConditionKind.DECLARED -> when (params.answer(condition.id)) {
                    true -> Unit
                    false -> failing += condition.text
                    null -> pending += condition
                }
                ConditionKind.NO_OTHER_COUNTRY_OVER -> {
                    val other = book.summary(year.start, minOf(year.end, today)).countries.filter { it.country != params.country }.maxByOrNull { it.days }
                    if (other != null && other.days > condition.days) failing += "${Countries.name(other.country)} had ${other.days} days"
                }
            }
        }

        fun result(used: Int, projected: Int, needed: Int, reachedOn: LocalDate?, detail: String): TestStatus {
            val daysMet = if (test.below) used < needed else used >= needed
            return TestStatus(test, used, projected, needed, daysMet, if (test.below) projected < needed else projected >= needed, reachedOn, failing, pending, detail)
        }

        return when (test.kind) {
            TestKind.DECLARED -> {
                val answer = params.answers[test.id]
                val testPending = if (answer == null) listOf(Condition(test.id, test.name)) else emptyList()
                TestStatus(test, 0, 0, 0, answer == true, answer == true, null, if (answer == false) listOf(test.name) else failing, testPending + pending, test.note.ifBlank { test.name })
            }
            TestKind.DAYS -> if (test.windowDays == null) {
                val used = book.count(year.start, today, set, false, counting) + extraNow
                var running = used
                var reached: LocalDate? = null
                for (day in book.between(today.plusDays(1), year.end)) {
                    if (counting.counts(day.path, set, day.transit)) running++
                    if (running >= test.days && reached == null && used < test.days) reached = day.date
                }
                result(used, running + extraPlanned, test.days, reached, "$used of ${test.days} days in $label")
            } else {
                val width = test.windowDays.toLong()
                val used = book.count(today.minusDays(width - 1), today, set, false, counting)
                // The highest count of any window ending this tax year so far, then with plans.
                var peak = 0
                var date = year.start
                var window = book.count(year.start.minusDays(width - 1), year.start, set, false, counting)
                while (date <= today) {
                    if (date > year.start) {
                        if (book.counts(date, set, false, counting)) window++
                        if (book.counts(date.minusDays(width), set, false, counting)) window--
                    }
                    peak = max(peak, window)
                    date = date.plusDays(1)
                }
                var projected = peak
                var reached: LocalDate? = null
                val horizon = minOf(year.end, book.lastPlanned ?: today)
                window = used
                date = today.plusDays(1)
                while (date <= horizon) {
                    if (book.counts(date, set, true, counting)) window++
                    if (book.counts(date.minusDays(width), set, true, counting)) window--
                    if (window > projected) projected = window
                    if (window >= test.days && reached == null && peak < test.days) reached = date
                    date = date.plusDays(1)
                }
                result(max(peak, used), projected, test.days, reached, "$used of ${test.days} days in the last ${months(test.windowDays)}${if (peak > used) " (peak $peak)" else ""}")
            }
            TestKind.CONTINUOUS -> {
                // Runs of days there, bridging breaks up to maxBreak; the longest touching this tax year.
                val from = year.start.minusDays(test.days.toLong() + test.maxBreak)
                val horizon = maxOf(today, minOf(year.end, book.lastPlanned ?: today))
                var run = 0
                var gap = 0
                var longest = 0
                var current = 0
                var projected = 0
                var reached: LocalDate? = null
                var date = from
                while (date <= horizon) {
                    val there = book.counts(date, set, withPlans = date > today, counting = counting)
                    if (there) {
                        run += gap + 1
                        gap = 0
                    } else if (run > 0) {
                        gap++
                        if (gap > test.maxBreak) {
                            run = 0
                            gap = 0
                        }
                    }
                    if (date <= today) {
                        if (date >= year.start) longest = max(longest, run)
                        current = if (gap == 0) run else 0
                    } else if (run >= test.days && reached == null && longest < test.days) {
                        reached = date
                    }
                    if (date >= year.start) projected = max(projected, run)
                    date = date.plusDays(1)
                }
                result(
                    max(current, longest),
                    projected,
                    test.days,
                    reached,
                    if (current > 0) "Day $current of ${test.days} in a row" else "Longest stay this year: $longest of ${test.days} days",
                )
            }
            TestKind.MULTI_YEAR -> {
                val weights = test.weights.ifEmpty { listOf(1.0) }
                val counts = weights.indices.map { back ->
                    val window = RuleEngine.yearWindow(year.start.minusYears(back.toLong()), monthDay(year.start))
                    book.count(window.start, minOf(window.end, today), set, false, counting) + if (back == 0) extraNow else 0
                }
                val planned = book.count(today.plusDays(1), year.end, set, true, counting) + extraPlanned
                fun total(current: Int): Double = weights.indices.sumOf { i ->
                    val n = if (i == 0) current else counts[i]
                    if (n < test.ignoreBelow) 0.0 else n * weights[i]
                }
                val yearsOk = counts.drop(1).zip(weights.drop(1)).all { (n, w) -> w == 0.0 && test.yearMin == 0 || n >= test.yearMin }
                if (!yearsOk) failing += "Fewer than ${test.yearMin} days in an earlier year"
                val score = total(counts[0])
                val needed = test.days
                val current = counts[0]
                val daysMet = current >= max(test.currentMin, test.yearMin) && score >= needed
                val projectedScore = total(current + planned)
                val parts = weights.indices.joinToString(" + ") { i -> if (weights[i] == 1.0) "${counts[i]}" else if (weights[i] == 0.0) "" else "${counts[i]}×${fraction(weights[i])}" }.replace(" +  + ", " + ").trim(' ', '+')
                TestStatus(
                    test,
                    score.roundToInt(),
                    projectedScore.roundToInt(),
                    needed,
                    daysMet,
                    current + planned >= max(test.currentMin, test.yearMin) && projectedScore >= needed,
                    null,
                    failing,
                    pending,
                    buildString {
                        append("$parts = ${score.roundToInt()} of $needed")
                        if (test.currentMin > 0) append(", with $current of ${test.currentMin} this year")
                    },
                )
            }
            TestKind.UK_TIES -> {
                val leaver = params.answers[UK_PRIOR] == true
                val used = book.count(year.start, today, set, false, counting)
                val ties = ukTies(params, book, set, year, leaver)
                val needed = ukThreshold(ties, leaver)
                val projected = used + book.count(today.plusDays(1), year.end, set, true, counting)
                val pendingTies = UK_TIE_QUESTIONS.filter { params.answers[it.id] == null }
                TestStatus(
                    test,
                    used,
                    projected,
                    needed ?: 183,
                    needed != null && used >= needed,
                    needed != null && projected >= needed,
                    null,
                    failing,
                    pending + pendingTies + listOfNotNull(Condition(UK_PRIOR, "UK resident in any of the last 3 tax years").takeIf { params.answers[UK_PRIOR] == null }),
                    "${plural(ties, "tie")}: ${needed?.let { "$it days make you resident" } ?: "no ties threshold below 183 days"}",
                )
            }
        }
    }

    const val UK_PRIOR = "uk_prior"
    val UK_TIE_QUESTIONS = listOf(
        Condition("uk_family", "Your spouse, partner or minor children live in the UK"),
        Condition("uk_home", "A place to live in the UK, used at least one night"),
        Condition("uk_work", "40 or more days working over 3 hours in the UK"),
    )

    private fun ukTies(params: ResidencyParams, book: DayBook, set: CountrySet, year: DateRange, leaver: Boolean): Int {
        var ties = UK_TIE_QUESTIONS.count { params.answers[it.id] == true }
        // 90-day tie: more than 90 days in either of the two previous tax years.
        val previous = (1..2).map { back ->
            val window = RuleEngine.yearWindow(year.start.minusYears(back.toLong()), monthDay(year.start))
            book.count(window.start, window.end, set, false, DayCounting.MIDNIGHT)
        }
        if (previous.any { it > 90 }) ties++
        // Country tie, for leavers: more midnights in the UK than in any other single country.
        if (leaver) {
            val end = minOf(year.end, book.today)
            val midnights = book.between(year.start, end).groupingBy { it.last }.eachCount()
            val uk = midnights[set.codes.single()] ?: 0
            if (uk > 0 && midnights.all { (c, n) -> c == set.codes.single() || n <= uk }) ties++
        }
        return ties
    }

    /** The days from which the given number of ties makes you resident; null when none do. */
    fun ukThreshold(ties: Int, leaver: Boolean): Int? = if (leaver) {
        when {
            ties >= 4 -> 16
            ties == 3 -> 46
            ties == 2 -> 91
            ties == 1 -> 121
            else -> null
        }
    } else {
        when {
            ties >= 4 -> 46
            ties == 3 -> 91
            ties == 2 -> 121
            else -> null
        }
    }

    private fun monthDay(date: LocalDate) = date.monthValue * 100 + date.dayOfMonth

    private fun months(days: Int) = if (days % 365 == 0) "${days / 365 * 12} months" else if (days % 30 == 0) "${days / 30} months" else "$days days"

    private fun fraction(weight: Double) = when (weight) {
        1.0 / 3 -> "⅓"
        1.0 / 6 -> "⅙"
        0.5 -> "½"
        else -> "%.2f".format(Locale.getDefault(), weight)
    }

    private fun plural(n: Int, word: String) = "$n $word${if (n == 1) "" else "s"}"

    /** A residency rule for [country], from Sojourn's catalogue. */
    fun rule(country: String, goal: Goal): Rule {
        val known = CountryRules[country]
        val params = ResidencyParams.fromCatalogue(country, goal)
        val first = params.tests.firstOrNull { it.kind == TestKind.DAYS || it.kind == TestKind.CONTINUOUS }
        return Rule(
            name = "${if (goal == Goal.AVOID) "Avoid" else "Keep"} ${Countries.name(country)} tax residency",
            type = RuleType.RESIDENCY,
            countries = country,
            days = first?.days ?: 184,
            window = RuleWindow.YEAR,
            windowDays = 365,
            yearStart = known?.yearStart ?: 101,
            params = params.encode(),
        )
    }
}

/** Spain's sporadic absences, worked out the way the courts read article 9.1.a of the IRPF law. */
object Sporadic {
    /** [days] lived absences in the year; [withPlans] including planned ones; [ongoing] the current trip away. */
    data class Result(val days: Int, val withPlans: Int, val ongoing: Int)

    /**
     * An absence is a run of days without [country], with days there on both sides. Its whole
     * length counts, across the year end, and one longer than [SporadicOptions.maxRun] isn't
     * sporadic. Only its days inside [year] are added. A trip still going on isn't added yet.
     */
    fun count(book: DayBook, country: String, year: DateRange, counting: DayCounting, options: SporadicOptions): Result {
        val set = CountrySet(setOf(country))
        val from = year.start.minusDays(options.maxRun.toLong() + 1)
        val to = year.end.plusDays(options.maxRun.toLong() + 1)
        var lived = 0
        var planned = 0
        var ongoing = 0
        var runStart: LocalDate? = null
        var sawHome = false
        var date = from
        while (date <= to) {
            val day = book[date]
            val home = day != null && counting.counts(day.path, set, day.transit)
            if (home) {
                val start = runStart
                if (start != null && sawHome) {
                    val length = ChronoUnit.DAYS.between(start, date).toInt()
                    if (length <= options.maxRun) {
                        val inYear = ChronoUnit.DAYS.between(maxOf(start, year.start), minOf(date.minusDays(1), year.end)).toInt() + 1
                        if (inYear > 0) {
                            if (date <= book.today) lived += inYear else planned += inYear
                        }
                    }
                }
                runStart = null
                sawHome = true
            } else if (runStart == null && sawHome) {
                runStart = date
            }
            if (date == book.today && runStart != null && sawHome) ongoing = ChronoUnit.DAYS.between(runStart, date).toInt() + 1
            if (date > book.today && book.lastPlanned?.let { date > it } != false) break
            date = date.plusDays(1)
        }
        return Result(lived, lived + planned, if (runStart != null) ongoing else 0)
    }
}
