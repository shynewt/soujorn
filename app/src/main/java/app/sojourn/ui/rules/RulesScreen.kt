package app.sojourn.ui.rules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Anchor
import androidx.compose.material.icons.rounded.Balance
import androidx.compose.material.icons.rounded.Flight
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.DoNotDisturbOn
import androidx.compose.material.icons.rounded.Euro
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.sojourn.AppContainer
import app.sojourn.data.CountrySet
import app.sojourn.data.db.Rule
import app.sojourn.domain.CountingPolicies
import app.sojourn.domain.RulePreset
import app.sojourn.domain.RuleStatus
import app.sojourn.ui.components.segmentedColors
import app.sojourn.ui.appViewModel
import app.sojourn.ui.components.CountryPickerSheet
import app.sojourn.ui.components.EmptyState
import app.sojourn.ui.components.RuleCard
import app.sojourn.ui.components.SectionHeader
import app.sojourn.ui.components.ShapeIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class RulesState(
    val loaded: Boolean = false,
    val active: List<RuleStatus> = emptyList(),
    val paused: List<Rule> = emptyList(),
    val recents: List<String> = emptyList(),
    val policies: CountingPolicies = CountingPolicies(),
)

/** The rules, with their statuses as the rest of the app sees them (`repository.statuses`). */
class RulesViewModel(container: AppContainer) : ViewModel() {
    private val repository = container.repository

    val state: StateFlow<RulesState> = combine(repository.book, repository.rules, repository.statuses) { book, rules, statuses ->
        RulesState(
            loaded = true,
            active = statuses,
            paused = rules.filter { !it.enabled },
            recents = book.recentCountries(),
            policies = book.policies,
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RulesState())

    fun save(rule: Rule) {
        viewModelScope.launch { repository.saveRule(rule) }
    }

    fun delete(rule: Rule) {
        viewModelScope.launch { repository.deleteRule(rule.id) }
    }
}

/**
 * Every rule, active ones as cards and paused ones as a list. With [ruleId], that rule's
 * editor opens straight away (from a card elsewhere); closing it leaves you on this list.
 */
@Composable
fun RulesScreen(ruleId: Long?, back: () -> Unit) {
    val vm = appViewModel { RulesViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    var choosing by remember { mutableStateOf(false) }
    var presetNeedingCountry by remember { mutableStateOf<RulePreset?>(null) }
    var editing by remember { mutableStateOf<Rule?>(null) }
    // Opened once per visit, so turning the phone or coming back doesn't reopen it.
    var opened by rememberSaveable { mutableStateOf(ruleId == null) }
    LaunchedEffect(state.loaded) {
        if (opened || !state.loaded) return@LaunchedEffect
        opened = true
        editing = state.active.firstOrNull { it.rule.id == ruleId }?.rule ?: state.paused.firstOrNull { it.id == ruleId }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Rules", style = MaterialTheme.typography.titleLargeEmphasized) },
                navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { choosing = true },
                icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                text = { Text("Add a rule") },
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.loaded && state.active.isEmpty() && state.paused.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Rounded.Shield,
                        title = "No rules yet",
                        body = "Rules count your days against visa limits and each country's tax residency tests, and warn you before you cross one.",
                    )
                }
            }
            items(state.active, key = { it.rule.id }) { status -> RuleCard(status, onClick = { editing = status.rule }) }
            if (state.paused.isNotEmpty()) {
                item { SectionHeader("Paused") }
                itemsIndexed(state.paused, key = { _, rule -> "p-${rule.id}" }) { index, rule ->
                    SegmentedListItem(
                        colors = segmentedColors(),
                        verticalAlignment = Alignment.CenterVertically,
                        onClick = { editing = rule },
                        shapes = ListItemDefaults.segmentedShapes(index, state.paused.size),
                        supportingContent = { Text(CountrySet.describe(rule.countries)) },
                        trailingContent = { Switch(checked = false, onCheckedChange = { vm.save(rule.copy(enabled = true)) }) },
                    ) { Text(rule.name) }
                }
            }
        }
    }

    if (choosing) {
        PresetSheet(
            onDismiss = { choosing = false },
            onPick = { preset ->
                choosing = false
                if (preset.needsCountry) presetNeedingCountry = preset else editing = preset.template()
            },
        )
    }
    presetNeedingCountry?.let { preset ->
        CountryPickerSheet(
            title = preset.title,
            suggestions = state.recents,
            onDismiss = { presetNeedingCountry = null },
            onPick = {
                presetNeedingCountry = null
                editing = preset.template(it)
            },
        )
    }
    editing?.let { rule ->
        RuleEditorSheet(
            initial = rule,
            status = state.active.firstOrNull { it.rule.id == rule.id && rule.id != 0L },
            recents = state.recents,
            policies = state.policies,
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

@Composable
private fun PresetSheet(onDismiss: () -> Unit, onPick: (RulePreset) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp)) {
            Text("Add a rule", style = MaterialTheme.typography.headlineSmallEmphasized, modifier = Modifier.padding(start = 8.dp, bottom = 16.dp))
            PresetGroup(RulePreset.entries.filter { !it.unitedStates }, onPick)
            Text(
                "United States",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 8.dp, top = 24.dp, bottom = 8.dp),
            )
            PresetGroup(RulePreset.entries.filter { it.unitedStates }, onPick)
        }
    }
}

@Composable
private fun PresetGroup(presets: List<RulePreset>, onPick: (RulePreset) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        presets.forEachIndexed { index, preset ->
            SegmentedListItem(
                colors = segmentedColors(onSheet = true),
                verticalAlignment = Alignment.CenterVertically,
                onClick = { onPick(preset) },
                shapes = ListItemDefaults.segmentedShapes(index, presets.size),
                leadingContent = {
                    ShapeIcon(
                        when (preset) {
                            RulePreset.SCHENGEN -> Icons.Rounded.Euro
                            RulePreset.AVOID_RESIDENCY -> Icons.Rounded.DoNotDisturbOn
                            RulePreset.KEEP_RESIDENCY -> Icons.Rounded.Home
                            RulePreset.CONTINUITY -> Icons.Rounded.Anchor
                            RulePreset.VISA_STAY -> Icons.Rounded.Flight
                            RulePreset.US_PRESENCE -> Icons.Rounded.Balance
                            RulePreset.FEIE -> Icons.Rounded.Shield
                            RulePreset.CUSTOM -> Icons.Rounded.Tune
                        },
                    )
                },
                supportingContent = { Text(preset.description) },
            ) { Text(preset.title, style = MaterialTheme.typography.titleMedium) }
        }
    }
}
