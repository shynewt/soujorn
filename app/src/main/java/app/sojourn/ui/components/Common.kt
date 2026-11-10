package app.sojourn.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ToggleButton
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.sojourn.data.Countries
import app.sojourn.domain.Level

/** A flag emoji set in one of the expressive shapes. */
@Composable
fun FlagBadge(
    code: String,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    shape: Shape = MaterialShapes.Cookie9Sided.toShape(),
    container: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    alpha: Float = 1f,
) {
    Box(
        modifier = modifier.size(size).clip(shape).background(container.copy(alpha = container.alpha * alpha)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = Countries.flag(code),
            fontSize = (size.value * 0.5f).sp,
            modifier = Modifier.padding(top = 1.dp).alpha(alpha),
        )
    }
}

/** An icon in a soft expressive shape, used to lead list rows and empty states. */
@Composable
fun ShapeIcon(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    shape: Shape = MaterialShapes.Cookie6Sided.toShape(),
    container: Color = MaterialTheme.colorScheme.secondaryContainer,
    content: Color = MaterialTheme.colorScheme.onSecondaryContainer,
) {
    Box(modifier.size(size).clip(shape).background(container), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(size * 0.5f))
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(
        modifier = modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMediumEmphasized,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        action?.invoke()
    }
}

data class BarSegment(val color: Color, val value: Float, val planned: Boolean = false)

/**
 * Proportional bar of separate rounded segments. Planned segments are drawn translucent with
 * stripes, so the future reads as softer than what already happened.
 */
@Composable
fun StackedBar(segments: List<BarSegment>, modifier: Modifier = Modifier, height: Dp = 16.dp, gap: Dp = 3.dp) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Canvas(modifier.fillMaxWidth().height(height)) {
        val total = segments.sumOf { it.value.toDouble() }.toFloat()
        val radius = CornerRadius(size.height / 2, size.height / 2)
        if (total <= 0f) {
            drawRoundRect(track, cornerRadius = radius)
            return@Canvas
        }
        val gapPx = gap.toPx()
        val visible = segments.filter { it.value > 0 }
        val usable = size.width - gapPx * (visible.size - 1).coerceAtLeast(0)
        run {
            var x = 0f
            visible.forEach { segment ->
                val width = (usable * segment.value / total).coerceAtLeast(2f)
                val segmentRadius = CornerRadius(minOf(radius.x, width / 2), minOf(radius.y, width / 2))
                if (segment.planned) {
                    drawRoundRect(segment.color.copy(alpha = 0.35f), Offset(x, 0f), Size(width, size.height), segmentRadius)
                    clipRect(left = x, right = x + width) {
                        var stripe = x - size.height
                        while (stripe < x + width) {
                            drawLine(segment.color.copy(alpha = 0.7f), Offset(stripe, size.height), Offset(stripe + size.height, 0f), strokeWidth = 1.5.dp.toPx())
                            stripe += 5.dp.toPx()
                        }
                    }
                } else {
                    drawRoundRect(segment.color, Offset(x, 0f), Size(width, size.height), segmentRadius)
                }
                x += width + gapPx
            }
        }
    }
}

@Composable
fun LegendDot(color: Color, modifier: Modifier = Modifier, size: Dp = 10.dp) {
    Box(modifier.size(size).clip(MaterialShapes.Circle.toShape()).background(color))
}

@Composable
fun levelColor(level: Level): Color = when (level) {
    Level.GOOD -> MaterialTheme.colorScheme.primary
    Level.CAUTION -> MaterialTheme.colorScheme.tertiary
    Level.ALERT -> MaterialTheme.colorScheme.error
}

@Composable
fun levelContainer(level: Level): Color = levelColors(level).container

/**
 * One choice out of a few, as a connected button group (Material's replacement for segmented
 * buttons), announced as radio buttons so screen readers say it's a choice of one.
 */
@Composable
fun <T> ChoiceGroup(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        options.forEachIndexed { index, (value, label) ->
            ToggleButton(
                checked = selected == value,
                onCheckedChange = { onSelect(value) },
                modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
            ) { Text(label, maxLines = 1, softWrap = false) }
        }
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ShapeIcon(
            icon,
            size = 88.dp,
            shape = MaterialShapes.SoftBurst.toShape(),
            container = MaterialTheme.colorScheme.primaryContainer,
            content = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        Spacer(Modifier.height(4.dp))
        Text(title, style = MaterialTheme.typography.titleLargeEmphasized, textAlign = TextAlign.Center)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        action?.let {
            Spacer(Modifier.width(4.dp))
            it()
        }
    }
}

/**
 * Segmented rows default to the plain surface, which is this app's background; tint them like
 * the cards instead, one step higher inside sheets, which are tinted themselves.
 */
@Composable
fun segmentedColors(onSheet: Boolean = false) = ListItemDefaults.segmentedColors(
    containerColor = if (onSheet) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLow,
    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
    selectedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
)

/** A small rounded label, e.g. "Planned" or "Assumed". */
@Composable
fun Tag(text: String, modifier: Modifier = Modifier, container: Color = MaterialTheme.colorScheme.secondaryContainer, content: Color = MaterialTheme.colorScheme.onSecondaryContainer) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = content,
        modifier = modifier.clip(RoundedCornerShape(50)).background(container).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}
