package app.sojourn.ui.home

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CellTower
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.EditCalendar
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Flight
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.NoteAdd
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Today
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.ToggleFloatingActionButtonDefaults
import androidx.compose.material3.ToggleFloatingActionButtonDefaults.animateIcon
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sojourn.data.Countries
import app.sojourn.data.TrackingMode
import app.sojourn.data.db.Plan
import app.sojourn.data.db.Source
import app.sojourn.data.geo.WorldMapData
import app.sojourn.ui.LocalAppActions
import app.sojourn.ui.LocalCountryColors
import app.sojourn.ui.SettingsSection
import app.sojourn.ui.appViewModel
import app.sojourn.ui.components.BarSegment
import app.sojourn.ui.components.ChoiceGroup
import app.sojourn.ui.components.CountryPickerSheet
import app.sojourn.ui.components.FlagBadge
import app.sojourn.ui.components.Format
import app.sojourn.ui.components.LegendDot
import app.sojourn.ui.components.RuleCard
import app.sojourn.ui.components.SectionHeader
import app.sojourn.ui.components.ShapeIcon
import app.sojourn.ui.components.StackedBar
import app.sojourn.ui.components.WorldMap
import app.sojourn.ui.flights.FlightCard

/** Today: where you stand. See docs/ui-redesign.md. */
@Composable
fun HomeScreen() {
    val vm = appViewModel { HomeViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val map by vm.map.collectAsStateWithLifecycle()
    val dismissed by vm.dismissed.collectAsStateWithLifecycle()
    val context = LocalContext.current
    HomeContent(
        state = state,
        map = map,
        onYear = vm::selectYear,
        onCheckNow = { vm.checkNow(context) },
        onSetToday = vm::setToday,
        dismissed = dismissed,
        onConfirmPlan = vm::confirmPlan,
        onDeletePlan = vm::deletePlan,
        onDismiss = vm::dismiss,
    )
}

/** Today without its view model, for screenshot tests. Everything else opens through [LocalAppActions]. */
@Composable
fun HomeContent(
    state: HomeState,
    map: WorldMapData?,
    onYear: (Int?) -> Unit,
    onCheckNow: () -> Unit,
    onSetToday: (String) -> Unit,
    dismissed: Set<String> = emptySet(),
    onConfirmPlan: (Plan) -> Unit = {},
    onDeletePlan: (Plan) -> Unit = {},
    onDismiss: (Attention) -> Unit = {},
) {
    val actions = LocalAppActions.current
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var picking by rememberSaveable { mutableStateOf(false) }
    val attention = state.attention.filter { it.key !in dismissed }
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("Today") },
                subtitle = { Text(Format.long(state.today)) },
                actions = {
                    IconButton(onClick = { actions.settings() }) { Icon(Icons.Rounded.Settings, contentDescription = "Settings") }
                },
                scrollBehavior = scroll,
            )
        },
        floatingActionButton = { AddMenu(onLogToday = { picking = true }) },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 4.dp, bottom = padding.calculateBottomPadding() + 104.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "now") { NowCard(state, onCheckNow, onSetToday, onPickOther = { picking = true }) }

            if (attention.isNotEmpty()) {
                item(key = "attention-header") { SectionHeader("Needs attention", Modifier.padding(top = 8.dp)) }
                item(key = "attention") { AttentionList(attention, state, onConfirmPlan, onDeletePlan, onDismiss) }
            }

            item(key = "rules-header") {
                SectionHeader("Rules", Modifier.padding(top = 8.dp)) {
                    if (state.statuses.isNotEmpty()) TextButton(onClick = { actions.rules() }) { Text("Manage") }
                }
            }
            if (state.statuses.isEmpty()) {
                item(key = "no-rules") { NoRulesCard { actions.rules() } }
            } else {
                items(state.statuses, key = { "rule-${it.rule.id}" }) { status ->
                    RuleCard(status, onClick = { actions.rules(status.rule.id) })
                }
            }

            state.nextFlight?.let { flight ->
                item(key = "flight-header") { SectionHeader("Next flight", Modifier.padding(top = 8.dp)) }
                item(key = "flight") { FlightCard(flight, state.today, onClick = { actions.flight(flight) }) }
            }

            item(key = "year-header") {
                SectionHeader(
                    when (state.year) {
                        null -> "All time"
                        state.today.year -> "This year"
                        else -> state.year.toString()
                    },
                    Modifier.padding(top = 8.dp),
                )
            }
            if (state.years.size > 1) item(key = "years") { YearSwitcher(state.years, state.year, onYear) }
            item(key = "summary") { SummaryCard(state, map) }
        }
    }
    if (picking) {
        CountryPickerSheet(
            title = "Where are you today?",
            suggestions = state.recents,
            onDismiss = { picking = false },
            onPick = {
                picking = false
                onSetToday(it)
            },
        )
    }
}

/** Everything that can be added, from the one place that's always there. */
@Composable
private fun AddMenu(onLogToday: () -> Unit) {
    val actions = LocalAppActions.current
    var open by rememberSaveable { mutableStateOf(false) }
    FloatingActionButtonMenu(
        expanded = open,
        button = {
            ToggleFloatingActionButton(
                checked = open,
                onCheckedChange = { open = it },
                containerSize = ToggleFloatingActionButtonDefaults.containerSizeMedium(),
                containerCornerRadius = ToggleFloatingActionButtonDefaults.containerCornerRadiusMedium(),
            ) {
                val icon by remember { derivedStateOf { if (checkedProgress > 0.5f) Icons.Rounded.Close else Icons.Rounded.Add } }
                Icon(
                    icon,
                    contentDescription = if (open) "Close" else "Add",
                    modifier = Modifier.animateIcon({ checkedProgress }, size = ToggleFloatingActionButtonDefaults.iconSizeMedium()),
                )
            }
        },
    ) {
        val items = listOf(
            Triple(Icons.Rounded.Today, "Log today", onLogToday),
            Triple(Icons.Rounded.Flight, "Add a flight") { actions.flight() },
            Triple(Icons.Rounded.EditCalendar, "Plan a stay") { actions.planStay() },
            Triple(Icons.Rounded.NoteAdd, "Add evidence") { actions.addEvidence() },
            Triple(Icons.Rounded.PhotoLibrary, "Import") { actions.import() },
        )
        items.forEach { (icon, label, action) ->
            FloatingActionButtonMenuItem(
                onClick = {
                    open = false
                    action()
                },
                icon = { Icon(icon, contentDescription = null) },
                text = { Text(label) },
            )
        }
    }
}

/** The screen's one expressive moment: where you are, since when, and how Sojourn knows. */
@Composable
private fun NowCard(state: HomeState, onCheckNow: () -> Unit, onSetToday: (String) -> Unit, onPickOther: () -> Unit) {
    val current = state.current
    val country = current?.last
    val onContainer = MaterialTheme.colorScheme.onPrimaryContainer
    Surface(shape = RoundedCornerShape(32.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp).animateContentSize()) {
            if (country == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ShapeIcon(
                        Icons.Rounded.Explore,
                        size = 64.dp,
                        shape = MaterialShapes.Cookie9Sided.toShape(),
                        container = MaterialTheme.colorScheme.primary,
                        content = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text("Where are you today?", style = MaterialTheme.typography.titleLargeEmphasized, color = onContainer)
                        Text(
                            if (state.settings.tracking == TrackingMode.OFF) "Add today by hand, or turn tracking on." else "The first check runs shortly.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = onContainer,
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = onPickOther) {
                        Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Set today's country")
                    }
                    Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))
                TrackingRow(state, onCheckNow)
                return@Column
            }

            val isToday = current.date == state.today
            Row(verticalAlignment = Alignment.CenterVertically) {
                FlagBadge(country, size = 76.dp, container = MaterialTheme.colorScheme.surfaceContainerLowest)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (isToday) "You're in" else "Last seen in", style = MaterialTheme.typography.labelLarge, color = onContainer)
                    Text(
                        Countries.name(country),
                        style = MaterialTheme.typography.headlineMediumEmphasized,
                        color = onContainer,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val since = current.date.minusDays(state.streak - 1L)
                    Text(
                        if (isToday) "Day ${state.streak} · since ${Format.short(since, state.today)}" else Format.long(current.date),
                        style = MaterialTheme.typography.bodyMedium,
                        color = onContainer,
                    )
                }
                if (current.countries.size > 1) {
                    Column(horizontalAlignment = Alignment.End) {
                        current.countries.dropLast(1).takeLast(2).forEach { Text(Countries.flag(it), style = MaterialTheme.typography.titleMedium) }
                        Text("travel day", style = MaterialTheme.typography.labelSmall, color = onContainer)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            TrackingRow(state, onCheckNow)
            if (!isToday) {
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Suggested replies to "where are you today?", so suggestion chips.
                    SuggestionChip(
                        onClick = { onSetToday(country) },
                        label = { Text("Still in ${Countries.name(country)}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        colors = SuggestionChipDefaults.suggestionChipColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                    )
                    SuggestionChip(
                        onClick = onPickOther,
                        label = { Text("Somewhere else") },
                        icon = { Icon(Icons.Rounded.EditNote, null, Modifier.size(18.dp)) },
                        colors = SuggestionChipDefaults.suggestionChipColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                    )
                }
            }
        }
    }
}

/**
 * How Sojourn knows: the last check, or that tracking is off. Tapping it opens Tracking in
 * Settings; Check now sits beside it while tracking runs.
 */
@Composable
private fun TrackingRow(state: HomeState, onCheckNow: () -> Unit) {
    val actions = LocalAppActions.current
    val settings = state.settings
    val onContainer = MaterialTheme.colorScheme.onPrimaryContainer
    val icon = when {
        settings.tracking == TrackingMode.OFF -> Icons.Rounded.PauseCircle
        settings.lastCheck != null && settings.lastCheckSource == Source.NETWORK.name -> Icons.Rounded.CellTower
        else -> Icons.Rounded.MyLocation
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            onClick = { actions.settings(SettingsSection.TRACKING) },
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            contentColor = onContainer,
        ) {
            Row(Modifier.height(36.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(app.sojourn.ui.settings.lastCheckText(settings), style = MaterialTheme.typography.labelMedium)
            }
        }
        Spacer(Modifier.weight(1f))
        if (settings.tracking != TrackingMode.OFF) {
            FilledTonalIconButton(onClick = onCheckNow) { Icon(Icons.Rounded.Refresh, contentDescription = "Check now") }
        }
    }
}

@Composable
private fun YearSwitcher(years: List<Int>, selected: Int?, onYear: (Int?) -> Unit) {
    val options: List<Int?> = years.take(3).reversed() + listOf(null)
    ChoiceGroup(options.map { it to (it?.toString() ?: "All time") }, selected, onYear)
}

@Composable
private fun SummaryCard(state: HomeState, map: WorldMapData?) {
    val summary = state.summary
    val visited = summary?.countries?.filter { it.days > 0 }.orEmpty()
    // The one palette, so a country is the same colour here as in the calendar and the plan.
    val palette = LocalCountryColors.current
    val other = MaterialTheme.colorScheme.outline
    val colors = remember(visited, palette, other) { visited.associate { it.country to (palette[it.country] ?: other) } }
    var selected by remember { mutableStateOf<String?>(null) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(32.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 20.dp).animateContentSize()) {
            WorldMap(
                data = map,
                colors = colors,
                selected = selected,
                onCountryClick = { code -> selected = code?.takeIf { it != selected } },
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            Spacer(Modifier.height(16.dp))
            Column(Modifier.padding(horizontal = 20.dp)) {
                val tracked = summary?.trackedDays ?: 0
                Text(
                    if (state.year == null) "${Format.days(tracked)} recorded · ${visited.size} countries" else "$tracked of ${state.elapsedDays} days recorded · ${visited.size} countries",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                if (visited.isEmpty()) {
                    Text(
                        if (state.hasData) "Nothing recorded in this period." else "Your countries will appear here as days add up.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    return@Column
                }
                StackedBar(visited.map { BarSegment(colors.getValue(it.country), it.days.toFloat()) })
                Spacer(Modifier.height(12.dp))
                val total = visited.sumOf { it.days }.coerceAtLeast(1)
                val shown = if (expanded) visited else visited.take(5)
                shown.forEach { item ->
                    val isSelected = item.country == selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (isSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                            .selectable(selected = isSelected, role = Role.Button) { selected = item.country.takeIf { !isSelected } }
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        LegendDot(colors.getValue(item.country))
                        Spacer(Modifier.width(12.dp))
                        Text(Countries.flag(item.country))
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(Countries.name(item.country), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            // Travel days the country itself doesn't count, like leaving Cyprus.
                            val uncounted = item.present - item.days
                            if (uncounted > 0) {
                                Text(
                                    "+${Format.days(uncounted)} there that ${if (uncounted == 1) "doesn't" else "don't"} count",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Text("${item.days}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${item.days * 100 / total}%",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(44.dp).padding(start = 8.dp),
                        )
                    }
                }
                if (visited.size > 5) {
                    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Show fewer" else "Show all ${visited.size} countries") }
                }
            }
        }
    }
}

@Composable
private fun NoRulesCard(onAdd: () -> Unit) {
    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ShapeIcon(Icons.Rounded.Shield, shape = MaterialShapes.Clover4Leaf.toShape())
                Spacer(Modifier.width(14.dp))
                Text("Stay on the right side of visa limits and tax residency", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Schengen 90/180, tax residency days, keeping a residence permit, visa-free stays, or your own rules.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            FilledTonalButton(onClick = onAdd) { Text("Add a rule") }
        }
    }
}
