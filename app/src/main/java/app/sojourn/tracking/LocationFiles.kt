package app.sojourn.tracking

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.JsonReader
import android.util.JsonToken
import app.sojourn.data.geo.CountryLocator
import java.io.BufferedInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.zip.ZipInputStream
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Countries found on one day, with how many samples placed each. */
/** A day a source places in some countries; [photos] are links to the photos that show each one. */
data class FoundDay(val date: LocalDate, val countries: Map<String, Int>, val photos: Map<String, List<String>> = emptyMap())

/**
 * Collects located moments into days. Dense tracks are thinned to one sample per half hour
 * before any country lookup, so a year of one-second GPS points costs no more than a sparse one.
 */
class DayCollector(private val locate: (Double, Double) -> String?) {
    private val samples = HashMap<LocalDate, HashMap<Int, Pair<Double, Double>>>()

    /** Located moments read from the file, before thinning. */
    var points = 0
        private set

    /**
     * [offset] is the local time offset when the source records it. Without one the offset is
     * estimated from the longitude, which only matters for moments near midnight.
     */
    fun point(lat: Double, lon: Double, time: Instant, offset: ZoneOffset?) {
        if (!valid(lat, lon)) return
        points++
        val local = time.atOffset(offset ?: solarOffset(lon)).toLocalDateTime()
        samples.getOrPut(local.toLocalDate()) { HashMap() }.putIfAbsent(local.hour * 2 + local.minute / 30, lat to lon)
    }

    /** A stay at one place: every day it covers counts, not just its two ends. */
    fun span(lat: Double, lon: Double, start: Instant, end: Instant, offset: ZoneOffset?) {
        if (!valid(lat, lon) || end < start) return
        point(lat, lon, start, offset)
        val zone = offset ?: solarOffset(lon)
        val last = end.atOffset(zone).toLocalDate()
        var day = start.atOffset(zone).toLocalDate().plusDays(1)
        var guard = 0
        while (day < last && guard++ < MAX_SPAN_DAYS) {
            samples.getOrPut(day) { HashMap() }.putIfAbsent(NOON, lat to lon)
            day = day.plusDays(1)
        }
        point(lat, lon, end, offset)
    }

    /**
     * The days, cleaned the way a tracker's raw points need: a lone point in another country
     * between points that agree, minutes apart, is a GPS glitch and dropped; and each day also
     * counts where you were at midnight, the last place before it, which fills the days a
     * tracker like OwnTracks stays quiet because you didn't move. Real trips over a border
     * stay: any part of a day abroad counts in places like Spain.
     */
    fun days(): List<FoundDay> {
        // Nearby samples share a lookup; a 0.05° grid is far finer than any border question.
        val cache = HashMap<Long, String?>()
        // Every sample in time order, as half hours since the epoch and its country.
        val located = samples.entries.sortedBy { it.key }.flatMap { (date, buckets) ->
            buckets.entries.sortedBy { it.key }.mapNotNull { (bucket, place) ->
                val (lat, lon) = place
                val key = (lat * 20).roundToInt().toLong() * 100_000 + (lon * 20).roundToInt()
                val country = cache.getOrPut(key) { locate(lat, lon) } ?: return@mapNotNull null
                Located(date.toEpochDay() * SLOTS + bucket, country)
            }
        }
        val clean = located.filterIndexed { i, here ->
            val before = located.getOrNull(i - 1)
            val after = located.getOrNull(i + 1)
            !(before != null && after != null && before.country == after.country && here.country != before.country &&
                here.slot - before.slot <= GLITCH && after.slot - here.slot <= GLITCH)
        }
        if (clean.isEmpty()) return emptyList()
        val counts = clean.groupBy { Math.floorDiv(it.slot, SLOTS.toLong()) }.mapValues { (_, day) -> day.groupingBy { it.country }.eachCount() }
        val first = Math.floorDiv(clean.first().slot, SLOTS.toLong())
        val last = Math.floorDiv(clean.last().slot, SLOTS.toLong())
        val days = mutableListOf<FoundDay>()
        var next = 0
        for (epochDay in first..last) {
            val midnight = epochDay * SLOTS
            while (next < clean.size && clean[next].slot < midnight) next++
            val before = clean.getOrNull(next - 1)
            val after = clean.getOrNull(next)
            // Still where the last point was, if it was lately or the next point agrees.
            val atMidnight = before?.takeIf {
                midnight - it.slot <= RECENT || after != null && after.country == it.country && after.slot - it.slot <= QUIET
            }?.country
            val found = counts[epochDay].orEmpty().toMutableMap()
            if (atMidnight != null && atMidnight !in found) found[atMidnight] = 1
            if (found.isNotEmpty()) days += FoundDay(LocalDate.ofEpochDay(epochDay), found)
        }
        return days
    }

    private data class Located(val slot: Long, val country: String)

    private fun valid(lat: Double, lon: Double) =
        lat in -90.0..90.0 && lon in -180.0..180.0 && !(lat == 0.0 && lon == 0.0)

    companion object {
        private const val NOON = 24
        private const val SLOTS = 48
        /** A lone point this close, in half hours, to agreeing ones on both sides is a glitch. */
        private const val GLITCH = 4
        /** A point from 18:00 on, in half hours before midnight, says where you were at midnight. */
        private const val RECENT = 12
        /** Or one up to two weeks before, when the next point is in the same country. */
        private const val QUIET = 14 * 48
        private const val MAX_SPAN_DAYS = 366

        fun solarOffset(lon: Double): ZoneOffset = ZoneOffset.ofHours((lon / 15).roundToInt().coerceIn(-12, 14))

        /** ISO times; an explicit offset other than Z is kept, since it tells the local day. */
        fun parseTime(value: String): Pair<Instant, ZoneOffset?>? {
            val text = value.trim()
            // Epoch seconds (OwnTracks, Dawarich) or milliseconds (Google).
            text.toDoubleOrNull()?.let { n -> return (if (n < 1e11) Instant.ofEpochMilli((n * 1000).toLong()) else Instant.ofEpochMilli(n.toLong())) to null }
            return runCatching {
                val parsed = OffsetDateTime.parse(text)
                parsed.toInstant() to parsed.offset.takeIf { it != ZoneOffset.UTC || !text.endsWith("Z") }
            }.recoverCatching {
                // Some exporters leave the zone out entirely; UTC is the usual meaning.
                java.time.LocalDateTime.parse(text).toInstant(ZoneOffset.UTC) to null
            }.getOrNull()
        }
    }
}

/**
 * Every Google Timeline export there has been: the on-device Timeline.json (semanticSegments,
 * rawSignals), Takeout's Records.json and Semantic Location History, and the iOS export.
 *
 * Rather than one parser per format, it walks the JSON once and pairs coordinates with the
 * nearest times around them. A visit's place and its duration usually sit in sibling objects,
 * so coordinates without a time travel up until an enclosing object supplies one.
 */
object TimelineReader {
    private class Pending(val lat: Double, val lon: Double, val atEnd: Boolean)

    private class Frame(val key: String?) {
        var lat: Double? = null
        var lon: Double? = null
        var start: Pair<Instant, ZoneOffset?>? = null
        var end: Pair<Instant, ZoneOffset?>? = null
        val pending = mutableListOf<Pending>()

        /** Whether this object or anything inside it held coordinates. */
        var placed = false
    }

    private val COORDINATES = Regex("""(-?\d+(?:\.\d+)?)°?\s*,\s*(-?\d+(?:\.\d+)?)""")
    private val START_TIMES = setOf("timestamp", "timestampMs", "time", "startTime", "startTimestamp", "startTimestampMs", "tst")
    private val END_TIMES = setOf("endTime", "endTimestamp", "endTimestampMs")
    private val PLACE_STRINGS = setOf("latLng", "LatLng", "point", "placeLocation", "location")
    private val END_KEYS = setOf("end", "endLocation")

    // Alternatives Google considered but rejected for a visit.
    private val SKIP = setOf("otherCandidateLocations", "candidateLocations")

    fun read(input: InputStream, collector: DayCollector, tick: () -> Unit) {
        val reader = JsonReader(InputStreamReader(input, Charsets.UTF_8))
        reader.isLenient = true
        var count = 0
        value(reader, null, null, collector) { if (++count % 2_000 == 0) tick() }
    }

    private fun value(reader: JsonReader, key: String?, parent: Frame?, collector: DayCollector, tick: () -> Unit) {
        when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> if (key in SKIP) reader.skipValue() else obj(reader, key, parent, collector, tick)
            JsonToken.BEGIN_ARRAY -> {
                reader.beginArray()
                while (reader.hasNext()) value(reader, key, parent, collector, tick)
                reader.endArray()
            }
            else -> reader.skipValue()
        }
    }

    private fun obj(reader: JsonReader, key: String?, parent: Frame?, collector: DayCollector, tick: () -> Unit) {
        val frame = Frame(key)
        reader.beginObject()
        while (reader.hasNext()) {
            val name = reader.nextName()
            when (reader.peek()) {
                JsonToken.BEGIN_OBJECT, JsonToken.BEGIN_ARRAY -> value(reader, name, frame, collector, tick)
                JsonToken.NULL -> reader.nextNull()
                JsonToken.BOOLEAN -> reader.nextBoolean()
                else -> scalar(frame, name, reader.nextString())
            }
        }
        reader.endObject()
        close(frame, parent, collector)
        tick()
    }

    private fun scalar(frame: Frame, name: String, raw: String) {
        when (name) {
            "latitudeE7", "latE7" -> frame.lat = raw.toLongOrNull()?.let { it / 1e7 }
            "longitudeE7", "lngE7" -> frame.lon = raw.toLongOrNull()?.let { it / 1e7 }
            // OwnTracks and Dawarich: plain degrees, the time as epoch seconds in "tst".
            "lat", "latitude" -> frame.lat = raw.toDoubleOrNull()
            "lon", "lng", "longitude" -> frame.lon = raw.toDoubleOrNull()
            in PLACE_STRINGS -> COORDINATES.find(raw)?.let {
                frame.lat = it.groupValues[1].toDouble()
                frame.lon = it.groupValues[2].toDouble()
            }
            // The iOS export writes an activity's ends as "geo:" strings right on the activity.
            "start", "end" -> COORDINATES.find(raw)?.let {
                frame.pending += Pending(it.groupValues[1].toDouble(), it.groupValues[2].toDouble(), atEnd = name == "end")
            }
            in START_TIMES -> if (frame.start == null) frame.start = DayCollector.parseTime(raw)
            in END_TIMES -> frame.end = DayCollector.parseTime(raw)
        }
    }

    private fun close(frame: Frame, parent: Frame?, collector: DayCollector) {
        val lat = frame.lat
        val lon = frame.lon
        if (lat != null && lon != null) frame.pending += Pending(lat, lon, atEnd = frame.key in END_KEYS)
        if (frame.pending.isNotEmpty()) frame.placed = true
        if (frame.placed && parent != null) parent.placed = true
        val start = frame.start
        val end = frame.end
        when {
            start != null && frame.pending.isNotEmpty() -> {
                val single = frame.pending.singleOrNull()
                if (single != null && end != null) {
                    collector.span(single.lat, single.lon, start.first, end.first, start.second)
                } else {
                    frame.pending.forEach {
                        val moment = if (it.atEnd && end != null) end else start
                        collector.point(it.lat, it.lon, moment.first, moment.second)
                    }
                }
            }
            parent == null -> Unit
            frame.pending.isNotEmpty() -> parent.pending += frame.pending
            // A "duration" object only holds times; they belong to the visit around it. Objects
            // whose places were dated already keep their times, or unrelated places further up
            // (like a list of frequent places) would pick them up.
            start != null && !frame.placed && parent.start == null -> {
                parent.start = start
                parent.end = end
            }
        }
    }
}

/**
 * Opens location files of any kind this app knows: GPX, TCX, KML/KMZ, GeoJSON, FIT, NMEA and
 * Google Timeline exports, alone or in zips. The format is told from the content, not the
 * name, since shared files often arrive without one.
 */
class LocationFileImporter(private val context: Context, private val locator: () -> CountryLocator) {
    data class Result(val days: List<FoundDay>, val points: Int, val files: Int)

    suspend fun read(uris: List<Uri>, progress: (Float) -> Unit): Result = withContext(Dispatchers.IO) {
        val sizes = uris.map { uri ->
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
            }
        }
        val total = sizes.sumOf { it ?: 0L }.takeIf { sizes.none { size -> size == null } && it > 0 }
        val collector = DayCollector { lat, lon -> locator().locate(lat, lon) }
        var done = 0L
        var read = 0
        for (uri in uris) {
            val raw = context.contentResolver.openInputStream(uri) ?: throw IOException("Can't open the file")
            val counting = CountingInputStream(raw)
            val tick = {
                ensureActive()
                if (total != null) progress(((done + counting.count).toFloat() / total).coerceIn(0f, 1f))
            }
            BufferedInputStream(counting, 64 * 1024).use { if (readAny(it, collector, tick)) read++ }
            done += counting.count
        }
        if (read == 0) throw IOException(if (uris.size == 1) "This isn't a location file Sojourn can read" else "None of these are location files Sojourn can read")
        progress(1f)
        Result(collector.days(), collector.points, read)
    }

    /** Returns whether the stream held anything readable. */
    private fun readAny(input: BufferedInputStream, collector: DayCollector, tick: () -> Unit): Boolean {
        when (sniff(input)) {
            Kind.ZIP -> {
                var found = false
                val zip = ZipInputStream(input)
                while (true) {
                    zip.nextEntry ?: break
                    // Entries are told apart by content too; a KMZ's doc.kml is just XML inside.
                    found = readAny(BufferedInputStream(KeepOpen(zip), 64 * 1024), collector, tick) || found
                }
                return found
            }
            Kind.FIT -> FitReader.read(input, collector, tick)
            Kind.XML -> XmlTrackReader.read(input, collector, tick)
            Kind.GEOJSON -> GeoJsonReader.read(input, collector, tick)
            Kind.JSON -> TimelineReader.read(input, collector, tick)
            Kind.NMEA -> NmeaReader.read(input, collector, tick)
            Kind.OWNTRACKS -> input.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val json = line.substringAfter('{', "").takeIf { it.isNotEmpty() } ?: return@forEach
                    TimelineReader.read("{$json".byteInputStream(), collector, tick)
                }
            }
            Kind.UNKNOWN -> return false
        }
        return true
    }

    private enum class Kind { ZIP, FIT, XML, GEOJSON, JSON, NMEA, OWNTRACKS, UNKNOWN }

    private fun sniff(input: BufferedInputStream): Kind {
        input.mark(HEAD)
        val head = ByteArray(HEAD)
        var read = 0
        while (read < HEAD) {
            val n = input.read(head, read, HEAD - read)
            if (n <= 0) break
            read += n
        }
        input.reset()
        if (read >= 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()) return Kind.ZIP
        if (read >= 12 && String(head, 8, 4, Charsets.US_ASCII) == ".FIT") return Kind.FIT
        // Skip leading whitespace; a UTF-8 byte order mark is three bytes above 127.
        val first = (0 until read).map { head[it].toInt() and 0xFF }.firstOrNull { it < 128 && !it.toChar().isWhitespace() }?.toChar()
        val text = String(head, 0, read, Charsets.UTF_8)
        return when {
            first == '<' -> Kind.XML
            (first == '{' || first == '[') && (text.contains("\"FeatureCollection\"") || text.contains("\"Feature\"")) -> Kind.GEOJSON
            first == '{' || first == '[' -> Kind.JSON
            first == '$' -> Kind.NMEA
            // OwnTracks Recorder's .rec: a time, a tab, a topic, a tab, then one JSON message a line.
            REC_LINE.containsMatchIn(text) -> Kind.OWNTRACKS
            else -> Kind.UNKNOWN
        }
    }

    private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
        @Volatile var count = 0L

        override fun read(): Int = super.read().also { if (it >= 0) count++ }

        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }
    }

    /** Readers close what they read; a zip entry must stay open for the next one. */
    private class KeepOpen(input: InputStream) : FilterInputStream(input) {
        override fun close() = Unit
    }

    private companion object {
        const val HEAD = 4096
        val REC_LINE = Regex("""^\d{4}-\d{2}-\d{2}T[\d:]+Z?\t""")
    }
}
