package app.sojourn.data

import java.text.Collator
import java.util.Locale

object Countries {
    /** ISO 3166 codes plus Kosovo, which has no official code but its own entry stamps. */
    val codes: List<String> = (Locale.getISOCountries().toList() + "XK").distinct()

    fun name(code: String, locale: Locale = Locale.getDefault()): String {
        if (code == "XK") return "Kosovo"
        val region = runCatching { Locale.Builder().setRegion(code).build() }.getOrNull() ?: return code
        val name = region.getDisplayCountry(locale)
        return name.ifBlank { code }
    }

    /** Builds the flag emoji from the two regional indicator symbols of the code. */
    fun flag(code: String): String {
        if (code.length != 2 || !code.all { it in 'A'..'Z' }) return "🏳️"
        val first = Character.toChars(0x1F1E6 + (code[0] - 'A'))
        val second = Character.toChars(0x1F1E6 + (code[1] - 'A'))
        return String(first) + String(second)
    }

    fun sortedByName(locale: Locale = Locale.getDefault()): List<String> {
        val collator = Collator.getInstance(locale)
        return codes.sortedWith { a, b -> collator.compare(name(a, locale), name(b, locale)) }
    }
}

/** Named sets of countries that rules can refer to, so membership changes apply to old rules. */
enum class CountryGroup(val key: String, val label: String, val members: Set<String>) {
    // Bulgaria and Romania joined fully in January 2025. Cyprus and Ireland are not members.
    SCHENGEN(
        "@schengen",
        "Schengen Area",
        setOf(
            "AT", "BE", "BG", "HR", "CZ", "DK", "EE", "FI", "FR", "DE", "GR", "HU", "IS", "IT", "LV",
            "LI", "LT", "LU", "MT", "NL", "NO", "PL", "PT", "RO", "SK", "SI", "ES", "SE", "CH",
        ),
    ),
    EU(
        "@eu",
        "European Union",
        setOf(
            "AT", "BE", "BG", "HR", "CY", "CZ", "DK", "EE", "FI", "FR", "DE", "GR", "HU", "IE", "IT",
            "LV", "LT", "LU", "MT", "NL", "PL", "PT", "RO", "SK", "SI", "ES", "SE",
        ),
    ),
    ;

    companion object {
        fun byKey(key: String) = entries.firstOrNull { it.key == key }

        /** EU, EEA and Swiss citizens may live anywhere in here, so visitor limits don't apply. */
        val FREE_MOVEMENT: Set<String> = EU.members + setOf("IS", "LI", "NO", "CH")
    }
}

/**
 * The countries a rule counts, written as a comma separated spec: "PT", "@schengen", "ES,PT",
 * or "!US" for "anywhere except the United States".
 */
data class CountrySet(val codes: Set<String>, val except: Boolean = false) {
    /**
     * Whether a day spent in [countries] counts. An "except" set only counts days spent entirely
     * outside it, which is what the FEIE physical presence test calls a full day abroad.
     */
    fun matches(countries: List<String>): Boolean = when {
        countries.isEmpty() -> false
        except -> countries.none { it in codes }
        else -> countries.any { it in codes }
    }

    /** Whether a day in [country] is inside this set: one of the codes, or for "except" sets, none of them. */
    fun contains(country: String): Boolean = if (except) country !in codes else country in codes

    fun label(): String {
        val names = codes.map { Countries.name(it) }.sorted()
        val base = when (names.size) {
            0 -> "No countries"
            1, 2, 3 -> names.joinToString(", ")
            else -> "${names.take(2).joinToString(", ")} +${names.size - 2}"
        }
        return if (except) "Outside $base" else base
    }

    companion object {
        fun parse(spec: String): CountrySet {
            val except = spec.startsWith("!")
            val codes = spec.removePrefix("!").split(',').map { it.trim() }.filter { it.isNotEmpty() }
                .flatMap { CountryGroup.byKey(it)?.members ?: setOf(it.uppercase()) }
                .toSet()
            return CountrySet(codes, except)
        }

        /** Human label that keeps group names instead of listing every member. */
        fun describe(spec: String): String {
            val except = spec.startsWith("!")
            val parts = spec.removePrefix("!").split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val label = parts.joinToString(", ") { CountryGroup.byKey(it)?.label ?: Countries.name(it) }
            return if (except) "Outside $label" else label
        }
    }
}
