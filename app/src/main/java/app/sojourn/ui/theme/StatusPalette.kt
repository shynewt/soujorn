package app.sojourn.ui.theme

import androidx.compose.ui.graphics.Color
import app.sojourn.domain.Level

data class StatusColors(val container: Color, val content: Color, val accent: Color)

object StatusPalette {
    private val goodLight = StatusColors(Color(0xFFD8F3E5), Color(0xFF123D2C), Color(0xFF176342))
    private val goodDark = StatusColors(Color(0xFF163D2D), Color(0xFFB6F2CF), Color(0xFF78DCAA))
    private val cautionLight = StatusColors(Color(0xFFFFEAC2), Color(0xFF523700), Color(0xFF805600))
    private val cautionDark = StatusColors(Color(0xFF43320E), Color(0xFFFFE2A6), Color(0xFFFFCA66))
    private val alertLight = StatusColors(Color(0xFFFFDAD6), Color(0xFF6D1713), Color(0xFFB3261E))
    private val alertDark = StatusColors(Color(0xFF53211D), Color(0xFFFFDAD6), Color(0xFFFFB4AB))

    fun forLevel(level: Level, dark: Boolean): StatusColors = when (level) {
        Level.GOOD -> if (dark) goodDark else goodLight
        Level.CAUTION -> if (dark) cautionDark else cautionLight
        Level.ALERT -> if (dark) alertDark else alertLight
    }
}
