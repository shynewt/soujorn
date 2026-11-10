package app.sojourn.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import app.sojourn.data.geo.WorldMapData

/** Map width over height, so the layout can reserve space before the borders have loaded. */
const val WORLD_ASPECT = 2.15f

/**
 * The world in the Equal Earth projection, visited countries in their summary colour. The view
 * frames the coloured countries, zooming out as they spread, and glides when they change.
 * Countries too small to see at the current zoom get a dot as well.
 */
@Composable
fun WorldMap(
    data: WorldMapData?,
    colors: Map<String, Color>,
    modifier: Modifier = Modifier,
    selected: String? = null,
    onCountryClick: ((String?) -> Unit)? = null,
) {
    val land = MaterialTheme.colorScheme.surfaceContainerHighest
    val borders = MaterialTheme.colorScheme.surfaceContainerLow
    val outline = MaterialTheme.colorScheme.onSurface
    val reveal by animateFloatAsState(if (data == null) 0f else 1f, label = "map")
    val landColor by animateColorAsState(land, label = "land")

    val paths = remember(data) {
        data?.countries?.map { country ->
            country.code to Path().apply {
                country.rings.forEach { ring ->
                    moveTo(ring[0] - data.minX, ring[1] - data.minY)
                    for (i in 1 until ring.size / 2) lineTo(ring[i * 2] - data.minX, ring[i * 2 + 1] - data.minY)
                    close()
                }
            }
        }.orEmpty()
    }
    // The canvas keeps the whole world's shape; only what it shows changes, so the card never jumps.
    val aspect = data?.let { it.width / it.height } ?: WORLD_ASPECT
    val target = remember(data, colors.keys) {
        data?.viewport(colors.keys, aspect)?.let { Rect(it[0], it[1], it[0] + it[2], it[1] + it[3]) } ?: Rect.Zero
    }
    // The first view appears as is; later ones glide there. No overshoot: a bounce past the
    // world's edge would flash empty space.
    val animation = remember { Animatable(Rect.Zero, Rect.VectorConverter) }
    val spec = MaterialTheme.motionScheme.slowEffectsSpec<Rect>()
    LaunchedEffect(target) {
        if (animation.value == Rect.Zero || target == Rect.Zero) animation.snapTo(target) else animation.animateTo(target, spec)
    }
    val view = animation.value
    val currentView by rememberUpdatedState(view)

    val tap = if (data != null && onCountryClick != null) {
        Modifier.pointerInput(data) {
            detectTapGestures { offset ->
                val shown = currentView
                if (shown.width <= 0f) return@detectTapGestures
                val scale = size.width / shown.width
                val x = offset.x / scale + shown.left
                val y = offset.y / scale + shown.top
                // Smallest first, so tapping an enclave picks it over the country around it.
                val hit = data.countries.sortedBy { it.area }.firstOrNull { it.contains(x, y) }
                onCountryClick(hit?.code)
            }
        }
    } else {
        Modifier
    }

    Canvas(modifier.fillMaxWidth().aspectRatio(aspect).clipToBounds().then(tap)) {
        if (data == null || view.width <= 0f) return@Canvas
        val scale = size.width / view.width
        translate(-(view.left - data.minX) * scale, -(view.top - data.minY) * scale) {
            scale(scale, scale, pivot = Offset.Zero) {
                val hairline = Stroke(width = 0.6.dp.toPx() / scale)
                paths.forEach { (code, path) ->
                    val color = colors[code] ?: landColor
                    drawPath(path, color, alpha = reveal)
                }
                paths.forEach { (_, path) -> drawPath(path, borders, style = hairline, alpha = reveal) }
                if (selected != null) {
                    paths.firstOrNull { it.first == selected }?.let { (_, path) ->
                        drawPath(path, outline, style = Stroke(width = 1.6.dp.toPx() / scale))
                    }
                }
            }
        }
        val dot = 3.dp.toPx()
        // Judged on screen, so Malta gets a dot on a world map but not once zoomed into Europe.
        val tiny = (8.dp.toPx() / scale).let { it * it }
        data.countries.forEach { country ->
            val color = colors[country.code] ?: return@forEach
            if (country.area > tiny) return@forEach
            val center = Offset((country.label[0] - view.left) * scale, (country.label[1] - view.top) * scale)
            drawCircle(borders, radius = dot + 1.5.dp.toPx(), center = center, alpha = reveal)
            drawCircle(color, radius = dot, center = center, alpha = reveal)
        }
    }
}
