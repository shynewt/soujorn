package app.sojourn.domain

import app.sojourn.domain.DayCounting.ANY_PART
import app.sojourn.domain.DayCounting.ARRIVAL_DAY
import app.sojourn.domain.DayCounting.MIDNIGHT
import app.sojourn.domain.DayCounting.MORNING

enum class Confidence(val label: String) {
    HIGH("Checked against the law or the tax authority"),
    MEDIUM("From reliable summaries; details may differ"),
    LOW("A rough guide only; check with an adviser"),
}

/**
 * What Sojourn knows about a country's individual tax residency: how it counts a day, when its
 * tax year starts, and every test that can make someone resident. Researched in October 2026,
 * mostly from PwC Worldwide Tax Summaries and the tax authorities; a starting point the user can
 * adjust, never tax advice.
 */
data class CountryRule(
    val country: String,
    val counting: DayCounting,
    /** True when the law says how part days count; false when it's silent and practice is assumed. */
    val countingStated: Boolean,
    val tests: List<ResidencyTest>,
    val yearStart: Int = 101,
    val confidence: Confidence = Confidence.MEDIUM,
    val notes: String = "",
    val source: String = "",
    /** Spain's sporadic absences, which add days abroad to days in Spain. */
    val sporadicAbsences: Boolean = false,
)

object CountryRules {
    operator fun get(country: String): CountryRule? = all[country]

    private fun wts(slug: String) = "https://taxsummaries.pwc.com/$slug/individual/residence"

    // Builders that read like the law: "more than 183 days" is days(184, strict).
    private fun moreThan(n: Int, name: String = "${n}-day rule", window: Int? = null, conditions: List<Condition> = emptyList(), note: String = "", id: String = "days") =
        ResidencyTest(id, name, TestKind.DAYS, n + 1, strict = true, windowDays = window, conditions = conditions, note = note)

    private fun atLeast(n: Int, name: String = "${n}-day rule", window: Int? = null, conditions: List<Condition> = emptyList(), note: String = "", id: String = "days") =
        ResidencyTest(id, name, TestKind.DAYS, n, windowDays = window, conditions = conditions, note = note)

    private fun continuous(n: Int, name: String, maxBreak: Int = 30, note: String = "", id: String = "stay", conditions: List<Condition> = emptyList()) =
        ResidencyTest(id, name, TestKind.CONTINUOUS, n, maxBreak = maxBreak, note = note, conditions = conditions)

    private fun declared(id: String, name: String, note: String = "") = ResidencyTest(id, name, TestKind.DECLARED, note = note)

    private fun ask(id: String, text: String) = Condition(id, text)

    private const val YEAR_12M = 365

    private val home = declared("home", "Your permanent home is here")
    private val interests = declared("interests", "The centre of your economic or personal life is here")

    val all: Map<String, CountryRule> = listOf(
        CountryRule(
            "CY", ARRIVAL_DAY, true,
            listOf(
                moreThan(183, "183-day rule"),
                atLeast(
                    60, "60-day rule", id = "sixty",
                    conditions = listOf(
                        Condition("other183", "No more than 183 days in any other single country", ConditionKind.NO_OTHER_COUNTRY_OVER, 183),
                        ask("cy_work", "You have a business, job or directorship of a Cyprus resident company all year"),
                        ask("cy_home", "You own or rent a permanent home in Cyprus"),
                    ),
                    note = "Since the 2026 reform, being tax resident elsewhere no longer rules it out",
                ),
            ),
            confidence = Confidence.HIGH, source = wts("cyprus"),
            notes = "Cyprus counts the day you arrive but not the day you leave. Arriving and leaving on the same day counts as a day in Cyprus; leaving and coming back on the same day counts as a day abroad.",
        ),
        CountryRule(
            "ES", ANY_PART, true,
            listOf(
                moreThan(183, "183-day rule"),
                declared("interests", "The main base of your business or economic interests is in Spain"),
                declared("family", "Your spouse and minor children live in Spain", note = "A presumption you can rebut"),
            ),
            confidence = Confidence.HIGH, source = "https://www.boe.es/buscar/act.php?id=BOE-A-2006-20764",
            sporadicAbsences = true,
            notes = "Any moment in Spain makes the whole day Spanish (TEAC 2023). Sporadic absences, trips away from Spain and back, are added to the days in Spain unless you can prove tax residence in another country (its tax residence certificate; for a tax haven, Hacienda can also ask for 183 days there). Say in the rule whether you have that certificate. An absence over 183 days in a row isn't sporadic (Supreme Court, 28 Nov 2017).",
        ),
        CountryRule(
            "PT", MIDNIGHT, true,
            listOf(moreThan(183, "183-day rule", window = YEAR_12M), declared("home", "A home in Portugal you mean to keep as your usual residence")),
            confidence = Confidence.HIGH, source = wts("portugal"),
            notes = "A day counts when it includes a night spent in Portugal. The 183 days are counted in any 12 months starting or ending in the tax year.",
        ),
        CountryRule(
            "IT", ANY_PART, true, listOf(atLeast(183, "183-day rule"), declared("domicile", "Your domicile, the centre of your personal and family life, is in Italy")),
            confidence = Confidence.HIGH, source = wts("italy"),
            notes = "Since 2024 fractions of a day count as a whole day. In a leap year the test is 184 days.",
        ),
        CountryRule(
            "FR", ANY_PART, false,
            listOf(
                declared("foyer", "Your household or main home is in France"),
                declared("work", "Your main job or business is in France"),
                declared("interests", "The centre of your economic interests is in France"),
                moreThan(183, "Main place of stay").copy(indicative = true, note = "Only a sign of your main place of stay when you have no household anywhere"),
            ),
            confidence = Confidence.HIGH, source = wts("france"), notes = "France has no day test in law.",
        ),
        CountryRule(
            "DE", ANY_PART, false,
            listOf(continuous(184, "Habitual abode", note = "More than six months in a row, which may span two years; short trips don't break it"), declared("home", "A home in Germany you keep and use")),
            source = wts("germany"),
        ),
        CountryRule("AT", ANY_PART, false, listOf(continuous(184, "Habitual abode"), declared("home", "A home in Austria you keep and use")), source = wts("austria")),
        CountryRule("LU", ANY_PART, false, listOf(continuous(184, "Six months in a row"), declared("home", "A home in Luxembourg you actually use")), source = wts("luxembourg")),
        CountryRule("FI", ANY_PART, false, listOf(continuous(184, "Six months in a row"), declared("home", "Your permanent home is in Finland")), source = wts("finland")),
        CountryRule("DK", ANY_PART, false, listOf(continuous(180, "Six months in a row", note = "Holidays abroad don't break it; work trips abroad do"), declared("home", "A home in Denmark and a stay that isn't a holiday")), source = wts("denmark")),
        CountryRule("SE", ANY_PART, false, listOf(continuous(180, "Six months in a row"), declared("home", "Your home is in Sweden")), source = wts("sweden"), notes = "A proposal would change this from 2027 to more than 160 days with overnight stays."),
        CountryRule("GR", ANY_PART, false, listOf(moreThan(183, "183-day rule", window = YEAR_12M), interests), source = "https://www.aade.gr/en/greeks-abroad-non-residents/income-taxation/tax-residence-natural-persons-itc", notes = "Stays only for tourism or medical care up to 365 days don't make you resident."),
        CountryRule("MT", ANY_PART, false, listOf(moreThan(183, "183 days").copy(indicative = true, note = "Malta has few written rules; this is how it's usually read")), confidence = Confidence.LOW, source = wts("malta")),
        CountryRule(
            "IE", ANY_PART, true,
            listOf(
                atLeast(183, "183-day rule"),
                ResidencyTest("two", "280 days over two years", TestKind.MULTI_YEAR, 280, weights = listOf(1.0, 1.0), currentMin = 31, ignoreBelow = 31, note = "A year with 30 days or fewer doesn't count"),
            ),
            confidence = Confidence.HIGH, source = wts("ireland"),
        ),
        CountryRule(
            "GB", MIDNIGHT, true,
            listOf(
                ResidencyTest("overseas16", "First automatic overseas test", TestKind.DAYS, 16, below = true, conditions = listOf(ask(ResidencyEngine.UK_PRIOR, "UK resident in any of the last 3 tax years")), note = "Fewer than 16 days: not resident"),
                ResidencyTest("overseas46", "Second automatic overseas test", TestKind.DAYS, 46, below = true, conditions = listOf(Condition("!" + ResidencyEngine.UK_PRIOR, "Not UK resident in any of the last 3 tax years")), note = "Fewer than 46 days: not resident"),
                atLeast(183, "183-day automatic test"),
                declared("only_home", "Your only home is in the UK, held at least 91 days and used 30 or more"),
                declared("uk_fulltime", "You work full-time in the UK"),
                ResidencyTest("ties", "Sufficient ties test", TestKind.UK_TIES, note = "The more UK ties you have, the fewer days make you resident"),
            ),
            yearStart = 406, confidence = Confidence.HIGH, source = "https://www.gov.uk/government/publications/rdr3-statutory-residence-test-srt",
            notes = "The UK counts the days you're there at midnight; arriving and leaving the next day while in transit doesn't count.",
        ),
        CountryRule("NL", ANY_PART, false, listOf(interests), source = wts("netherlands"), notes = "No day test: residence follows your circumstances."),
        CountryRule("BE", ANY_PART, false, listOf(declared("home", "Your home or the seat of your wealth is in Belgium")), source = wts("belgium"), notes = "No day test."),
        CountryRule("CH", ANY_PART, false, listOf(continuous(30, "30 days working", maxBreak = 7, id = "work", conditions = listOf(ask("ch_job", "You work in Switzerland"))), continuous(90, "90 days in a row"), home), source = wts("switzerland")),
        CountryRule("HR", ANY_PART, false, listOf(atLeast(183, "183-day rule"), home), source = wts("croatia")),
        CountryRule("CZ", ANY_PART, true, listOf(atLeast(183, "183-day rule"), home), confidence = Confidence.HIGH, source = wts("czech-republic")),
        CountryRule("PL", ANY_PART, false, listOf(moreThan(183, "183-day rule"), interests), source = wts("poland")),
        CountryRule("HU", ANY_PART, false, listOf(atLeast(183, "183-day rule"), home), source = wts("hungary")),
        CountryRule("RO", ANY_PART, false, listOf(moreThan(183, "183-day rule", window = YEAR_12M), interests), source = wts("romania")),
        CountryRule("BG", ANY_PART, false, listOf(moreThan(183, "183-day rule", window = YEAR_12M), interests), source = wts("bulgaria")),
        CountryRule("EE", ANY_PART, true, listOf(moreThan(183, "183-day rule", window = YEAR_12M)), confidence = Confidence.HIGH, source = wts("estonia")),
        CountryRule("LV", ANY_PART, false, listOf(atLeast(183, "183-day rule", window = YEAR_12M)), source = wts("latvia")),
        CountryRule(
            "LT", ANY_PART, false,
            listOf(atLeast(183, "183-day rule"), ResidencyTest("two", "280 days over two years", TestKind.MULTI_YEAR, 280, weights = listOf(1.0, 1.0), yearMin = 90)),
            confidence = Confidence.HIGH, source = wts("lithuania"),
        ),
        CountryRule("SI", ANY_PART, false, listOf(moreThan(183, "183-day rule"), interests), source = wts("slovenia")),
        CountryRule("SK", ANY_PART, true, listOf(atLeast(183, "183-day rule"), home), confidence = Confidence.HIGH, source = wts("slovakia")),
        CountryRule(
            "NO", ANY_PART, true,
            listOf(moreThan(183, "183 days in 12 months", window = YEAR_12M), moreThan(270, "270 days in 36 months", window = 3 * 365, id = "three")),
            confidence = Confidence.HIGH, source = wts("norway"),
        ),
        CountryRule("IS", ANY_PART, false, listOf(moreThan(183, "183-day rule", window = YEAR_12M)), source = wts("iceland")),
        CountryRule("GE", ANY_PART, false, listOf(atLeast(183, "183 days in 12 months", window = YEAR_12M)), source = wts("georgia")),
        CountryRule("RS", ANY_PART, false, listOf(atLeast(183, "183 days in 12 months", window = YEAR_12M), interests), source = wts("serbia")),
        CountryRule("ME", ANY_PART, false, listOf(atLeast(183, "183-day rule"), interests), source = wts("montenegro")),
        CountryRule("AL", ANY_PART, false, listOf(moreThan(183, "183-day rule")), source = wts("albania")),
        CountryRule("MK", ANY_PART, false, listOf(atLeast(183, "183 days in 12 months", window = YEAR_12M)), source = wts("north-macedonia")),
        CountryRule("TR", ANY_PART, false, listOf(moreThan(183, "Six months in a year"), declared("settle", "You live in Turkey meaning to settle")), source = wts("turkey")),
        CountryRule(
            "AE", ANY_PART, false,
            listOf(
                atLeast(183, "183 days in 12 months", window = YEAR_12M),
                atLeast(
                    90, "90-day rule", window = YEAR_12M, id = "ninety",
                    conditions = listOf(
                        ask("ae_status", "You're a UAE or GCC national, or hold a UAE residence permit"),
                        ask("ae_tie", "You have a permanent home or a job or business in the UAE"),
                    ),
                ),
                declared("interests", "Your usual residence and the centre of your interests are in the UAE"),
            ),
            confidence = Confidence.HIGH, source = wts("united-arab-emirates"),
        ),
        CountryRule(
            "IL", ANY_PART, true,
            listOf(
                atLeast(183, "183-day rule"),
                ResidencyTest("three", "425 days over three years", TestKind.MULTI_YEAR, 425, weights = listOf(1.0, 1.0, 1.0), currentMin = 30),
                declared("centre", "The centre of your life is in Israel"),
            ),
            confidence = Confidence.HIGH, source = wts("israel"), notes = "The day tests are presumptions; the centre of your life decides.",
        ),
        CountryRule("TH", ANY_PART, false, listOf(atLeast(180, "180-day rule")), source = wts("thailand")),
        CountryRule("MY", ANY_PART, true, listOf(atLeast(182, "182-day rule")), source = wts("malaysia"), notes = "Malaysia has further tests linking stays across years, not counted here."),
        CountryRule("ID", ANY_PART, false, listOf(moreThan(183, "183 days in 12 months", window = YEAR_12M), declared("intent", "You're in Indonesia meaning to live there")), source = wts("indonesia")),
        CountryRule("VN", ANY_PART, false, listOf(atLeast(183, "183-day rule"), atLeast(183, "183 days in 12 months", window = YEAR_12M, id = "rolling"), home), source = wts("vietnam")),
        CountryRule("PH", ANY_PART, false, listOf(declared("intent", "You live in the Philippines with no definite intention to leave")), confidence = Confidence.LOW, source = wts("philippines"), notes = "Residence depends on intention; 180 days only changes how a non-resident is taxed."),
        CountryRule("JP", ANY_PART, false, listOf(declared("domicile", "Your domicile is in Japan, or you live there a year or longer")), source = wts("japan"), notes = "No day test."),
        CountryRule(
            "KR", MORNING, true,
            listOf(atLeast(183, "183-day rule"), continuous(183, "183 days in a row", maxBreak = 0, id = "straddle", note = "From 2026, also across two tax years")),
            source = wts("republic-of-korea"), notes = "Korea counts from the day after you arrive until the day you leave.",
        ),
        CountryRule("SG", ANY_PART, true, listOf(atLeast(183, "183-day rule"), continuous(183, "183 days in a row", maxBreak = 0, id = "straddle", note = "Across two years makes you resident in both")), confidence = Confidence.HIGH, source = wts("singapore")),
        CountryRule(
            "IN", ANY_PART, false,
            listOf(
                atLeast(182, "182-day rule"),
                ResidencyTest(
                    "sixty", "60-day rule", TestKind.MULTI_YEAR, 365, weights = listOf(0.0, 1.0, 1.0, 1.0, 1.0), currentMin = 60,
                    note = "60 days this year and 365 in the four years before. It's 120 or 182 days for some Indian citizens and people of Indian origin",
                ),
            ),
            yearStart = 401, source = wts("india"),
        ),
        CountryRule("LK", ANY_PART, false, listOf(atLeast(183, "183 days in 12 months", window = YEAR_12M)), yearStart = 401, source = "https://www.ird.gov.lk"),
        CountryRule(
            "AU", ANY_PART, true,
            listOf(
                atLeast(183, "183-day test", conditions = listOf(ask("au_abode", "Your usual home is not outside Australia, or you mean to live there"))),
                declared("resides", "You reside in Australia by ordinary standards"),
                declared("domicile", "Your domicile is Australia and your permanent home isn't abroad"),
            ),
            yearStart = 701, confidence = Confidence.HIGH, source = wts("australia"),
        ),
        CountryRule("NZ", ANY_PART, true, listOf(moreThan(183, "183 days in 12 months", window = YEAR_12M), declared("abode", "You have a permanent place of abode in New Zealand")), yearStart = 401, confidence = Confidence.HIGH, source = wts("new-zealand")),
        CountryRule("CA", ANY_PART, true, listOf(atLeast(183, "Sojourner rule"), declared("ties", "You have significant residential ties to Canada (home, spouse, dependants)")), confidence = Confidence.HIGH, source = wts("canada")),
        CountryRule(
            "US", ANY_PART, true,
            listOf(
                ResidencyTest("spt", "Substantial presence test", TestKind.MULTI_YEAR, 183, weights = listOf(1.0, 1.0 / 3, 1.0 / 6), currentMin = 31),
                declared("greencard", "You hold a green card"),
            ),
            confidence = Confidence.HIGH, source = wts("united-states"),
            notes = "Transit under 24 hours between two foreign places doesn't count, nor do days as an exempt individual (students, teachers, diplomats).",
        ),
        CountryRule("MX", ANY_PART, false, listOf(declared("home", "Your home is in Mexico"), declared("interests", "More than half your income or your main professional base is in Mexico")), confidence = Confidence.HIGH, source = wts("mexico"), notes = "No day test."),
        CountryRule("BR", ANY_PART, false, listOf(moreThan(183, "183 days in 12 months", window = YEAR_12M), declared("permanent", "You hold a permanent visa or a Brazilian job")), source = wts("brazil")),
        CountryRule("AR", ANY_PART, false, listOf(continuous(366, "Twelve months", maxBreak = 30)), confidence = Confidence.LOW, source = wts("argentina")),
        CountryRule("CO", ANY_PART, false, listOf(moreThan(183, "183 days in 365", window = YEAR_12M)), source = wts("colombia")),
        CountryRule("CR", ANY_PART, true, listOf(moreThan(183, "183-day rule")), source = wts("costa-rica"), notes = "Short trips abroad may still count unless you prove residence elsewhere."),
        CountryRule("PA", ANY_PART, false, listOf(moreThan(183, "183-day rule")), source = wts("panama")),
        CountryRule(
            "ZA", ANY_PART, true,
            listOf(
                ResidencyTest(
                    "presence", "Physical presence test", TestKind.MULTI_YEAR, 916, weights = listOf(0.0, 1.0, 1.0, 1.0, 1.0, 1.0), currentMin = 92, yearMin = 92,
                    note = "More than 91 days this year and in each of the five before, and more than 915 over those five",
                ),
                declared("ordinary", "You're ordinarily resident in South Africa"),
            ),
            yearStart = 301, confidence = Confidence.HIGH, source = wts("south-africa"),
        ),
        CountryRule("EG", ANY_PART, false, listOf(moreThan(183, "183 days in 12 months", window = YEAR_12M), home), confidence = Confidence.LOW, source = wts("egypt")),
        CountryRule("MA", ANY_PART, false, listOf(moreThan(183, "183 days in 365", window = YEAR_12M), home), source = wts("morocco")),
    ).associateBy { it.country }
}
