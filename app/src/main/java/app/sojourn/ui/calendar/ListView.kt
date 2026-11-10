package app.sojourn.ui.calendar

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Flight
import androidx.compose.material.icons.rounded.QuestionMark
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
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
import app.sojourn.domain.DayKind
import app.sojourn.domain.Leg
import app.sojourn.ui.LocalAppActions
import app.sojourn.ui.components.EmptyState
import app.sojourn.ui.components.FlagBadge
import app.sojourn.ui.components.Format
import app.sojourn.ui.components.Tag
import app.sojourn.ui.flights.flightTimes
import java.time.LocalDate

/** Where "Now" sits in the list, for opening there and for "Go to today". */
internal fun StayListState.nowIndex(): Int = if (ahead.isEmpty()) 0 else 1 + ahead.size

/** The width of the line down the left, which carries each stay's flag. */
private val RAIL = 48.dp

/**
 * The Calendar's list view (the old Timeline): stays newest first, the crossings between them,
 * and untracked days with "Fill in", under sticky Ahead / Now / year headers. It opens at "Now",
 * with the next crossing just above it.
 *
 * Built to be read in one glance: where (flag and country), when (dates) and how long (one
 * figure). Anything else about a stay is one short muted line, only when it adds something;
 * crossings are slim connectors between the stays rather than rows of their own weight.
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
            stickyHeader(key = "h-$year") { Header(year.toString(), year = true) }
            // Under a year's header the dates needn't repeat the year.
            rows(items, LocalDate.of(year, 1, 1), onFill, onStay, onCrossing)
        }
    }
}

private fun LazyListScope.rows(
    items: List<StayListItem>,
    reference: LocalDate,
    onFill: (DateRange) -> Unit,
    onStay: (StayItem) -> Unit,
    onCrossing: (Leg.Crossing) -> Unit,
) {
    items(items, key = { it.key }) { item ->
        when (item) {
            is StayListItem.StayRow -> StayRow(item.stay, reference) { onStay(item.stay) }
            is StayListItem.CrossingRow -> CrossingRow(item.crossing, reference) { onCrossing(item.crossing) }
            is StayListItem.GapRow -> GapRow(item.range, reference) { onFill(item.range) }
        }
    }
}

/** Ahead and Now in the accent colour; a year as a large plain heading, so years stand apart. */
@Composable
private fun Header(text: String, year: Boolean = false) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Text(
            text,
            style = if (year) MaterialTheme.typography.headlineSmallEmphasized else MaterialTheme.typography.titleMediumEmphasized,
            color = if (year) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp, top = if (year) 24.dp else 16.dp, bottom = 8.dp).semantics { heading() },
        )
    }
}

/** The line down the left that joins stays through their crossings. */
@Composable
private fun Rail(dashed: Boolean = false, content: @Composable () -> Unit) {
    val color = MaterialTheme.colorScheme.outlineVariant
    Box(
        Modifier.width(RAIL).fillMaxHeight().drawBehind {
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

/**
 * A stay: country and dates on the left, its length as the one figure on the right. Below, in
 * one muted line and only when they differ from that figure: the days its country counts, the
 * days still to come, and the country's total for its tax year.
 */
@Composable
private fun StayRow(stay: StayItem, reference: LocalDate, onClick: () -> Unit) {
    val leg = stay.leg
    val planned = leg.kind == DayKind.PLANNED
    val details = buildList {
        if (leg.counted != leg.present) add("${leg.counted} counted")
        if (leg.planned > 0 && !planned) add("${leg.planned} still to come")
        if (stay.yearTotal != leg.counted) add("${Format.days(stay.yearTotal)} in ${stay.yearLabel}")
    }
    Row(Modifier.height(IntrinsicSize.Min)) {
        Rail(dashed = planned) { FlagBadge(leg.country, size = 40.dp, alpha = if (planned) 0.6f else 1f) }
        Spacer(Modifier.width(8.dp))
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(20.dp),
            color = when {
                stay.now -> MaterialTheme.colorScheme.primaryContainer
                planned -> Color.Transparent
                else -> MaterialTheme.colorScheme.surfaceContainerLow
            },
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 3.dp)
                .then(if (planned) Modifier.dashedOutline(MaterialTheme.colorScheme.outlineVariant, 20.dp) else Modifier),
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            Countries.name(leg.country),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        when {
                            stay.now -> Tag("Now", Modifier.padding(start = 8.dp), MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary)
                            leg.kind == DayKind.INFERRED -> Tag("Assumed", Modifier.padding(start = 8.dp))
                        }
                    }
                    Text(Format.days(leg.present), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.padding(start = 12.dp))
                }
                Text(Format.range(leg.start, leg.presentEnd, reference), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (details.isNotEmpty()) {
                    Text(
                        details.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

/** A dashed edge, for stays that are only planned. */
private fun Modifier.dashedOutline(color: Color, radius: Dp): Modifier = drawBehind {
    drawRoundRect(
        color = color,
        cornerRadius = CornerRadius(radius.toPx()),
        style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
    )
}

/**
 * Where one stay gave way to the next: a slim connector on the rail with the date, and the
 * flight's number and times when there was one. The countries are the stays either side.
 */
@Composable
private fun CrossingRow(crossing: Leg.Crossing, reference: LocalDate, onClick: () -> Unit) {
    val via = crossing.path.drop(1).dropLast(1).distinct()
    val text = buildList {
        add(Format.short(crossing.date, reference))
        if (crossing.flights.isEmpty()) {
            add("${Countries.name(crossing.from)} to ${Countries.name(crossing.to)}")
        } else {
            crossing.flights.forEach { f -> add(listOfNotNull(Airlines.format(f.number).ifBlank { null }, flightTimes(f)).joinToString("  ")) }
        }
        if (via.isNotEmpty()) add("via " + via.joinToString { if (it in crossing.transit) "${Countries.name(it)} (transit)" else Countries.name(it) })
    }.filter { it.isNotBlank() }.joinToString(" · ")
    Row(Modifier.height(IntrinsicSize.Min)) {
        Rail(dashed = crossing.planned) {
            if (crossing.flights.isNotEmpty()) {
                Box(Modifier.size(22.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Flight, contentDescription = "Flight", tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(14.dp).rotate(45f))
                }
            } else {
                Box(Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.outline))
            }
        }
        Spacer(Modifier.width(8.dp))
        Surface(onClick = onClick, shape = RoundedCornerShape(12.dp), color = Color.Transparent, modifier = Modifier.weight(1f)) {
            Box(Modifier.padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
                Text(
                    text,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Days nobody recorded: plainly marked on a dashed rail, with Fill in to say where you were. */
@Composable
private fun GapRow(range: DateRange, reference: LocalDate, onFill: () -> Unit) {
    Row(Modifier.height(IntrinsicSize.Min)) {
        Rail(dashed = true) {
            Box(Modifier.size(24.dp).clip(CircleShape).background(MaterialTheme.colorScheme.tertiaryContainer), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.QuestionMark, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.size(14.dp))
            }
        }
        Spacer(Modifier.width(8.dp))
        Row(
            Modifier
                .weight(1f)
                .padding(vertical = 3.dp)
                .dashedOutline(MaterialTheme.colorScheme.outline, 20.dp)
                .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("${Format.days(range.days)} untracked", style = MaterialTheme.typography.titleSmall)
                Text(Format.range(range.start, range.end, reference), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FilledTonalButton(onClick = onFill) { Text("Fill in") }
        }
    }
}
