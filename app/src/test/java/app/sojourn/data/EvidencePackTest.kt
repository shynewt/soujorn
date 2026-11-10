package app.sojourn.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.sojourn.data.db.Document
import app.sojourn.data.db.DocumentKind
import app.sojourn.data.db.Flight
import app.sojourn.data.db.Presence
import app.sojourn.data.db.Source
import app.sojourn.domain.CountingOptions
import app.sojourn.domain.DayBook
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.util.zip.ZipInputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The evidence pack holds each kept file once, in its kind's folder, listed by group on the summary page. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class EvidencePackTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun packListsPeriodAndFlightDocumentsAndCopiesSharedFilesOnce() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("evidence").toFile()
        File(dir, "lease.pdf").writeText("lease")
        File(dir, "pass.png").writeText("pass")
        val day = LocalDate.of(2025, 3, 4)
        val presence = (0L..9L).map { Presence(day.plusDays(it), if (it < 5) "ES" else "IT", Source.LOCATION, 0, 0) }
        val flight = Flight(7, "FR8337", "BCN", "BGY", "ES", "IT", day.plusDays(5), day.plusDays(5), reference = "ABC123")
        val book = DayBook.build(presence, emptyList(), CountingOptions(), LocalDate.of(2026, 1, 10), listOf(flight))
        val documents = listOf(
            Document(1, LocalDate.of(2025, 1, 1), "ES", DocumentKind.LEASE, "Lease, Madrid", file = "lease.pdf", until = LocalDate.of(2025, 12, 31), note = "Signed in 2024"),
            // One boarding pass, kept for both ends of its flight.
            Document(2, day.plusDays(5), "ES", DocumentKind.BOARDING_PASS, "Boarding pass: FR 8337 BCN→BGY", file = "pass.png", flightId = 7),
            Document(3, day.plusDays(5), "IT", DocumentKind.BOARDING_PASS, "Boarding pass: FR 8337 BCN→BGY", file = "pass.png", flightId = 7),
            Document(4, LocalDate.of(2025, 2, 1), "ES", DocumentKind.UTILITY_BILL, "Electricity", file = "gone.pdf", until = LocalDate.of(2025, 2, 28)),
        )
        val out = ByteArrayOutputStream()
        val result = EvidenceStore(context, dir).writePack(out, 2025, book, documents)

        val entries = LinkedHashMap<String, String>()
        ZipInputStream(out.toByteArray().inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes().decodeToString()
            }
        }
        assertEquals("lease", entries["home/2025-01-01_to_2025-12-31_ES.pdf"])
        assertEquals(1, entries.keys.count { it.startsWith("boarding-passes/") })
        assertEquals(2, result.files)
        assertTrue(result.missing.single(), "file missing" in result.missing.single())

        val index = entries.getValue("index.html")
        assertTrue("<h3>Home</h3>" in index)
        assertTrue("2025-01-01 to 2025-12-31" in index)
        assertTrue("Signed in 2024" in index)
        assertTrue("ABC123" in index)
        assertTrue("FR 8337" in index)
        // The flight's pass is listed once, next to its flight.
        assertEquals(1, Regex("boarding-passes/").findAll(index.substringAfter("<h3>Flights</h3>").substringBefore("</table>")).count())
        assertTrue("days.csv" in entries)
        dir.deleteRecursively()
    }
}
