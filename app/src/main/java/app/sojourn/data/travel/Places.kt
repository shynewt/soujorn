package app.sojourn.data.travel

import app.sojourn.data.flights.Airport
import app.sojourn.data.flights.AirportDirectory
import java.text.Normalizer
import java.time.ZoneId

/**
 * Finds the airport a calendar event means by a place. Calendar apps write cities in the
 * phone's language ("Vuelo a Londres", "Flug nach Mailand"), while the airport list has them
 * in English, and airports by local names ("Aeropuerto Adolfo Suárez Madrid-Barajas").
 */
object Places {
    /** City names in other languages, folded (no accents, lower case), to the airport most flights use. */
    private val EXONYMS: Map<String, String> = buildMap {
        fun alias(code: String, vararg names: String) {
            for (name in names) this[name] = code
        }
        alias("LHR", "londres", "londra", "londen", "londyn", "lontoo", "londra")
        alias("CDG", "paris", "parigi", "parys", "paryz", "pariisi")
        alias("JFK", "nueva york", "nova iorque", "nova york", "new york", "nowy jork", "new york city")
        alias("MUC", "munich", "munchen", "monaco di baviera", "munique", "monachium")
        alias("CGN", "colonia", "koln", "cologne", "keulen")
        alias("LIS", "lisboa", "lisbonne", "lissabon", "lisbona", "lizbona", "lisbon")
        alias("OPO", "oporto", "porto")
        alias("ATH", "atenas", "athenes", "athen", "atene", "ateny", "athens")
        alias("WAW", "varsovia", "varsovie", "warschau", "varsavia", "warszawa", "warsaw", "varsovia")
        alias("PRG", "praga", "prague", "prag", "praha")
        alias("VIE", "viena", "vienne", "wien", "vienna")
        alias("BRU", "bruselas", "bruxelles", "brussel", "brussel", "bruxelas", "bruksela", "brussels")
        alias("CPH", "copenhague", "kopenhagen", "copenaghen", "kobenhavn", "kopenhaga", "copenhagen")
        alias("ARN", "estocolmo", "stoccolma", "sztokholm", "stockholm")
        alias("GVA", "ginebra", "geneve", "genf", "ginevra", "genebra", "genewa", "geneva")
        alias("ZRH", "zurich", "zurigo", "zurique", "zurych")
        alias("BSL", "basilea", "bale", "basel", "basileia")
        alias("MXP", "milan", "milano", "milao", "mailand", "mediolan")
        alias("FCO", "roma", "rome", "rom", "rzym")
        alias("FLR", "florencia", "florence", "firenze", "florenz", "florenca")
        alias("VCE", "venecia", "venise", "venedig", "venezia", "veneza", "wenecja", "venice")
        alias("NAP", "napoles", "naples", "neapel", "napoli", "neapol")
        alias("TRN", "turin", "torino", "turim", "turyn")
        alias("GOA", "genova", "genes", "genua", "genoa")
        alias("SVQ", "sevilla", "seville", "sevilha", "siviglia")
        alias("LCG", "la coruna", "a coruna", "coruna")
        alias("PMI", "palma de mallorca", "mallorca", "majorca", "palma")
        alias("IBZ", "ibiza", "eivissa")
        alias("MAH", "menorca", "minorca", "mahon")
        alias("TFS", "tenerife", "tenerife sur")
        alias("TFN", "tenerife norte")
        alias("LPA", "gran canaria", "las palmas", "las palmas de gran canaria")
        alias("ACE", "lanzarote")
        alias("FUE", "fuerteventura")
        alias("EAS", "san sebastian", "donostia")
        alias("SCQ", "santiago de compostela")
        alias("SCL", "santiago de chile")
        alias("EDI", "edimburgo", "edimbourg", "edynburg", "edinburgh")
        alias("FRA", "francfort", "francfort del meno", "frankfurt", "francoforte", "frankfurt am main", "frankfurt nad menem")
        alias("HAM", "hamburgo", "hambourg", "amburgo", "hamburg")
        alias("IST", "estambul", "istanbul", "istambul", "stambul", "istanboel")
        alias("SVO", "moscu", "moscou", "moskau", "mosca", "moskwa", "moscow")
        alias("PEK", "pekin", "peking", "pechino", "pequim", "beijing")
        alias("HND", "tokio", "tokyo", "toquio")
        alias("DEL", "nueva delhi", "new delhi", "nuova delhi", "delhi")
        alias("BOM", "bombay", "mumbai", "bombaim")
        alias("CAI", "el cairo", "le caire", "kairo", "il cairo", "cairo", "o cairo", "kair")
        alias("MEX", "ciudad de mexico", "mexico df", "mexico city", "cidade do mexico", "citta del messico")
        alias("OTP", "bucarest", "bucharest", "bukarest", "bucareste", "bukareszt")
        alias("BEG", "belgrado", "belgrade", "belgrad")
        alias("LJU", "liubliana", "ljubljana", "lubiana")
        alias("KRK", "cracovia", "cracovie", "krakau", "krakow", "cracow")
        alias("SKG", "tesalonica", "salonica", "thessalonique", "saloniki", "salonicco", "thessaloniki")
        alias("LCA", "larnaca", "larnaka", "nicosia", "lefkosia")
        alias("PFO", "pafos", "paphos")
        alias("OKA", "okinawa", "naha")
        alias("MLA", "malta", "la valeta", "la valette", "valletta")
        alias("RAK", "marrakech", "marrakesh", "marraquech", "marrakesz", "marraquexe")
        alias("TNG", "tanger", "tangier", "tangeri")
        alias("TUN", "tunez", "tunis", "tunisi")
        alias("ALG", "argel", "alger", "algiers", "algeri")
        alias("CPT", "ciudad del cabo", "le cap", "kapstadt", "citta del capo", "cidade do cabo", "cape town")
        alias("JNB", "johannesburgo", "johannesbourg", "johannesburg")
        alias("AUH", "abu dabi", "abou dabi", "abu dhabi", "abu zabi")
        alias("SIN", "singapur", "singapour", "cingapura", "singapore")
        alias("ICN", "seul", "seoul", "seoel")
        alias("GIG", "rio de janeiro", "rio")
        alias("GRU", "sao paulo", "san pablo", "san paolo")
        alias("EZE", "buenos aires")
        alias("HAV", "la habana", "habana", "havane", "havanna", "l avana", "havana")
        alias("KEF", "reikiavik", "reykjavik", "reykiavik")
        alias("TLL", "tallin", "tallinn")
        alias("VNO", "vilna", "vilnius", "wilno")
        alias("LUX", "luxemburgo", "luxembourg", "lussemburgo", "luksemburg")
        alias("NCE", "niza", "nice", "nizza")
        alias("MRS", "marsella", "marseille", "marsiglia", "marselha", "marsylia")
        alias("BOD", "burdeos", "bordeaux", "bordeus")
        alias("TLS", "tolosa", "toulouse")
        alias("LYS", "lyon", "lion", "lione", "lyons")
        alias("SXB", "estrasburgo", "strasbourg", "strasburgo", "strassburg")
        alias("TLV", "tel aviv", "tel aviv yafo", "jerusalen", "jerusalem", "gerusalemme")
        alias("HER", "creta", "crete", "kreta", "heraklion", "iraklion")
        alias("CFU", "corfu", "korfu")
        alias("RHO", "rodas", "rhodes", "rodi", "rodos")
        alias("DUB", "dublin", "dublino")
        alias("AMS", "amsterdam", "amesterdao")
        alias("BER", "berlin", "berlino", "berlim")
        alias("DUS", "dusseldorf")
        alias("STR", "stuttgart", "estugarda", "stoccarda")
        alias("BUD", "budapest", "budapeste", "budapeszt")
        alias("SOF", "sofia")
        alias("HEL", "helsinki", "helsingfors")
        alias("OSL", "oslo")
        alias("RIX", "riga")
        alias("CMN", "casablanca")
        alias("DXB", "dubai", "dubay")
        alias("MIA", "miami")
        alias("LAX", "los angeles")
        alias("SFO", "san francisco")
        alias("PHL", "filadelfia", "philadelphie", "philadelphia")
        alias("MSY", "nueva orleans", "la nouvelle orleans", "new orleans")
        alias("YUL", "montreal")
        alias("YYZ", "toronto")
        alias("BOG", "bogota")
        alias("LIM", "lima")
        alias("HKG", "hong kong")
        alias("BKK", "bangkok", "banguecoque")
        alias("SYD", "sidney", "sydney")
        // Time zone cities whose airport the list files under a suburb.
        alias("LHR", "london")
        alias("CDG", "paris")
        alias("ATH", "athens")
        alias("BRU", "brussels")
        alias("ARN", "stockholm")
        alias("OSL", "oslo")
        alias("CPH", "copenhagen")
        alias("WAW", "warsaw")
        alias("BUD", "budapest")
        alias("PRG", "prague")
        alias("VIE", "vienna")
        alias("IST", "istanbul")
        alias("KBP", "kiev", "kyiv")
        alias("TBS", "tbilisi")
        alias("PVG", "shanghai")
        alias("TPE", "taipei")
        alias("CGK", "jakarta")
        alias("KUL", "kuala lumpur")
        alias("MNL", "manila")
        alias("CCU", "kolkata", "calcutta")
        alias("ORD", "chicago")
        alias("DEN", "denver")
        alias("PHX", "phoenix")
        alias("YVR", "vancouver")
    }

    /** Words that name an airport rather than a place, in the languages calendars use. */
    private val AIRPORT_WORDS = setOf(
        "aeropuerto", "airport", "aeroport", "aeroporto", "flughafen", "luchthaven", "lotnisko", "lentoasema", "flygplats",
        "lufthavn", "havalimani", "international", "internacional", "internationale", "internazionale", "intl", "int", "terminal",
        "de", "del", "la", "el", "di", "da", "do", "des", "du", "the", "of", "von", "der", "and", "y", "e",
    )

    /** An airport for [text]: its code, a city by any of its names, or an airport by its name. */
    fun airport(text: String, airports: AirportDirectory, country: String? = null): String? {
        Regex("""\b([A-Z]{3})\b""").findAll(text).map { it.groupValues[1] }.firstOrNull { airports[it] != null }?.let { return it }
        val name = text.substringBefore('(').substringBefore('\n').trim().trimEnd(',', '.', '-')
        if (name.length < 3) return null
        val folded = fold(name)
        EXONYMS[folded]?.let { return it }
        // "Londres, Reino Unido" or "Madrid-Barajas": try the part before a comma, too.
        EXONYMS[fold(name.substringBefore(','))]?.let { return it }
        pick(airports.search(name.substringBefore(','), limit = 10), country)?.let { return it.code }
        // An airport's local name: drop the airport words and match what's left.
        val words = folded.split(Regex("[^a-z0-9]+")).filter { it.length >= 3 && it !in AIRPORT_WORDS }
        if (words.isEmpty()) return null
        EXONYMS[words.joinToString(" ")]?.let { return it }
        // One word is a city ("Milano"); more may name one of its airports ("Warsaw Modlin"),
        // which beats the city's main one.
        if (words.size == 1) EXONYMS[words[0]]?.let { return it }
        named(words, airports, country, enough = 2)?.let { return it }
        words.firstNotNullOfOrNull { EXONYMS[it] }?.let { return it }
        return named(words, airports, country, enough = 1).takeIf { words.size == 1 }
    }

    /** The airport whose city and name share the most of [words], at least [enough] of them. */
    private fun named(words: List<String>, airports: AirportDirectory, country: String?, enough: Int): String? {
        val haystacks = haystacks(airports)
        val scored = airports.airports.indices.mapNotNull { i ->
            val haystack = haystacks[i]
            val hits = words.count { it in haystack }
            if (hits == 0) null else Triple(airports.airports[i], hits, airports.airports[i].size)
        }
        val best = scored.maxOfOrNull { it.second } ?: return null
        // One word in common is only enough when it's the whole name ("Barajas").
        if (best < enough) return null
        return pick(scored.filter { it.second == best }.sortedByDescending { it.third }.map { it.first }, country)?.code
    }

    private var cached: Pair<AirportDirectory, List<Set<String>>>? = null

    /** Each airport's city and name as words, worked out once per airport list. */
    private fun haystacks(airports: AirportDirectory): List<Set<String>> = synchronized(this) {
        cached?.takeIf { it.first === airports }?.second
            ?: airports.airports.map { fold("${it.city} ${it.name}").split(' ').toSet() }.also { cached = airports to it }
    }

    /** The first airport, or the first in [country] when it's known and one is there. */
    private fun pick(found: List<Airport>, country: String?): Airport? =
        (country?.let { c -> found.firstOrNull { it.country == c } }) ?: found.firstOrNull()

    /**
     * The country of a time zone. Calendars give a flight's departure and arrival zones
     * (Gmail's events do), which say the countries when the places in the title can't.
     */
    fun country(zone: ZoneId?, airports: AirportDirectory): String? {
        zone ?: return null
        icuRegion(zone.id)?.let { return it }
        // "Europe/Madrid", "America/Argentina/Buenos_Aires": the zone's city, as an airport city.
        val city = zone.id.substringAfterLast('/').replace('_', ' ')
        if (zone.id.startsWith("Etc/") || city.length < 3) return null
        EXONYMS[fold(city)]?.let { code -> return airports[code]?.country }
        return airports.airports.filter { fold(it.city) == fold(city) }.maxByOrNull { it.size }?.country
    }

    /** The biggest airport of a time zone's city ("Europe/Lisbon" → LIS), as a last resort. */
    fun zoneAirport(zone: ZoneId?, airports: AirportDirectory): String? {
        zone ?: return null
        val city = fold(zone.id.substringAfterLast('/').replace('_', ' '))
        if (zone.id.startsWith("Etc/") || city.length < 3) return null
        EXONYMS[city]?.let { return it }
        return airports.airports.filter { fold(it.city) == city }.maxByOrNull { it.size }?.code
    }

    private fun icuRegion(id: String): String? = runCatching {
        android.icu.util.TimeZone.getRegion(id)?.takeIf { it.length == 2 && it != "001" }
    }.getOrNull()

    fun fold(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
}
