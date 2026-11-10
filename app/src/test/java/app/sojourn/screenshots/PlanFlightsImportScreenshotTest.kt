package app.sojourn.screenshots

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.sojourn.data.travel.UnreadEvent
import app.sojourn.domain.Reports
import app.sojourn.ui.flights.FlightCard
import app.sojourn.ui.flights.FlightEditorContent
import app.sojourn.ui.flights.FlightForm
import app.sojourn.ui.imports.ImportDone
import app.sojourn.ui.imports.ImportPhase
import app.sojourn.ui.imports.TripsPreview
import app.sojourn.ui.reports.FeieContent
import app.sojourn.ui.reports.HistoryContent
import app.sojourn.ui.reports.untrackedSince
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The flight editor and rows, Import's review and results, and the two reports. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = TALL_PHONE, application = Application::class)
class PlanFlightsImportScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val booked = Sample.flights[1].copy(reference = "QX7Z2P", passenger = "DOE/JANE MRS", seat = "32A")

    /** The sheet's content on the sheet's own colour. */
    private fun editor(name: String, dark: Boolean, form: () -> FlightForm, isNew: Boolean) = compose.screenshot(name, dark) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
            FlightEditorContent(
                form = androidx.compose.runtime.remember { form() },
                isNew = isNew,
                impact = Sample.statuses.filter { it.forecast != null }.take(2),
                pasteMessage = null,
                onPaste = {},
                onPickAirport = {},
                onPickDate = {},
                onPickTime = {},
                onSave = {},
                onDelete = if (isNew) null else ({}),
                modifier = Modifier.padding(top = 24.dp),
            )
        }
    }

    @Test fun flightEditorNew() = editor("flight_editor_new", dark = false, form = { FlightForm(null) }, isNew = true)

    @Test fun flightEditorBooked() = editor("flight_editor_booked_dark", dark = true, form = { FlightForm(booked) }, isNew = false)

    private fun cards(name: String, dark: Boolean) = compose.screenshot(name, dark) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FlightCard(Sample.flights[0], Sample.today, onClick = {})
                FlightCard(booked, Sample.today, onClick = {})
                FlightCard(Sample.flights[3].copy(seat = "14C", reference = "K9LM2D"), Sample.today, onClick = {})
                FlightCard(Sample.flights[3].copy(departureMinute = null, arrivalMinute = null, number = ""), Sample.today)
            }
        }
    }

    @Test fun flightCards() = cards("flight_cards", dark = false)

    @Test fun flightCardsDark() = cards("flight_cards_dark", dark = true)

    @Test
    fun tripsWithUnread() = compose.screenshot("import_trips_unread") {
        Scaffold { padding ->
            Box(Modifier.padding(padding)) {
                TripsPreview(Sample.trips, unread = listOf(UnreadEvent("Flight to Larnaca", Sample.today.minusDays(200)), UnreadEvent("Trip to Malta", Sample.today.minusDays(90))))
            }
        }
    }

    @Test
    fun importDoneTrips() = compose.screenshot("import_done_trips") {
        Scaffold { padding ->
            Box(Modifier.padding(padding)) { ImportDone(ImportPhase.Done(12, trips = true, skipped = 3, evidence = 9, duplicatesRemoved = 1), onMore = {}) }
        }
    }

    @Test
    fun importDoneDays() = compose.screenshot("import_done_days_dark", dark = true) {
        Scaffold { padding ->
            Box(Modifier.padding(padding)) { ImportDone(ImportPhase.Done(41, backedUp = 120, evidence = 160), onMore = {}) }
        }
    }

    @Test
    fun feie() = compose.screenshot("report_feie") {
        val year = Sample.today.year
        FeieContent(Reports.feie(Sample.book, year), year, listOf(year, year - 1, year - 2), {}, {}, {}, {})
    }

    private fun history(name: String, dark: Boolean) = compose.screenshot(name, dark) {
        val from = Sample.today.minusYears(5)
        HistoryContent(Reports.travelHistory(Sample.book, from, setOf("DE")), from, untrackedSince(Sample.book, from), 5, {}, setOf("DE"), true, {}, {}, {})
    }

    @Test fun travelHistory() = history("report_history", dark = false)

    @Test fun travelHistoryDark() = history("report_history_dark", dark = true)
}
