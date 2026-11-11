package app.sojourn.data

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.net.toUri
import app.sojourn.data.db.Document
import app.sojourn.data.db.DocumentKind
import app.sojourn.data.db.Flight
import app.sojourn.data.db.alternatives
import app.sojourn.data.flights.Airlines
import app.sojourn.domain.Csv
import app.sojourn.domain.Reports
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * The backup: one zip with everything Sojourn keeps, laid out to be read without it. CSVs of
 * the days, flights and documents, the evidence files in a folder per kind, and backup.json,
 * which holds all of it plus plans, rules and settings for a restore. Restores also take the
 * plain JSON backups made before evidence was included.
 */
class BackupArchive(private val context: Context, private val repository: Repository, private val store: EvidenceStore) {

    data class Written(val days: Int, val documents: Int, val files: Int, val missing: List<String>)

    /** What a backup holds; [hasEvidence] is false for a JSON file on its own, which brings no files. */
    data class Contents(val days: Int, val documents: Int, val hasEvidence: Boolean)

    data class Restored(val tables: Repository.RestoreResult, val documents: Int)

    suspend fun write(out: OutputStream): Written = withContext(Dispatchers.IO) {
        val documents = repository.allDocuments()
        val flights = repository.flights.first().associateBy { it.id }
        val days = repository.presenceRows()
        ZipOutputStream(out).use { zip ->
            val evidence = EvidenceWriter(zip).apply { documents.forEach(::add) }
            val records = documents.map { it.toDto(evidence.restorable[it.id], flights[it.flightId]) }
            zip.put("README.txt", readme(days.size, documents.size, evidence.missing))
            zip.put("days.csv", Reports.presenceCsv(days))
            zip.put("flights.csv", flightsCsv(flights.values.sortedBy { it.departureDate }))
            zip.put("documents.csv", documentsCsv(documents, flights, evidence.readable))
            zip.put(JSON, repository.exportJson(records))
            Written(days.size, documents.size, evidence.files, evidence.missing)
        }
    }

    /** Reads the backup through without unpacking its files, to say what restoring it would bring. */
    suspend fun read(uri: Uri): Contents = withContext(Dispatchers.IO) {
        val unpacked = unpack(uri, scratch = null)
        Contents(unpacked.backup.days.size, unpacked.backup.documents.size, unpacked.hasEvidence)
    }

    /**
     * Restores [uri]. Everything is read and checked, and the new files copied in, before
     * anything already kept changes, so a bad backup leaves the phone as it was. Merging adds
     * what's missing, never a second copy of a file already kept for the same day. Replacing
     * swaps everything for the backup's, evidence too when the backup brings its files.
     */
    suspend fun restore(uri: Uri, replace: Boolean): Restored = withContext(Dispatchers.IO) {
        val scratch = File(context.cacheDir, "restore").apply { deleteRecursively(); mkdirs() }
        try {
            val unpacked = unpack(uri, scratch)
            val records = unpacked.backup.documents.map { it to it.toDocument() }
            if (!unpacked.hasEvidence) return@withContext Restored(repository.restore(unpacked.backup, replace), 0)
            val planned = plan(records, unpacked.files, merging = !replace)
            val copies = copyIn(planned, unpacked.files)
            try {
                val tables = repository.restore(unpacked.backup, replace)
                val flights = repository.flights.first()
                val documents = planned.map { it.document.copy(file = it.copyFrom?.let(copies::getValue), flightId = it.record.flight?.let { key -> flights.firstOrNull { flight -> key.names(flight) }?.id }) }
                val added = if (replace) documents.also { repository.replaceEvidence(it) }.size else documents.count { repository.addDocument(it) }
                Restored(tables, added)
            } catch (failure: Throwable) {
                copies.values.forEach { store.file(it).delete() }
                throw failure
            }
        } finally {
            scratch.deleteRecursively()
        }
    }

    /** The backup's records, and with [scratch] its evidence files unpacked there by number, so no name in the zip picks where a file goes. */
    private class Unpacked(val backup: BackupFile, val zipped: Boolean, val names: Set<String>, val files: Map<String, File>) {
        val hasEvidence: Boolean get() = zipped && backup.hasEvidence
    }

    private fun unpack(uri: Uri, scratch: File?): Unpacked = open(uri).use { input ->
        if (!input.isZip()) return@use Unpacked(BackupCodec.decode(input.readText(MAX_JSON_BYTES)), zipped = false, emptySet(), emptyMap())
        val zip = ZipInputStream(input)
        var backup: BackupFile? = null
        val names = HashSet<String>()
        val files = HashMap<String, File>()
        var unpacked = 0L
        generateSequence { zip.nextEntry }.filterNot { it.isDirectory }.forEachIndexed { index, entry ->
            require(index < MAX_ENTRIES) { "This zip holds too many files to be a Sojourn backup" }
            when {
                entry.name == JSON -> {
                    require(backup == null) { "This zip holds two backups" }
                    backup = BackupCodec.decode(zip.readText(MAX_JSON_BYTES))
                }
                entry.name.startsWith(EVIDENCE) && scratch != null -> {
                    val file = File(scratch, files.size.toString())
                    unpacked += file.outputStream().use { zip.copyTo(it, MAX_UNPACKED_BYTES - unpacked) }
                    files[entry.name] = file
                    names += entry.name
                }
                entry.name.startsWith(EVIDENCE) -> names += entry.name
            }
        }
        val found = backup ?: error("This zip isn't a Sojourn backup")
        val missing = found.documents.mapNotNull { it.file }.distinct().count { it !in names }
        check(missing == 0) { "This backup is missing $missing of its evidence files" }
        Unpacked(found, zipped = true, names, files)
    }

    /** One backed-up document as it'll be kept, and the file in the backup it needs copied in, if any. */
    private class Planned(val record: DocumentDto, val document: Document, val copyFrom: String?)

    /** Works out what to keep before anything is copied: what's already kept is left out when merging. */
    private suspend fun plan(records: List<Pair<DocumentDto, Document>>, files: Map<String, File>, merging: Boolean): List<Planned> = records.mapNotNull { (record, document) ->
        val path = record.file
        when {
            document.kind == DocumentKind.PHOTO -> {
                val galleryPhoto = record.photo?.takeIf { isGalleryPhoto(it) && opens(it) }
                when {
                    galleryPhoto != null -> Planned(record, document.copy(uri = galleryPhoto, candidates = record.photoChoices.filter(::isGalleryPhoto).joinToString("\n")), null)
                    merging && repository.documents(document.day, document.country, DocumentKind.PHOTO).isNotEmpty() -> null
                    path != null -> Planned(record, document, path)
                    else -> null
                }
            }
            path == null -> Planned(record, document, null)
            merging && isKeptAlready(files.getValue(path), document) -> null
            else -> Planned(record, document, path)
        }
    }

    /** Copies each needed file in once; on any failure removes what was copied and fails. */
    private suspend fun copyIn(planned: List<Planned>, files: Map<String, File>): Map<String, String> {
        val copies = HashMap<String, String>()
        try {
            for (path in planned.mapNotNull { it.copyFrom }.distinct()) {
                copies[path] = store.keep(Uri.fromFile(files.getValue(path)), path.substringAfterLast('/')) ?: error("Couldn't copy $path from the backup")
            }
        } catch (failure: Throwable) {
            copies.values.forEach { store.file(it).delete() }
            throw failure
        }
        return copies
    }

    private suspend fun isKeptAlready(source: File, document: Document): Boolean {
        val hash = store.hash(Uri.fromFile(source)) ?: return false
        return repository.documents(document.day, document.country, document.kind).any { existing -> existing.file?.let { store.hashOf(it) } == hash }
    }

    /** Only photos in the gallery are linked: a backup could name any other address, the app's own files included. */
    private fun isGalleryPhoto(uri: String): Boolean = uri.toUri().let { it.scheme == "content" && it.authority == "media" }

    private fun opens(uri: String): Boolean = runCatching { context.contentResolver.openInputStream(uri.toUri())!!.close() }.isSuccess

    private fun open(uri: Uri): BufferedInputStream =
        (context.contentResolver.openInputStream(uri) ?: error("Couldn't open the backup")).buffered()

    /** Copies each document's file into the zip once, named by kind, date and country. */
    private inner class EvidenceWriter(private val zip: ZipOutputStream) {
        /** The file each document restores from. */
        val restorable = HashMap<Long, String>()

        /** Where each document can be read, its text included. */
        val readable = HashMap<Long, String>()
        val missing = mutableListOf<String>()
        var files = 0
            private set
        private val written = HashMap<String, String>()
        private val taken = HashMap<String, Int>()

        fun add(doc: Document) {
            when {
                doc.kind == DocumentKind.PHOTO -> addPhoto(doc)
                doc.file != null -> addFile(doc, doc.file)
                doc.text.isNotBlank() -> addText(doc)
            }
        }

        private fun addPhoto(doc: Document) {
            val extension = doc.uri?.let { context.contentResolver.getType(it.toUri()) }
                ?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
                ?: doc.file?.substringAfterLast('.', "")?.ifBlank { null }
                ?: "jpg"
            val input = photoWithoutLocation(doc)
            if (input == null) {
                missing += "${doc.day} ${Countries.name(doc.country)}: photo no longer in the gallery"
                return
            }
            val path = pathFor(doc, extension)
            input.use { zip.put(path, it) }
            restorable[doc.id] = path
            readable[doc.id] = path
            files++
        }

        /** The gallery gives the photo without where it was taken unless asked for the original. */
        private fun photoWithoutLocation(doc: Document): InputStream? =
            doc.uri?.let { runCatching { context.contentResolver.openInputStream(it.toUri()) }.getOrNull() }
                ?: doc.file?.let { name -> store.file(name).takeIf { it.exists() }?.inputStream() }

        private fun addFile(doc: Document, name: String) {
            val path = written[name] ?: run {
                val stored = store.file(name)
                if (!stored.exists()) {
                    missing += "${doc.day} ${Countries.name(doc.country)}: ${doc.title}, file missing"
                    return
                }
                pathFor(doc, stored.extension.ifBlank { "bin" }).also { path ->
                    stored.inputStream().use { zip.put(path, it) }
                    written[name] = path
                    files++
                }
            }
            restorable[doc.id] = path
            readable[doc.id] = path
        }

        /** A readable copy only: the text itself goes back from backup.json. */
        private fun addText(doc: Document) {
            val path = if (doc.kind == DocumentKind.CALENDAR_EVENT) pathFor(doc, "ics") else pathFor(doc, "txt")
            val text = if (doc.kind == DocumentKind.CALENDAR_EVENT) "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Sojourn//Backup//EN\r\n${doc.text}END:VCALENDAR\r\n" else doc.text
            zip.put(path, text)
            readable[doc.id] = path
        }

        private fun pathFor(doc: Document, extension: String): String {
            val dates = doc.until?.takeIf { it != doc.day }?.let { "${doc.day}_to_$it" } ?: "${doc.day}"
            val base = "$EVIDENCE${store.folder(doc.kind)}/${dates}_${doc.country}"
            val n = (taken[base] ?: 0) + 1
            taken[base] = n
            return if (n == 1) "$base.$extension" else "${base}_$n.$extension"
        }
    }

    private fun Document.toDto(file: String?, flight: Flight?) = DocumentDto(
        day = day.toString(),
        until = until?.toString(),
        country = country,
        kind = kind.name,
        title = title,
        note = note,
        text = text,
        file = file,
        photo = uri,
        photoChoices = alternatives,
        flight = flight?.let { FlightKeyDto(it.number, it.origin, it.destination, it.departureDate.toString()) },
        addedAt = addedAt,
    )

    /** Fails on anything a backup made by Sojourn couldn't hold, before the restore changes anything. */
    private fun DocumentDto.toDocument(): Document {
        require(COUNTRY.matches(country)) { "This backup names a country that doesn't exist: $country" }
        val day = runCatching { LocalDate.parse(day) }.getOrElse { throw IllegalArgumentException("This backup has a date that can't be read: $day") }
        val until = until?.let { runCatching { LocalDate.parse(it) }.getOrElse { throw IllegalArgumentException("This backup has a date that can't be read: $it") } }
        return Document(
            day = day,
            until = until,
            country = country,
            kind = DocumentKind.entries.firstOrNull { it.name == kind } ?: DocumentKind.OTHER,
            title = title,
            note = note,
            text = text,
            addedAt = addedAt ?: System.currentTimeMillis(),
        )
    }

    private fun FlightKeyDto.names(flight: Flight): Boolean =
        flight.departureDate.toString() == departureDate && flight.origin.equals(origin, ignoreCase = true) &&
            flight.destination.equals(destination, ignoreCase = true) && Airlines.format(flight.number) == Airlines.format(number)

    private fun flightsCsv(flights: List<Flight>): String = buildString {
        appendLine("departure_date,arrival_date,number,from,from_country,to,to_country,departure_time,arrival_time,booking_reference,passenger,seat,note")
        for (f in flights) {
            val fields = listOf(
                f.departureDate.toString(), f.arrivalDate.toString(), Airlines.format(f.number), f.origin, Countries.name(f.originCountry),
                f.destination, Countries.name(f.destinationCountry), clock(f.departureMinute), clock(f.arrivalMinute), f.reference, f.passenger, f.seat, f.note,
            )
            appendLine(fields.joinToString(",", transform = Csv::field))
        }
    }

    private fun documentsCsv(documents: List<Document>, flights: Map<Long, Flight>, paths: Map<Long, String>): String = buildString {
        appendLine("date,until,country_code,country,kind,title,note,flight,file")
        for (doc in documents) {
            val flight = flights[doc.flightId]?.let { "${Airlines.format(it.number)} ${it.origin}-${it.destination} ${it.departureDate}".trim() }.orEmpty()
            val fields = listOf(
                doc.day.toString(), doc.until?.toString().orEmpty(), doc.country, Countries.name(doc.country), doc.kind.name.lowercase(),
                doc.title, doc.note, flight, paths[doc.id].orEmpty(),
            )
            appendLine(fields.joinToString(",", transform = Csv::field))
        }
    }

    private fun readme(days: Int, documents: Int, missing: List<String>): String = buildString {
        val made = LocalDateTime.now().format(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm"))
        appendLine("Sojourn backup, made $made")
        appendLine()
        appendLine("Everything Sojourn keeps: $days recorded days and $documents pieces of evidence, with their files.")
        appendLine("To restore it, open Sojourn > Settings > Your data > Restore backup and choose this zip.")
        appendLine()
        appendLine("What's inside, readable without Sojourn:")
        appendLine("  days.csv        every recorded day: date, country, how it was recorded")
        appendLine("  flights.csv     every flight: dates, number, route, times, booking reference, passenger, seat")
        appendLine("  documents.csv   every piece of evidence: date or period, country, kind, title, note, flight, file")
        appendLine("  evidence/       the evidence files, a folder per kind, named by date and country")
        appendLine("  backup.json     all of the above plus plans, rules and settings, for Sojourn to restore")
        if (missing.isNotEmpty()) {
            appendLine()
            appendLine("Not included:")
            missing.forEach { appendLine("  $it") }
        }
    }

    private fun clock(minute: Int?): String = minute?.let { "%02d:%02d".format(it / 60, it % 60) }.orEmpty()

    private fun ZipOutputStream.put(path: String, text: String) = put(path, text.byteInputStream())

    private fun ZipOutputStream.put(path: String, input: InputStream) {
        putNextEntry(ZipEntry(path))
        input.copyTo(this)
        closeEntry()
    }

    private fun BufferedInputStream.isZip(): Boolean {
        mark(2)
        val zipped = read() == 'P'.code && read() == 'K'.code
        reset()
        return zipped
    }

    private fun InputStream.readText(limit: Long): String {
        val bytes = java.io.ByteArrayOutputStream()
        copyTo(bytes, limit)
        return bytes.toByteArray().decodeToString()
    }

    /** Copies at most [limit] bytes and fails past it, so an oversized zip can't fill the phone. */
    private fun InputStream.copyTo(out: OutputStream, limit: Long): Long {
        val buffer = ByteArray(64 * 1024)
        var copied = 0L
        while (true) {
            val read = read(buffer)
            if (read < 0) return copied
            copied += read
            check(copied <= limit) { "This backup is larger than any Sojourn makes" }
            out.write(buffer, 0, read)
        }
    }

    private companion object {
        const val JSON = "backup.json"
        const val EVIDENCE = "evidence/"
        const val MAX_ENTRIES = 100_000
        const val MAX_JSON_BYTES = 64L shl 20
        const val MAX_UNPACKED_BYTES = 16L shl 30
        val COUNTRY = Regex("[A-Z]{2}")
    }
}
