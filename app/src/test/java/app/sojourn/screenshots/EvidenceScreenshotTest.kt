package app.sojourn.screenshots

import android.app.Application
import android.net.Uri
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.sojourn.data.EvidenceStore
import app.sojourn.data.Settings
import app.sojourn.data.db.Document
import app.sojourn.data.db.DocumentKind
import app.sojourn.data.travel.FoundTrip
import app.sojourn.data.travel.PassengerMatch
import app.sojourn.data.travel.TripKind
import app.sojourn.domain.CountingOptions
import app.sojourn.domain.DayBook
import app.sojourn.ui.evidence.AddEvidenceLayout
import app.sojourn.ui.evidence.EvidenceContent
import app.sojourn.ui.evidence.EvidencePackContent
import app.sojourn.ui.evidence.FormValues
import app.sojourn.ui.evidence.PackExport
import app.sojourn.ui.evidence.PassRead
import app.sojourn.ui.evidence.PassReading
import app.sojourn.ui.evidence.evidenceState
import app.sojourn.ui.evidence.packPreview
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The Evidence tab, its pack and the add form, for a German living in Portugal with Spain's rule on. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = TALL_PHONE, application = Application::class)
class EvidenceScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val today = Sample.today
    private val year = today.year
    private val settings = Settings(onboarded = true, citizenships = setOf("DE"), taxResidence = "PT")
    private val flight = Sample.flights.first { it.id == 4L }

    private val documents: List<Document> = listOf(
        Document(1, flight.departureDate, "ES", DocumentKind.BOARDING_PASS, "Boarding pass: FR 8337 BCN→BGY", file = "pass.png", flightId = 4),
        Document(2, flight.arrivalDate, "IT", DocumentKind.BOARDING_PASS, "Boarding pass: FR 8337 BCN→BGY", file = "pass.png", flightId = 4),
        Document(3, flight.departureDate, "ES", DocumentKind.CALENDAR_EVENT, "Calendar event: FR 8337 BCN→BGY", text = "BEGIN:VEVENT", flightId = 4),
        Document(4, LocalDate.of(year, 1, 1), "PT", DocumentKind.LEASE, "Lease, Lisbon flat", file = "lease.pdf", until = LocalDate.of(year, 12, 31), note = "Two-year contract"),
        Document(5, LocalDate.of(year, 7, 1), "ME", DocumentKind.CARD_STATEMENT, "Bank statement, July", file = "july.pdf", until = LocalDate.of(year, 7, 31)),
        Document(6, LocalDate.of(year, 9, 12), "GB", DocumentKind.MEDICAL, "Dentist in London", file = "dentist.pdf"),
    ) + (0 until 14).map { Document(100L + it, LocalDate.of(year, 5, 2 + it * 2), "ES", DocumentKind.PHOTO, "Photo", uri = "content://media/$it") } +
        (0 until 9).map { Document(200L + it, LocalDate.of(year, 10, 1 + it * 3), "PT", DocumentKind.PHOTO, "Photo", uri = "content://media/p$it") }

    private val state get() = evidenceState(Sample.book, documents, settings, Sample.rules, year)

    @Test fun evidence() = compose.screenshot("evidence_light") { EvidenceContent(state) {} }

    @Test fun evidenceDark() = compose.screenshot("evidence_dark", dark = true) { EvidenceContent(state) {} }

    @Test
    fun evidenceEmpty() = compose.screenshot("evidence_empty") {
        val empty = DayBook.build(emptyList(), emptyList(), CountingOptions(), today)
        EvidenceContent(evidenceState(empty, emptyList(), settings, emptyList(), year)) {}
    }

    @Test
    fun pack() = compose.screenshot("evidence_pack") {
        EvidencePackContent(packPreview(year, Sample.book, documents, settings, Sample.rules), PackExport.Idle, {}, {})
    }

    @Test
    fun packDoneDark() = compose.screenshot("evidence_pack_done_dark", dark = true) {
        val result = EvidenceStore.PackResult(310, 26, listOf("2 Oct ${year} Portugal: photo no longer in the gallery"))
        EvidencePackContent(packPreview(year, Sample.book, documents, settings, Sample.rules), PackExport.Done(result, Uri.parse("content://x")), {}, {})
    }

    @Test
    fun addKinds() = compose.screenshot("evidence_add_kinds") {
        AddEvidenceLayout(FormValues(null, null, null, today, null), {}, null, "PT", emptyList(), false, null, {}, {}, {}) { _, _ -> }
    }

    @Test
    fun addForm() = compose.screenshot("evidence_add_form", dark = true) {
        val values = FormValues(DocumentKind.UTILITY_BILL, null, null, LocalDate.of(year, 1, 1), LocalDate.of(year, 3, 31))
        AddEvidenceLayout(values, {}, null, "PT", emptyList(), false, null, {}, {}, {}) { _, _ -> }
    }

    @Test
    fun addPass() = compose.screenshot("evidence_add_pass") {
        val trip = FoundTrip(
            TripKind.FLIGHT, LocalDate.of(year, 11, 24), number = "W64666", from = "BCN", to = "LCA",
            departureMinute = 6 * 60 + 10, arrivalMinute = 10 * 60 + 45, passenger = "Ana Ruiz", seat = "12A", reference = "K7QX2P",
        )
        val flight = flight.copy(id = 0, number = "W64666", origin = "BCN", destination = "LCA", originCountry = "ES", destinationCountry = "CY", departureDate = trip.departure, arrivalDate = trip.arrival)
        val values = FormValues(DocumentKind.BOARDING_PASS, "content://pass.pdf", "ES", trip.departure, null, title = "Boarding pass: W6 4666 BCN→LCA")
        val pass = PassReading.Read(PassRead(trip, flight, saved = null, passenger = PassengerMatch.MATCH))
        AddEvidenceLayout(values, {}, "pass.pdf", "ES", emptyList(), false, null, {}, {}, {}, pass) { _, _ -> }
    }
}
