package app.sojourn.screenshots

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.sojourn.data.RangeEdit
import app.sojourn.data.db.Flight
import app.sojourn.data.db.Presence
import app.sojourn.data.db.Source
import app.sojourn.domain.CountingOptions
import app.sojourn.domain.DayBook
import app.sojourn.ui.LocalCountryColors
import app.sojourn.ui.calendar.CalendarActions
import app.sojourn.ui.calendar.DayDetails
import app.sojourn.ui.calendar.LegendContent
import app.sojourn.ui.theme.CountryPalette
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE, application = Application::class)
class CalendarScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val none = object : CalendarActions {
        override fun edit(from: LocalDate, to: LocalDate, edit: RangeEdit, country: String?) = Unit
    }

    /** The sheets' content on the sheet's own colour, since a bottom sheet can't be captured. */
    private fun sheet(name: String, dark: Boolean, content: @Composable () -> Unit) = compose.screenshot(name, dark) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) { Box(Modifier.padding(top = 24.dp)) { content() } }
    }

    private fun day(name: String, date: LocalDate, dark: Boolean = false) = sheet(name, dark) {
        DayDetails(date, Sample.book, none, documents = emptyList(), onDismiss = {}, onAddCountry = {}, onElsewhere = {}, onClear = {})
    }

    /** The day of the sample's flight from Barcelona to Bergamo: a travel day with its flight. */
    @Test fun dayTravel() = day("calendar_day_travel", Sample.today.minusDays(40))

    @Test fun dayTravelDark() = day("calendar_day_travel_dark", Sample.today.minusDays(40), dark = true)

    @Test fun dayPlanned() = day("calendar_day_planned", Sample.today.plusDays(30))

    /** Days in Poland between two kept boarding passes: proven there whatever recorded them. */
    private val passes: DayBook by lazy {
        val today = Sample.today
        fun days(country: String, from: Long, to: Long) = (from..to).map { Presence(today.minusDays(it), country, Source.LOCATION, 0, 0) }
        val flights = listOf(
            Flight(10, "LO434", "MAD", "WAW", "ES", "PL", today.minusDays(20), today.minusDays(20), 9 * 60, 13 * 60),
            Flight(11, "LO433", "WAW", "MAD", "PL", "ES", today.minusDays(15), today.minusDays(15), 14 * 60, 18 * 60 + 30),
        )
        DayBook.build(days("ES", 21, 30) + days("PL", 16, 19) + days("ES", 0, 14), emptyList(), CountingOptions(), today, flights, passes = setOf(10, 11))
    }

    @Test fun dayBetweenPasses() = sheet("calendar_day_between", dark = false) {
        DayDetails(Sample.today.minusDays(17), passes, none, documents = emptyList(), onDismiss = {}, onAddCountry = {}, onElsewhere = {}, onClear = {})
    }

    @Test fun legend() = sheet("calendar_legend", dark = false) { LegendContent(onCounting = {}) }

    @Test fun legendDark() = sheet("calendar_legend_dark", dark = true) { LegendContent(onCounting = {}) }
}
