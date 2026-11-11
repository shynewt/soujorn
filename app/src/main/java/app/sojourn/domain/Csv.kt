package app.sojourn.domain

object Csv {
    /** Leading characters a spreadsheet would run as a formula. */
    private val FORMULA_START = setOf('=', '+', '-', '@', '\t', '\r')

    fun field(value: String): String {
        val safe = if (value.firstOrNull() in FORMULA_START) "'$value" else value
        return if (safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"${safe.replace("\"", "\"\"")}\"" else safe
    }
}
