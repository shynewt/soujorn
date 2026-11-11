package app.sojourn.screenshots

import android.app.Application
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.sojourn.data.Change
import app.sojourn.data.Settings
import app.sojourn.data.TrackingMode
import app.sojourn.data.db.Plan
import app.sojourn.domain.DayBook
import app.sojourn.domain.RuleEngine
import app.sojourn.domain.Traveller
import app.sojourn.domain.unconfirmedPlans
import app.sojourn.ui.LocalCountryColors
import app.sojourn.ui.SettingsSection
import app.sojourn.ui.components.RuleCard
import app.sojourn.ui.home.HomeContent
import app.sojourn.ui.home.homeState
import app.sojourn.ui.settings.PendingRestore
import app.sojourn.ui.settings.SettingsCommands
import app.sojourn.ui.settings.SettingsContent
import app.sojourn.ui.settings.SettingsExtras
import app.sojourn.ui.theme.CountryPalette
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The app's one country palette, as the shell provides it, for screens drawn on their own. */
@Composable
fun WithCountryColors(book: DayBook = Sample.book, content: @Composable () -> Unit) {
    val ranked = book.summary(book.firstDay ?: book.today, book.today).countries.sortedByDescending { it.total }.map { it.country }
    CompositionLocalProvider(LocalCountryColors provides CountryPalette.assign(ranked)) { content() }
}

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = TALL_PHONE, application = Application::class)
class TodayScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    // The coworker: tax resident in Cyprus, watching Spain, tracking off, a trip never confirmed.
    private val settings = Settings(onboarded = true, tracking = TrackingMode.OFF, citizenships = setOf("DE"), taxResidence = "CY")
    private val traveller = Traveller(citizenships = setOf("DE"), taxResidence = "CY")
    private val pastPlan = Plan(9, "GR", Sample.today.withDayOfYear(1).plusMonths(7).plusDays(26), Sample.today.withDayOfYear(1).plusMonths(8).plusDays(1))

    private fun attention(name: String, dark: Boolean) = compose.screenshot(name, dark) {
        val statuses = Sample.rules.map { RuleEngine.evaluate(it, Sample.book, traveller) }.sortedBy { !it.applies }
        WithCountryColors {
            HomeContent(homeState(Sample.book, statuses, settings, Sample.today.year, plans = listOf(pastPlan)), Sample.map, {}, {}, {})
        }
    }

    @Test fun todayAttention() = attention("today_attention", dark = false)

    @Test fun todayAttentionDark() = attention("today_attention_dark", dark = true)

    @Test
    fun todayNoRules() = compose.screenshot("today_empty") {
        WithCountryColors { HomeContent(homeState(Sample.book, emptyList(), settings.copy(tracking = TrackingMode.LOCATION, taxResidence = null), Sample.today.year), null, {}, {}, {}) }
    }

    @Test
    fun pastPlanIsUnconfirmedUntilRecorded() {
        assertEquals(listOf(pastPlan), unconfirmedPlans(Sample.book, listOf(pastPlan) + Sample.plans))
    }

    private val noCommands = object : SettingsCommands {
        override fun update(transform: (Settings) -> Settings) = Unit
        override fun checkNow() = Unit
        override fun exportBackup(uri: Uri) = Unit
        override fun exportSpreadsheet(uri: Uri) = Unit
        override suspend fun read(uri: Uri): PendingRestore? = null
        override fun restore(backup: PendingRestore, replace: Boolean) = Unit
        override fun chooseBackupFolder(uri: Uri) = Unit
        override fun revert(change: Change) = Unit
        override fun erase() = Unit
    }

    private val mine = Settings(
        onboarded = true,
        tracking = TrackingMode.LOCATION,
        citizenships = setOf("DE"),
        taxResidence = "ES",
        lastCheck = System.currentTimeMillis() - 3_600_000,
    )

    private fun settings(name: String, section: SettingsSection?, dark: Boolean = false) = compose.screenshot(name, dark) {
        SettingsContent(mine, SettingsExtras(visited = listOf("ES", "PT"), activeRules = 2), section, noCommands, back = {})
    }

    @Test fun settingsList() = settings("settings", section = null)

    @Test fun settingsListDark() = settings("settings_dark", section = null, dark = true)

    @Test fun settingsCounting() = settings("settings_counting", SettingsSection.COUNTING)

    @Test fun settingsAboutYou() = settings("settings_about", SettingsSection.ABOUT_YOU, dark = true)

    @Test fun settingsAppearance() = settings("settings_appearance", SettingsSection.APPEARANCE)

    @Test fun settingsData() = settings("settings_data", SettingsSection.DATA)

    @Test
    @Config(qualifiers = "w900dp-h700dp-xhdpi")
    fun settingsTablet() = settings("settings_tablet", SettingsSection.ABOUT_YOU)

    @Test
    fun rulesExpanded() = compose.screenshot("rules_expanded") {
        Column(Modifier.background(MaterialTheme.colorScheme.surface).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Sample.residencyStatuses.forEach { RuleCard(it, expanded = true) }
        }
    }
}
