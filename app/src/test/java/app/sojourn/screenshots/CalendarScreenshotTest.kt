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

/** The sample's countries in the one palette, ranked by days, as the shell provides them. */
@Composable
fun WithCountryColors(content: @Composable () -> Unit) {
    val book = Sample.book
    val years = book.years()
    val ranked = book.summary(LocalDate.of(years.min(), 1, 1), LocalDate.of(years.max(), 12, 31)).countries.sortedByDescending { it.total }.map { it.country }
    CompositionLocalProvider(LocalCountryColors provides CountryPalette.assign(ranked)) { content() }
}

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

    @Test fun legend() = sheet("calendar_legend", dark = false) { LegendContent(onCounting = {}) }

    @Test fun legendDark() = sheet("calendar_legend_dark", dark = true) { LegendContent(onCounting = {}) }
}
