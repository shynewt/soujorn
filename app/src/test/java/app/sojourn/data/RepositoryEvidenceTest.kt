package app.sojourn.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.sojourn.data.db.Document
import app.sojourn.data.db.DocumentKind
import app.sojourn.data.db.Flight
import app.sojourn.data.db.SojournDatabase
import app.sojourn.data.travel.FoundTrip
import app.sojourn.data.travel.TripKind
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Flights and the evidence that names them, through the repository and a real database. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class RepositoryEvidenceTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val db = Room.inMemoryDatabaseBuilder(context, SojournDatabase::class.java).allowMainThreadQueries().build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val repository = Repository(db, SettingsStore(context), scope, onDaysChanged = {})
    private val day = LocalDate.of(2026, 1, 12)
    private val flight = Flight(number = "W64666", origin = "BCN", destination = "LCA", originCountry = "ES", destinationCountry = "CY", departureDate = day, arrivalDate = day.plusDays(1))

    @After
    fun close() {
        scope.cancel()
        db.close()
    }

    @Test
    fun evidenceFindsItsFlightAgainAfterTheFlightGetsANewId() = runBlocking {
        repository.saveFlight(flight)
        val saved = db.flights().all().single()
        repository.addDocument(Document(day = day, country = "ES", kind = DocumentKind.BOARDING_PASS, title = "Boarding pass: W6 4666 BCN→LCA", file = "pass.pdf", flightId = saved.id))
        // A restore that replaces everything puts the flight back under a new id.
        val backup = BackupCodec.encode(emptyList(), emptyList(), emptyList(), Settings(), listOf(saved))
        repository.restore(backup, replace = true)
        val restored = db.flights().all().single()
        assertEquals(restored.id, db.documents().flightDocuments().single().flightId)
    }

    @Test
    fun importingAFlightAlreadySavedOnlyFillsInWhatItLacked() = runBlocking {
        repository.saveFlight(flight.copy(note = "Mine"))
        val pass = flight.copy(number = "W6 4666", reference = "NGJY6Y", passenger = "DOE/JANE", seat = "13B", departureMinute = 19 * 60 + 55, note = "Boarding pass")
        repository.importFlights(listOf(pass))
        val kept = db.flights().all().single()
        assertEquals("Mine", kept.note)
        assertEquals("NGJY6Y", kept.reference)
        assertEquals("13B", kept.seat)
        assertEquals(19 * 60 + 55, kept.departureMinute)
    }

    @Test
    fun savingAnOpenEditorKeepsWhatAPassFilledInMeanwhile() = runBlocking {
        repository.saveFlight(flight)
        val opened = db.flights().all().single()
        db.flights().upsert(opened.copy(reference = "NGJY6Y", seat = "13B"))
        repository.saveFlight(opened.copy(note = "Window seat"))
        val kept = repository.flights.first().single()
        assertEquals("NGJY6Y", kept.reference)
        assertEquals("Window seat", kept.note)
    }

    @Test
    fun aBoardingPassFillsInOnlyWhatTheFlightLacked() = runBlocking {
        repository.saveFlight(flight.copy(seat = "2A"))
        val saved = db.flights().all().single()
        val pass = FoundTrip(TripKind.FLIGHT, day, number = "W6 4666", from = "BCN", to = "LCA", departureMinute = 19 * 60 + 55, passenger = "DOE/JANE", reference = "NGJY6Y", seat = "13B")
        repository.enrichFlight(saved, pass)
        val kept = db.flights().all().single()
        assertEquals("NGJY6Y", kept.reference)
        assertEquals("DOE/JANE", kept.passenger)
        assertEquals("2A", kept.seat)
        assertEquals(19 * 60 + 55, kept.departureMinute)
    }
}
