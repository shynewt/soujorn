package app.sojourn.domain

import app.sojourn.data.CountrySet

/**
 * How a country decides whether a calendar day spent partly there is one of its days. Countries
 * disagree mostly about travel days: Spain counts any moment on its soil, the UK only where you
 * are at midnight, Cyprus the day you arrive but not the day you leave.
 */
enum class DayCounting(val title: String, val summary: String) {
    ANY_PART(
        "Any part of a day",
        "Arrival and departure days both count, even a few hours there",
    ),
    MIDNIGHT(
        "Present at midnight",
        "The day counts where you are at the end of it: arrival days count, departure days don't",
    ),
    ARRIVAL_DAY(
        "Arrival day counts, departure day doesn't",
        "In and out on the same day counts; out and back on the same day doesn't",
    ),
    MORNING(
        "Present at the start of the day",
        "Departure days count, arrival days don't: counting starts the day after you arrive",
    ),
    FULL_DAYS(
        "Only full days",
        "Neither arrival nor departure days count, only whole days there",
    ),
    ;

    /**
     * Whether a day that went through [path] (countries in the order visited, a country repeated
     * when the day left and came back) counts as a day in [set]. Airside [transit] never counts.
     */
    fun counts(path: List<String>, set: CountrySet, transit: Set<String> = emptySet()): Boolean {
        val inside = collapse(path.filter { it !in transit }.map { set.contains(it) })
        if (inside.isEmpty()) return false
        return when (this) {
            ANY_PART -> inside.any { it }
            MIDNIGHT -> inside.last()
            MORNING -> inside.first()
            // A day that starts inside and goes out is a departure (or out-and-back) day; one
            // that starts outside and comes in is an arrival (or in-and-out) day.
            ARRIVAL_DAY -> if (inside.size == 1) inside[0] else !inside.first()
            FULL_DAYS -> inside.size == 1 && inside[0]
        }
    }

    /** Why this day does or doesn't count under this policy, for the day sheet. */
    fun explain(path: List<String>, set: CountrySet, transit: Set<String> = emptySet()): String {
        val visible = path.filter { it !in transit }
        val inside = collapse(visible.map { set.contains(it) })
        if (path.any { it in transit && set.contains(it) } && inside.none { it }) return "Airside transit doesn't count"
        if (inside.size <= 1) return if (inside.firstOrNull() == true) "Whole day there" else "Not there"
        val arriving = !inside.first() && inside.last()
        val leaving = inside.first() && !inside.last()
        val outAndBack = inside.first() && inside.last()
        val inAndOut = !inside.first() && !inside.last()
        val counted = counts(path, set, transit)
        val kind = when {
            arriving -> "Arrival day"
            leaving -> "Departure day"
            outAndBack -> "Left and came back the same day"
            inAndOut -> "In and out on the same day"
            else -> "Travel day"
        }
        return "$kind: ${if (counted) "counts" else "doesn't count"}"
    }

    companion object {
        private fun collapse(flags: List<Boolean>): List<Boolean> {
            val out = ArrayList<Boolean>(flags.size)
            for (flag in flags) if (out.lastOrNull() != flag) out += flag
            return out
        }
    }
}

/** Which counting each country uses: the built-in knowledge, then the user's own choices. */
data class CountingPolicies(
    val overrides: Map<String, DayCounting> = emptyMap(),
    /** For countries Sojourn has no rules for. */
    val fallback: DayCounting = DayCounting.ANY_PART,
) {
    fun of(country: String): DayCounting = overrides[country] ?: CountryRules[country]?.counting ?: fallback

    fun isCustom(country: String): Boolean = country in overrides

    /**
     * A rule's counting when it doesn't choose one: a single country's own, any part of a day for
     * a group like Schengen, and only full days for "anywhere except" sets (time away from home).
     */
    fun forSet(set: CountrySet): DayCounting = when {
        set.except -> DayCounting.FULL_DAYS
        set.codes.size == 1 -> of(set.codes.single())
        else -> DayCounting.ANY_PART
    }
}
