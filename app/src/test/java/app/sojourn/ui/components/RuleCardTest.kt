package app.sojourn.ui.components

import android.app.Application
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.sojourn.data.ThemeMode
import app.sojourn.domain.Goal
import app.sojourn.domain.Level
import app.sojourn.domain.ResidencySamples
import app.sojourn.domain.RuleStatus
import app.sojourn.ui.theme.SojournTheme
import app.sojourn.ui.theme.StatusPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w320dp-h1200dp-xxhdpi", application = Application::class)
class RuleCardTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(status: RuleStatus, onClick: (() -> Unit)? = null) {
        compose.setContent {
            SojournTheme(ThemeMode.DARK, dynamicColor = false) {
                RuleCard(status, showForecast = false, onClick = onClick)
            }
        }
    }

    private fun headlineColor(headline: String = "Not enough days left"): Color {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(headline, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single().layoutInput.style.color
    }

    @Test
    fun unreachableAvoidGoalKeepsItsTextWithGreenStylingAndRightHandCheckmark() {
        show(ResidencySamples.spain(withAbsences = true))
        assertEquals(StatusPalette.forLevel(Level.GOOD, dark = true).accent, headlineColor())
        val headline = compose.onNodeWithText("Not enough days left").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithText("Reached").assertDoesNotExist()
        val checkmark = compose.onNodeWithContentDescription("On track").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(checkmark.left >= headline.right)
    }

    @Test
    fun mainCardShowsOnlyTheChosenDayCountAndPlainExplanation() {
        show(ResidencySamples.spain(withAbsences = true))
        compose.onNodeWithText("Not enough days left").assertIsDisplayed()
        compose.onNodeWithText("Even if you stay in Spain until 31 Dec 2026, you won't reach 184 days there.").assertIsDisplayed()
        compose.onNodeWithText("Check time abroad").assertDoesNotExist()
        compose.onNodeWithText("Trips outside Spain").assertDoesNotExist()
        compose.onNodeWithText("237 days", substring = true).assertDoesNotExist()
        compose.onNodeWithText("99 days").assertIsDisplayed()
        compose.onNodeWithText("134 days").assertIsDisplayed()
        compose.onNodeWithText("Counting sporadic absences:", substring = true).assertDoesNotExist()
        compose.onNodeWithText("183-day rule", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Day tests out of reach").assertDoesNotExist()
    }

    @Test
    fun detailsCanBeOpenedAndClosedWithoutEditingTheRule() {
        var edits = 0
        show(ResidencySamples.spain(withAbsences = true), onClick = { edits++ })
        compose.onNodeWithText("Details").performClick()
        compose.onNodeWithText("Trips outside Spain").assertIsDisplayed()
        compose.onNodeWithText("237 days if return trips outside Spain are counted too.").assertIsDisplayed()
        compose.onNodeWithText("Not included in your current day count.").assertIsDisplayed()
        compose.onNodeWithText("183-day rule", substring = true).assertExists()
        compose.onNodeWithText("Counting sporadic absences:", substring = true).assertExists()
        assertEquals(0, edits)
        compose.onNodeWithText("Hide details").performClick()
        compose.onNodeWithText("183-day rule", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Trips outside Spain").assertDoesNotExist()
        compose.onNodeWithText("Not enough days left").assertIsDisplayed()
    }

    @Test
    fun avoidGoalUsesPlainLanguageWhenThereIsNoAbsenceRisk() {
        show(ResidencySamples.spain())
        compose.onNodeWithText("Not enough days left").assertIsDisplayed()
        compose.onNodeWithText("Trips outside Spain").assertDoesNotExist()
        compose.onNodeWithText("99 days").assertIsDisplayed()
        compose.onNodeWithText("134 days").assertIsDisplayed()
    }

    @Test
    fun keepGoalExplainsThatThereAreNotEnoughDaysLeft() {
        show(ResidencySamples.spain(goal = Goal.KEEP))
        assertEquals(StatusPalette.forLevel(Level.ALERT, dark = true).accent, headlineColor())
        compose.onNodeWithText("Not enough days left").assertIsDisplayed()
        compose.onNodeWithText("Even if you stay in Spain until 31 Dec 2026, you won't reach 184 days there.").assertIsDisplayed()
    }
}
