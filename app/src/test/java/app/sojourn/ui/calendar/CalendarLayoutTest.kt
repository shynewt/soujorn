package app.sojourn.ui.calendar

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.sojourn.ui.theme.SojournTheme
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class CalendarLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun dayNumbersFitInsideTheirTilesAtTheNarrowestCard() {
        compose.setContent {
            SojournTheme(dynamicColor = false) {
                Box(Modifier.width(160.dp)) {
                    MonthCard(
                        month = YearMonth.of(2026, 11), firstDay = DayOfWeek.MONDAY,
                        book = null, today = LocalDate.of(2026, 10, 25), colors = emptyMap(),
                        selection = null, hits = DayHitTest(),
                        callbacks = DayGestureCallbacks({}, {}, {}),
                    )
                }
            }
        }
        listOf("1", "11", "30").forEach { number ->
            val node = compose.onNodeWithText(number, useUnmergedTree = true)
            val results = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            val text = results.single()
            val tile = node.fetchSemanticsNode().parent!!.boundsInRoot
            assertEquals(1, text.lineCount)
            assertTrue("$number text height ${text.size.height} exceeds tile height ${tile.height}", text.size.height <= tile.height + 1)
            val lineCenter = (text.getLineTop(0) + text.getLineBottom(0)) / 2
            val bounds = node.fetchSemanticsNode().boundsInRoot
            assertEquals(tile.center.y, bounds.top + lineCenter, 1f)
        }
    }
}
