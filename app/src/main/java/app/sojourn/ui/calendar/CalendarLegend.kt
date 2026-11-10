package app.sojourn.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.sojourn.ui.LocalAppActions
import app.sojourn.ui.SettingsSection

/** The four kinds of day, in the words used everywhere: what each looks like in the calendar. */
private enum class Kind(val word: String, val meaning: String) {
    RECORDED("Recorded", "Sojourn or you recorded where you were."),
    ASSUMED("Assumed", "Nothing recorded, but you were in the same country before and after. Striped."),
    PLANNED("Planned", "Days to come, from your plans and flights. Pale, with a dashed edge."),
    UNTRACKED("Untracked", "No country since you started. Fill them in from the list."),
}

/** A small tile drawn the way the calendar draws that kind of day. */
@Composable
private fun Swatch(kind: Kind, size: Int = 16) {
    val color = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outline
    val empty = MaterialTheme.colorScheme.surfaceContainerHighest
    Box(
        Modifier.size(size.dp).clip(RoundedCornerShape(4.dp)).drawBehind {
            when (kind) {
                Kind.RECORDED -> drawRect(color.copy(alpha = 0.9f))
                Kind.ASSUMED -> {
                    drawRect(color.copy(alpha = 0.25f))
                    hatch(color.copy(alpha = 0.85f), spacing = 4.dp.toPx(), width = 1.2.dp.toPx())
                }
                Kind.PLANNED -> {
                    drawRect(color.copy(alpha = 0.3f))
                    drawRoundRect(outline, cornerRadius = CornerRadius(4.dp.toPx()), style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx()))))
                }
                Kind.UNTRACKED -> drawRect(empty)
            }
        },
    )
}

/** A one-line key under the year view, where colours carry the meaning. */
@Composable
fun DayKey(modifier: Modifier = Modifier) {
    FlowRow(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Kind.entries.forEach { kind ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Swatch(kind, size = 12)
                Spacer(Modifier.width(6.dp))
                Text(kind.word, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * How to read the calendar, kept out of the way until asked for: the kinds of day, travel
 * days, faint flags and picking several days. A first-time user needs it once, not on every visit.
 */
@Composable
fun LegendSheet(onDismiss: () -> Unit) {
    val app = LocalAppActions.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LegendContent(onCounting = {
            onDismiss()
            app.settings(SettingsSection.COUNTING)
        })
    }
}

@Composable
fun LegendContent(onCounting: () -> Unit) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Reading the calendar", style = MaterialTheme.typography.titleLargeEmphasized, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.semantics { heading() })
        Kind.entries.forEach { kind -> Entry({ Swatch(kind, size = 20) }, kind.word, kind.meaning) }
        Entry(
            { TravelSwatch() },
            "Travel days",
            "A day in two countries. The month shows both flags; the year splits the tile corner to corner, from where the day started to where it ended.",
        )
        Entry(
            { Text("🇨🇾", fontSize = 18.sp, modifier = Modifier.alpha(0.35f)) },
            "Faint flags",
            "You were there, but that country doesn't count the day under its own rules, like the day you leave Cyprus.",
        ) { TextButton(onClick = onCounting, contentPadding = PaddingValues(0.dp)) { Text("Counting settings") } }
        Entry(
            { FlightSwatch() },
            "Flights",
            "A day with a flight. The month shows a small plane in the day; the year puts one on the corner of the day's tile. Tap the day to open it.",
        )
        Entry(
            { Box(Modifier.size(20.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.secondaryContainer)) },
            "Several days at once",
            "Long-press a day and drag. Then set, add or take off a country on all of them.",
        )
    }
}

/** A day tile with the year view's flight mark on its corner. */
@Composable
private fun FlightSwatch() {
    val color = MaterialTheme.colorScheme.primary
    Box(Modifier.size(24.dp)) {
        Box(Modifier.align(Alignment.BottomStart).size(20.dp).clip(RoundedCornerShape(4.dp)).background(color.copy(alpha = 0.9f)))
        FlightMark(Modifier.align(Alignment.TopEnd), size = 12.dp)
    }
}

@Composable
private fun TravelSwatch() {
    val first = MaterialTheme.colorScheme.primary
    val last = MaterialTheme.colorScheme.tertiary
    Box(
        Modifier.size(20.dp).clip(RoundedCornerShape(4.dp)).drawBehind {
            drawPath(Path().apply { moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(0f, size.height); close() }, first)
            drawPath(Path().apply { moveTo(size.width, 0f); lineTo(size.width, size.height); lineTo(0f, size.height); close() }, last)
        },
    )
}

@Composable
private fun Entry(mark: @Composable () -> Unit, title: String, body: String, action: (@Composable () -> Unit)? = null) {
    Row {
        Box(Modifier.width(40.dp).padding(top = 2.dp).clearAndSetSemantics { }, contentAlignment = Alignment.TopStart) { mark() }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(2.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            action?.invoke()
        }
    }
}
