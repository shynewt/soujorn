package app.sojourn.data.travel

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.CalendarContract
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import app.sojourn.data.flights.AirportDirectory
import app.sojourn.data.flights.FlightText
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Trips found in some sources, with how many things were looked at, for the review screen.
 * [unread] are events that look like flights but whose airports couldn't be made out, so the
 * user sees what was left out instead of flights silently going missing.
 */
data class TripScan(val trips: List<FoundTrip>, val looked: Int, val what: String, val unread: List<UnreadEvent> = emptyList())

data class UnreadEvent(val title: String, val date: LocalDate)

/**
 * Finds trips in the phone's calendars and in files: boarding pass screenshots and PDFs, Apple
 * Wallet passes, .ics files and packs of them. Everything is read on the
 * phone; nothing is sent anywhere, as the app can't reach the internet at all.
 */
class TravelImporter(private val context: Context, private val airports: () -> AirportDirectory) {

    /** Flight events in every calendar on the phone between [from] and [to]. */
    suspend fun calendar(from: LocalDate, to: LocalDate, progress: (Float) -> Unit = {}): TripScan = withContext(Dispatchers.IO) {
        val zone = ZoneId.systemDefault()
        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, from.atStartOfDay(zone).toInstant().toEpochMilli())
        ContentUris.appendId(builder, to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
        val columns = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.DESCRIPTION,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.EVENT_TIMEZONE,
            CalendarContract.Instances.EVENT_END_TIMEZONE,
            CalendarContract.Instances.ALL_DAY,
        )
        val events = mutableListOf<CalendarEvent>()
        context.contentResolver.query(builder.build(), columns, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { cursor ->
            while (cursor.moveToNext()) {
                events += CalendarEvent(
                    id = cursor.getLong(0).toString(),
                    title = cursor.getString(1).orEmpty(),
                    location = cursor.getString(2).orEmpty(),
                    description = cursor.getString(3).orEmpty(),
                    start = Instant.ofEpochMilli(cursor.getLong(4)),
                    end = Instant.ofEpochMilli(cursor.getLong(5)),
                    startZone = cursor.getString(6)?.let { runCatching { ZoneId.of(it) }.getOrNull() },
                    endZone = cursor.getString(7)?.let { runCatching { ZoneId.of(it) }.getOrNull() },
                    allDay = cursor.getInt(8) == 1,
                )
            }
        }
        val directory = airports()
        val unread = mutableListOf<UnreadEvent>()
        val trips = events.mapIndexedNotNull { i, event ->
            if (i % 50 == 0) progress(i / events.size.coerceAtLeast(1).toFloat())
            val trip = CalendarTrips.parse(event, directory)?.let {
                it.copy(source = "Calendar: ${it.source}", raw = Ics.write(event), rawKind = RawKind.CALENDAR_EVENT)
            }
            if (trip == null && CalendarTrips.isFlight(event) && !event.allDay) {
                unread += UnreadEvent(event.title, event.start.atZone(event.startZone ?: zone).toLocalDate())
            }
            trip
        }
        TripScan(trips, events.size, "calendar events", unread.distinct())
    }

    /** Trips in shared or chosen files, whatever they are. */
    suspend fun files(uris: List<Uri>, progress: (Float) -> Unit = {}): TripScan = withContext(Dispatchers.IO) {
        val trips = mutableListOf<FoundTrip>()
        var looked = 0
        // Files taken out of zips for this scan, kept until the next one to copy as evidence.
        unpacked.deleteRecursively()
        uris.forEachIndexed { index, uri ->
            progress(index / uris.size.toFloat())
            val name = displayName(uri)
            val type = context.contentResolver.getType(uri).orEmpty()
            val found = runCatching { read(uri, name, type) { looked++ } }.getOrDefault(emptyList())
            // Boarding passes keep their file; calendars and emails keep their text.
            trips += found.map { trip -> if (trip.raw == null && trip.file == null) trip.copy(file = uri.toString(), fileName = name, rawKind = RawKind.BOARDING_PASS) else trip }
        }
        TripScan(trips, looked, if (uris.size == 1) "file" else "files")
    }

    /** Text shared from another app: a booking email, a confirmation page, a boarding pass's data. */
    fun text(text: String, html: String? = null, source: String = "Shared text"): List<FoundTrip> =
        fromMessage(MailMessage(source, "", html.orEmpty(), text), source)

    /** Copies a zip entry, up to [MAX_ENTRY_BYTES]; false when it's bigger than any pass. */
    private fun copyLimited(input: InputStream, file: File): Boolean = file.outputStream().use { out ->
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        var n = input.read(buffer)
        while (n >= 0 && total + n <= MAX_ENTRY_BYTES) {
            out.write(buffer, 0, n)
            total += n
            n = input.read(buffer)
        }
        n < 0
    }

    private fun read(uri: Uri, name: String, type: String, depth: Int = 0, counted: () -> Unit): List<FoundTrip> {
        fun open(): InputStream = context.contentResolver.openInputStream(uri)!!
        val head = BufferedInputStream(open()).use { input -> ByteArray(512).let { buf -> buf.copyOf(input.read(buf).coerceAtLeast(0)) } }
        val lower = name.lowercase()
        return when {
            type.startsWith("image/") || head.startsWith(0xFF, 0xD8) || head.startsWith(0x89, 'P'.code) || head.startsWith('R'.code, 'I'.code) -> {
                counted()
                val bitmap = Barcodes.image(::open) ?: return emptyList()
                val near = near(uri)
                Barcodes.read(bitmap).flatMap { Bcbp.parse(it, near) }
            }
            type == "application/pdf" || head.startsWith('%'.code, 'P'.code, 'D'.code, 'F'.code) -> {
                counted()
                pdf(::open, near(uri))
            }
            lower.endsWith(".pkpass") || type.contains("pkpass") || head.startsWith('P'.code, 'K'.code) -> zip(::open, uri, counted, depth)
            else -> {
                val text = String(head)
                when {
                    text.contains("BEGIN:VCALENDAR") -> {
                        val events = Ics.parse(open().use { it.readBytes().decodeToString() })
                        events.forEach { _ -> counted() }
                        val directory = airports()
                        events.mapNotNull { e -> CalendarTrips.parse(e, directory)?.copy(source = "Calendar file: ${e.title}", raw = Ics.write(e), rawKind = RawKind.CALENDAR_EVENT) }
                    }
                    else -> {
                        counted()
                        val content = open().use { it.readBytes().decodeToString() }
                        val html = if (content.contains("<html", true) || content.contains("<script", true)) content else ""
                        fromMessage(MailMessage(name, "", html, if (html.isEmpty()) content else ""), name)
                    }
                }
            }
        }
    }

    /**
     * Flights in a boarding pass PDF: its barcodes, or failing those, the flight printed on it.
     * It's read from a copy, as the PDF reader needs a file it can seek in and some apps share
     * attachments as a stream.
     */
    private fun pdf(open: () -> InputStream, near: LocalDate): List<FoundTrip> {
        val copy = File.createTempFile("pass", ".pdf", context.cacheDir)
        try {
            open().use { input -> copy.outputStream().use { input.copyTo(it) } }
            // The printed text, where Android can read it, has the full dates the barcode lacks.
            val texts = Barcodes.pdfText(copy)
            val printed = texts.flatMap(FlightText::dates)
            val directory = airports()
            // Page by page, one pass per passenger: every barcode on it, else what it prints.
            var index = 0
            val pages = runCatching {
                Barcodes.pdfPages(copy) { page ->
                    val text = texts.getOrNull(index++).orEmpty()
                    val dates = FlightText.dates(text) + printed
                    Barcodes.read(page).flatMap { Bcbp.parse(it, near, dates) }
                        .ifEmpty { if (text.isBlank()) emptyList() else PrintedPass.flights(text, directory, near) }
                }.flatten()
            }.getOrDefault(emptyList())
            // Pages that couldn't be drawn still have their text.
            return pages.ifEmpty { texts.flatMap { PrintedPass.flights(it, directory, near) } }.distinctBy { it.key }
        } finally {
            copy.delete()
        }
    }

    /**
     * Trips in one email: its schema.org reservations when it has them (with each passenger's
     * name), else a boarding pass string or a flight written out in its text.
     */
    private fun fromMessage(message: MailMessage, source: String): List<FoundTrip> {
        // The booking's text is kept as evidence, cut to a sensible length.
        val text = message.text.ifBlank { Mail.stripHtml(message.html) }
        val raw = "${message.subject}\n\n${text.take(8_000)}"
        return tripsInMessage(message, source).map { it.copy(raw = raw, rawKind = RawKind.BOOKING) }
    }

    private fun tripsInMessage(message: MailMessage, source: String): List<FoundTrip> {
        val label = source.ifBlank { "Email" }
        val marked = Reservations.fromHtml(message.html, label)
        if (marked.isNotEmpty()) return marked
        val text = message.text.ifBlank { Mail.stripHtml(message.html) }
        Bcbp.parse(text).takeIf { it.isNotEmpty() }?.let { return it.map { t -> t.copy(source = label) } }
        val guess = FlightText.parse(text, airports())
        val number = guess.number
        val from = guess.origin
        val to = guess.destination
        val date = guess.date
        if (number == null || from == null || to == null || date == null) return emptyList()
        return listOf(FoundTrip(TripKind.FLIGHT, date, number = number, from = from, to = to, source = label))
    }

    private val unpacked get() = File(context.cacheDir, "unpacked")

    /**
     * A Wallet pass, or a pack of boarding passes:
     * PDFs, screenshots and Wallet passes in one zip (or a .pkpasses bundle). Each file inside
     * is read as if chosen alone, and keeps its own copy for the evidence pack.
     */
    private fun zip(open: () -> InputStream, uri: Uri, counted: () -> Unit, depth: Int = 0): List<FoundTrip> {
        val trips = mutableListOf<FoundTrip>()
        var entries = 0
        java.util.zip.ZipInputStream(open()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name.substringAfterLast('/')
                when {
                    entry.isDirectory || name.startsWith(".") || entry.name.startsWith("__MACOSX") -> Unit
                    name == "pass.json" -> {
                        counted()
                        Pkpass.messages(zip.readBytes().decodeToString()).flatMap { Bcbp.parse(it, near(uri)) }.forEach { trips += it.copy(source = "Wallet pass") }
                    }
                    depth < 2 && entries++ < MAX_ENTRIES && name.substringAfterLast('.', "").lowercase() in PACKED -> {
                        val file = File(unpacked, "${System.nanoTime()}-$name").apply { parentFile?.mkdirs() }
                        if (!copyLimited(zip, file)) {
                            file.delete()
                            continue
                        }
                        // Its date in the zip tells a pass's year, as a file's date does.
                        if (entry.time > 0) file.setLastModified(entry.time)
                        val inner = Uri.fromFile(file)
                        val found = runCatching { read(inner, name, "", depth + 1, counted) }.getOrDefault(emptyList())
                        trips += found.map { trip -> if (trip.raw == null && trip.file == null) trip.copy(file = inner.toString(), fileName = name, rawKind = RawKind.BOARDING_PASS) else trip }
                    }
                }
            }
        }
        return trips
    }

    /**
     * Whether a file holds trips (passes, calendars, emails, pictures of boarding passes)
     * rather than location history, which the location importer reads.
     */
    fun isTravel(uri: Uri): Boolean = runCatching {
        val name = displayName(uri).lowercase()
        val type = context.contentResolver.getType(uri).orEmpty()
        if (type.startsWith("image/") || type == "application/pdf" || type.contains("pkpass") || type == "text/calendar") return true
        if (listOf(".pkpass", ".ics", ".pdf", ".jpg", ".jpeg", ".png", ".webp", ".html", ".htm", ".txt").any { name.endsWith(it) }) return true
        if (listOf(".gpx", ".tcx", ".kml", ".kmz", ".fit", ".geojson", ".json").any { name.endsWith(it) }) return false
        val head = context.contentResolver.openInputStream(uri)!!.use { input -> ByteArray(512).let { buf -> buf.copyOf(input.read(buf).coerceAtLeast(0)) } }
        val text = String(head)
        when {
            head.startsWith(0xFF, 0xD8) || head.startsWith(0x89, 'P'.code) || head.startsWith('%'.code, 'P'.code, 'D'.code, 'F'.code) -> true
            text.contains("BEGIN:VCALENDAR") -> true
            head.startsWith('P'.code, 'K'.code) -> zipEntries(uri).any { entry ->
                entry.endsWith("pass.json") || entry.substringAfterLast('.', "").lowercase() in PACKED
            }
            else -> false
        }
    }.getOrDefault(false)

    /** The files in a chosen folder and the folders inside it, for a whole folder of passes. */
    fun folder(tree: Uri): List<Uri> {
        val files = mutableListOf<Uri>()
        fun walk(parent: String, depth: Int) {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
            val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_MIME_TYPE)
            context.contentResolver.query(children, columns, null, null, null)?.use { c ->
                while (c.moveToNext() && files.size < MAX_ENTRIES) {
                    val id = c.getString(0)
                    if (c.getString(1) == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (depth < 3) walk(id, depth + 1)
                    } else {
                        files += DocumentsContract.buildDocumentUriUsingTree(tree, id)
                    }
                }
            }
        }
        walk(DocumentsContract.getTreeDocumentId(tree), 0)
        return files
    }

    private fun zipEntries(uri: Uri): List<String> = java.util.zip.ZipInputStream(context.contentResolver.openInputStream(uri)!!).use { zip ->
        generateSequence { zip.nextEntry?.name }.toList()
    }

    /** The date a file was made, so a boarding pass's day of the year lands in the right year. */
    private fun near(uri: Uri): LocalDate = if (uri.scheme == "file") {
        uri.path?.let(::File)?.lastModified()?.takeIf { it > 0 }?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() } ?: LocalDate.now()
    } else runCatching {
        context.contentResolver.query(uri, arrayOf("last_modified", "date_modified"), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val millis = (0 until c.columnCount).firstNotNullOfOrNull { i -> c.getLong(i).takeIf { it > 0 } }
                millis?.let { m -> Instant.ofEpochMilli(if (m < 10_000_000_000L) m * 1000 else m).atZone(ZoneId.systemDefault()).toLocalDate() }
            } else {
                null
            }
        }
    }.getOrNull() ?: LocalDate.now()

    fun displayName(uri: Uri): String = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment.orEmpty()

    private companion object {
        /** What a pack of passes holds: boarding passes, screenshots, Wallet passes, calendar files. */
        val PACKED = setOf("pdf", "png", "jpg", "jpeg", "webp", "pkpass", "ics")
        const val MAX_ENTRIES = 300
        const val MAX_ENTRY_BYTES = 25L * 1024 * 1024
    }

    private fun ByteArray.startsWith(vararg bytes: Int) = size >= bytes.size && bytes.indices.all { (this[it].toInt() and 0xFF) == bytes[it] }
}
