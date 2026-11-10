package app.sojourn.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import app.sojourn.data.db.Document
import app.sojourn.data.db.DocumentKind
import app.sojourn.data.db.EvidenceGroup
import app.sojourn.data.db.Flight
import app.sojourn.data.flights.Airlines
import app.sojourn.domain.DayBook
import app.sojourn.domain.DayKind
import app.sojourn.domain.Evidence
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Evidence files and the yearly evidence pack. Boarding passes are copied into the app, since
 * the files they came from are often temporary; photos stay in the gallery and are only linked,
 * so the app never holds their locations. The pack copies everything out at export time.
 */
class EvidenceStore(private val context: Context, private val dir: File) {

    /** Copies a file the user imported into the app's evidence folder; returns its name there. */
    suspend fun keep(uri: Uri, name: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            var clean = name.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(60).ifBlank { "file" }
            // Without an extension the file wouldn't open, show or export as what it is.
            if ('.' !in clean) {
                context.contentResolver.getType(uri)?.let { android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }?.let { clean += ".$it" }
            }
            val stored = "${UUID.randomUUID().toString().take(8)}-$clean"
            context.contentResolver.openInputStream(uri)!!.use { input -> File(dir, stored).outputStream().use { input.copyTo(it) } }
            stored
        }.getOrNull()
    }

    fun file(name: String): File = File(dir, name)

    /** A file's content as a SHA-256, to tell the same file shared twice. */
    suspend fun hash(uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching { context.contentResolver.openInputStream(uri)!!.use(::digest) }.getOrNull()
    }

    /** The same for a file kept here. */
    suspend fun hashOf(name: String): String? = withContext(Dispatchers.IO) {
        runCatching { File(dir, name).inputStream().use(::digest) }.getOrNull()
    }

    private fun digest(input: java.io.InputStream): String {
        val sha = java.security.MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            sha.update(buffer, 0, n)
        }
        return sha.digest().joinToString("") { "%02x".format(it) }
    }

    /** The pack's folder for a kind: "boarding-passes", "utility-bills", "work"… */
    private fun folder(kind: DocumentKind): String = when (kind) {
        DocumentKind.PHOTO -> "photos"
        DocumentKind.BOARDING_PASS -> "boarding-passes"
        DocumentKind.CALENDAR_EVENT -> "calendar"
        DocumentKind.BOOKING -> "bookings"
        DocumentKind.TICKET -> "tickets"
        DocumentKind.STAY -> "stays"
        DocumentKind.CARD_STATEMENT -> "card-statements"
        DocumentKind.MEDICAL -> "medical"
        DocumentKind.APPOINTMENT -> "appointments"
        DocumentKind.RECEIPT -> "receipts"
        DocumentKind.LEASE -> "home"
        DocumentKind.UTILITY_BILL -> "utility-bills"
        DocumentKind.RESIDENCE_CERTIFICATE -> "residence-certificates"
        DocumentKind.TAX_RETURN -> "tax-records"
        DocumentKind.WORK -> "work"
        DocumentKind.REGISTRATION -> "registration"
        DocumentKind.FAMILY -> "family"
        DocumentKind.OTHER -> "other"
    }

    /** What's in a year's pack, and what couldn't be copied. */
    data class PackResult(val days: Int, val files: Int, val missing: List<String>)

    /**
     * Writes [year]'s evidence pack to [out] as a zip: a summary page to read or print, listing
     * every document by group and each flight with its documents; every day as CSV with its
     * sources; the calendar events as one .ics; and every kept file (boarding passes, bills,
     * a lease…) in a folder per kind. Photos are copied from the gallery with their dates and
     * locations as the camera saved them.
     */
    suspend fun writePack(out: OutputStream, year: Int, book: DayBook, documents: List<Document>): PackResult = withContext(Dispatchers.IO) {
        val from = LocalDate.of(year, 1, 1)
        val to = minOf(LocalDate.of(year, 12, 31), book.today)
        // Period documents (a lease, a year's bills) count for every year they overlap. Documents
        // go in to the year's end, as the pack screen lists them (a flight booked for December);
        // the days stop at today.
        val inYear = documents.filter { it.day <= LocalDate.of(year, 12, 31) && (it.until ?: it.day) >= from }
        // One file backing two days (a pass's departure and arrival) goes in once.
        val copiedFiles = HashMap<String, String>()
        val missing = mutableListOf<String>()
        val paths = HashMap<Long, String>()
        var files = 0
        ZipOutputStream(out).use { zip ->
            fun put(path: String, write: (OutputStream) -> Unit) {
                zip.putNextEntry(ZipEntry(path))
                write(zip)
                zip.closeEntry()
            }
            val counters = HashMap<String, Int>()
            fun nameFor(doc: Document, folder: String, ext: String): String {
                val dates = doc.until?.takeIf { it != doc.day }?.let { "${doc.day}_to_$it" } ?: "${doc.day}"
                val base = "$folder/${dates}_${doc.country}"
                val n = (counters[base] ?: 0) + 1
                counters[base] = n
                return if (n == 1) "$base.$ext" else "${base}_$n.$ext"
            }
            for (doc in inYear) {
                when (doc.kind) {
                    DocumentKind.PHOTO -> {
                        val uri = doc.uri?.let(Uri::parse) ?: continue
                        val path = nameFor(doc, "photos", "jpg")
                        val copied = openPhoto(uri)?.use { input -> put(path) { input.copyTo(it) }; true } ?: false
                        if (copied) {
                            paths[doc.id] = path
                            files++
                        } else {
                            missing += "${doc.day} ${Countries.name(doc.country)}: photo no longer in the gallery"
                        }
                    }
                    DocumentKind.CALENDAR_EVENT -> paths[doc.id] = "calendar.ics"
                    else -> {
                        val name = doc.file
                        if (name == null) {
                            // Text only, like a booking's confirmation.
                            if (doc.text.isBlank()) continue
                            val path = nameFor(doc, folder(doc.kind), "txt")
                            put(path) { it.write(doc.text.toByteArray()) }
                            paths[doc.id] = path
                            files++
                            continue
                        }
                        copiedFiles[name]?.let { paths[doc.id] = it; continue }
                        val stored = File(dir, name)
                        if (!stored.exists()) {
                            missing += "${doc.day} ${Countries.name(doc.country)}: ${doc.title}, file missing"
                            continue
                        }
                        val path = nameFor(doc, folder(doc.kind), stored.extension.ifBlank { "bin" })
                        put(path) { o -> stored.inputStream().use { it.copyTo(o) } }
                        copiedFiles[name] = path
                        paths[doc.id] = path
                        files++
                    }
                }
            }
            val events = inYear.filter { it.kind == DocumentKind.CALENDAR_EVENT }.map { it.text }.distinct()
            if (events.isNotEmpty()) {
                put("calendar.ics") { o ->
                    o.write("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Sojourn//Evidence//EN\r\n".toByteArray())
                    events.forEach { o.write(it.toByteArray()) }
                    o.write("END:VCALENDAR\r\n".toByteArray())
                }
                files++
            }
            put("days.csv") { it.write(daysCsv(book, from, to, inYear, paths).toByteArray()) }
            put("index.html") { it.write(indexHtml(year, book, from, to, inYear, paths, missing).toByteArray()) }
        }
        PackResult(book.between(from, to).count { it.kind != DayKind.PLANNED }, files, missing)
    }

    /** The original photo, with its location if the app may read it, else as the gallery gives it. */
    private fun openPhoto(uri: Uri): InputStream? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { context.contentResolver.openInputStream(MediaStore.setRequireOriginal(uri)) }.getOrNull()?.let { return it }
        }
        return runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
    }

    private fun daysCsv(book: DayBook, from: LocalDate, to: LocalDate, docs: List<Document>, paths: Map<Long, String>): String = buildString {
        appendLine("date,country_code,country,counted,evidence,sources,documents")
        val byDay = docs.filter { it.until == null || it.until == it.day }.groupBy { it.day to it.country }
        for (day in book.between(from, to)) {
            if (day.kind == DayKind.PLANNED) continue
            for (country in day.countries) {
                if (country in day.transit) continue
                val files = byDay[day.date to country].orEmpty().mapNotNull { paths[it.id] }.distinct().joinToString(";")
                val sources = day.sources[country].orEmpty().joinToString(";") { it.name.lowercase() }
                append("${day.date},$country,${csv(Countries.name(country))},${if (book.countsFor(country, day)) "yes" else "no"},")
                appendLine("${day.evidenceOf(country).label.lowercase()},$sources,$files")
            }
        }
    }

    private fun indexHtml(year: Int, book: DayBook, from: LocalDate, to: LocalDate, docs: List<Document>, paths: Map<Long, String>, missing: List<String>): String {
        val flights = book.flights.associateBy { it.id }
        val summary = book.summary(from, to)
        // Period documents back the ties, not single days, so they're listed by group only.
        val byDay = docs.filter { it.until == null || it.until == it.day }.groupBy { it.day to it.country }
        val made = LocalDateTime.now().format(DateTimeFormatter.ofPattern("d MMM yyyy HH:mm"))
        return buildString {
            append("<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width\">")
            append("<title>Travel evidence $year</title><style>")
            append("body{font:14px/1.45 system-ui,sans-serif;max-width:960px;margin:24px auto;padding:0 16px;color:#1b1b1b}")
            append("table{border-collapse:collapse;width:100%;margin:12px 0 24px}th,td{border-bottom:1px solid #ddd;padding:5px 8px;text-align:left;vertical-align:top}")
            append("th{background:#f3f3f3}.muted{color:#666}.assumed{color:#a15c00}h1{margin-bottom:4px}")
            append("@media print{a{color:inherit;text-decoration:none}}</style></head><body>")
            append("<h1>Travel evidence, $year</h1>")
            append("<p class=\"muted\">${esc(from.toString())} to ${esc(to.toString())} · made with Sojourn on ${esc(made)}. ")
            append("Each day lists where it was spent, whether that country counts it, and what shows it.</p>")

            append("<h2>Days per country</h2><table><tr><th>Country</th><th>Days counted</th><th>Documented</th><th>Tracked</th><th>By hand</th><th>Assumed</th></tr>")
            for (item in summary.countries) {
                val e = book.evidence(from, to, CountrySet(setOf(item.country)))
                append("<tr><td>${esc(Countries.name(item.country))}</td><td>${item.days}</td><td>${e.documented}</td><td>${e.tracked}</td><td>${e.manual}</td><td>${e.assumed}</td></tr>")
            }
            append("</table>")
            append("<p class=\"muted\">Documented: a photo, a flight, a booking or an exported location history. Tracked: the phone's location, checked by Sojourn. ")
            append("By hand: entered in Sojourn. Assumed: nothing recorded, the same country on the days before and after.</p>")

            if (missing.isNotEmpty()) {
                append("<h2>Not included</h2><ul>")
                missing.forEach { append("<li>${esc(it)}</li>") }
                append("</ul>")
            }

            documentsHtml(docs, flights, paths)

            append("<h2>Every day</h2><table><tr><th>Date</th><th>Country</th><th>Counted</th><th>Evidence</th><th>Documents</th></tr>")
            for (day in book.between(from, to)) {
                if (day.kind == DayKind.PLANNED) continue
                for (country in day.countries) {
                    if (country in day.transit) continue
                    val evidence = day.evidenceOf(country)
                    val sources = day.sources[country].orEmpty().joinToString(", ") { it.name.lowercase() }
                    val links = byDay[day.date to country].orEmpty().mapNotNull { doc ->
                        paths[doc.id]?.let { "<a href=\"${esc(it)}\">${esc(doc.kind.label)}</a>" }
                    }.distinct().joinToString(" · ")
                    append("<tr><td>${day.date}</td><td>${esc(Countries.name(country))}</td><td>${if (book.countsFor(country, day)) "yes" else "no"}</td>")
                    append("<td${if (evidence == Evidence.ASSUMED) " class=\"assumed\"" else ""}>${esc(evidence.label)}${if (sources.isNotBlank()) " <span class=\"muted\">($sources)</span>" else ""}</td>")
                    append("<td>$links</td></tr>")
                }
            }
            append("</table></body></html>")
        }
    }

    /**
     * The documents section of the summary page: flights first, each with its number, route,
     * booking reference and the documents that back it up, then every other document by group
     * with its kind, date or period, country, strength and file. A file kept for both of a
     * flight's days is listed once.
     */
    private fun StringBuilder.documentsHtml(docs: List<Document>, flights: Map<Long, Flight>, paths: Map<Long, String>) {
        if (docs.isEmpty()) return
        append("<h2>Documents</h2>")
        val (forFlights, others) = docs.partition { it.flightId != null && it.flightId in flights }
        if (forFlights.isNotEmpty()) {
            append("<h3>Flights</h3><table><tr><th>Date</th><th>Flight</th><th>Route</th><th>Booking reference</th><th>Documents</th></tr>")
            forFlights.groupBy { it.flightId!! }.entries.sortedBy { flights.getValue(it.key).departureDate }.forEach { (id, kept) ->
                val flight = flights.getValue(id)
                val route = "${flight.origin} (${Countries.name(flight.originCountry)}) → ${flight.destination} (${Countries.name(flight.destinationCountry)})"
                val links = kept.distinctBy { paths[it.id] ?: it.id.toString() }.joinToString("<br>") { link(it, paths) + " <span class=\"muted\">${esc(it.kind.strength.label)}</span>" }
                append("<tr><td>${flight.departureDate}</td><td>${esc(Airlines.format(flight.number))}</td><td>${esc(route)}</td>")
                append("<td>${esc(flight.reference)}</td><td>$links</td></tr>")
            }
            append("</table>")
        }
        for (group in EvidenceGroup.entries) {
            val inGroup = others.filter { it.kind.group == group }.distinctBy { paths[it.id]?.let { p -> p to it.kind } ?: it.id }
            if (inGroup.isEmpty()) continue
            append("<h3>${esc(group.label)}</h3><table><tr><th>Date or period</th><th>Kind</th><th>Country</th><th>Strength</th><th>Document</th><th>Note</th></tr>")
            for (doc in inGroup.sortedWith(compareBy({ it.day }, { it.kind }))) {
                val dates = doc.until?.takeIf { it != doc.day }?.let { "${doc.day} to $it" } ?: doc.day.toString()
                append("<tr><td>$dates</td><td>${esc(doc.kind.label)}</td><td>${esc(Countries.name(doc.country))}</td><td>${esc(doc.kind.strength.label)}</td>")
                append("<td>${link(doc, paths)}</td><td>${esc(doc.note)}</td></tr>")
            }
            append("</table>")
        }
        append("<p class=\"muted\">Strong: third-party proof of where you were that day. Medium: indirect, or covering a period. ")
        append("Supporting: backs up other evidence. Ties: shows a home, work or family there rather than days.</p>")
    }

    private fun link(doc: Document, paths: Map<Long, String>): String =
        paths[doc.id]?.let { "<a href=\"${esc(it)}\">${esc(doc.title)}</a>" } ?: esc(doc.title)

    private fun esc(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun csv(value: String) = if (value.any { it == ',' || it == '"' }) "\"${value.replace("\"", "\"\"")}\"" else value

    companion object {
        /** What a kept file is, by its extension, for opening it in another app. */
        fun mimeType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "pdf" -> "application/pdf"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "heic", "heif" -> "image/heif"
            "pkpass" -> "application/vnd.apple.pkpass"
            "txt" -> "text/plain"
            "ics" -> "text/calendar"
            else -> "application/octet-stream"
        }

        /** A small picture of a kept image file; null for anything that isn't a picture. */
        fun thumbnailOf(file: File, px: Int): Bitmap? {
            if (!mimeType(file.name).startsWith("image/")) return null
            return runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= px && bounds.outHeight / (sample * 2) >= px) sample *= 2
                BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            }.getOrNull()
        }

        /** A small picture of a gallery photo, for the day sheet; null if it's gone. */
        fun thumbnail(context: Context, uri: String, px: Int): Bitmap? = runCatching {
            val parsed = Uri.parse(uri)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.loadThumbnail(parsed, Size(px, px), null)
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(parsed)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= px && bounds.outHeight / (sample * 2) >= px) sample *= 2
                context.contentResolver.openInputStream(parsed)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            }
        }.getOrNull()
    }
}
