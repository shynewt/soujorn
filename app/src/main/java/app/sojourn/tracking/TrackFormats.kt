package app.sojourn.tracking

import android.util.JsonReader
import android.util.JsonToken
import android.util.Xml
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import org.xmlpull.v1.XmlPullParser

private typealias Moment = Pair<Instant, ZoneOffset?>

/**
 * GPX, TCX and KML, which all come down to dated coordinates in XML:
 * - GPX track, route and way points with a `<time>`;
 * - TCX `<Trackpoint>`s with `<Time>` and `<Position>`;
 * - KML `<gx:Track>`s (`<when>`s paired in order with `<gx:coord>`s), and placemarks dated by
 *   a `<TimeStamp>` or `<TimeSpan>`, as Google's old Timeline KML export wrote them.
 * Coordinates without a time can't be put on a day and are skipped.
 */
object XmlTrackReader {
    private val WHITESPACE = Regex("\\s+")

    fun read(input: InputStream, collector: DayCollector, tick: () -> Unit) {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)

        // GPX and TCX points.
        var inPoint = false
        var lat: Double? = null
        var lon: Double? = null
        var time: Moment? = null
        // KML tracks and placemarks.
        var inTrack = false
        val whens = ArrayDeque<Moment?>()
        var placemark = false
        var at: Moment? = null
        var begin: Moment? = null
        var end: Moment? = null
        val coordinates = mutableListOf<Pair<Double, Double>>()
        var count = 0

        fun text() = runCatching { parser.nextText() }.getOrDefault("")

        while (true) {
            when (parser.next()) {
                XmlPullParser.END_DOCUMENT -> return
                XmlPullParser.START_TAG -> when (parser.name.substringAfter(':')) {
                    "trkpt", "rtept", "wpt" -> {
                        inPoint = true
                        lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                        lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                        time = null
                    }
                    "Trackpoint" -> {
                        inPoint = true
                        lat = null
                        lon = null
                        time = null
                    }
                    "time", "Time" -> if (inPoint) time = DayCollector.parseTime(text())
                    "LatitudeDegrees" -> if (inPoint) lat = text().trim().toDoubleOrNull()
                    "LongitudeDegrees" -> if (inPoint) lon = text().trim().toDoubleOrNull()
                    "Track" -> if (parser.name.contains(':')) {
                        // gx:Track; TCX also has a <Track>, but without a prefix.
                        inTrack = true
                        whens.clear()
                    }
                    "when" -> DayCollector.parseTime(text()).let { if (inTrack) whens.addLast(it) else at = it }
                    "coord" -> if (inTrack) {
                        val parts = text().trim().split(WHITESPACE)
                        val moment = whens.removeFirstOrNull()
                        val x = parts.getOrNull(0)?.toDoubleOrNull()
                        val y = parts.getOrNull(1)?.toDoubleOrNull()
                        if (moment != null && x != null && y != null) collector.point(y, x, moment.first, moment.second)
                        if (++count % 2_000 == 0) tick()
                    }
                    "Placemark" -> {
                        placemark = true
                        at = null
                        begin = null
                        end = null
                        coordinates.clear()
                    }
                    "begin" -> begin = DayCollector.parseTime(text())
                    "end" -> end = DayCollector.parseTime(text())
                    "coordinates" -> if (placemark) {
                        text().trim().split(WHITESPACE).forEach { tuple ->
                            val parts = tuple.split(',')
                            val x = parts.getOrNull(0)?.toDoubleOrNull()
                            val y = parts.getOrNull(1)?.toDoubleOrNull()
                            if (x != null && y != null) coordinates += y to x
                        }
                    }
                }
                XmlPullParser.END_TAG -> when (parser.name.substringAfter(':')) {
                    "trkpt", "rtept", "wpt", "Trackpoint" -> {
                        val moment = time
                        val y = lat
                        val x = lon
                        if (y != null && x != null && moment != null) collector.point(y, x, moment.first, moment.second)
                        inPoint = false
                        if (++count % 2_000 == 0) tick()
                    }
                    "Track" -> inTrack = false
                    "Placemark" -> {
                        val start = at ?: begin
                        if (start != null && coordinates.isNotEmpty()) {
                            val finish = end
                            val single = coordinates.singleOrNull()
                            if (single != null && finish != null) {
                                collector.span(single.first, single.second, start.first, finish.first, start.second)
                            } else {
                                // A dated path: where it began on its start, where it ended on its end.
                                collector.point(coordinates.first().first, coordinates.first().second, start.first, start.second)
                                if (finish != null) collector.point(coordinates.last().first, coordinates.last().second, finish.first, finish.second)
                            }
                        }
                        placemark = false
                    }
                }
            }
        }
    }
}

/**
 * GeoJSON points and lines. Times come from the properties: `time`/`timestamp` and friends for a
 * whole feature, or per coordinate from `coordTimes` (togeojson) or `coordinateProperties.times`,
 * or a fourth coordinate value holding epoch seconds or milliseconds.
 */
object GeoJsonReader {
    private val TIME_KEYS = listOf("time", "timestamp", "datetime", "date", "when", "startTime", "start", "begin")
    private val END_KEYS = listOf("endTime", "end")

    fun read(input: InputStream, collector: DayCollector, tick: () -> Unit) {
        val reader = JsonReader(InputStreamReader(input, Charsets.UTF_8))
        reader.isLenient = true
        walk(tree(reader), collector, tick)
    }

    private fun walk(node: Any?, collector: DayCollector, tick: () -> Unit) {
        when (node) {
            is List<*> -> node.forEach { walk(it, collector, tick) }
            is Map<*, *> -> when (node["type"]) {
                "FeatureCollection" -> walk(node["features"], collector, tick)
                "Feature" -> {
                    feature(node, collector)
                    tick()
                }
                else -> node.values.forEach { if (it is Map<*, *> || it is List<*>) walk(it, collector, tick) }
            }
        }
    }

    private fun feature(feature: Map<*, *>, collector: DayCollector) {
        val geometry = feature["geometry"] as? Map<*, *> ?: return
        val properties = feature["properties"] as? Map<*, *> ?: emptyMap<Any, Any>()
        // Times as text or, as Dawarich writes them, epoch seconds.
        val start = TIME_KEYS.firstNotNullOfOrNull { properties[it]?.toString()?.let(DayCollector::parseTime) }
        val end = END_KEYS.firstNotNullOfOrNull { properties[it]?.toString()?.let(DayCollector::parseTime) }
        val lines = when (geometry["type"]) {
            "Point" -> listOf(listOf(geometry["coordinates"]))
            "LineString", "MultiPoint" -> listOf(geometry["coordinates"] as? List<*> ?: return)
            "MultiLineString", "Polygon" -> (geometry["coordinates"] as? List<*> ?: return).filterIsInstance<List<*>>()
            else -> return
        }
        val times = (properties["coordTimes"] ?: (properties["coordinateProperties"] as? Map<*, *>)?.get("times")) as? List<*>
        lines.forEachIndexed { lineIndex, line ->
            val lineTimes = times?.let { if (lines.size > 1) it.getOrNull(lineIndex) as? List<*> else it }
            val points = line.mapNotNull { it as? List<*> }
            points.forEachIndexed { index, position ->
                val lon = (position.getOrNull(0) as? Double) ?: return@forEachIndexed
                val lat = (position.getOrNull(1) as? Double) ?: return@forEachIndexed
                val own = (lineTimes?.getOrNull(index) as? String)?.let(DayCollector::parseTime)
                    ?: (position.getOrNull(3) as? Double)?.let(::epoch)
                when {
                    own != null -> collector.point(lat, lon, own.first, own.second)
                    points.size == 1 && start != null && end != null -> collector.span(lat, lon, start.first, end.first, start.second)
                    index == 0 && start != null -> collector.point(lat, lon, start.first, start.second)
                    index == points.lastIndex && end != null -> collector.point(lat, lon, end.first, end.second)
                }
            }
        }
    }

    private fun epoch(value: Double): Moment = Instant.ofEpochMilli(if (value > 1e11) value.toLong() else (value * 1000).toLong()) to null

    private fun tree(reader: JsonReader): Any? = when (reader.peek()) {
        JsonToken.BEGIN_OBJECT -> {
            val map = HashMap<String, Any?>()
            reader.beginObject()
            while (reader.hasNext()) map[reader.nextName()] = tree(reader)
            reader.endObject()
            map
        }
        JsonToken.BEGIN_ARRAY -> {
            val list = ArrayList<Any?>()
            reader.beginArray()
            while (reader.hasNext()) list += tree(reader)
            reader.endArray()
            list
        }
        JsonToken.NUMBER -> reader.nextDouble()
        JsonToken.STRING -> reader.nextString()
        JsonToken.BOOLEAN -> reader.nextBoolean()
        else -> reader.nextNull().let { null }
    }
}

/** NMEA 0183 logs, as raw GPS loggers write them. Only RMC sentences carry a date. */
object NmeaReader {
    fun read(input: InputStream, collector: DayCollector, tick: () -> Unit) {
        input.bufferedReader().useLines { lines ->
            lines.forEachIndexed { index, line ->
                if (index % 2_000 == 0) tick()
                val fields = line.trim().substringBefore('*').split(',')
                if (fields.size < 10 || !fields[0].endsWith("RMC") || fields[2] != "A") return@forEachIndexed
                val lat = degrees(fields[3], 2)?.let { if (fields[4] == "S") -it else it } ?: return@forEachIndexed
                val lon = degrees(fields[5], 3)?.let { if (fields[6] == "W") -it else it } ?: return@forEachIndexed
                val time = fields[1]
                val date = fields[9]
                if (time.length < 6 || date.length != 6) return@forEachIndexed
                val moment = runCatching {
                    // Two-digit years: anything that would lie in the future is from the 1900s.
                    val shortYear = date.substring(4, 6).toInt()
                    val year = if (2000 + shortYear > LocalDate.now().year) 1900 + shortYear else 2000 + shortYear
                    LocalDate.of(year, date.substring(2, 4).toInt(), date.substring(0, 2).toInt())
                        .atTime(LocalTime.of(time.substring(0, 2).toInt(), time.substring(2, 4).toInt(), time.substring(4, 6).toInt()))
                        .toInstant(ZoneOffset.UTC)
                }.getOrNull() ?: return@forEachIndexed
                collector.point(lat, lon, moment, null)
            }
        }
    }

    /** "4807.038" with two degree digits is 48° 07.038'. */
    private fun degrees(value: String, degreeDigits: Int): Double? {
        if (value.length <= degreeDigits) return null
        val whole = value.substring(0, degreeDigits).toIntOrNull() ?: return null
        val minutes = value.substring(degreeDigits).toDoubleOrNull() ?: return null
        return whole + minutes / 60
    }
}

/**
 * Garmin's binary FIT format, written by most watches and bike computers. Only `record`
 * messages matter here: their timestamp and position. Everything else is skipped by size.
 */
object FitReader {
    private const val RECORD = 20
    private const val TIMESTAMP = 253
    private const val LATITUDE = 0
    private const val LONGITUDE = 1

    /** FIT times count seconds from 31 December 1989, UTC. */
    private const val FIT_EPOCH = 631_065_600L
    private const val SEMICIRCLES = 180.0 / 2147483648.0
    private const val MAX_BYTES = 64 * 1024 * 1024

    private class Field(val number: Int, val size: Int)

    private class Definition(val global: Int, val bigEndian: Boolean, val fields: List<Field>, val developerBytes: Int)

    fun read(input: InputStream, collector: DayCollector, tick: () -> Unit) {
        val bytes = input.readBytes()
        if (bytes.size > MAX_BYTES) throw IOException("This FIT file is too large")
        var offset = 0
        // A file can chain several FIT files one after another.
        while (offset + 12 <= bytes.size && String(bytes, offset + 8, 4, Charsets.US_ASCII) == ".FIT") {
            val headerSize = bytes[offset].toInt() and 0xFF
            val dataSize = int32(bytes, offset + 4, bigEndian = false).toInt()
            val start = offset + headerSize
            val end = minOf(start + dataSize, bytes.size)
            messages(bytes, start, end, collector, tick)
            offset = end + 2 // the file CRC
        }
    }

    private fun messages(bytes: ByteArray, from: Int, to: Int, collector: DayCollector, tick: () -> Unit) {
        val definitions = arrayOfNulls<Definition>(16)
        var lastTimestamp = 0L
        var position = from
        var count = 0
        while (position < to) {
            val header = bytes[position++].toInt() and 0xFF
            if (header and 0x80 != 0) {
                // Compressed timestamp header: a data message with a 5-bit time offset.
                val definition = definitions[(header shr 5) and 0x03] ?: throw IOException("Broken FIT file")
                val offset = (header and 0x1F).toLong()
                lastTimestamp = (lastTimestamp and 0x1F.toLong().inv()) + offset + if (offset >= (lastTimestamp and 0x1F)) 0 else 0x20
                position = data(bytes, position, definition, lastTimestamp, collector) { lastTimestamp = it }
            } else if (header and 0x40 != 0) {
                val bigEndian = bytes[position + 1].toInt() == 1
                val global = int16(bytes, position + 2, bigEndian)
                val fieldCount = bytes[position + 4].toInt() and 0xFF
                position += 5
                val fields = List(fieldCount) { i ->
                    Field(bytes[position + i * 3].toInt() and 0xFF, bytes[position + i * 3 + 1].toInt() and 0xFF)
                }
                position += fieldCount * 3
                var developerBytes = 0
                if (header and 0x20 != 0) {
                    val developerCount = bytes[position++].toInt() and 0xFF
                    repeat(developerCount) { i -> developerBytes += bytes[position + i * 3 + 1].toInt() and 0xFF }
                    position += developerCount * 3
                }
                definitions[header and 0x0F] = Definition(global, bigEndian, fields, developerBytes)
            } else {
                val definition = definitions[header and 0x0F] ?: throw IOException("Broken FIT file")
                position = data(bytes, position, definition, null, collector) { lastTimestamp = it }
            }
            if (++count % 2_000 == 0) tick()
        }
    }

    /** Reads one data message and returns the position after it. */
    private fun data(
        bytes: ByteArray,
        start: Int,
        definition: Definition,
        compressedTime: Long?,
        collector: DayCollector,
        onTimestamp: (Long) -> Unit,
    ): Int {
        var position = start
        var timestamp = compressedTime
        var lat: Int? = null
        var lon: Int? = null
        for (field in definition.fields) {
            if (position + field.size > bytes.size) return bytes.size
            if (field.size == 4) {
                val value = int32(bytes, position, definition.bigEndian)
                when (field.number) {
                    TIMESTAMP -> if (value != 0xFFFFFFFFL) timestamp = value.also(onTimestamp)
                    LATITUDE -> lat = value.toInt()
                    LONGITUDE -> lon = value.toInt()
                }
            }
            position += field.size
        }
        position += definition.developerBytes
        val time = timestamp
        if (definition.global == RECORD && time != null && lat != null && lon != null && lat != Int.MAX_VALUE && lon != Int.MAX_VALUE) {
            collector.point(lat * SEMICIRCLES, lon * SEMICIRCLES, Instant.ofEpochSecond(FIT_EPOCH + time), null)
        }
        return position
    }

    private fun int16(bytes: ByteArray, at: Int, bigEndian: Boolean): Int {
        val a = bytes[at].toInt() and 0xFF
        val b = bytes[at + 1].toInt() and 0xFF
        return if (bigEndian) (a shl 8) or b else (b shl 8) or a
    }

    private fun int32(bytes: ByteArray, at: Int, bigEndian: Boolean): Long {
        var value = 0L
        for (i in 0 until 4) {
            val byte = (bytes[at + if (bigEndian) i else 3 - i].toLong() and 0xFF)
            value = (value shl 8) or byte
        }
        return value
    }
}
