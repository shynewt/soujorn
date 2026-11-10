package app.sojourn.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import app.sojourn.data.ThemeMode

// Deep sea teal with an apricot accent: the colours of the app icon's arch and sun.
private val Light = lightColorScheme(
    primary = Color(0xFF006A64),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF9EF2E9),
    onPrimaryContainer = Color(0xFF00201E),
    secondary = Color(0xFF8C4F25),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDBC8),
    onSecondaryContainer = Color(0xFF331200),
    tertiary = Color(0xFF8B5000),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDCBE),
    onTertiaryContainer = Color(0xFF2C1600),
    background = Color(0xFFF5FAF8),
    onBackground = Color(0xFF171D1C),
    surface = Color(0xFFF5FAF8),
    onSurface = Color(0xFF171D1C),
    surfaceVariant = Color(0xFFDAE5E2),
    onSurfaceVariant = Color(0xFF3F4947),
    outline = Color(0xFF6F7977),
    outlineVariant = Color(0xFFBEC9C6),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFEFF5F3),
    surfaceContainer = Color(0xFFE9EFED),
    surfaceContainerHigh = Color(0xFFE3EAE8),
    surfaceContainerHighest = Color(0xFFDEE4E2),
    inverseSurface = Color(0xFF2B3231),
    inverseOnSurface = Color(0xFFECF2F0),
    inversePrimary = Color(0xFF82D5CD),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF82D5CD),
    onPrimary = Color(0xFF003734),
    primaryContainer = Color(0xFF00504B),
    onPrimaryContainer = Color(0xFF9EF2E9),
    secondary = Color(0xFFFFB68C),
    onSecondary = Color(0xFF532200),
    secondaryContainer = Color(0xFF6F3810),
    onSecondaryContainer = Color(0xFFFFDBC8),
    tertiary = Color(0xFFFFB870),
    onTertiary = Color(0xFF4A2800),
    tertiaryContainer = Color(0xFF693C00),
    onTertiaryContainer = Color(0xFFFFDCBE),
    background = Color(0xFF0E1514),
    onBackground = Color(0xFFDDE4E2),
    surface = Color(0xFF0E1514),
    onSurface = Color(0xFFDDE4E2),
    surfaceVariant = Color(0xFF3F4947),
    onSurfaceVariant = Color(0xFFBEC9C6),
    outline = Color(0xFF889391),
    outlineVariant = Color(0xFF3F4947),
    surfaceContainerLowest = Color(0xFF09100F),
    surfaceContainerLow = Color(0xFF171D1C),
    surfaceContainer = Color(0xFF1B2120),
    surfaceContainerHigh = Color(0xFF252B2A),
    surfaceContainerHighest = Color(0xFF303635),
    inverseSurface = Color(0xFFDDE4E2),
    inverseOnSurface = Color(0xFF2B3231),
    inversePrimary = Color(0xFF006A64),
)

@Composable
fun SojournTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val scheme: ColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> Dark
        else -> Light
    }
    MaterialExpressiveTheme(colorScheme = scheme, motionScheme = MotionScheme.expressive(), content = content)
}

/**
 * Colours for countries in charts and on the map. They are picked to stay apart from each other
 * and readable on both light and dark surfaces, in rank order of days spent.
 */
object CountryPalette {
    private val colors = listOf(
        Color(0xFF2A9D8F),
        Color(0xFFE76F51),
        Color(0xFFE9B949),
        Color(0xFF7E6BC4),
        Color(0xFF4EA8DE),
        Color(0xFFD35D90),
        Color(0xFF7FB069),
        Color(0xFF5C6BC0),
        Color(0xFFC08552),
        Color(0xFF3FB6B2),
    )

    fun assign(ranked: List<String>): Map<String, Color> =
        ranked.mapIndexed { index, code -> code to colors[index % colors.size] }.toMap()

    /** A steady colour for a country not ranked yet (only planned, say), the same every time. */
    fun fallback(code: String): Color = colors[Math.floorMod(code.hashCode(), colors.size)]

    val other: Color
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.outline
}
