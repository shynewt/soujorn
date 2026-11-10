package app.sojourn.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.EventNote
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.sojourn.data.Countries
import app.sojourn.domain.Evidence
import app.sojourn.domain.EvidenceCount
import app.sojourn.domain.Goal
import app.sojourn.domain.Level
import app.sojourn.domain.RuleStatus
import app.sojourn.domain.TestKind
import app.sojourn.domain.TestStatus
import app.sojourn.ui.theme.StatusPalette

@Composable
internal fun RuleCardDetails(status: RuleStatus, colors: LevelColors, showForecast: Boolean) {
    status.meter?.let { meter ->
        Spacer(Modifier.height(14.dp))
        val fill = if (meter.minimum && status.level != Level.ALERT) {
            StatusPalette.forLevel(Level.GOOD, dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f).accent
        } else {
            colors.accent
        }
        DaysMeter(
            meter,
            fill,
            description = "${meter.value} days" + (if (meter.planned > 0) ", ${meter.planned} more planned" else "") +
                "; " + meter.marks.joinToString { "${it.label} at ${it.at}" },
        )
    }
    if (status.tests.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            status.tests.filter { !it.test.below || it.met }.forEach { TestLine(it, status.goal, colors) }
        }
    }
    status.evidence?.let { evidence ->
        Spacer(Modifier.height(12.dp))
        EvidenceBar(evidence, colors)
    }
    val lines = buildList {
        if (status.exempt.isNotEmpty()) {
            val names = status.exempt.map { Countries.name(it) }.sorted()
            val shown = if (names.size <= 3) names.joinToString(", ") else "${names.take(2).joinToString(", ")} and ${names.size - 2} more"
            add(Icons.Rounded.Home to "Not counted, you can live there: $shown")
        }
        status.note?.let { add(Icons.Rounded.Schedule to it) }
        status.extras.forEach { add(Icons.Rounded.Info to it) }
    }
    val forecast = status.forecast?.takeIf { showForecast }
    if (lines.isNotEmpty() || forecast != null) Spacer(Modifier.height(12.dp))
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        lines.forEach { (icon, text) -> NoteLine(icon, text, colors.muted) }
        forecast?.let { NoteLine(Icons.AutoMirrored.Rounded.EventNote, it.text, if (it.level == Level.GOOD) colors.muted else levelColor(it.level)) }
    }
}

/**
 * This tax year's counted days by how well each is backed up, darkest first: documented,
 * tracked, entered by hand, assumed. What would carry weight if the days were questioned.
 */
@Composable
private fun EvidenceBar(evidence: EvidenceCount, colors: LevelColors) {
    val shades = mapOf(Evidence.DOCUMENTED to 1f, Evidence.TRACKED to 0.6f, Evidence.MANUAL to 0.32f, Evidence.ASSUMED to 0.14f)
    val parts = Evidence.entries.map { it to evidence[it] }.filter { it.second > 0 }
    val text = parts.joinToString(" · ") { (kind, days) -> "$days ${kind.label.lowercase()}" }
    Column(Modifier.semantics(mergeDescendants = true) { contentDescription = "Evidence for the days counted: $text" }) {
        Row(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50))) {
            parts.forEach { (kind, days) ->
                Box(Modifier.weight(days.toFloat()).fillMaxHeight().background(colors.accent.copy(alpha = shades.getValue(kind))))
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Evidence: $text" + if (evidence.corroborated > 0) ". ${evidence.corroborated} backed by two kinds of source" else "",
            style = MaterialTheme.typography.bodySmall,
            color = colors.muted,
        )
    }
}

@Composable
private fun TestLine(status: TestStatus, goal: Goal?, colors: LevelColors) {
    val (icon, state) = when {
        status.test.below && status.met -> Icons.Rounded.CheckCircle to "Applies: not resident"
        status.met -> (if (goal == Goal.KEEP) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline) to "Met"
        !status.possible -> Icons.Rounded.Block to "Doesn't apply"
        status.outOfReach -> Icons.Rounded.Block to "Not enough days left"
        status.daysMet -> Icons.AutoMirrored.Rounded.HelpOutline to "Days reached"
        else -> Icons.Rounded.RadioButtonUnchecked to if (status.test.kind == TestKind.DECLARED) "Not answered" else "${status.left} to go"
    }
    val reason = when {
        status.test.kind == TestKind.DECLARED -> null
        status.failing.isNotEmpty() -> status.failing.first()
        status.outOfReach -> "At most ${status.maximum} days this tax year; ${status.needed} needed."
        status.pending.isNotEmpty() -> "If " + status.pending.joinToString(" and ") { it.text.replaceFirstChar(Char::lowercaseChar) }
        else -> null
    }
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.semantics(mergeDescendants = true) {}) {
        Icon(icon, contentDescription = state, tint = colors.muted, modifier = Modifier.padding(top = 1.dp).size(16.dp))
        Column(Modifier.padding(start = 8.dp)) {
            Text(
                buildString {
                    append(status.test.name)
                    if (status.test.indicative) append(" (a guide only)")
                    if (status.test.kind != TestKind.DECLARED) append(" · ${status.detail}")
                },
                style = MaterialTheme.typography.bodySmall,
                color = colors.content,
            )
            reason?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.muted) }
        }
    }
}

@Composable
private fun NoteLine(icon: ImageVector, text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = color, modifier = Modifier.padding(start = 8.dp))
    }
}
