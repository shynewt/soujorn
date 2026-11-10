package app.sojourn.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.sojourn.data.CountrySet
import app.sojourn.data.db.RuleType
import app.sojourn.domain.Level
import app.sojourn.domain.RuleStatus
import app.sojourn.ui.theme.StatusPalette

/** The colours of a rule card: its container, the text on it, and the accent for figures. */
data class LevelColors(val container: Color, val content: Color, val muted: Color, val accent: Color)

@Composable
fun levelColors(level: Level, applies: Boolean = true): LevelColors {
    val scheme = MaterialTheme.colorScheme
    val status = StatusPalette.forLevel(level, dark = scheme.surface.luminance() < 0.5f)
    return when {
        !applies -> LevelColors(scheme.surfaceContainerLow, scheme.onSurface, scheme.onSurfaceVariant, scheme.onSurfaceVariant)
        level == Level.GOOD -> LevelColors(scheme.surfaceContainerLow, scheme.onSurface, scheme.onSurfaceVariant, status.accent)
        else -> LevelColors(status.container, status.content, status.content, status.accent)
    }
}

fun levelLabel(level: Level): String = when (level) {
    Level.GOOD -> "On track"
    Level.CAUTION -> "Needs attention"
    Level.ALERT -> "At risk"
}

@Composable
fun RuleCard(status: RuleStatus, modifier: Modifier = Modifier, showForecast: Boolean = true, onClick: (() -> Unit)? = null) {
    val dayLimit = status.dayLimit
    if (dayLimit != null) {
        ResidencyDayLimitCard(status, dayLimit, modifier, showForecast, onClick)
        return
    }
    val colors = levelColors(status.level, status.applies)
    val shape = RoundedCornerShape(28.dp)
    val content: @Composable () -> Unit = {
        Column(Modifier.padding(20.dp)) {
            if (!status.applies) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(status.rule.name, style = MaterialTheme.typography.labelLarge, color = colors.muted)
                        Text(status.headline, style = MaterialTheme.typography.titleLargeEmphasized, color = colors.content)
                    }
                    Icon(Icons.Rounded.Verified, contentDescription = "Doesn't apply", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                }
                Spacer(Modifier.height(4.dp))
                Text(status.detail, style = MaterialTheme.typography.bodyMedium, color = colors.muted)
                return@Column
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(status.rule.name, style = MaterialTheme.typography.labelLarge, color = colors.muted)
                    Text(status.headline, style = MaterialTheme.typography.headlineSmallEmphasized, color = if (status.level == Level.GOOD) colors.accent else colors.content)
                }
                val icon = when {
                    status.met || status.level == Level.GOOD -> Icons.Rounded.CheckCircle
                    status.level == Level.CAUTION -> Icons.Rounded.WarningAmber
                    else -> Icons.Rounded.ErrorOutline
                }
                Icon(icon, contentDescription = levelLabel(status.level), tint = if (status.level == Level.GOOD) colors.accent else colors.content, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.height(2.dp))
            Text(
                if (status.rule.type == RuleType.RESIDENCY) status.detail else "${status.detail} · ${CountrySet.describe(status.rule.countries)}",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.muted,
            )
            RuleCardDetails(status, colors, showForecast)
        }
    }
    val described = modifier.fillMaxWidth().semantics { stateDescription = levelLabel(status.level) }
    if (onClick != null) {
        Surface(onClick = onClick, shape = shape, color = colors.container, contentColor = colors.content, modifier = described) { content() }
    } else {
        Surface(shape = shape, color = colors.container, contentColor = colors.content, modifier = described) { content() }
    }
}
