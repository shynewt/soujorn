package app.sojourn.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.sojourn.domain.Meter

/**
 * A measurement against known thresholds, like "47 of 90 days" or Cyprus's 60 and 184 days.
 * This is a meter, not a progress indicator: nothing is loading, so it stays still and only
 * moves when the figure itself changes. Lived days are solid, planned days striped, and each
 * threshold gets a tick with its figure underneath.
 */
@Composable
fun DaysMeter(meter: Meter, color: Color, modifier: Modifier = Modifier, description: String? = null) {
    val scale = maxOf(meter.max, meter.value + meter.planned).coerceAtLeast(1).toFloat()
    val lived by animateFloatAsState((meter.value / scale).coerceIn(0f, 1f), MaterialTheme.motionScheme.defaultSpatialSpec(), label = "lived")
    val planned by animateFloatAsState((meter.planned / scale).coerceIn(0f, 1f), MaterialTheme.motionScheme.defaultSpatialSpec(), label = "planned")
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val tick = MaterialTheme.colorScheme.onSurface
    val gap = MaterialTheme.colorScheme.surface
    Column(
        modifier.fillMaxWidth().semantics(mergeDescendants = true) {
            progressBarRangeInfo = ProgressBarRangeInfo(meter.value.toFloat(), 0f..meter.max.toFloat())
            stateDescription = description ?: "${meter.value} days, thresholds at ${meter.marks.joinToString { "${it.at}" }}"
        },
    ) {
        Canvas(Modifier.fillMaxWidth().height(14.dp).padding(vertical = 2.dp)) {
            val radius = CornerRadius(size.height / 2)
            drawRoundRect(track, cornerRadius = radius)
            val livedWidth = size.width * lived
            if (livedWidth > 0f) drawRoundRect(color, size = Size(livedWidth.coerceAtLeast(size.height), size.height), cornerRadius = radius)
            val plannedWidth = size.width * planned
            if (plannedWidth > 0f) {
                val left = livedWidth
                drawRoundRect(color.copy(alpha = 0.3f), Offset(left, 0f), Size(plannedWidth, size.height), radius)
                clipRect(left = left, right = left + plannedWidth) {
                    var x = left - size.height
                    while (x < left + plannedWidth) {
                        drawLine(color.copy(alpha = 0.75f), Offset(x, size.height), Offset(x + size.height, 0f), strokeWidth = 1.5.dp.toPx())
                        x += 5.dp.toPx()
                    }
                }
            }
            meter.marks.forEach { mark ->
                val x = (size.width * mark.at / scale).coerceIn(1.5.dp.toPx(), size.width - 1.5.dp.toPx())
                // A sliver of the surface on each side keeps the tick readable over any fill.
                drawLine(gap, Offset(x, -4.dp.toPx()), Offset(x, size.height + 4.dp.toPx()), strokeWidth = 4.dp.toPx())
                drawLine(tick, Offset(x, -4.dp.toPx()), Offset(x, size.height + 4.dp.toPx()), strokeWidth = 2.dp.toPx())
            }
        }
        if (meter.marks.size > 1 || meter.marks.firstOrNull()?.let { it.at != meter.max } == true) {
            MarkLabels(meter, scale)
        }
    }
}

/** The figures under the ticks, each centred on its tick and kept inside the bar's width. */
@Composable
private fun MarkLabels(meter: Meter, scale: Float) {
    Layout(
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp).clearAndSetSemantics { },
        content = {
            meter.marks.forEach { mark ->
                Text("${mark.at}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0)) }
        val height = placeables.maxOfOrNull { it.height } ?: 0
        layout(constraints.maxWidth, height) {
            var lastEnd = Int.MIN_VALUE
            placeables.forEachIndexed { i, p ->
                val centre = (constraints.maxWidth * meter.marks[i].at / scale).toInt()
                val x = (centre - p.width / 2).coerceIn(0, constraints.maxWidth - p.width)
                // Labels that would collide are left out; the tick still shows.
                if (x >= lastEnd + 4) {
                    p.place(x, 0)
                    lastEnd = x + p.width
                }
            }
        }
    }
}
