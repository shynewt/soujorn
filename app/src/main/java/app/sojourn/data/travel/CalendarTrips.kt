package app.sojourn.data.travel

import app.sojourn.data.flights.AirportDirectory
import app.sojourn.data.flights.Airlines
import app.sojourn.data.flights.FlightText
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** A calendar event, from the phone's calendars or an .ics file. Times are instants. */
data class CalendarEvent(
    val title: String,
    val location: String = "",
    val description: String = "",
    val start: Instant,
    val end: Instant? = null,
    val startZone: ZoneId? = null,
    val endZone: ZoneId? = null,
    val id: String = "",
    /** Whole-day events have no time; their dates are taken as they are. */
    val allDay: Boolean = false,
)

/**
 * Turns flight events into flights: the ones Gmail adds from booking emails ("Flight to London
 * (BA 117)", in your language), airline "add to calendar" files, TripIt and the like.
 */
object CalendarTrips {
    // A flight number between the flight word and the places: "Flight FR 4178 to Warsaw Modlin".
    private const val NUMBER = """(?:\s+[A-Z0-9]{2,3}\s?\d{1,4})?"""

    // "Flight to", "Flug nach", "Vol vers", "Vuelo a", "Voo para", "Volo per", "Vlucht naar", "Lot do"…
    private val FLIGHT_TO = Regex(
        """\b(?:flight|flug|vol|vuelo|voo|volo|vlucht|lot|lento|flyg|fly|let|uçuş)\b$NUMBER\s+(?:to|nach|vers|pour|à|a|para|per|naar|do|til|till|kohteeseen|na)\s+(.+?)\s*(?:\(|$|\n|,| - )""",
        RegexOption.IGNORE_CASE,
    )
    // "Flight from Madrid to London", "Vuelo de Madrid a Londres", "Flug von Berlin nach Rom"…
    private val FLIGHT_FROM_TO = Regex(
        """\b(?:flight|flug|vol|vuelo|voo|volo|vlucht|lot|lento|flyg|fly|let|uçuş)\b$NUMBER\s+(?:from|de|desde|von|da|dal|van|z|ze|depuis|fra|från|frå)\s+(.+?)\s+(?:to|a|al|hacia|nach|à|vers|pour|para|naar|do|per|til|till|na)\s+(.+?)\s*(?:\(|$|\n|,| - )""",
        RegexOption.IGNORE_CASE,
    )
    // "Flight EZY8653 to Cyprus Paphos from London Gatwick on Sun 11 October", as airlines' reminders put it.
    private val FLIGHT_TO_FROM = Regex(
        """\bflight\b$NUMBER\s+to\s+(.+?)\s+from\s+(.+?)\s*(?:\bon\b|\bat\b|\(|$|\n|,| - )""",
        RegexOption.IGNORE_CASE,
    )
    private val FLIGHT_WORD = Regex("""\b(flight|flug|vol|vuelo|voo|volo|vlucht|lot|lento|flyg|fly|uçuş|boarding)\b""", RegexOption.IGNORE_CASE)
    // Getting to an airport isn't a flight; an airport with a flight number is.
    private val AIRPORT_WORD = Regex("""\b(airport|aeropuerto|aeroport|aéroport|aeroporto|flughafen)\b""", RegexOption.IGNORE_CASE)
    private val FLIGHT_NUMBER = Regex("""\b(?:[A-Z][A-Z0-9]|[0-9][A-Z])\s?\d{1,4}\b""")
    private val CODE_PAIR = Regex("""\b([A-Z]{3})\s*(?:->|—|-|–|→|>|to|TO)\s*([A-Z]{3})\b""")
    // "Madrid → Londres", "Madrid - London" once the flight words are gone.
    private val PLACE_ARROW = Regex("""^(.+?)\s*(?:→|->|⇒|>| - | – | — )\s*(.+)$""")
    private val NOISE = Regex("""[✈🛫🛬️]|\([^)]*\)|\b(?:flight|flug|vol|vuelo|voo|volo|vlucht|lot|lento|flyg|fly)\b|\b[A-Z0-9]{2}\s?\d{2,4}\b""", RegexOption.IGNORE_CASE)

    fun isFlight(event: CalendarEvent): Boolean =
        FLIGHT_WORD.containsMatchIn(event.title) || CODE_PAIR.containsMatchIn(event.title) || event.title.contains('✈') ||
            AIRPORT_WORD.containsMatchIn(event.title) && FLIGHT_NUMBER.containsMatchIn(event.title)

    fun parse(event: CalendarEvent, airports: AirportDirectory): FoundTrip? {
        if (!isFlight(event) || event.allDay) return null
        val all = listOf(event.title, event.location, event.description).joinToString("\n")
        val guess = FlightText.parse(all, airports, event.start.atZone(ZoneOffset.UTC).toLocalDate())
        // Calendars keep the departure and arrival zones: Gmail's flights set both.
        val startCountry = Places.country(event.startZone, airports)
        val endCountry = Places.country(event.endZone, airports)?.takeIf { event.endZone != event.startZone }
        val route = CODE_PAIR.find(event.title)?.groupValues
        var from = route?.get(1)?.takeIf { airports[it] != null }
        var to = route?.get(2)?.takeIf { airports[it] != null }
        // "Flight from A to B" in the title, else in the description, where people put the route.
        for (text in listOf(event.title, event.description)) {
            val (a, b) = FLIGHT_FROM_TO.find(text)?.destructured?.let { (a, b) -> a to b }
                ?: FLIGHT_TO_FROM.find(text)?.destructured?.let { (b, a) -> a to b }
                ?: continue
            if (from == null) from = Places.airport(a, airports, startCountry)
            if (to == null) to = Places.airport(b, airports, endCountry)?.takeIf { it != from }
            if (from != null && to != null) break
        }
        if (from == null) from = guess.origin
        if (to == null) to = guess.destination
        // Gmail's own events name only the destination city in the title, in the phone's
        // language ("Vuelo a Londres"); the location is usually the departure airport.
        val city = FLIGHT_TO.find(event.title)?.groupValues?.get(1)?.trim()
        if (city != null) {
            val match = Places.airport(city, airports, endCountry)
            if (match != null && match != from) {
                if (to == null || to == from || endCountry != null && to != match && airports[to]?.country != endCountry) to = match
                if (from == to) from = null
            }
        }
        if (from == null || to == null) {
            val bare = NOISE.replace(event.title, " ").replace(Regex("\\s+"), " ").trim()
            PLACE_ARROW.find(bare)?.groupValues?.let { (_, a, b) ->
                val first = Places.airport(a, airports, startCountry)
                val second = Places.airport(b, airports, endCountry)
                if (first != null && second != null && first != second) {
                    if (from == null) from = first
                    if (to == null) to = second
                }
            }
        }
        if (from == null && event.location.isNotBlank()) from = Places.airport(event.location, airports, startCountry)?.takeIf { it != to }
        // Still no airport, but the time zones tell the countries: take the zone city's airport.
        var guessed = false
        if (from == null && to != null && startCountry != null && startCountry != airports[to]?.country) {
            from = Places.zoneAirport(event.startZone, airports)?.also { guessed = true }
        }
        if (to == null && from != null && endCountry != null && endCountry != airports[from]?.country) {
            to = Places.zoneAirport(event.endZone, airports)?.also { guessed = true }
        }
        val origin = from
        val destination = to
        if (origin == null || destination == null || origin == destination) return null

        val startZone = event.startZone ?: ZoneId.systemDefault()
        val endZone = event.endZone ?: event.startZone ?: ZoneId.systemDefault()
        val departure = event.start.atZone(startZone).toLocalDateTime()
        val arrival = event.end?.atZone(endZone)?.toLocalDateTime()
        return FoundTrip(
            kind = TripKind.FLIGHT,
            departure = departure.toLocalDate(),
            departureMinute = departure.hour * 60 + departure.minute,
            arrival = arrival?.toLocalDate() ?: departure.toLocalDate(),
            arrivalMinute = arrival?.let { it.hour * 60 + it.minute },
            number = guess.number?.let(Airlines::format).orEmpty(),
            from = origin,
            to = destination,
            source = if (guessed) "${event.title} (airport from its time zone)" else event.title,
        )
    }

    /** An airport for a place: its code in the text, else a city or airport by any of its names. */
    fun place(text: String, airports: AirportDirectory): String? = Places.airport(text, airports)
}

/** iCalendar (.ics) files: the events, with their times and time zones. */
object Ics {
    fun parse(text: String): List<CalendarEvent> {
        // Unfold continuation lines first.
        val lines = text.replace("\r\n", "\n").replace(Regex("\n[ \t]"), "").lines()
        val events = mutableListOf<CalendarEvent>()
        var fields: MutableMap<String, Pair<String, Map<String, String>>>? = null
        for (line in lines) {
            when {
                line == "BEGIN:VEVENT" -> fields = HashMap()
                line == "END:VEVENT" -> {
                    fields?.let { f -> event(f)?.let(events::add) }
                    fields = null
                }
                fields != null && ':' in line -> {
                    val head = line.substringBefore(':')
                    val value = line.substringAfter(':')
                    val name = head.substringBefore(';').uppercase()
                    val params = head.split(';').drop(1).associate { it.substringBefore('=').uppercase() to it.substringAfter('=').trim('"') }
                    if (name !in fields) fields[name] = value to params
                }
            }
        }
        return events
    }

    private fun event(f: Map<String, Pair<String, Map<String, String>>>): CalendarEvent? {
        val (startText, startParams) = f["DTSTART"] ?: return null
        val start = instant(startText, startParams) ?: return null
        val end = f["DTEND"]?.let { (t, p) -> instant(t, p) }
        return CalendarEvent(
            title = unescape(f["SUMMARY"]?.first.orEmpty()),
            location = unescape(f["LOCATION"]?.first.orEmpty()),
            description = unescape(f["DESCRIPTION"]?.first.orEmpty()),
            start = start.first,
            end = end?.first,
            startZone = start.second,
            endZone = end?.second,
            id = f["UID"]?.first.orEmpty(),
            allDay = startParams["VALUE"] == "DATE" || startText.length == 8,
        )
    }

    private fun instant(text: String, params: Map<String, String>): Pair<Instant, ZoneId?>? = runCatching {
        when {
            text.endsWith("Z") -> LocalDateTime.parse(text.removeSuffix("Z"), FORMAT).toInstant(ZoneOffset.UTC) to null
            text.length == 8 -> LocalDate.parse(text, DateTimeFormatter.BASIC_ISO_DATE).atStartOfDay(ZoneOffset.UTC).toInstant() to null
            else -> {
                val zone = params["TZID"]?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()
                LocalDateTime.parse(text, FORMAT).atZone(zone).toInstant() to zone
            }
        }
    }.getOrNull()

    private val FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

    /** An event written back out as an iCalendar VEVENT, with its own time zones. */
    fun write(event: CalendarEvent): String = buildString {
        fun line(name: String, value: String) {
            if (value.isNotBlank()) append(name).append(':').append(escape(value)).append("\r\n")
        }
        fun time(name: String, at: Instant, zone: ZoneId?) {
            if (zone == null) append(name).append(':').append(FORMAT.format(at.atZone(ZoneOffset.UTC))).append("Z\r\n")
            else append(name).append(";TZID=").append(zone.id).append(':').append(FORMAT.format(at.atZone(zone))).append("\r\n")
        }
        append("BEGIN:VEVENT\r\n")
        line("UID", event.id.ifBlank { "${event.start.toEpochMilli()}-${event.title.hashCode()}@sojourn" })
        line("SUMMARY", event.title)
        line("LOCATION", event.location)
        line("DESCRIPTION", event.description)
        time("DTSTART", event.start, event.startZone)
        event.end?.let { time("DTEND", it, event.endZone ?: event.startZone) }
        append("END:VEVENT\r\n")
    }

    private fun escape(text: String) = text.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\n", "\\n")

    private fun unescape(text: String) = text.replace("\\n", "\n").replace("\\N", "\n").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\")
}
