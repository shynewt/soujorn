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
import androidx.compose.foundation.layout.size
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
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FlightTakeoff
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.ToggleFloatingActionButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import app.sojourn.data.db.Flight
import app.sojourn.data.db.Plan
import app.sojourn.ui.flights.FlightCard
import app.sojourn.ui.flights.FlightEditorSheet
import app.sojourn.domain.Level
import app.sojourn.domain.RuleStatus
import app.sojourn.ui.components.segmentedColors
import app.sojourn.ui.appViewModel
import app.sojourn.ui.components.BarSegment
import app.sojourn.ui.components.EmptyState
import app.sojourn.ui.components.FlagBadge
import app.sojourn.ui.components.ChoiceGroup
import app.sojourn.ui.components.Format
import app.sojourn.ui.components.LegendDot
import app.sojourn.ui.components.SectionHeader
import app.sojourn.ui.components.StackedBar
import app.sojourn.ui.components.Tag
import app.sojourn.ui.components.levelColor
import app.sojourn.ui.theme.CountryPalette
import java.time.temporal.ChronoUnit

@Composable
fun PlanScreen() {
    val actions = app.sojourn.ui.LocalAppActions.current
    PlanScreen(openRules = { actions.rules() })
}

@Composable
private fun PlanScreen(openRules: () -> Unit) {
    val vm = appViewModel { PlanViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Plan?>(null) }
    // Holds the flight being edited, or NEW_FLIGHT for a new one.
    var flight by remember { mutableStateOf<Flight?>(null) }
    PlanContent(
        state,
        vm::selectYear,
        onEdit = { editing = it },
        onConfirm = vm::confirm,
        onDelete = vm::delete,
        openRules = openRules,
        onFlight = { flight = it ?: NEW_FLIGHT },
    )
    flight?.let { current ->
        FlightEditorSheet(
            initial = current.takeIf { it !== NEW_FLIGHT },
            recentAirports = state.recentAirports,
            preview = vm::previewFlight,
            onDismiss = { flight = null },
            onSave = {
                vm.saveFlight(it)
                flight = null
            },
            onDelete = {
                vm.deleteFlight(it)
                flight = null
            },
        )
    }
    editing?.let { plan ->
        PlanEditorSheet(
            initial = plan,
            recents = state.recents,
            preview = vm::preview,
            onDismiss = { editing = null },
            onSave = {
                vm.save(it)
                editing = null
            },
            onDelete = {
                vm.delete(it)
                editing = null
            },
        )
    }
}

/** A new plan starts out empty; id 0 tells the editor it isn't saved yet. */
private val NEW_PLAN = Plan(country = "", start = java.time.LocalDate.MIN, end = java.time.LocalDate.MIN)

/** Marks "add a flight" in the editor state; never saved. */
private val NEW_FLIGHT = Flight(number = "", origin = "", destination = "", originCountry = "", destinationCountry = "", departureDate = java.time.LocalDate.MIN, arrivalDate = java.time.LocalDate.MIN)

@Composable
fun PlanContent(
    state: PlanState,
    onYear: (Int) -> Unit,
    onEdit: (Plan) -> Unit,
    onConfirm: (Plan) -> Unit,
    onDelete: (Plan) -> Unit,
    openRules: () -> Unit,
    onFlight: (Flight?) -> Unit = {},
) {
    val list = rememberLazyListState()
    Scaffold(
        topBar = { TopAppBar(title = { Text("Plan", style = MaterialTheme.typography.titleLargeEmphasized) }) },
        floatingActionButton = {
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
                        onFlight(null)
                    },
                    icon = { Icon(Icons.Rounded.Flight, contentDescription = null) },
                    text = { Text("Add a flight") },
                )
                FloatingActionButtonMenuItem(
                    onClick = {
                        open = false
                        onEdit(NEW_PLAN)
                    },
                    icon = { Icon(Icons.Rounded.EditCalendar, contentDescription = null) },
                    text = { Text("Plan a stay") },
                )
            }
        },
    ) { padding ->
        LazyColumn(
            state = list,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "years") {
                val years = listOf(state.today.year, state.today.year + 1)
                ChoiceGroup(years.map { it to it.toString() }, state.year, onYear)
            }
            item(key = "forecast") { ForecastCard(state) }

            val forecasts = state.statuses
            if (forecasts.isNotEmpty()) {
                item(key = "limits") {
                    SectionHeader("Limits ahead") { TextButton(onClick = openRules) { Text("Rules") } }
                }
                item(key = "limit-list") { LimitsAhead(forecasts) }
            }

            if (state.unconfirmed.isNotEmpty()) {
                item(key = "unconfirmed-header") { SectionHeader("Did these happen?") }
                itemsIndexed(state.unconfirmed, key = { _, plan -> "u-${plan.id}" }) { index, plan ->
                    SegmentedListItem(
                        colors = segmentedColors(),
                        verticalAlignment = Alignment.CenterVertically,
                        shapes = ListItemDefaults.segmentedShapes(index, state.unconfirmed.size),
                        leadingContent = { FlagBadge(plan.country, size = 40.dp) },
                        supportingContent = { Text(Format.range(plan.start, plan.end, state.today)) },
                        trailingContent = {
                            Row {
                                TextButton(onClick = { onDelete(plan) }) { Text("No") }
                                FilledTonalButton(onClick = { onConfirm(plan) }) { Text("Yes") }
                            }
                        },
                    ) { Text(Countries.name(plan.country)) }
                }
            }

            if (state.flights.isNotEmpty()) {
                item(key = "flights-header") { SectionHeader("Flights") }
                items(state.flights, key = { "f-${it.id}" }) { FlightCard(it, state.today, onClick = { onFlight(it) }) }
            }

            item(key = "upcoming-header") { SectionHeader("Upcoming stays") }
            if (state.upcoming.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        icon = Icons.Rounded.FlightTakeoff,
                        title = "Plan the year ahead",
                        body = "Add the stays you have in mind. Sojourn checks them against your limits before you book anything.",
                    )
                }
            } else {
                itemsIndexed(state.upcoming, key = { _, plan -> "p-${plan.id}" }) { index, plan ->
                    val now = state.today
                    val until = ChronoUnit.DAYS.between(now, plan.start)
                    SegmentedListItem(
                        colors = segmentedColors(),
                        verticalAlignment = Alignment.CenterVertically,
                        onClick = { onEdit(plan) },
                        shapes = ListItemDefaults.segmentedShapes(index, state.upcoming.size),
                        leadingContent = { FlagBadge(plan.country, size = 44.dp) },
                        overlineContent = { Text(if (until <= 0) "Now" else if (until == 1L) "Tomorrow" else "In ${Format.days(until.toInt())}") },
                        supportingContent = {
                            Text(
                                listOf(Format.range(plan.start, plan.end, now), Format.days(ChronoUnit.DAYS.between(plan.start, plan.end).toInt() + 1), plan.note)
                                    .filter { it.isNotBlank() }.joinToString(" · "),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    ) { Text(Countries.name(plan.country), style = MaterialTheme.typography.titleMedium) }
                }
            }
        }
    }
}

@Composable
private fun ForecastCard(state: PlanState) {
    val summary = state.summary ?: return
    val ranked = summary.countries.sortedByDescending { it.total }
    val colors = remember(ranked) { CountryPalette.assign(ranked.map { it.country }) }
    Surface(shape = RoundedCornerShape(32.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp).animateContentSize()) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("${state.year} at a glance", style = MaterialTheme.typography.headlineSmallEmphasized, modifier = Modifier.weight(1f))
            }
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
            val segments = ranked.flatMap { listOf(BarSegment(colors.getValue(it.country), it.days.toFloat()), BarSegment(colors.getValue(it.country), it.planned.toFloat(), planned = true)) } +
                BarSegment(MaterialTheme.colorScheme.surfaceContainerHighest, (state.open + state.untracked).toFloat())
            StackedBar(segments, height = 20.dp)
            Spacer(Modifier.height(16.dp))
            if (ranked.isEmpty()) {
                Text("Nothing lived or planned in ${state.year} yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ranked.forEach { item ->
                Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    LegendDot(colors.getValue(item.country))
                    Spacer(Modifier.width(12.dp))
                    Text("${Countries.flag(item.country)}  ${Countries.name(item.country)}", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (item.planned > 0) {
                        Tag("+${item.planned} planned")
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("${item.total}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun LimitsAhead(statuses: List<RuleStatus>) {
    Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        statuses.forEachIndexed { index, status ->
            val forecast = status.forecast
            val level = forecast?.level ?: status.level
            SegmentedListItem(
                colors = segmentedColors(),
                verticalAlignment = Alignment.CenterVertically,
                shapes = ListItemDefaults.segmentedShapes(index, statuses.size),
                leadingContent = {
                    Icon(
                        when (level) {
                            Level.GOOD -> Icons.Rounded.CheckCircle
                            Level.CAUTION -> Icons.Rounded.WarningAmber
                            Level.ALERT -> Icons.Rounded.ErrorOutline
                        },
                        contentDescription = null,
                        tint = levelColor(level),
                        modifier = Modifier.size(28.dp),
                    )
                },
                supportingContent = { Text(forecast?.text ?: "Now: ${status.headline} · ${status.detail}") },
            ) { Text(status.rule.name, style = MaterialTheme.typography.titleMedium) }
        }
    }
}
