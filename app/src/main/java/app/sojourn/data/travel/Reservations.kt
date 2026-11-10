package app.sojourn.data.travel

import app.sojourn.data.Countries
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Reservations marked up with schema.org in booking emails, the same markup Gmail reads to put
 * flights in your calendar: JSON-LD scripts and microdata. Each passenger of a booking has a
 * reservation of their own, with their name, so trips booked for others can be told apart.
 */
object Reservations {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val SCRIPT = Regex("""<script[^>]+type\s*=\s*["']application/ld\+json["'][^>]*>(.*?)</script>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    fun fromHtml(html: String, source: String = ""): List<FoundTrip> {
        val nodes = SCRIPT.findAll(html).flatMap { match ->
            val element = runCatching { json.parseToJsonElement(unescape(match.groupValues[1]).trim()) }.getOrNull() ?: return@flatMap emptySequence()
            flatten(element)
        }.toList() + Microdata.parse(html)
        return nodes.mapNotNull { trip(it, source) }.distinctBy { it.key }
    }

    /** Every object in a JSON-LD document, including those in arrays and @graph. */
    private fun flatten(element: JsonElement): Sequence<JsonObject> = when (element) {
        is JsonArray -> element.asSequence().flatMap(::flatten)
        is JsonObject -> sequenceOf(element) + (element["@graph"]?.let(::flatten) ?: emptySequence())
        else -> emptySequence()
    }

    private fun JsonObject.type(): String = (this["@type"] as? JsonPrimitive)?.contentOrNull?.substringAfterLast('/') ?: (this["@type"] as? JsonArray)?.firstOrNull()?.let { (it as? JsonPrimitive)?.contentOrNull } ?: ""

    private fun JsonElement?.text(): String? = when (this) {
        is JsonPrimitive -> contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        is JsonObject -> (this["name"] ?: this["@id"]).text()
        is JsonArray -> firstOrNull().text()
        else -> null
    }

    private fun JsonElement?.obj(): JsonObject? = when (this) {
        is JsonObject -> this
        is JsonArray -> firstOrNull() as? JsonObject
        else -> null
    }

    fun trip(node: JsonObject, source: String): FoundTrip? {
        val type = node.type()
        val passenger = node["underName"].text()
        val reference = node["reservationNumber"].text()
        val trip = node["reservationFor"].obj()
        return when (type) {
            "FlightReservation" -> {
                trip ?: return null
                val departure = time(trip["departureTime"].text()) ?: return null
                val arrival = time(trip["arrivalTime"].text())
                val airline = trip["airline"].obj()?.get("iataCode").text() ?: trip["airline"].obj()?.get("name").text().orEmpty()
                FoundTrip(
                    kind = TripKind.FLIGHT,
                    departure = departure.first,
                    departureMinute = departure.second,
                    arrival = arrival?.first ?: departure.first,
                    arrivalMinute = arrival?.second,
                    number = listOf(airline, trip["flightNumber"].text().orEmpty().removePrefix(airline)).filter { it.isNotBlank() }.joinToString(" "),
                    from = trip["departureAirport"].obj()?.get("iataCode").text().orEmpty(),
                    to = trip["arrivalAirport"].obj()?.get("iataCode").text().orEmpty(),
                    passenger = passenger,
                    source = source,
                    reference = reference,
                ).takeIf { it.from.length == 3 && it.to.length == 3 }
            }
            "TrainReservation", "BusReservation", "BoatReservation" -> {
                trip ?: return null
                val (fromKey, toKey, numberKey, kind) = when (type) {
                    "TrainReservation" -> Quad("departureStation", "arrivalStation", "trainNumber", TripKind.TRAIN)
                    "BusReservation" -> Quad("departureBusStop", "arrivalBusStop", "busNumber", TripKind.BUS)
                    else -> Quad("departureBoatTerminal", "arrivalBoatTerminal", "name", TripKind.FERRY)
                }
                val departure = time(trip["departureTime"].text()) ?: return null
                val arrival = time(trip["arrivalTime"].text())
                val from = trip[fromKey].obj()
                val to = trip[toKey].obj()
                FoundTrip(
                    kind = kind,
                    departure = departure.first,
                    departureMinute = departure.second,
                    arrival = arrival?.first ?: departure.first,
                    arrivalMinute = arrival?.second,
                    number = trip[numberKey].text().orEmpty(),
                    from = from?.get("name").text().orEmpty(),
                    to = to?.get("name").text().orEmpty(),
                    fromCountry = country(from),
                    toCountry = country(to),
                    passenger = passenger,
                    source = source,
                    reference = reference,
                )
            }
            "LodgingReservation" -> {
                val checkIn = time(node["checkinTime"].text() ?: node["checkinDate"].text()) ?: return null
                val checkOut = time(node["checkoutTime"].text() ?: node["checkoutDate"].text())
                FoundTrip(
                    kind = TripKind.STAY,
                    departure = checkIn.first,
                    arrival = checkOut?.first ?: checkIn.first,
                    from = trip?.get("name").text().orEmpty(),
                    fromCountry = country(trip),
                    toCountry = country(trip),
                    passenger = passenger,
                    source = source,
                    reference = reference,
                )
            }
            "RentalCarReservation" -> {
                val pickup = time(node["pickupTime"].text()) ?: return null
                val dropoff = time(node["dropoffTime"].text())
                FoundTrip(
                    kind = TripKind.CAR,
                    departure = pickup.first,
                    departureMinute = pickup.second,
                    arrival = dropoff?.first ?: pickup.first,
                    arrivalMinute = dropoff?.second,
                    from = node["pickupLocation"].obj()?.get("name").text().orEmpty(),
                    to = node["dropoffLocation"].obj()?.get("name").text().orEmpty(),
                    fromCountry = country(node["pickupLocation"].obj()),
                    toCountry = country(node["dropoffLocation"].obj()),
                    passenger = passenger,
                    source = source,
                    reference = reference,
                )
            }
            else -> null
        }
    }

    private data class Quad(val a: String, val b: String, val c: String, val d: TripKind)

    /** The ISO code of a place's address country, written as a code, a name, or a Country object. */
    fun country(place: JsonObject?): String? {
        place ?: return null
        val address = place["address"].obj() ?: place
        val raw = address["addressCountry"].text() ?: return null
        return countryCode(raw)
    }

    fun countryCode(raw: String): String? {
        val value = raw.trim()
        if (value.length == 2 && value.all { it.isLetter() }) return value.uppercase()
        val folded = Names.fold(value)
        return Countries.codes.firstOrNull { code -> Names.fold(Countries.name(code, Locale.ENGLISH)) == folded || Names.fold(Countries.name(code)) == folded }
    }

    /** A local date and minute of the day from an ISO date or date-time, with or without an offset. */
    fun time(text: String?): Pair<LocalDate, Int?>? {
        text ?: return null
        val local = runCatching { OffsetDateTime.parse(text).toLocalDateTime() }.getOrNull()
            ?: runCatching { ZonedDateTime.parse(text).toLocalDateTime() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(text) }.getOrNull()
        if (local != null) return local.toLocalDate() to local.hour * 60 + local.minute
        return runCatching { LocalDate.parse(text.take(10)) to null }.getOrNull()
    }

    private fun unescape(text: String) = text.replace("&quot;", "\"").replace("&amp;", "&").replace("<!--", "").replace("-->", "")
}

/**
 * schema.org microdata, the other way booking emails mark reservations up: nested itemscope
 * elements with itemprop values in content, href or the element's text. Turned into the same
 * JSON shape as JSON-LD so one reader handles both.
 */
object Microdata {
    private val TAG = Regex("""<(/?)([a-zA-Z0-9]+)([^>]*?)(/?)>|([^<]+)""", RegexOption.DOT_MATCHES_ALL)
    private val ATTR = Regex("""([a-zA-Z-]+)\s*=\s*("([^"]*)"|'([^']*)')""")
    private val VOID = setOf("meta", "link", "br", "img", "hr", "input")

    private class Item(val type: String, val values: MutableMap<String, MutableList<Any>> = LinkedHashMap())

    fun parse(html: String): List<JsonObject> {
        if (!html.contains("itemscope", ignoreCase = true)) return emptyList()
        val roots = mutableListOf<Item>()
        // A stack of open elements: the item each one started (if any), and a pending property.
        data class Open(val tag: String, val item: Item?, val prop: String?, val text: StringBuilder?)
        val stack = ArrayDeque<Open>()
        fun currentItem(): Item? = stack.lastOrNull { it.item != null }?.item
        for (m in TAG.findAll(html)) {
            val text = m.groups[5]?.value
            if (text != null) {
                stack.forEach { it.text?.append(text) }
                continue
            }
            val closing = m.groupValues[1] == "/"
            val tag = m.groupValues[2].lowercase()
            val attrs = ATTR.findAll(m.groupValues[3]).associate { it.groupValues[1].lowercase() to (it.groups[3]?.value ?: it.groups[4]?.value ?: "") }
            if (closing) {
                while (stack.isNotEmpty()) {
                    val open = stack.removeLast()
                    val parent = currentItem()
                    if (open.prop != null && parent != null) {
                        val value: Any = open.item ?: open.text.toString().trim()
                        parent.values.getOrPut(open.prop) { mutableListOf() } += value
                    }
                    if (open.tag == tag) break
                }
                continue
            }
            val prop = attrs["itemprop"]
            val scope = "itemscope" in attrs || m.groupValues[3].contains("itemscope", ignoreCase = true)
            val item = if (scope) Item(attrs["itemtype"].orEmpty().substringAfterLast('/')) else null
            if (item != null && prop == null && currentItem() == null) roots += item
            val direct = attrs["content"] ?: attrs["href"] ?: attrs["datetime"]
            if (tag in VOID || m.groupValues[4] == "/") {
                val parent = currentItem()
                if (prop != null && parent != null && (direct != null || item != null)) parent.values.getOrPut(prop) { mutableListOf() } += (item ?: direct!!)
                continue
            }
            if (prop != null && direct != null && item == null) {
                currentItem()?.values?.getOrPut(prop) { mutableListOf() }?.add(direct)
                stack.addLast(Open(tag, null, null, null))
            } else {
                stack.addLast(Open(tag, item, prop, if (prop != null && item == null) StringBuilder() else null))
            }
        }
        return roots.map(::toJson)
    }

    private fun toJson(item: Item): JsonObject = JsonObject(
        buildMap {
            put("@type", JsonPrimitive(item.type))
            item.values.forEach { (key, values) ->
                val value = values.first()
                put(key, if (value is Item) toJson(value) else JsonPrimitive(value.toString()))
            }
        },
    )
}
