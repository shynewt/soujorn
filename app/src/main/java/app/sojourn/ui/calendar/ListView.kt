package app.sojourn.ui.calendar

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Flight
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.sojourn.data.Countries
import app.sojourn.data.flights.Airlines
import app.sojourn.domain.DateRange
import app.sojourn.domain.DayCounting
import app.sojourn.domain.DayKind
import app.sojourn.domain.Leg
import app.sojourn.ui.LocalAppActions
import app.sojourn.ui.components.EmptyState
import app.sojourn.ui.components.FlagBadge
import app.sojourn.ui.components.Format
import app.sojourn.ui.components.ShapeIcon
import app.sojourn.ui.components.Tag
import app.sojourn.ui.flights.flightTimes
import java.time.LocalDate

/** Where "Now" sits in the list, for opening there and for "Go to today". */
internal fun StayListState.nowIndex(): Int = if (ahead.isEmpty()) 0 else 1 + ahead.size

/**
 * The Calendar's list view (the old Timeline): stays newest first, the crossings between them,
 * and untracked days with "Fill in", under sticky Ahead / Now / year headers. It opens at "Now",
 * with the next crossing just above it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StayListView(
    state: StayListState,
    list: LazyListState,
    onFill: (DateRange) -> Unit,
    onStay: (StayItem) -> Unit,
    onCrossing: (Leg.Crossing) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.loaded && state.isEmpty) {
        val app = LocalAppActions.current
        EmptyState(
            icon = Icons.Rounded.Route,
            title = "Your journey starts here",
            body = "Stays show up here as days are recorded. Import photos, location history or your calendar to fill in the past.",
            modifier = modifier,
            action = { FilledTonalButton(onClick = app::import) { Text("Import") } },
        )
        return
    }
    var positioned by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.loaded) {
        if (state.loaded && !positioned) {
            list.scrollToItem((state.nowIndex() - 2).coerceAtLeast(0))
            positioned = true
        }
    }
    LazyColumn(state = list, modifier = modifier, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
        if (state.ahead.isNotEmpty()) {
            stickyHeader(key = "h-ahead") { Header("Ahead") }
            rows(state.ahead, state.today, onFill, onStay, onCrossing)
        }
        state.now?.let { now ->
            stickyHeader(key = "h-now") { Header("Now") }
            item(key = "now") { StayRow(now, state.today) { onStay(now) } }
        }
        state.past.forEach { (year, items) ->
            stickyHeader(key = "h-$year") { Header(year.toString()) }
            rows(items, state.today, onFill, onStay, onCrossing)
        }
    }
}

private fun LazyListScope.rows(
    items: List<StayListItem>,
    today: LocalDate,
    onFill: (DateRange) -> Unit,
    onStay: (StayItem) -> Unit,
    onCrossing: (Leg.Crossing) -> Unit,
) {
    items(items, key = { it.key }) { item ->
        when (item) {
            is StayListItem.StayRow -> StayRow(item.stay, today) { onStay(item.stay) }
            is StayListItem.CrossingRow -> CrossingRow(item.crossing, today) { onCrossing(item.crossing) }
            is StayListItem.GapRow -> GapRow(item.range, today) { onFill(item.range) }
        }
    }
}

@Composable
private fun Header(text: String) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Text(
            text,
            style = MaterialTheme.typography.titleMediumEmphasized,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 8.dp).semantics { heading() },
        )
    }
}

/** The line down the left that joins stays through their crossings. */
@Composable
private fun Rail(width: Dp, dashed: Boolean = false, content: @Composable () -> Unit) {
    val color = MaterialTheme.colorScheme.outlineVariant
    Box(
        Modifier.width(width).fillMaxHeight().drawBehind {
            drawLine(
                color,
                Offset(size.width / 2, 0f),
                Offset(size.width / 2, size.height),
                strokeWidth = 2.dp.toPx(),
                pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())) else null,
            )
        },
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun StayRow(stay: StayItem, today: LocalDate, onClick: () -> Unit) {
    val leg = stay.leg
    val planned = leg.kind == DayKind.PLANNED
    Row(Modifier.height(IntrinsicSize.Min)) {
        Rail(56.dp, dashed = planned) { FlagBadge(leg.country, size = 48.dp, alpha = if (planned) 0.6f else 1f) }
        Spacer(Modifier.width(8.dp))
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(24.dp),
            color = if (stay.now) MaterialTheme.colorScheme.primaryContainer else if (planned) MaterialTheme.colorScheme.surfaceContainerLowest else MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.weight(1f).padding(vertical = 4.dp),
        ) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(Countries.name(leg.country), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        when {
                            stay.now -> Tag("Now", Modifier.padding(start = 8.dp), MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary)
                            planned -> Tag("Planned", Modifier.padding(start = 8.dp))
                            leg.kind == DayKind.INFERRED -> Tag("Assumed", Modifier.padding(start = 8.dp))
                        }
                    }
                    Text(Format.range(leg.start, leg.presentEnd, today), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val presentLine = buildString {
                        append(Format.days(leg.present))
                        append(" there")
                        if (leg.counted != leg.present) append(" · ${leg.counted} counted (${stay.counting.short()})")
                        if (leg.planned > 0 && !planned) append(" · ${leg.planned} planned")
                    }
                    Text(presentLine, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${stay.yearLabel} so far: ${Format.days(stay.yearTotal)} in ${Countries.name(leg.country)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 12.dp)) {
                    Text("${leg.counted}", style = MaterialTheme.typography.headlineSmallEmphasized, fontWeight = FontWeight.SemiBold)
                    Text(if (leg.counted == 1) "day" else "days", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun DayCounting.short(): String = when (this) {
    DayCounting.ANY_PART -> "any part of a day"
    DayCounting.MIDNIGHT -> "at midnight"
    DayCounting.ARRIVAL_DAY -> "departure day not counted"
    DayCounting.MORNING -> "arrival day not counted"
    DayCounting.FULL_DAYS -> "full days only"
}

/** Where one country gave way to the next: the date, the route, and the flight if there was one. */
@Composable
private fun CrossingRow(crossing: Leg.Crossing, today: LocalDate, onClick: () -> Unit) {
    Row(Modifier.height(IntrinsicSize.Min)) {
        Rail(56.dp, dashed = crossing.planned) {
            ShapeIcon(
                if (crossing.flights.isNotEmpty()) Icons.Rounded.Flight else Icons.Rounded.SwapHoriz,
                size = 28.dp,
                shape = CircleShape,
                container = MaterialTheme.colorScheme.secondaryContainer,
                content = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        Spacer(Modifier.width(8.dp))
        Surface(onClick = onClick, shape = RoundedCornerShape(16.dp), color = Color.Transparent, modifier = Modifier.weight(1f)) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(
                    crossing.path.joinToString("  →  ") { "${Countries.flag(it)} ${if (it in crossing.transit) "${Countries.name(it)} (transit)" else Countries.name(it)}" },
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val flights = crossing.flights.joinToString(", ") { f -> listOfNotNull(Airlines.format(f.number).ifBlank { null }, flightTimes(f)).joinToString(" ") }
                Text(
                    listOf(Format.short(crossing.date, today), flights).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun GapRow(range: DateRange, today: LocalDate, onFill: () -> Unit) {
    val outline = MaterialTheme.colorScheme.outline
    Row(Modifier.height(IntrinsicSize.Min)) {
        Rail(56.dp, dashed = true) {
            ShapeIcon(
                Icons.AutoMirrored.Rounded.HelpOutline,
                size = 36.dp,
                shape = MaterialShapes.Sunny.toShape(),
                container = MaterialTheme.colorScheme.tertiaryContainer,
                content = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
        Spacer(Modifier.width(8.dp))
        Row(
            Modifier
                .weight(1f)
                .padding(vertical = 4.dp)
                .drawBehind {
                    drawRoundRect(
                        color = outline,
                        cornerRadius = CornerRadius(24.dp.toPx()),
                        style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx()))),
                    )
                }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Untracked · ${Format.days(range.days)}", style = MaterialTheme.typography.titleSmall)
                Text(Format.range(range.start, range.end, today), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FilledTonalButton(onClick = onFill) { Text("Fill in") }
        }
    }
}
