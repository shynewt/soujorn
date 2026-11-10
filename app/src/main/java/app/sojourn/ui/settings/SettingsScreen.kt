package app.sojourn.ui.settings

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.sojourn.AppContainer
import app.sojourn.BuildConfig
import app.sojourn.data.Countries
import app.sojourn.data.Settings
import app.sojourn.data.ThemeMode
import app.sojourn.data.TrackingMode
import app.sojourn.domain.Reports
import app.sojourn.data.ChangeLog
import app.sojourn.tracking.AutoBackup
import app.sojourn.tracking.Tracking
import app.sojourn.ui.components.segmentedColors
import app.sojourn.ui.appViewModel
import app.sojourn.ui.components.CountryPickerSheet
import app.sojourn.ui.components.ChoiceGroup
import app.sojourn.ui.components.Format
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.RadioButton
import androidx.compose.ui.semantics.Role
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.material.icons.automirrored.rounded.Rule
import app.sojourn.ui.components.SectionHeader
import app.sojourn.ui.components.ShapeIcon
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(val container: AppContainer) : ViewModel() {
    val settings: StateFlow<Settings?> = container.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun update(transform: (Settings) -> Settings) {
        viewModelScope.launch { container.settings.update(transform) }
    }
}

@Composable
fun SettingsScreen(back: () -> Unit, openRules: () -> Unit) {
    val vm = appViewModel { SettingsViewModel(it) }
    val settings by vm.settings.collectAsStateWithLifecycle()
    val current = settings ?: return
    val locale = LocalConfiguration.current.locales[0]
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val repository = vm.container.repository
    var pickingCitizenship by remember { mutableStateOf(false) }
    var pickingResidence by remember { mutableStateOf(false) }
    var pickingPermit by remember { mutableStateOf(false) }
    var pendingImport by remember { mutableStateOf<String?>(null) }
    var confirmErase by remember { mutableStateOf(false) }
    var countingSheet by remember { mutableStateOf(false) }
    var changesSheet by remember { mutableStateOf(false) }
    val changes by repository.changes.collectAsStateWithLifecycle()
    val book by repository.book.collectAsStateWithLifecycle(initialValue = null)
    val visited = remember(book) { book?.let { b -> b.firstDay?.let { b.summary(it, b.today).countries.map { c -> c.country } } }.orEmpty() }
    var editingNames by remember { mutableStateOf(false) }

    fun say(message: String) = scope.launch { snackbar.showSnackbar(message) }

    val exportJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val json = repository.exportJson()
                withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(json.toByteArray()) } }
            }.onSuccess { say("Backup saved") }.onFailure { say("Couldn't save: ${it.message}") }
        }
    }
    val exportCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val csv = Reports.presenceCsv(repository.presenceRows())
                withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(csv.toByteArray()) } }
            }.onSuccess { say("Spreadsheet saved") }.onFailure { say("Couldn't save: ${it.message}") }
        }
    }
    val importJson = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() } } }
                .onSuccess { pendingImport = it }
                .onFailure { say("Couldn't read that file") }
        }
    }
    val chooseFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        vm.update { it.copy(backupFolder = uri.toString(), autoBackup = true) }
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { AutoBackup.writeNow(context) } }
                .onSuccess { say("Backed up to $it") }.onFailure { say("Couldn't back up: ${it.message}") }
        }
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.update { it.copy(limitAlerts = granted) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Settings", style = MaterialTheme.typography.titleLargeEmphasized) },
                navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { SectionHeader("Tracking") }
            item { TrackingSetup(current.tracking, onMode = { mode -> vm.update { it.copy(tracking = mode) } }) }
            if (current.tracking != TrackingMode.OFF) {
                item {
                    Column(Modifier.padding(top = 8.dp)) {
                        Text("Check every", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
                        ChoiceGroup(listOf(2, 4, 6, 12).map { it to "$it h" }, current.intervalHours, { value -> vm.update { it.copy(intervalHours = value) } })
                        Text(
                            "A time zone change also triggers a check, so crossings are caught quickly even with long intervals. Each check takes a second or two and never turns on GPS.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(4.dp),
                        )
                        TextButton(onClick = {
                            Tracking.checkNow(context)
                            say("Checking where you are…")
                        }) {
                            Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text("Check now", modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }

            item { SectionHeader("Counting") }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                    SegmentedListItem(
                        colors = segmentedColors(),
                        verticalAlignment = Alignment.CenterVertically,
                        onClick = { countingSheet = true },
                        shapes = ListItemDefaults.segmentedShapes(0, 4),
                        leadingContent = { ShapeIcon(Icons.AutoMirrored.Rounded.Rule) },
                        supportingContent = {
                            Text(
                                if (current.counting.isEmpty()) "Each country counts travel days its own way: Spain any part of a day, the UK at midnight, Cyprus the day you arrive"
                                else "${current.counting.size} set by you; the rest follow each country's own rules",
                            )
                        },
                    ) { Text("Day counting by country") }
                    SwitchRow(1, 4, "Other countries: travel days count everywhere", "For countries Sojourn has no rules for. Off counts a day only where it ended", current.countAllCountries) { value ->
                        vm.update { it.copy(countAllCountries = value) }
                    }
                    SwitchRow(2, 4, "Fill gaps", "Untracked days between two days in the same country count there too", current.fillGaps) { value ->
                        vm.update { it.copy(fillGaps = value) }
                    }
                    val days = listOf(0) + (1..7).toList()
                    var open by remember { mutableStateOf(false) }
                    SegmentedListItem(
                        colors = segmentedColors(),
                        verticalAlignment = Alignment.CenterVertically,
                        onClick = { open = true },
                        shapes = ListItemDefaults.segmentedShapes(3, 4),
                        supportingContent = {
                            Text(if (current.weekStart == 0) "From your language settings" else DayOfWeek.of(current.weekStart).getDisplayName(TextStyle.FULL, locale))
                        },
                    ) { Text("Week starts on") }
                    if (open) {
                        var choice by remember { mutableIntStateOf(current.weekStart) }
                        AlertDialog(
                            onDismissRequest = { open = false },
                            confirmButton = {
                                TextButton(onClick = {
                                    vm.update { it.copy(weekStart = choice) }
                                    open = false
                                }) { Text("OK") }
                            },
                            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
                            title = { Text("Week starts on") },
                            text = {
                                Column(Modifier.selectableGroup()) {
                                    days.forEach { day ->
                                        Row(
                                            Modifier.fillMaxWidth().selectable(selected = choice == day, role = Role.RadioButton) { choice = day }.padding(vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            RadioButton(selected = choice == day, onClick = null)
                                            Spacer(Modifier.width(12.dp))
                                            Text(if (day == 0) "From your language settings" else DayOfWeek.of(day).getDisplayName(TextStyle.FULL, locale))
                                        }
                                    }
                                }
                            },
                        )
                    }
                }
            }

            item { SectionHeader("About you") }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                    CountryChipsRow(
                        index = 0,
                        count = 5,
                        title = "Citizenship",
                        body = "Visitor limits skip your own countries, and all of the EU, EEA and Switzerland with a passport from there. Also left out of travel history.",
                        codes = current.citizenships,
                        onRemove = { code -> vm.update { it.copy(citizenships = it.citizenships - code) } },
                        onAdd = { pickingCitizenship = true },
                    )
                    CountryChipsRow(
                        index = 1,
                        count = 5,
                        title = "Residence permits",
                        body = "Where you hold a residence permit or long-stay visa. Days there don't count towards visitor limits like Schengen 90/180. Tax residence alone doesn't do this.",
                        codes = current.residencePermits,
                        onRemove = { code -> vm.update { it.copy(residencePermits = it.residencePermits - code) } },
                        onAdd = { pickingPermit = true },
                    )
                    SegmentedListItem(
                        colors = segmentedColors(),
                        verticalAlignment = Alignment.CenterVertically,
                        onClick = { pickingResidence = true },
                        shapes = ListItemDefaults.segmentedShapes(2, 5),
                        supportingContent = { Text(current.taxResidence?.let { "${Countries.flag(it)} ${Countries.name(it)}" } ?: "Not set") },
                        trailingContent = {
                            if (current.taxResidence != null) {
                                IconButton(onClick = { vm.update { it.copy(taxResidence = null) } }) { Icon(Icons.Rounded.Close, contentDescription = "Clear tax residence") }
                            }
                        },
                    ) { Text("Tax residence") }
                    SegmentedListItem(
                        colors = segmentedColors(),
                        verticalAlignment = Alignment.CenterVertically,
                        onClick = { editingNames = true },
                        shapes = ListItemDefaults.segmentedShapes(3, 5),
                        supportingContent = {
                            Text(
                                if (current.travellerNames.isEmpty()) "So imported bookings made for someone else aren't taken as your trips"
                                else current.travellerNames.joinToString(" · "),
                            )
                        },
                    ) { Text("Your name on tickets") }
                    SwitchRow(4, 5, "I file US taxes", "Adds the Form 2555 helper for the foreign earned income exclusion", current.usTaxpayer) { value ->
                        vm.update { it.copy(usTaxpayer = value) }
                    }
                }
            }
            item {
                TextButton(onClick = openRules) {
                    Icon(Icons.Rounded.Shield, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("Limits & residency rules", modifier = Modifier.padding(start = 8.dp))
                }
            }

            item { SectionHeader("Notifications") }
            item {
                SwitchRow(0, 1, "Limit alerts", "A heads-up when a rule gets close to its limit, once per change", current.limitAlerts) { value ->
                    if (value && Build.VERSION.SDK_INT >= 33 && !context.granted(Manifest.permission.POST_NOTIFICATIONS)) {
                        notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        vm.update { it.copy(limitAlerts = value) }
                    }
                }
            }

            item { SectionHeader("Appearance") }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ChoiceGroup(listOf(ThemeMode.SYSTEM to "System", ThemeMode.LIGHT to "Light", ThemeMode.DARK to "Dark"), current.theme, { value -> vm.update { it.copy(theme = value) } })
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        SwitchRow(0, 1, "Wallpaper colours", "Match the app to your wallpaper", current.dynamicColor) { value ->
                            vm.update { it.copy(dynamicColor = value) }
                        }
                    }
                }
            }

            item { SectionHeader("Backup & data") }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                    ActionRow(
                        0,
                        6,
                        Icons.Rounded.History,
                        "Recent changes",
                        if (changes.isEmpty()) "Your last ${ChangeLog.LIMIT} changes to days, plans and flights, each one revertible"
                        else "${changes.size} kept · last: ${changes.first().label}",
                    ) { changesSheet = true }
                    ActionRow(1, 6, Icons.Rounded.Download, "Export backup", "Everything in one JSON file") { exportJson.launch("sojourn-${LocalDate.now()}.json") }
                    ActionRow(2, 6, Icons.Rounded.Upload, "Restore backup", "Merge a backup in, or replace everything") { importJson.launch(arrayOf("application/json", "text/plain", "*/*")) }
                    ActionRow(3, 6, Icons.Rounded.TableChart, "Export spreadsheet", "Every day and country as CSV") { exportCsv.launch("sojourn-days-${LocalDate.now()}.csv") }
                    ActionRow(
                        4,
                        6,
                        Icons.Rounded.Folder,
                        "Automatic backup",
                        when {
                            current.backupFolder == null -> "Choose a folder, e.g. one synced by Syncthing or a cloud drive"
                            current.lastBackup != null -> "Weekly · last ${Format.ago(current.lastBackup)} · tap to change folder"
                            else -> "Weekly · tap to change folder"
                        },
                        trailing = {
                            if (current.backupFolder != null) {
                                Switch(checked = current.autoBackup, onCheckedChange = { value -> vm.update { it.copy(autoBackup = value) } })
                            }
                        },
                    ) { chooseFolder.launch(null) }
                    ActionRow(5, 6, Icons.Rounded.DeleteForever, "Erase all data", "Days, plans, rules and evidence. Settings stay.") { confirmErase = true }
                }
            }
            item {
                Column(Modifier.padding(top = 24.dp, start = 8.dp, end = 8.dp)) {
                    Icon(Icons.Rounded.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(8.dp))
                    Text("Private by design", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Sojourn has no internet access. Countries are worked out on the phone from built-in borders, and only the country is stored, never coordinates. Your data leaves only when you export it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Sojourn ${BuildConfig.VERSION_NAME} · Borders from Natural Earth · Airports from OurAirports",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
    }

    if (changesSheet) {
        RecentChangesSheet(
            changes = changes,
            onDismiss = { changesSheet = false },
            onRevert = { change ->
                scope.launch {
                    repository.revert(change)
                    say("Reverted: ${change.label}")
                }
            },
        )
    }
    if (pickingCitizenship) {
        CountryPickerSheet(
            title = "Citizenship",
            exclude = current.citizenships,
            onDismiss = { pickingCitizenship = false },
            onPick = { code ->
                pickingCitizenship = false
                vm.update { it.copy(citizenships = it.citizenships + code, usTaxpayer = it.usTaxpayer || code == "US") }
            },
        )
    }
    if (pickingPermit) {
        CountryPickerSheet(
            title = "Residence permit",
            exclude = current.residencePermits,
            onDismiss = { pickingPermit = false },
            onPick = { code ->
                pickingPermit = false
                vm.update { it.copy(residencePermits = it.residencePermits + code) }
            },
        )
    }
    if (pickingResidence) {
        CountryPickerSheet(
            title = "Tax residence",
            onDismiss = { pickingResidence = false },
            onPick = { code ->
                pickingResidence = false
                vm.update { it.copy(taxResidence = code) }
            },
        )
    }
    pendingImport?.let { json ->
        var replace by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text("Restore backup") },
            text = {
                Column(Modifier.selectableGroup()) {
                    listOf(
                        false to ("Merge" to "Add what's missing and keep everything you have"),
                        true to ("Replace" to "Swap all days, plans, rules and settings for the backup's"),
                    ).forEach { (value, texts) ->
                        Row(
                            Modifier.fillMaxWidth().selectable(selected = replace == value, role = Role.RadioButton) { replace = value }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            RadioButton(selected = replace == value, onClick = null)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(texts.first, style = MaterialTheme.typography.bodyLarge)
                                Text(texts.second, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingImport = null
                    scope.launch {
                        runCatching { repository.restore(json, replace = replace) }
                            .onSuccess { say("${if (replace) "Replaced with" else "Merged"} ${it.days} days, ${it.plans} plans, ${it.rules} rules") }
                            .onFailure { say("Not a Sojourn backup") }
                    }
                }) { Text("Restore") }
            },
            dismissButton = { TextButton(onClick = { pendingImport = null }) { Text("Cancel") } },
        )
    }
    if (countingSheet) {
        CountingSheet(current, visited, onDismiss = { countingSheet = false }) { country, policy ->
            vm.update { it.copy(counting = if (policy == null) it.counting - country else it.counting + (country to policy)) }
        }
    }
    if (editingNames) {
        NamesDialog(current.travellerNames, onDismiss = { editingNames = false }) { names ->
            vm.update { it.copy(travellerNames = names) }
            editingNames = false
        }
    }
    if (confirmErase) {
        AlertDialog(
            onDismissRequest = { confirmErase = false },
            title = { Text("Erase all data?") },
            text = { Text("Every recorded day, plan and rule will be deleted from this phone. Export a backup first if you might want them back.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmErase = false
                    scope.launch {
                        repository.eraseEverything()
                        say("All data erased")
                    }
                }) { Text("Erase", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmErase = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun CountryChipsRow(
    index: Int,
    count: Int,
    title: String,
    body: String,
    codes: Set<String>,
    onRemove: (String) -> Unit,
    onAdd: () -> Unit,
) {
    SegmentedListItem(
        colors = segmentedColors(),
        verticalAlignment = Alignment.CenterVertically,
        shapes = ListItemDefaults.segmentedShapes(index, count),
        supportingContent = {
            Column {
                Text(body)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    codes.sortedBy { Countries.name(it) }.forEach { code ->
                        InputChip(
                            selected = false,
                            onClick = { onRemove(code) },
                            label = { Text("${Countries.flag(code)} ${Countries.name(code)}") },
                            trailingIcon = { Icon(Icons.Rounded.Close, contentDescription = "Remove ${Countries.name(code)}", modifier = Modifier.size(16.dp)) },
                        )
                    }
                    TextButton(onClick = onAdd) {
                        Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Add")
                    }
                }
            }
        },
    ) { Text(title) }
}

@Composable
private fun SwitchRow(index: Int, count: Int, title: String, body: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    SegmentedListItem(
        colors = segmentedColors(),
        verticalAlignment = Alignment.CenterVertically,
        checked = checked,
        onCheckedChange = onChange,
        shapes = ListItemDefaults.segmentedShapes(index, count),
        supportingContent = { Text(body) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
    ) { Text(title) }
}

@Composable
private fun ActionRow(
    index: Int,
    count: Int,
    icon: ImageVector,
    title: String,
    body: String,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    SegmentedListItem(
        colors = segmentedColors(),
        verticalAlignment = Alignment.CenterVertically,
        onClick = onClick,
        shapes = ListItemDefaults.segmentedShapes(index, count),
        leadingContent = { ShapeIcon(icon) },
        supportingContent = { Text(body) },
        trailingContent = trailing,
    ) { Text(title) }
}
