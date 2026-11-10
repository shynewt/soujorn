package app.sojourn.tracking

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.sojourn.data.geo.CountryLocator
import app.sojourn.data.geo.GeoShapes
import app.sojourn.ui.imports.ImportViewModel
import java.io.File
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class LocationFilesTest {
    private val locator by lazy { CountryLocator(File("src/main/assets/borders.bin").inputStream().use(GeoShapes::read)) }

    private fun collector() = DayCollector { lat, lon -> locator.locate(lat, lon) }

    /** Each day's countries, most samples first (ties by code), as "2025-05-01" to "ES". */
    private fun summary(days: List<FoundDay>) = days.associate { day ->
        day.date.toString() to day.countries.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).joinToString(",") { it.key }
    }

    @Test
    fun gpxTrackPointsAreDatedByLocalTime() {
        val gpx = """<?xml version="1.0"?>
            <gpx version="1.1" creator="test" xmlns="http://www.topografix.com/GPX/1/1">
              <wpt lat="10" lon="10"><name>no time, skipped</name></wpt>
              <trk><trkseg>
                <trkpt lat="41.3874" lon="2.1686"><ele>10</ele><time>2025-05-01T10:00:00Z</time></trkpt>
                <trkpt lat="38.7223" lon="-9.1393"><time>2025-05-02T23:30:00Z</time></trkpt>
              </trkseg></trk>
            </gpx>"""
        val collector = collector()
        XmlTrackReader.read(gpx.byteInputStream(), collector) {}
        // 23:30 UTC in Lisbon is still the 2nd locally.
        assertEquals(mapOf("2025-05-01" to "ES", "2025-05-02" to "PT"), summary(collector.days()))
    }

    @Test
    fun takeoutRecordsWithBothTimeFormats() {
        val json = """{"locations":[
            {"latitudeE7":413874000,"longitudeE7":21686000,"accuracy":10,"activity":[{"activity":[{"type":"STILL","confidence":100}],"timestamp":"2025-05-01T10:00:01.000Z"}],"timestamp":"2025-05-01T10:00:00.000Z"},
            {"latitudeE7":387223000,"longitudeE7":-91393000,"timestampMs":"1746180000000"}
        ]}"""
        val collector = collector()
        TimelineReader.read(json.byteInputStream(), collector) {}
        assertEquals(mapOf("2025-05-01" to "ES", "2025-05-02" to "PT"), summary(collector.days()))
    }

    @Test
    fun semanticHistoryVisitsCoverEveryDayAndTripsTheirEnds() {
        val json = """{"timelineObjects":[
            {"placeVisit":{"location":{"latitudeE7":137563000,"longitudeE7":1005018000,"name":"Bangkok"},
              "duration":{"startTimestamp":"2025-03-01T12:00:00Z","endTimestamp":"2025-03-05T08:00:00Z"},
              "otherCandidateLocations":[{"latitudeE7":35676200,"longitudeE7":1396503000}]}},
            {"activitySegment":{"startLocation":{"latitudeE7":137563000,"longitudeE7":1005018000},
              "endLocation":{"latitudeE7":31390000,"longitudeE7":1016869000},
              "duration":{"startTimestamp":"2025-03-05T09:00:00Z","endTimestamp":"2025-03-06T02:00:00Z"},
              "activityType":"FLYING"}}
        ]}"""
        val collector = collector()
        TimelineReader.read(json.byteInputStream(), collector) {}
        assertEquals(
            mapOf("2025-03-01" to "TH", "2025-03-02" to "TH", "2025-03-03" to "TH", "2025-03-04" to "TH", "2025-03-05" to "TH", "2025-03-06" to "MY"),
            summary(collector.days()),
        )
    }

    @Test
    fun onDeviceTimelineExport() {
        val json = """{"semanticSegments":[
            {"startTime":"2025-06-01T10:00:00.000+02:00","endTime":"2025-06-03T09:00:00.000+02:00","visit":{"hierarchyLevel":0,"probability":0.9,"topCandidate":{"placeId":"x","semanticType":"UNKNOWN","probability":0.8,"placeLocation":{"latLng":"48.8566°, 2.3522°"}}}},
            {"startTime":"2025-06-03T10:00:00.000+02:00","endTime":"2025-06-03T12:00:00.000+02:00","timelinePath":[{"point":"52.5200°, 13.4050°","time":"2025-06-03T11:00:00.000+02:00"}]}
          ],
          "rawSignals":[{"position":{"LatLng":"41.9028°, 12.4964°","accuracyMeters":10,"timestamp":"2025-06-05T10:00:00.000+02:00"}}],
          "userLocationProfile":{"frequentPlaces":[{"placeId":"y","placeLocation":"35.6762°, 139.6503°"}]}}"""
        val collector = collector()
        TimelineReader.read(json.byteInputStream(), collector) {}
        assertEquals(mapOf("2025-06-01" to "FR", "2025-06-02" to "FR", "2025-06-03" to "DE,FR", "2025-06-05" to "IT"), summary(collector.days()))
    }

    @Test
    fun iosTimelineExport() {
        val json = """[
            {"startTime":"2025-07-01T10:00:00.000+09:00","endTime":"2025-07-02T10:00:00.000+09:00","visit":{"probability":"0.9","topCandidate":{"probability":"0.8","placeLocation":"geo:35.676200,139.650300"}}},
            {"startTime":"2025-07-02T11:00:00.000+09:00","endTime":"2025-07-02T15:00:00.000+09:00","activity":{"start":"geo:35.6762,139.6503","end":"geo:37.5665,126.9780","topCandidate":{"type":"flying"}}}
        ]"""
        val collector = collector()
        TimelineReader.read(json.byteInputStream(), collector) {}
        assertEquals(mapOf("2025-07-01" to "JP", "2025-07-02" to "JP,KR"), summary(collector.days()))
    }

    @Test
    fun takeoutZipIsReadEntryByEntry() = runBlocking {
        val zip = File.createTempFile("takeout", ".zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("Takeout/Location History/Records.json"))
            out.write("""{"locations":[{"latitudeE7":413874000,"longitudeE7":21686000,"timestamp":"2025-05-01T10:00:00Z"}]}""".toByteArray())
            out.putNextEntry(ZipEntry("Takeout/archive_browser.html"))
            out.write("<html></html>".toByteArray())
            out.putNextEntry(ZipEntry("tracks/ride.gpx"))
            out.write("""<gpx><trk><trkseg><trkpt lat="38.7223" lon="-9.1393"><time>2025-05-04T12:00:00Z</time></trkpt></trkseg></trk></gpx>""".toByteArray())
        }
        val importer = LocationFileImporter(ApplicationProvider.getApplicationContext()) { locator }
        val result = importer.read(listOf(Uri.fromFile(zip))) {}
        assertEquals(2, result.points)
        assertEquals(mapOf("2025-05-01" to "ES", "2025-05-04" to "PT"), summary(result.days))
    }

    @Test
    fun tcxTrackpoints() {
        val tcx = """<?xml version="1.0" encoding="UTF-8"?>
            <TrainingCenterDatabase xmlns="http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2">
              <Activities><Activity Sport="Biking"><Id>2025-08-01T08:00:00Z</Id><Lap StartTime="2025-08-01T08:00:00Z"><Track>
                <Trackpoint><Time>2025-08-01T08:00:00Z</Time><Position><LatitudeDegrees>46.2044</LatitudeDegrees><LongitudeDegrees>6.1432</LongitudeDegrees></Position></Trackpoint>
                <Trackpoint><Time>2025-08-01T09:00:00Z</Time><HeartRateBpm><Value>120</Value></HeartRateBpm></Trackpoint>
              </Track></Lap></Activity></Activities>
            </TrainingCenterDatabase>"""
        val collector = collector()
        XmlTrackReader.read(tcx.byteInputStream(), collector) {}
        assertEquals(mapOf("2025-08-01" to "CH"), summary(collector.days()))
    }

    @Test
    fun kmlTracksAndDatedPlacemarks() {
        val kml = """<?xml version="1.0" encoding="UTF-8"?>
            <kml xmlns="http://www.opengis.net/kml/2.2" xmlns:gx="http://www.google.com/kml/ext/2.2"><Document>
              <Placemark><gx:Track>
                <when>2025-09-01T10:00:00Z</when><when>2025-09-01T11:00:00Z</when>
                <gx:coord>14.4378 50.0755 200</gx:coord><gx:coord>16.3738 48.2082 180</gx:coord>
              </gx:Track></Placemark>
              <Placemark><name>Budapest</name>
                <TimeSpan><begin>2025-09-02T12:00:00Z</begin><end>2025-09-04T09:00:00Z</end></TimeSpan>
                <Point><coordinates>19.0402,47.4979,0</coordinates></Point>
              </Placemark>
              <Placemark><name>undated, skipped</name><Point><coordinates>2.35,48.85</coordinates></Point></Placemark>
            </Document></kml>"""
        val collector = collector()
        XmlTrackReader.read(kml.byteInputStream(), collector) {}
        assertEquals(mapOf("2025-09-01" to "AT,CZ", "2025-09-02" to "HU", "2025-09-03" to "HU", "2025-09-04" to "HU"), summary(collector.days()))
    }

    @Test
    fun geoJsonLinesAndPoints() {
        val json = """{"type":"FeatureCollection","features":[
            {"type":"Feature","properties":{"coordTimes":["2025-10-01T10:00:00Z","2025-10-02T10:00:00Z"]},
             "geometry":{"type":"LineString","coordinates":[[-3.7038,40.4168],[-9.1393,38.7223]]}},
            {"type":"Feature","properties":{"time":"2025-10-05T10:00:00+01:00"},"geometry":{"type":"Point","coordinates":[-0.1276,51.5072]}},
            {"type":"Feature","properties":{},"geometry":{"type":"Point","coordinates":[2.35,48.85,35,1759917600]}}
        ]}"""
        val collector = collector()
        GeoJsonReader.read(json.byteInputStream(), collector) {}
        assertEquals(mapOf("2025-10-01" to "ES", "2025-10-02" to "PT", "2025-10-05" to "GB", "2025-10-08" to "FR"), summary(collector.days()))
    }

    @Test
    fun nmeaRmcSentences() {
        val nmea = """
            ${'$'}GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47
            ${'$'}GPRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W*6A
            ${'$'}GNRMC,101500.00,V,,,,,,,240394,,,N*00
            ${'$'}GPRMC,080000,A,4124.894,N,00210.116,E,000.0,000.0,250394,,,A*00
        """.trimIndent()
        val collector = collector()
        NmeaReader.read(nmea.byteInputStream(), collector) {}
        assertEquals(mapOf("1994-03-23" to "DE", "1994-03-25" to "ES"), summary(collector.days()))
    }

    @Test
    fun fitRecordsIncludingCompressedTimestamps() {
        val collector = collector()
        FitReader.read(fitFile().inputStream(), collector) {}
        assertEquals(3, collector.points)
        assertEquals(mapOf("2025-06-15" to "NL"), summary(collector.days()))
    }

    /** A minimal FIT file: one record definition, a full record and two compressed-time ones. */
    private fun fitFile(): ByteArray {
        val data = java.io.ByteArrayOutputStream()
        fun u8(v: Int) = data.write(v and 0xFF)
        fun u32(v: Long) = repeat(4) { u8((v shr (8 * it)).toInt()) }
        val semicircles = { degrees: Double -> (degrees * 2147483648.0 / 180).toLong() }
        val time = java.time.Instant.parse("2025-06-15T10:00:00Z").epochSecond - 631_065_600L
        // Definition, local type 0: global 20 (record) with timestamp, latitude, longitude.
        u8(0x40); u8(0); u8(0); u8(20); u8(0); u8(3)
        u8(253); u8(4); u8(0x86)
        u8(0); u8(4); u8(0x85)
        u8(1); u8(4); u8(0x85)
        // A full record in Amsterdam.
        u8(0x00); u32(time); u32(semicircles(52.3676)); u32(semicircles(4.9041))
        // Definition, local type 1: record with position only, for compressed timestamps.
        u8(0x41); u8(0); u8(0); u8(20); u8(0); u8(2)
        u8(0); u8(4); u8(0x85)
        u8(1); u8(4); u8(0x85)
        repeat(2) { i ->
            u8(0x80 or (1 shl 5) or ((time + 5 * (i + 1)).toInt() and 0x1F))
            u32(semicircles(52.37)); u32(semicircles(4.90))
        }
        val body = data.toByteArray()
        val header = java.nio.ByteBuffer.allocate(14).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .put(14).put(0x20).putShort(2100).putInt(body.size).put(".FIT".toByteArray()).putShort(0).array()
        return header + body + byteArrayOf(0, 0)
    }

    @Test
    fun kmzAndFitInsideZipsAreFound() = runBlocking {
        val kmz = File.createTempFile("trip", ".kmz")
        ZipOutputStream(kmz.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("doc.kml"))
            out.write(
                """<kml xmlns:gx="http://www.google.com/kml/ext/2.2"><Placemark><gx:Track><when>2025-09-01T10:00:00Z</when><gx:coord>14.4378 50.0755 0</gx:coord></gx:Track></Placemark></kml>""".toByteArray(),
            )
            out.putNextEntry(ZipEntry("activity.fit"))
            out.write(fitFile())
        }
        val importer = LocationFileImporter(ApplicationProvider.getApplicationContext()) { locator }
        val result = importer.read(listOf(Uri.fromFile(kmz))) {}
        assertEquals(mapOf("2025-06-15" to "NL", "2025-09-01" to "CZ"), summary(result.days))
    }

    @Test
    fun severalSharedFilesAreReadTogether() = runBlocking {
        val gpx = File.createTempFile("ride", ".gpx").apply { writeText("""<gpx><wpt lat="41.3874" lon="2.1686"><time>2025-05-01T10:00:00Z</time></wpt></gpx>""") }
        val notes = File.createTempFile("notes", ".txt").apply { writeText("just some notes") }
        val fit = File.createTempFile("run", ".fit").apply { writeBytes(fitFile()) }
        val importer = LocationFileImporter(ApplicationProvider.getApplicationContext()) { locator }
        val result = importer.read(listOf(gpx, notes, fit).map(Uri::fromFile)) {}
        assertEquals(2, result.files)
        assertEquals(mapOf("2025-05-01" to "ES", "2025-06-15" to "NL"), summary(result.days))
    }

    @Test
    fun foundDaysGroupIntoStaysAcrossShortGaps() {
        val day = { date: String, country: String -> FoundDay(LocalDate.parse(date), mapOf(country to 1)) }
        val stays = ImportViewModel.group(
            listOf(day("2025-03-01", "TH"), day("2025-03-03", "TH"), day("2025-03-10", "TH"), day("2025-03-11", "MY")),
            known = setOf(LocalDate.parse("2025-03-02")),
        )
        assertEquals(listOf("MY" to 1, "TH" to 1, "TH" to 3), stays.map { it.country to it.days.size })
        assertEquals(2, stays.last().newDays)
    }

    @Test
    fun foundDaysBackUpDaysAlreadyRecordedThereAndKeepTheirPhotos() {
        val first = LocalDate.parse("2025-03-01")
        val days = listOf(
            FoundDay(first, mapOf("TH" to 2), mapOf("TH" to listOf("content://media/1", "content://media/2"))),
            FoundDay(first.plusDays(1), mapOf("TH" to 1), mapOf("TH" to listOf("content://media/3"))),
        )
        val stay = ImportViewModel.group(days, known = setOf(first), pairs = setOf(first to "TH")).single()
        assertEquals(1, stay.newDays)
        assertEquals(1, stay.backed)
        assertEquals(listOf("content://media/1", "content://media/2"), stay.photos[first])
    }

    /** A tracker every five minutes: a whole day of points, at one place unless moved. */
    private fun track(collector: DayCollector, day: String, from: Int, to: Int, lat: Double, lon: Double) {
        var t = java.time.LocalDateTime.parse("${day}T00:00").plusMinutes(from.toLong())
        val end = java.time.LocalDateTime.parse("${day}T00:00").plusMinutes(to.toLong())
        while (t < end) {
            collector.point(lat, lon, t.toInstant(java.time.ZoneOffset.ofHours(1)), java.time.ZoneOffset.ofHours(1))
            t = t.plusMinutes(5)
        }
    }

    @Test
    fun aLoneGpsJumpOverTheBorderIsDropped() {
        val collector = collector()
        // Girona all day, and one fix in France at 13:00 between fixes in Spain.
        track(collector, "2026-03-10", 0, 13 * 60, 41.98, 2.82)
        collector.point(42.70, 2.90, java.time.Instant.parse("2026-03-10T12:00:00Z"), java.time.ZoneOffset.ofHours(1))
        track(collector, "2026-03-10", 13 * 60 + 30, 24 * 60, 41.98, 2.82)
        assertEquals(mapOf("2026-03-10" to "ES"), summary(collector.days()))
    }

    @Test
    fun aRealTripOverTheBorderStays() {
        val collector = collector()
        track(collector, "2026-03-10", 0, 10 * 60, 41.98, 2.82)
        track(collector, "2026-03-10", 11 * 60, 16 * 60, 42.70, 2.90)
        track(collector, "2026-03-10", 17 * 60, 24 * 60, 41.98, 2.82)
        assertEquals(mapOf("2026-03-10" to "ES,FR"), summary(collector.days()))
    }

    @Test
    fun quietDaysAtHomeAreWhereYouWereAtMidnight() {
        val collector = collector()
        // OwnTracks reports nothing while you stay put: Madrid on the 1st, nothing until the 4th.
        collector.point(40.4168, -3.7038, java.time.Instant.parse("2026-02-01T18:00:00Z"), null)
        collector.point(40.4168, -3.7038, java.time.Instant.parse("2026-02-04T09:00:00Z"), null)
        assertEquals(mapOf("2026-02-01" to "ES", "2026-02-02" to "ES", "2026-02-03" to "ES", "2026-02-04" to "ES"), summary(collector.days()))
    }

    @Test
    fun midnightAbroadCountsForTheNextDay() {
        val collector = collector()
        // Perpignan until 23:00, then home to Girona the next morning.
        track(collector, "2026-03-10", 8 * 60, 23 * 60, 42.70, 2.90)
        track(collector, "2026-03-11", 9 * 60, 12 * 60, 41.98, 2.82)
        assertEquals(mapOf("2026-03-10" to "FR", "2026-03-11" to "ES,FR"), summary(collector.days()))
    }

    @Test
    fun dawarichGeoJsonAndOwnTracksRecordings() = runBlocking {
        val dir = File(System.getProperty("java.io.tmpdir"), "tracks-${System.nanoTime()}").apply { mkdirs() }
        // Dawarich writes the time as epoch seconds.
        val geojson = File(dir, "export.json").apply {
            writeText("""{"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"Point","coordinates":[-3.7038,40.4168]},"properties":{"timestamp":1772366400,"battery":80}}]}""")
        }
        // OwnTracks Recorder: time, topic, then the message, one a line.
        val rec = File(dir, "2026-03.rec").apply {
            writeText(
                "2026-03-02T10:00:00Z\t*                 \t{\"_type\":\"location\",\"lat\":38.7223,\"lon\":-9.1393,\"tst\":1772445600,\"acc\":10}\n" +
                    "2026-03-02T10:05:00Z\t*                 \t{\"_type\":\"location\",\"lat\":38.7224,\"lon\":-9.1394,\"tst\":1772445900}\n",
            )
        }
        val importer = LocationFileImporter(ApplicationProvider.getApplicationContext()) { locator }
        val result = importer.read(listOf(Uri.fromFile(geojson), Uri.fromFile(rec))) {}
        assertEquals(2, result.files)
        assertEquals(mapOf("2026-03-01" to "ES", "2026-03-02" to "PT"), summary(result.days))
    }
}
