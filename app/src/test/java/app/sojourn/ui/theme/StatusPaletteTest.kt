package app.sojourn.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import app.sojourn.domain.Level
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusPaletteTest {
    private fun contrast(first: Color, second: Color): Float {
        val a = first.luminance()
        val b = second.luminance()
        return (maxOf(a, b) + 0.05f) / (minOf(a, b) + 0.05f)
    }

    @Test
    fun statusTextHasAccessibleContrastInBothThemes() {
        listOf(false, true).forEach { dark ->
            val surface = if (dark) Color(0xFF171D1C) else Color(0xFFEFF5F3)
            Level.entries.forEach { level ->
                val colors = StatusPalette.forLevel(level, dark)
                assertTrue("$level content, dark=$dark", contrast(colors.content, colors.container) >= 4.5f)
                assertTrue("$level accent, dark=$dark", contrast(colors.accent, surface) >= 4.5f)
                assertTrue("$level icon, dark=$dark", contrast(colors.accent, colors.container) >= 3f)
            }
        }
    }
}
