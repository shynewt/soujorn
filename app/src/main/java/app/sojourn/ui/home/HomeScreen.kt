package app.sojourn.ui.home

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.CellTower
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Flight
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RequestQuote
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sojourn.data.Countries
import app.sojourn.data.TrackingMode
import app.sojourn.data.db.Flight
import app.sojourn.data.flights.Airlines
import app.sojourn.ui.PlanRoute
import app.sojourn.ui.flights.airportPlace
import app.sojourn.ui.flights.flightTimes
import app.sojourn.ui.flights.flightWhen
import app.sojourn.ui.flights.route
import app.sojourn.data.db.Source
import app.sojourn.data.geo.WorldMapData
import app.sojourn.ui.FeieRoute
import app.sojourn.ui.HistoryRoute
import app.sojourn.ui.EvidencePackRoute
import app.sojourn.ui.SettingsSection
import androidx.compose.material.icons.rounded.FolderZip
import app.sojourn.ui.ImportRoute
import app.sojourn.ui.RulesRoute
import app.sojourn.ui.SettingsRoute
import app.sojourn.ui.appViewModel
import app.sojourn.ui.components.BarSegment
import app.sojourn.ui.components.CountryPickerSheet
import app.sojourn.ui.components.FlagBadge
import app.sojourn.ui.components.ChoiceGroup
import app.sojourn.ui.components.Format
import app.sojourn.ui.components.LegendDot
import app.sojourn.ui.components.RuleCard
import app.sojourn.ui.components.SectionHeader
import app.sojourn.ui.components.ShapeIcon
import app.sojourn.ui.components.StackedBar
import app.sojourn.ui.components.WorldMap
import app.sojourn.ui.theme.CountryPalette

@Composable
fun HomeScreen() {
    val actions = app.sojourn.ui.LocalAppActions.current
    HomeScreen(
        navigate = { route ->
            when (route) {
                is SettingsRoute -> actions.settings(route.section?.let(SettingsSection::valueOf))
                is RulesRoute -> actions.rules()
                is EvidencePackRoute -> actions.evidencePack(route.year)
                ImportRoute -> actions.import()
                HistoryRoute -> actions.history()
                FeieRoute -> actions.feie()
            }
        },
        switchTab = { route ->
            when (route) {
                PlanRoute -> actions.tab(app.sojourn.ui.AppTab.PLAN)
                else -> actions.calendar(list = true)
            }
        },
    )
}

@Composable
private fun HomeScreen(navigate: (Any) -> Unit, switchTab: (Any) -> Unit) {
    val vm = appViewModel { HomeViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val map by vm.map.collectAsStateWithLifecycle()
    val context = LocalContext.current
    HomeContent(
        state = state,
        map = map,
        onYear = vm::selectYear,
        onCheckNow = { vm.checkNow(context) },
        onSetToday = vm::setToday,
        navigate = navigate,
        switchTab = switchTab,
    )
}

@Composable
fun HomeContent(
    state: HomeState,
    map: WorldMapData?,
    onYear: (Int?) -> Unit,
    onCheckNow: () -> Unit,
    onSetToday: (String) -> Unit,
    navigate: (Any) -> Unit,
    switchTab: (Any) -> Unit,
) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var picking by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("Sojourn") },
                subtitle = { Text(Format.long(state.today)) },
                actions = {
                    IconButton(onClick = { navigate(SettingsRoute()) }) { Icon(Icons.Rounded.Settings, contentDescription = "Settings") }
                },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 4.dp, bottom = padding.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "now") { NowCard(state, onCheckNow, onSetToday, onPickOther = { picking = true }) }
            state.nextFlight?.let { flight -> item(key = "flight") { NextFlightCard(flight, state.today) { switchTab(PlanRoute) } } }
            if (state.years.isNotEmpty()) item(key = "years") { YearSwitcher(state.years, state.year, onYear) }
            item(key = "summary") { SummaryCard(state, map) }
            if (state.untracked > 0) item(key = "gaps") { UntrackedCard(state.untracked) { switchTab(Unit) } }
            item(key = "limits") {
                SectionHeader("Limits & residency", Modifier.padding(top = 8.dp)) {
                    TextButton(onClick = { navigate(RulesRoute()) }) { Text("Manage") }
                }
            }
            if (state.statuses.isEmpty()) {
                item(key = "no-rules") { NoRulesCard { navigate(RulesRoute()) } }
            } else {
                items(state.statuses, key = { "rule-${it.rule.id}" }) { RuleCard(it, onClick = { navigate(RulesRoute()) }) }
            }
            item(key = "tools-header") { SectionHeader("Tools", Modifier.padding(top = 8.dp)) }
            item(key = "tools") { Tools(state.settings.usTaxpayer, navigate) }
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
                            if (state.settings.tracking == TrackingMode.OFF) "Add today by hand, or turn on tracking in settings." else "The first check runs shortly.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = onContainer,
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Button(onClick = onPickOther) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Set today's country")
                }
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                val settings = state.settings
                val (icon, text) = when {
                    settings.tracking == TrackingMode.OFF -> Icons.Rounded.PauseCircle to "Tracking is off"
                    settings.lastCheck == null -> Icons.Rounded.MyLocation to "Waiting for the first check"
                    settings.lastCheckSource == Source.NETWORK.name -> Icons.Rounded.CellTower to "Mobile network · ${Format.ago(settings.lastCheck)}"
                    else -> Icons.Rounded.MyLocation to "Location · ${Format.ago(settings.lastCheck)}"
                }
                Row(
                    Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceContainerLowest).padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(icon, contentDescription = null, tint = onContainer, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(text, style = MaterialTheme.typography.labelMedium, color = onContainer)
                }
                Spacer(Modifier.weight(1f))
                if (settings.tracking != TrackingMode.OFF) {
                    FilledTonalIconButton(onClick = onCheckNow) { Icon(Icons.Rounded.Refresh, contentDescription = "Check now") }
                }
            }
            if (!isToday) {
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Suggested replies to "where are you today?", so suggestion chips.
                    SuggestionChip(
                        onClick = { onSetToday(country) },
                        label = { Text("Still in ${Countries.name(country)}") },
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

@Composable
private fun NextFlightCard(flight: Flight, today: java.time.LocalDate, onClick: () -> Unit) {
    val onContainer = MaterialTheme.colorScheme.onSecondaryContainer
    Surface(onClick = onClick, shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            ShapeIcon(
                Icons.Rounded.Flight,
                size = 56.dp,
                shape = MaterialShapes.Cookie6Sided.toShape(),
                container = MaterialTheme.colorScheme.secondary,
                content = MaterialTheme.colorScheme.onSecondary,
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("Next flight · ${flightWhen(flight, today).lowercase()}", style = MaterialTheme.typography.labelLarge, color = onContainer)
                Text(route(flight), style = MaterialTheme.typography.titleLargeEmphasized, color = onContainer, maxLines = 1)
                Text(
                    "${airportPlace(flight.origin)} → ${airportPlace(flight.destination)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = onContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(Format.short(flight.departureDate, today), flightTimes(flight), Airlines.format(flight.number).ifBlank { null }).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = onContainer,
                )
            }
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
    val colors = remember(visited) { CountryPalette.assign(visited.map { it.country }) }
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
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(state.year?.toString() ?: "All time", style = MaterialTheme.typography.headlineSmallEmphasized, modifier = Modifier.weight(1f))
                    val tracked = summary?.trackedDays ?: 0
                    Text(
                        if (state.year == null) "${Format.days(tracked)} · ${visited.size} countries" else "$tracked of ${state.elapsedDays} days",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
private fun UntrackedCard(days: Int, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            ShapeIcon(Icons.AutoMirrored.Rounded.HelpOutline, container = MaterialTheme.colorScheme.tertiary, content = MaterialTheme.colorScheme.onTertiary, shape = MaterialShapes.Sunny.toShape())
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("${Format.days(days)} without a country", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
                Text("Fill them in so every count stays right", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
            }
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
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

private data class Tool(val title: String, val subtitle: String, val icon: ImageVector, val route: Any)

@Composable
private fun Tools(usTaxpayer: Boolean, navigate: (Any) -> Unit) {
    val tools = buildList {
        add(Tool("Import history", "From photos, GPX tracks or Google Timeline", Icons.Rounded.PhotoLibrary, ImportRoute))
        add(Tool("Travel history", "Every country and trip, for visa, residence and citizenship files", Icons.Rounded.Public, HistoryRoute))
        add(Tool("Evidence pack", "A year's photos, passes and bookings, for a residence certificate", Icons.Rounded.FolderZip, EvidencePackRoute(java.time.LocalDate.now().year)))
        if (usTaxpayer) add(Tool("Form 2555", "FEIE physical presence test, pre-filled", Icons.Rounded.RequestQuote, FeieRoute))
        add(Tool("Backup", "Export, restore, or back up to a folder", Icons.Rounded.Backup, SettingsRoute(SettingsSection.DATA.name)))
    }
    val shapes: List<Shape> = listOf(
        MaterialShapes.Cookie6Sided.toShape(),
        MaterialShapes.Clover4Leaf.toShape(),
        MaterialShapes.Gem.toShape(),
        MaterialShapes.Pentagon.toShape(),
    )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        tools.chunked(2).forEachIndexed { row, pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEachIndexed { column, tool ->
                    Surface(
                        onClick = { navigate(tool.route) },
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier.weight(1f).height(148.dp),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            ShapeIcon(tool.icon, shape = shapes[(row * 2 + column) % shapes.size])
                            Spacer(Modifier.weight(1f))
                            Text(tool.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(tool.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}
