package app.sojourn.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.sojourn.data.CountrySet
import app.sojourn.domain.Level
import app.sojourn.domain.ResidencyDayLimit
import app.sojourn.domain.RuleEngine
import app.sojourn.domain.RuleStatus
import app.sojourn.ui.theme.StatusColors
import app.sojourn.ui.theme.StatusPalette

@Composable
internal fun ResidencyDayLimitCard(
    status: RuleStatus,
    limit: ResidencyDayLimit,
    modifier: Modifier,
    showForecast: Boolean,
    onClick: (() -> Unit)?,
) {
    var expanded by rememberSaveable(status.rule.id) { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < 0.5f
    val colors = StatusPalette.forLevel(status.level, dark)
    val shape = RoundedCornerShape(28.dp)
    val described = modifier.fillMaxWidth().semantics { stateDescription = status.headline }
    val content: @Composable () -> Unit = {
        Column(Modifier.animateContentSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(status.rule.name, style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
                    Text(status.headline, style = MaterialTheme.typography.headlineSmallEmphasized, color = colors.accent)
                }
                Icon(
                    when (status.level) {
                        Level.GOOD -> Icons.Rounded.CheckCircle
                        Level.CAUTION -> Icons.Rounded.WarningAmber
                        Level.ALERT -> Icons.Rounded.ErrorOutline
                    },
                    contentDescription = levelLabel(status.level),
                    tint = colors.accent,
                    modifier = Modifier.size(28.dp),
                )
            }
            status.note?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                DayFigure("So far · ${RuleEngine.yearLabel(status.window, status.rule.yearStart)}", limit.used, Modifier.weight(1f))
                if (limit.projected > limit.used) {
                    DayFigure("With plans · +${limit.projected - limit.used}", limit.projected, Modifier.weight(1f))
                }
            }
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
                Text(if (expanded) "Hide details" else "Details")
                Spacer(Modifier.size(4.dp))
                Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null)
            }
            if (expanded) {
                if (limit.needsAbsenceReview) {
                    AlternativeCount(limit, CountrySet.describe(status.rule.countries), StatusPalette.forLevel(Level.CAUTION, dark))
                }
                RuleCardDetails(status, levelColors(status.level), showForecast)
            }
        }
    }
    if (onClick != null) {
        Surface(onClick = onClick, shape = shape, color = scheme.surfaceContainerLow, modifier = described) { content() }
    } else {
        Surface(shape = shape, color = scheme.surfaceContainerLow, modifier = described) { content() }
    }
}

@Composable
private fun DayFigure(label: String, days: Int, modifier: Modifier) {
    Column(modifier.semantics(mergeDescendants = true) {}) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("$days days", style = MaterialTheme.typography.titleLargeEmphasized, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun AlternativeCount(limit: ResidencyDayLimit, country: String, colors: StatusColors) {
    Surface(shape = RoundedCornerShape(16.dp), color = colors.container, contentColor = colors.content) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Rounded.WarningAmber, contentDescription = null, modifier = Modifier.size(20.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Trips outside $country", style = MaterialTheme.typography.labelLarge)
                val withAbsences = limit.withAbsences
                Text(
                    if (withAbsences != null) {
                        "$withAbsences days if return trips outside $country are counted too."
                    } else {
                        "Your current trip outside $country could add days if you return."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("Not included in your current day count.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
