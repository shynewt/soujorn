package app.sojourn.ui.plan

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.EditCalendar
import androidx.compose.material.icons.rounded.Flight
import androidx.compose.material.icons.rounded.FlightTakeoff
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.ToggleFloatingActionButtonDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sojourn.data.Countries
import app.sojourn.data.db.Plan
import app.sojourn.domain.RuleStatus
import app.sojourn.ui.LocalAppActions
import app.sojourn.ui.appViewModel
import app.sojourn.ui.components.BarSegment
import app.sojourn.ui.components.ChoiceGroup
import app.sojourn.ui.components.EmptyState
import app.sojourn.ui.components.FlagBadge
import app.sojourn.ui.components.Format
import app.sojourn.ui.components.LegendDot
import app.sojourn.ui.components.SectionHeader
import app.sojourn.ui.components.StackedBar
import app.sojourn.ui.components.Tag
import app.sojourn.ui.components.segmentedColors
import app.sojourn.ui.countryColor
import app.sojourn.ui.flights.FlightCard
import java.time.temporal.ChronoUnit

/** The Plan tab: the year ahead, its limits, and the flights and stays coming up. */
@Composable
fun PlanScreen() {
    val vm = appViewModel { PlanViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    PlanContent(state, vm::selectYear)
}

/** Plan as it looks for [state]; stateless, so screenshot tests can draw it. */
@Composable
fun PlanContent(state: PlanState, onYear: (Int) -> Unit) {
    val actions = LocalAppActions.current
    Scaffold(
        topBar = { TopAppBar(title = { Text("Plan", style = MaterialTheme.typography.titleLargeEmphasized) }) },
        floatingActionButton = { PlanMenu() },
    ) { padding ->
        LazyColumn(
            state = rememberLazyListState(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 112.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "years") {
                ChoiceGroup(listOf(state.today.year, state.today.year + 1).map { it to it.toString() }, state.year, onYear)
            }
            item(key = "forecast") { ForecastCard(state) }

            if (state.statuses.isNotEmpty()) {
                item(key = "limits") {
                    SectionHeader("Limits ahead") { TextButton(onClick = { actions.rules() }) { Text("All rules") } }
                }
                item(key = "limit-list") { LimitsAhead(state.statuses) }
            }

            if (state.flights.isNotEmpty()) {
                item(key = "flights-header") { SectionHeader("Upcoming flights") }
                items(state.flights, key = { "f-${it.id}" }) { flight ->
                    FlightCard(flight, state.today, onClick = { actions.flight(flight) })
                }
            }

            if (state.upcoming.isNotEmpty()) {
                item(key = "upcoming-header") { SectionHeader("Upcoming stays") }
                itemsIndexed(state.upcoming, key = { _, plan -> "p-${plan.id}" }) { index, plan ->
                    StayRow(plan, state, index)
                }
            } else if (state.loaded) {
                item(key = "empty") {
                    EmptyState(
                        icon = Icons.Rounded.FlightTakeoff,
                        title = if (state.flights.isEmpty()) "Plan the year ahead" else "No stays planned",
                        body = "Add the stays you have in mind. Sojourn checks them against your rules before you book anything.",
                        action = { FilledTonalButton(onClick = { actions.planStay() }) { Text("Plan a stay") } },
                    )
                }
            }
        }
    }
}

/** Add a flight or plan a stay; both open the shell's shared editors. */
@Composable
private fun PlanMenu() {
    val actions = LocalAppActions.current
    var open by rememberSaveable { mutableStateOf(false) }
    FloatingActionButtonMenu(
        expanded = open,
        button = {
            ToggleFloatingActionButton(checked = open, onCheckedChange = { open = it }) {
                val icon by remember { derivedStateOf { if (checkedProgress > 0.5f) Icons.Rounded.Close else Icons.Rounded.Add } }
                Icon(
                    icon,
                    contentDescription = if (open) "Close" else "Add to the plan",
                    modifier = with(ToggleFloatingActionButtonDefaults) { Modifier.animateIcon({ checkedProgress }) },
                )
            }
        },
    ) {
        FloatingActionButtonMenuItem(
            onClick = {
                open = false
                actions.flight()
            },
            icon = { Icon(Icons.Rounded.Flight, contentDescription = null) },
            text = { Text("Add a flight") },
        )
        FloatingActionButtonMenuItem(
            onClick = {
                open = false
                actions.planStay()
            },
            icon = { Icon(Icons.Rounded.EditCalendar, contentDescription = null) },
            text = { Text("Plan a stay") },
        )
    }
}

@Composable
private fun StayRow(plan: Plan, state: PlanState, index: Int) {
    val actions = LocalAppActions.current
    val until = ChronoUnit.DAYS.between(state.today, plan.start)
    SegmentedListItem(
        colors = segmentedColors(),
        verticalAlignment = Alignment.CenterVertically,
        onClick = { actions.planStay(plan) },
        shapes = ListItemDefaults.segmentedShapes(index, state.upcoming.size),
        leadingContent = { FlagBadge(plan.country, size = 44.dp) },
        overlineContent = { Text(if (until <= 0) "Now" else if (until == 1L) "Tomorrow" else "In ${Format.days(until.toInt())}") },
        supportingContent = {
            Text(
                listOf(Format.range(plan.start, plan.end, state.today), Format.days(ChronoUnit.DAYS.between(plan.start, plan.end).toInt() + 1), plan.note)
                    .filter { it.isNotBlank() }.joinToString(" · "),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
    ) { Text(Countries.name(plan.country), style = MaterialTheme.typography.titleMedium) }
}

/** The year by country: lived, planned, still open and untracked, in the app's one palette. */
@Composable
private fun ForecastCard(state: PlanState) {
    val actions = LocalAppActions.current
    val summary = state.summary ?: return
    val ranked = summary.countries.sortedByDescending { it.total }
    Surface(shape = RoundedCornerShape(32.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp).animateContentSize()) {
            Text("${state.year} at a glance", style = MaterialTheme.typography.headlineSmallEmphasized)
            Text(
                buildList {
                    add("${summary.trackedDays} lived")
                    add("${summary.plannedDays} planned")
                    if (state.open > 0) add("${state.open} open")
                    if (state.untracked > 0) add("${state.untracked} untracked")
                }.joinToString(" · ") + " days",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            val rest = MaterialTheme.colorScheme.surfaceContainerHighest
            val segments = ranked.flatMap {
                val color = countryColor(it.country)
                listOf(BarSegment(color, it.days.toFloat()), BarSegment(color, it.planned.toFloat(), planned = true))
            } + BarSegment(rest, (state.open + state.untracked).toFloat())
            StackedBar(segments, height = 20.dp)
            Spacer(Modifier.height(12.dp))
            if (ranked.isEmpty()) {
                Text("Nothing lived or planned in ${state.year} yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ranked.forEach { item ->
                Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    LegendDot(countryColor(item.country))
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "${Countries.flag(item.country)}  ${Countries.name(item.country)}",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (item.planned > 0) {
                        Tag("+${item.planned} planned")
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("${item.total}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
            }
            if (state.untracked > 0) {
                TextButton(onClick = { actions.calendar(list = true) }, modifier = Modifier.padding(top = 4.dp)) {
                    Text("Fill in ${if (state.untracked == 1) "1 untracked day" else "${state.untracked} untracked days"}")
                }
            }
        }
    }
}

/**
 * Each rule as it stands and where plans take it. The key date ("You can stay until 14 Mar")
 * comes first when the rule has one, since a date is easier to act on than a count.
 */
@Composable
private fun LimitsAhead(statuses: List<RuleStatus>) {
    val actions = LocalAppActions.current
    Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        statuses.forEachIndexed { index, status ->
            val forecast = status.forecast
            SegmentedListItem(
                colors = segmentedColors(),
                verticalAlignment = Alignment.CenterVertically,
                onClick = { actions.rules(status.rule.id) },
                shapes = ListItemDefaults.segmentedShapes(index, statuses.size),
                leadingContent = { LevelIcon(forecast?.level ?: status.level, size = 28.dp) },
                overlineContent = { Text(status.headline, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = {
                    Column {
                        Text(status.note ?: status.detail)
                        if (forecast != null) Text(forecast.text, style = MaterialTheme.typography.bodySmall)
                    }
                },
            ) { Text(status.rule.name, style = MaterialTheme.typography.titleMedium) }
        }
    }
}
