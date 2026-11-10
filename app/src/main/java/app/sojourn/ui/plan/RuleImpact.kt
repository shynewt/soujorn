package app.sojourn.ui.plan

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.sojourn.domain.Level
import app.sojourn.domain.RuleStatus
import app.sojourn.ui.components.levelColor

/** The icon for a level, so it never reads by colour alone. */
fun levelIcon(level: Level): ImageVector = when (level) {
    Level.GOOD -> Icons.Rounded.CheckCircle
    Level.CAUTION -> Icons.Rounded.WarningAmber
    Level.ALERT -> Icons.Rounded.ErrorOutline
}

@Composable
fun LevelIcon(level: Level, modifier: Modifier = Modifier, size: Dp = 22.dp) {
    Icon(levelIcon(level), contentDescription = null, tint = levelColor(level), modifier = modifier.size(size))
}

/**
 * What a stay or flight being edited would do to each rule ("With this stay"), shown in both
 * editors before saving so a plan that breaks a limit is caught before it's booked.
 */
@Composable
fun RuleImpact(title: String, statuses: List<RuleStatus>, modifier: Modifier = Modifier) {
    AnimatedVisibility(statuses.any { it.forecast != null }, modifier) {
        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            statuses.forEach { status ->
                val forecast = status.forecast ?: return@forEach
                Row(verticalAlignment = Alignment.Top) {
                    LevelIcon(forecast.level, Modifier.padding(top = 1.dp))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(status.rule.name, style = MaterialTheme.typography.labelLarge)
                        Text(forecast.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
