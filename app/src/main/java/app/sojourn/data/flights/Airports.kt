package app.sojourn.data.flights

import app.sojourn.data.Countries
import java.io.InputStream
import java.text.Normalizer

/** An airport with scheduled flights, from OurAirports (tools/build_airports.py). */
data class Airport(val code: String, val size: Int, val name: String, val city: String, val country: String) {
    /** "Lisbon", or the airport's name where OurAirports has no city. */
    val place: String get() = city.ifBlank { name }
}

class AirportDirectory(val airports: List<Airport>) {
    private val byCode = airports.associateBy { it.code }
    private val folded = airports.map { fold("${it.code} ${it.city} ${it.name} ${Countries.name(it.country)}") }

    operator fun get(code: String): Airport? = byCode[code.uppercase()]

    /**
     * Best matches first: the exact code, then cities and names starting with the query, then
     * anything containing it; bigger airports first within each, since they're likelier picks.
     */
    fun search(query: String, limit: Int = 30): List<Airport> {
        val needle = fold(query.trim())
        if (needle.isEmpty()) return emptyList()
        return airports.indices
            .mapNotNull { i ->
                val airport = airports[i]
                val rank = when {
                    airport.code.equals(needle, ignoreCase = true) -> 0
                    fold(airport.city).startsWith(needle) -> 1
                    fold(airport.name).startsWith(needle) -> 2
                    folded[i].contains(needle) -> 3
                    else -> return@mapNotNull null
                }
                Triple(rank, -airport.size, airport)
            }
            .sortedWith(compareBy({ it.first }, { it.second }, { it.third.city }))
            .take(limit)
            .map { it.third }
    }

    companion object {
        fun read(input: InputStream): AirportDirectory = AirportDirectory(
            input.bufferedReader().lineSequence().mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size < 5) null else Airport(parts[0], parts[1].toIntOrNull() ?: 0, parts[2], parts[3], parts[4])
            }.toList(),
        )

        private fun fold(text: String) = Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase()
    }
}

/** Common airlines by IATA code, so "TP 1234" can read "TAP Air Portugal". */
object Airlines {
    private val names = mapOf(
        // Europe
        "TP" to "TAP Air Portugal", "S4" to "Azores Airlines", "IB" to "Iberia", "VY" to "Vueling", "UX" to "Air Europa",
        "V7" to "Volotea", "FR" to "Ryanair", "U2" to "easyJet", "W6" to "Wizz Air", "W4" to "Wizz Air Malta", "W9" to "Wizz Air UK", "LH" to "Lufthansa", "EW" to "Eurowings",
        "DE" to "Condor", "LX" to "Swiss", "OS" to "Austrian", "SN" to "Brussels Airlines", "KL" to "KLM", "HV" to "Transavia",
        "AF" to "Air France", "TO" to "Transavia France", "BA" to "British Airways", "VS" to "Virgin Atlantic", "LS" to "Jet2",
        "BY" to "TUI Airways", "X3" to "TUI fly", "EI" to "Aer Lingus", "AY" to "Finnair", "SK" to "SAS", "DY" to "Norwegian",
        "WF" to "Widerøe", "FI" to "Icelandair", "OG" to "PLAY", "AZ" to "ITA Airways", "NO" to "Neos", "LO" to "LOT",
        "A3" to "Aegean", "OA" to "Olympic Air", "CY" to "Cyprus Airways", "KM" to "KM Malta Airlines", "BT" to "airBaltic",
        "RO" to "TAROM", "FB" to "Bulgaria Air", "JU" to "Air Serbia", "OU" to "Croatia Airlines", "LG" to "Luxair",
        "TK" to "Turkish Airlines", "PC" to "Pegasus", "XQ" to "SunExpress",
        // Middle East and Africa
        "EK" to "Emirates", "FZ" to "flydubai", "EY" to "Etihad", "G9" to "Air Arabia", "QR" to "Qatar Airways",
        "SV" to "Saudia", "WY" to "Oman Air", "GF" to "Gulf Air", "KU" to "Kuwait Airways", "RJ" to "Royal Jordanian",
        "ME" to "Middle East Airlines", "LY" to "El Al", "MS" to "EgyptAir", "AT" to "Royal Air Maroc", "ET" to "Ethiopian",
        "KQ" to "Kenya Airways", "SA" to "South African Airways",
        // Americas
        "AA" to "American Airlines", "DL" to "Delta", "UA" to "United", "B6" to "JetBlue", "WN" to "Southwest",
        "AS" to "Alaska Airlines", "AC" to "Air Canada", "WS" to "WestJet", "AM" to "Aeroméxico", "Y4" to "Volaris",
        "LA" to "LATAM", "AV" to "Avianca", "CM" to "Copa Airlines", "G3" to "GOL", "AD" to "Azul", "AR" to "Aerolíneas Argentinas",
        // Asia and Oceania
        "SQ" to "Singapore Airlines", "TR" to "Scoot", "CX" to "Cathay Pacific", "JL" to "Japan Airlines", "NH" to "ANA",
        "KE" to "Korean Air", "OZ" to "Asiana", "CI" to "China Airlines", "BR" to "EVA Air", "MH" to "Malaysia Airlines",
        "AK" to "AirAsia", "FD" to "Thai AirAsia", "TG" to "Thai Airways", "VN" to "Vietnam Airlines", "VJ" to "VietJet",
        "GA" to "Garuda Indonesia", "PR" to "Philippine Airlines", "5J" to "Cebu Pacific", "AI" to "Air India", "6E" to "IndiGo",
        "CA" to "Air China", "MU" to "China Eastern", "CZ" to "China Southern", "HU" to "Hainan Airlines",
        "QF" to "Qantas", "JQ" to "Jetstar", "VA" to "Virgin Australia", "NZ" to "Air New Zealand",
    )

    /** ICAO codes, which airlines' own emails and reminders use ("EZY8653"), to IATA ones. */
    private val icao = mapOf(
        "EZY" to "U2", "EJU" to "U2", "EZS" to "U2", "RYR" to "FR", "RUK" to "FR", "VLG" to "VY", "WZZ" to "W6", "WMT" to "W4",
        "WUK" to "W9", "IBE" to "IB", "AEA" to "UX", "VOE" to "V7", "BAW" to "BA", "DLH" to "LH", "EWG" to "EW", "AFR" to "AF",
        "KLM" to "KL", "TRA" to "HV", "TAP" to "TP", "SWR" to "LX", "AUA" to "OS", "EIN" to "EI", "ITY" to "AZ", "THY" to "TK",
        "PGT" to "PC", "NOZ" to "DY", "SAS" to "SK", "FIN" to "AY", "LOT" to "LO", "AEE" to "A3", "TOM" to "BY", "EXS" to "LS",
        "UAE" to "EK", "QTR" to "QR", "ETD" to "EY",
    )

    /** "EZY8653" → "U2 8653", for a known airline's ICAO code; null otherwise. */
    fun fromIcao(code: String, digits: String): String? = icao[code.uppercase()]?.let { format(it + digits) }

    fun name(flightNumber: String): String? = names[airline(flightNumber)]

    fun isKnown(code: String) = code in names

    /** "TP1234" or "TP 1234" → "TP". */
    fun airline(flightNumber: String): String = flightNumber.trim().take(2).uppercase()

    /** "tp1234", "TP 1234" → "TP 1234". */
    fun format(flightNumber: String): String {
        val clean = flightNumber.uppercase().filter { it.isLetterOrDigit() }
        return if (clean.length > 2) "${clean.take(2)} ${clean.drop(2).trimStart('0').ifEmpty { "0" }}" else clean
    }
}
