package app.sojourn.screenshots

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.sojourn.R
import app.sojourn.data.Settings
import app.sojourn.data.TrackingMode
import app.sojourn.data.db.Source
import androidx.compose.ui.graphics.Color
import app.sojourn.ui.calendar.CalendarActions
import app.sojourn.ui.components.WorldMap
import app.sojourn.ui.calendar.CalendarContent
import app.sojourn.ui.home.HomeContent
import app.sojourn.ui.imports.ImportSources
import app.sojourn.ui.home.homeState
import app.sojourn.ui.onboarding.OnboardingContent
import app.sojourn.ui.onboarding.SojournMark
import app.sojourn.ui.plan.PlanContent
import app.sojourn.ui.plan.planState
import app.sojourn.ui.timeline.TimelineContent
import app.sojourn.ui.timeline.timelineState
import app.sojourn.data.CalendarView
import java.time.DayOfWeek
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = TALL_PHONE, application = Application::class)
class ScreensScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val settings = Settings(
        onboarded = true,
        tracking = TrackingMode.LOCATION,
        citizenships = setOf("DE"),
        taxResidence = "PT",
        lastCheck = System.currentTimeMillis() - 2 * 3_600_000,
        lastCheckSource = Source.LOCATION.name,
    )

    private val noCalendar = object : CalendarActions {
        override fun edit(from: LocalDate, to: LocalDate, edit: app.sojourn.data.RangeEdit, country: String?) = Unit
    }

    private fun home(dark: Boolean, year: Int? = Sample.today.year) = compose.screenshot(if (year == null) "home_all" else if (dark) "home_dark" else "home_light", dark) {
        HomeContent(homeState(Sample.book, Sample.statuses, settings, year), Sample.map, {}, {}, {}, {}, {})
    }

    @Test fun homeLight() = home(dark = false)

    @Test fun homeDark() = home(dark = true)

    @Test fun homeAllTime() = home(dark = false, year = null)

    @Test
    fun homeNonEuDark() = compose.screenshot("home_visitor_dark", dark = true) {
        // An American with a Portuguese residence permit: Schengen counts, but not Portugal.
        val traveller = app.sojourn.domain.Traveller(citizenships = setOf("US"), residencePermits = setOf("PT"))
        val statuses = Sample.rules.map { app.sojourn.domain.RuleEngine.evaluate(it, Sample.book, traveller) }.sortedBy { !it.applies }
        HomeContent(homeState(Sample.book, statuses, settings.copy(usTaxpayer = true), Sample.today.year), Sample.map, {}, {}, {}, {}, {})
    }

    private val noFinish: (app.sojourn.ui.onboarding.OnboardingAnswers) -> Unit = {}

    @Test fun calendar() = compose.screenshot("calendar") { CalendarContent(Sample.book, DayOfWeek.MONDAY, noCalendar) }

    @Test fun calendarDark() = compose.screenshot("calendar_dark", dark = true) { CalendarContent(Sample.book, DayOfWeek.MONDAY, noCalendar) }

    @Test fun calendarGrid() = compose.screenshot("calendar_grid") { CalendarContent(Sample.book, DayOfWeek.MONDAY, noCalendar, CalendarView.GRID) }

    @Test fun calendarCompactDark() = compose.screenshot("calendar_compact_dark", dark = true) { CalendarContent(Sample.book, DayOfWeek.MONDAY, noCalendar, CalendarView.COMPACT) }

    @Test fun calendarRows() = compose.screenshot("calendar_rows") { CalendarContent(Sample.book, DayOfWeek.MONDAY, noCalendar, CalendarView.ROWS) }

    @Test
    fun residencyCards() = compose.screenshot("rules_residency") {
        androidx.compose.foundation.layout.Column(
            Modifier.background(MaterialTheme.colorScheme.surface).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Sample.residencyStatuses.forEach { app.sojourn.ui.components.RuleCard(it) }
        }
    }

    @Test
    @Config(qualifiers = "w320dp-h1600dp-xxhdpi")
    fun unreachableResidencyCards() = compose.screenshot("rules_unreachable", dark = true) {
        androidx.compose.foundation.layout.Column(
            Modifier.background(MaterialTheme.colorScheme.surface).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            app.sojourn.domain.Goal.entries.forEach { goal ->
                app.sojourn.ui.components.RuleCard(app.sojourn.domain.ResidencySamples.spain(goal))
            }
        }
    }

    private fun simpleResidencyCard(name: String, dark: Boolean) = compose.screenshot(name, dark) {
        androidx.compose.foundation.layout.Column(Modifier.background(MaterialTheme.colorScheme.surface).padding(16.dp)) {
            app.sojourn.ui.components.RuleCard(app.sojourn.domain.ResidencySamples.spain(withAbsences = true))
        }
    }

    @Test
    @Config(qualifiers = "w320dp-h1200dp-xxhdpi")
    fun simpleResidencyDark() = simpleResidencyCard("rules_simple_dark", dark = true)

    @Test
    fun simpleResidencyLight() = simpleResidencyCard("rules_simple_light", dark = false)

    @Test
    fun keepCyprusWithPlans() = compose.screenshot("rules_keep_cyprus_plans") {
        val today = LocalDate.of(2026, 10, 25)
        val rows = (0L until 80L).map { app.sojourn.data.db.Presence(today.minusDays(it), "CY", Source.MANUAL) }
        val plan = app.sojourn.data.db.Plan(country = "CY", start = today.plusDays(1), end = today.plusDays(30))
        val book = app.sojourn.domain.DayBook.build(rows, listOf(plan), app.sojourn.domain.CountingOptions(fillGaps = false), today)
        val rule = app.sojourn.domain.RulePreset.KEEP_RESIDENCY.template("CY")
        val params = app.sojourn.domain.ResidencyParams.decode(rule.params)!!
        val answered = rule.copy(params = params.copy(answers = params.questions().associate { it.id to true }).encode())
        androidx.compose.foundation.layout.Column(Modifier.padding(16.dp)) {
            app.sojourn.ui.components.RuleCard(app.sojourn.domain.RuleEngine.evaluate(answered, book))
        }
    }

    @Test
    fun tripsReview() = compose.screenshot("import_trips") {
        androidx.compose.material3.Scaffold { padding ->
            androidx.compose.foundation.layout.Box(Modifier.padding(padding)) { app.sojourn.ui.imports.TripsPreview(Sample.trips) }
        }
    }

    private fun plan(name: String, dark: Boolean) = compose.screenshot(name, dark) {
        PlanContent(planState(Sample.book, Sample.plans, Sample.statuses, Sample.today.year), {}, {}, {}, {}, {})
    }

    @Test fun plan() = plan("plan", dark = false)

    @Test fun planDark() = plan("plan_dark", dark = true)

    @Test fun timeline() = compose.screenshot("timeline") { TimelineContent(timelineState(Sample.book), {}, {}) }

    @Test fun timelineDark() = compose.screenshot("timeline_dark", dark = true) { TimelineContent(timelineState(Sample.book), {}, {}) }

    @Test fun onboardingWelcome() = compose.screenshot("onboarding_1") { OnboardingContent(noFinish) }

    @Test fun onboardingWelcomeDark() = compose.screenshot("onboarding_1_dark", dark = true) { OnboardingContent(noFinish) }

    @Test fun onboardingTrackingDark() = compose.screenshot("onboarding_2_dark", dark = true) { OnboardingContent(noFinish, initialPage = 1) }

    @Test fun onboardingAboutDark() = compose.screenshot("onboarding_3_dark", dark = true) { OnboardingContent(noFinish, initialPage = 2) }

    private fun sources(name: String, dark: Boolean) = compose.screenshot(name, dark) {
        androidx.compose.material3.Scaffold { padding ->
            androidx.compose.foundation.layout.Box(Modifier.padding(padding)) {
                ImportSources(Sample.today.minusYears(2) to Sample.today.minusDays(1), error = null, onDates = {}, onPhotos = {}, onFile = {})
            }
        }
    }

    @Test fun importSources() = sources("import", dark = false)

    @Test fun importSourcesDark() = sources("import_dark", dark = true)

    @Test
    fun mapFits() = compose.screenshot("map_fits") {
        val palette = listOf(Color(0xFF2A9D8F), Color(0xFFE76F51), Color(0xFFE9B949), Color(0xFF7E6BC4), Color(0xFF4EA8DE), Color(0xFFD35D90))
        androidx.compose.foundation.layout.Column(
            Modifier.background(MaterialTheme.colorScheme.surface).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            listOf(listOf("CY", "ES", "PL", "DE", "TR"), listOf("PT", "ES", "FR", "IT", "DE"), listOf("PT"), listOf("US", "MX", "PT", "TH")).forEach { codes ->
                WorldMap(Sample.map, codes.mapIndexed { i, code -> code to palette[i % palette.size] }.toMap())
            }
        }
    }

    @Test
    fun icons() = compose.screenshot("icons") {
        Row(Modifier.background(MaterialTheme.colorScheme.surface).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            SojournMark(160.dp)
            Icon(painterResource(R.drawable.ic_launcher_monochrome), null, Modifier.size(160.dp))
            Icon(painterResource(R.drawable.ic_stat_sojourn), null, Modifier.size(48.dp))
        }
    }
}
