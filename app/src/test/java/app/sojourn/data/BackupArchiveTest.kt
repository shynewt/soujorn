package app.sojourn.data

import android.app.Application
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.sojourn.data.db.Document
import app.sojourn.data.db.DocumentKind
import app.sojourn.data.db.Flight
import app.sojourn.data.db.SojournDatabase
import java.io.File
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The backup zip, written from one phone's data and restored into another's. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class BackupArchiveTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val day = LocalDate.of(2026, 1, 12)
    private val flight = Flight(number = "W64666", origin = "BCN", destination = "LCA", originCountry = "ES", destinationCountry = "CY", departureDate = day, arrivalDate = day.plusDays(1))

    private inner class Phone(name: String) {
        val db: SojournDatabase = Room.inMemoryDatabaseBuilder(context, SojournDatabase::class.java).allowMainThreadQueries().build()
        val evidence: File = folder.newFolder(name)
        val repository = Repository(db, SettingsStore(context), scope, evidenceDir = evidence, onDaysChanged = {})
        val archive = BackupArchive(context, repository, EvidenceStore(context, evidence))

        fun keep(name: String, content: String): String = name.also { File(evidence, it).writeText(content) }
    }

    private val phones = mutableListOf<Phone>()

    private fun phone(name: String) = Phone(name).also { phones += it }

    @After
    fun close() {
        scope.cancel()
        phones.forEach { it.db.close() }
    }

    /** A phone with a flight and its boarding pass on both days, a lease, a booking's text and a kept photo. */
    private fun filledPhone(): Phone = phone("old").apply {
        runBlocking {
            repository.saveFlight(flight)
            val saved = db.flights().all().single()
            val pass = keep("a1-pass.pdf", "PASS")
            repository.addDocument(Document(day = day, country = "ES", kind = DocumentKind.BOARDING_PASS, title = "Boarding pass: W6 4666 BCN→LCA", file = pass, flightId = saved.id))
            repository.addDocument(Document(day = day.plusDays(1), country = "CY", kind = DocumentKind.BOARDING_PASS, title = "Boarding pass: W6 4666 BCN→LCA", file = pass, flightId = saved.id))
            repository.addDocument(Document(day = LocalDate.of(2026, 1, 1), until = LocalDate.of(2026, 12, 31), country = "CY", kind = DocumentKind.LEASE, title = "Lease, Limassol", file = keep("b2-lease.pdf", "LEASE"), note = "Two years"))
            repository.addDocument(Document(day = day, country = "ES", kind = DocumentKind.BOOKING, title = "Booking: W6 4666", text = "Your booking K7QX2P"))
            repository.addDocument(Document(day = day.minusDays(3), country = "ES", kind = DocumentKind.PHOTO, title = "Photo", file = keep("c3-photo.jpg", "PHOTO")))
        }
    }

    private fun backupOf(phone: Phone): File = folder.newFile().also { file -> runBlocking { file.outputStream().use { phone.archive.write(it) } } }

    @Test
    fun theZipCanBeReadWithoutSojourn() {
        val zip = backupOf(filledPhone())
        val names = ZipFile(zip).use { z -> z.entries().toList().map { it.name } }
        assertTrue(names.containsAll(listOf("README.txt", "backup.json", "days.csv", "flights.csv", "documents.csv")))
        // The pass backs two days but is one file.
        assertEquals(listOf("evidence/boarding-passes/2026-01-12_ES.pdf"), names.filter { it.startsWith("evidence/boarding-passes/") })
        assertTrue("evidence/home/2026-01-01_to_2026-12-31_CY.pdf" in names)
        assertTrue("evidence/photos/2026-01-09_ES.jpg" in names)
        val documents = ZipFile(zip).use { z -> z.getInputStream(z.getEntry("documents.csv")).reader().readText() }
        assertTrue(documents.lines().any { "Lease, Limassol" in it && "Two years" in it && "evidence/home/2026-01-01_to_2026-12-31_CY.pdf" in it })
    }

    @Test
    fun restoringOnAnotherPhoneBringsBackEveryDocumentAndFile() = runBlocking {
        val zip = backupOf(filledPhone())
        val fresh = phone("new")
        val restored = fresh.archive.restore(Uri.fromFile(zip), replace = true)
        assertEquals(5, restored.documents)

        val documents = fresh.db.documents().all()
        val flightId = fresh.db.flights().all().single().id
        val passes = documents.filter { it.kind == DocumentKind.BOARDING_PASS }
        assertEquals(listOf(flightId, flightId), passes.map { it.flightId })
        assertEquals(1, passes.map { it.file }.distinct().size)
        assertEquals("PASS", File(fresh.evidence, passes.first().file!!).readText())

        val lease = documents.single { it.kind == DocumentKind.LEASE }
        assertEquals(LocalDate.of(2026, 12, 31), lease.until)
        assertEquals("Two years", lease.note)
        assertEquals("LEASE", File(fresh.evidence, lease.file!!).readText())
        assertEquals("Your booking K7QX2P", documents.single { it.kind == DocumentKind.BOOKING }.text)
        assertEquals("PHOTO", File(fresh.evidence, documents.single { it.kind == DocumentKind.PHOTO }.file!!).readText())
    }

    @Test
    fun mergingTheSameBackupTwiceAddsNothingTheSecondTime() = runBlocking {
        val zip = backupOf(filledPhone())
        val other = phone("other")
        other.archive.restore(Uri.fromFile(zip), replace = false)
        val again = other.archive.restore(Uri.fromFile(zip), replace = false)
        assertEquals(0, again.documents)
        assertEquals(5, other.db.documents().all().size)
        assertEquals(3, other.evidence.listFiles()!!.size)
    }

    @Test
    fun replacingWithAnOlderBackupWithoutEvidenceKeepsTheEvidence() = runBlocking {
        val phone = filledPhone()
        val old = BackupCodec.encode(emptyList(), emptyList(), emptyList(), Settings()).replace("\"version\": 2", "\"version\": 1")
        val file = folder.newFile("old.json").apply { writeText(old) }
        phone.archive.restore(Uri.fromFile(file), replace = true)
        assertEquals(5, phone.db.documents().all().size)
        assertEquals(3, phone.evidence.listFiles()!!.size)
    }

    private fun zipOf(vararg entries: Pair<String, String>): File = folder.newFile().also { file ->
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (name, text) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
    }

    private fun backupJson(vararg documents: DocumentDto) = BackupCodec.encode(emptyList(), emptyList(), emptyList(), Settings(), documents = documents.toList())

    @Test
    fun replacingWithTheBackupJsonAloneKeepsTheEvidence() = runBlocking {
        val phone = filledPhone()
        val json = ZipFile(backupOf(phone)).use { z -> z.getInputStream(z.getEntry("backup.json")).reader().readText() }
        val bare = folder.newFile("backup.json").apply { writeText(json) }
        assertEquals(false, phone.archive.read(Uri.fromFile(bare)).hasEvidence)
        phone.archive.restore(Uri.fromFile(bare), replace = true)
        assertEquals(5, phone.db.documents().all().size)
        assertEquals(3, phone.evidence.listFiles()!!.size)
    }

    @Test
    fun aPhotoAddressOutsideTheGalleryIsNeverLinked() = runBlocking {
        val photo = DocumentDto(day = "2026-01-12", country = "ES", kind = "PHOTO", title = "Photo", photo = "content://app.sojourn.files/evidence/a1-pass.pdf")
        val fresh = phone("fresh")
        fresh.archive.restore(Uri.fromFile(zipOf("backup.json" to backupJson(photo))), replace = false)
        assertNull(fresh.db.documents().all().firstOrNull { it.uri != null })
    }

    @Test
    fun aBackupWithAnUnreadableDocumentChangesNothing() {
        val phone = filledPhone()
        val bad = DocumentDto(day = "2026-01-12", country = "../../x", kind = "OTHER", title = "Bad")
        val zip = zipOf("backup.json" to backupJson(bad))
        assertThrows(IllegalArgumentException::class.java) { runBlocking { phone.archive.restore(Uri.fromFile(zip), replace = true) } }
        runBlocking { assertEquals(5, phone.db.documents().all().size) }
        assertEquals(3, phone.evidence.listFiles()!!.size)
    }

    @Test
    fun aBackupMissingItsFilesChangesNothing() {
        val phone = filledPhone()
        val lease = DocumentDto(day = "2026-01-01", country = "CY", kind = "LEASE", title = "Lease", file = "evidence/home/2026-01-01_CY.pdf")
        val zip = zipOf("backup.json" to backupJson(lease))
        assertThrows(IllegalStateException::class.java) { runBlocking { phone.archive.restore(Uri.fromFile(zip), replace = true) } }
        runBlocking { assertEquals(5, phone.db.documents().all().size) }
        assertEquals(3, phone.evidence.listFiles()!!.size)
    }
}
