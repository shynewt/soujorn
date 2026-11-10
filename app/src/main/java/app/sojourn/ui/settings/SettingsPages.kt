package app.sojourn.ui.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Rule
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CalendarViewWeek
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.sojourn.data.ChangeLog
import app.sojourn.data.Countries
import app.sojourn.data.Settings
import app.sojourn.data.ThemeMode
import app.sojourn.data.TrackingMode
import app.sojourn.ui.LocalAppActions
import app.sojourn.ui.SettingsSection
import app.sojourn.ui.components.ChoiceGroup
import app.sojourn.ui.components.CountryPickerSheet
import app.sojourn.ui.components.Format
import app.sojourn.ui.components.SectionHeader
import app.sojourn.ui.components.ShapeIcon
import app.sojourn.ui.components.segmentedColors
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import kotlinx.coroutines.launch

/** One category's page. Every change is saved as it's made; there's nothing to confirm. */
@Composable
internal fun SettingsPage(
    section: SettingsSection,
    settings: Settings,
    extras: SettingsExtras,
    vm: SettingsCommands,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    // Pages are short, so a plain scrolling column; several rows of one page need no laziness.
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (section) {
            SettingsSection.TRACKING -> TrackingPage(settings, vm)
            SettingsSection.COUNTING -> CountingPage(settings, extras.visited, vm)
            SettingsSection.ABOUT_YOU -> AboutYouPage(settings, vm)
            SettingsSection.NOTIFICATIONS -> NotificationsPage(settings, vm)
            SettingsSection.APPEARANCE -> AppearancePage(settings, vm)
            SettingsSection.DATA -> DataPage(settings, extras, vm)
        }
    }
}

@Composable
private fun TrackingPage(settings: Settings, vm: SettingsCommands) {
    TrackingSetup(settings.tracking, onMode = { mode -> vm.update { it.copy(tracking = mode) } })
    if (settings.tracking == TrackingMode.OFF) return
    Column(Modifier.padding(top = 8.dp)) {
        SectionHeader("Check every")
        ChoiceGroup(listOf(2, 4, 6, 12).map { it to "$it h" }, settings.intervalHours, { value -> vm.update { it.copy(intervalHours = value) } })
        Text(
            "A time zone change also triggers a check, so crossings are caught quickly even with long intervals. Each check takes a second or two and never turns on GPS.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(4.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            Text(lastCheckText(settings), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f).padding(start = 4.dp))
            FilledTonalButton(onClick = vm::checkNow) {
                Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("Check now", modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun CountingPage(settings: Settings, visited: List<String>, vm: SettingsCommands) {
    var sheet by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        SegmentedListItem(
            colors = segmentedColors(),
            verticalAlignment = Alignment.CenterVertically,
            onClick = { sheet = true },
            shapes = ListItemDefaults.segmentedShapes(0, 3),
            leadingContent = { ShapeIcon(Icons.AutoMirrored.Rounded.Rule) },
            supportingContent = {
                Text(
                    if (settings.counting.isEmpty()) "Each country counts travel days its own way: Spain any part of a day, the UK at midnight, Cyprus the day you arrive"
                    else "${settings.counting.size} set by you; the rest follow each country's own rules",
                )
            },
        ) { Text("Day counting by country") }
        SwitchRow(1, 3, "Travel days count everywhere", "For countries Sojourn has no rules for. Off counts a day only where it ended", settings.countAllCountries) { value ->
            vm.update { it.copy(countAllCountries = value) }
        }
        SwitchRow(2, 3, "Assume days between two days in the same country", "Untracked days in between count there too, marked Assumed", settings.fillGaps) { value ->
            vm.update { it.copy(fillGaps = value) }
        }
    }
    if (sheet) {
        CountingSheet(settings, visited, onDismiss = { sheet = false }) { country, policy ->
            vm.update { it.copy(counting = if (policy == null) it.counting - country else it.counting + (country to policy)) }
        }
    }
}

@Composable
private fun AboutYouPage(settings: Settings, vm: SettingsCommands) {
    var picking by remember { mutableStateOf<String?>(null) }
    var editingNames by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        CountryChipsRow(
            index = 0,
            count = 5,
            title = "Citizenship",
            body = "Visitor limits skip your own countries, and all of the EU, EEA and Switzerland with a passport from there. Also left out of travel history.",
            codes = settings.citizenships,
            onRemove = { code -> vm.update { it.copy(citizenships = it.citizenships - code) } },
            onAdd = { picking = "citizenship" },
        )
        CountryChipsRow(
            index = 1,
            count = 5,
            title = "Residence permits",
            body = "Where you hold a residence permit or long-stay visa. Days there don't count towards visitor limits like Schengen 90/180. Tax residence alone doesn't do this.",
            codes = settings.residencePermits,
            onRemove = { code -> vm.update { it.copy(residencePermits = it.residencePermits - code) } },
            onAdd = { picking = "permit" },
        )
        SegmentedListItem(
            colors = segmentedColors(),
            verticalAlignment = Alignment.CenterVertically,
            onClick = { picking = "residence" },
            shapes = ListItemDefaults.segmentedShapes(2, 5),
            supportingContent = { Text(settings.taxResidence?.let { "${Countries.flag(it)} ${Countries.name(it)}" } ?: "Not set") },
            trailingContent = {
                if (settings.taxResidence != null) {
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
                    if (settings.travellerNames.isEmpty()) "So imported bookings made for someone else aren't taken as your trips"
                    else settings.travellerNames.joinToString(" · "),
                )
            },
        ) { Text("Your name on tickets") }
        SwitchRow(4, 5, "I file US taxes", "Adds the Form 2555 helper for the foreign earned income exclusion", settings.usTaxpayer) { value ->
            vm.update { it.copy(usTaxpayer = value) }
        }
    }
    picking?.let { which ->
        CountryPickerSheet(
            title = when (which) {
                "citizenship" -> "Citizenship"
                "permit" -> "Residence permit"
                else -> "Tax residence"
            },
            exclude = when (which) {
                "citizenship" -> settings.citizenships
                "permit" -> settings.residencePermits
                else -> emptySet()
            },
            onDismiss = { picking = null },
            onPick = { code ->
                picking = null
                when (which) {
                    "citizenship" -> vm.update { it.copy(citizenships = it.citizenships + code, usTaxpayer = it.usTaxpayer || code == "US") }
                    "permit" -> vm.update { it.copy(residencePermits = it.residencePermits + code) }
                    else -> vm.update { it.copy(taxResidence = code) }
                }
            },
        )
    }
    if (editingNames) {
        NamesDialog(settings.travellerNames, onDismiss = { editingNames = false }) { names ->
            vm.update { it.copy(travellerNames = names) }
            editingNames = false
        }
    }
}

@Composable
private fun NotificationsPage(settings: Settings, vm: SettingsCommands) {
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.update { it.copy(limitAlerts = granted) }
    }
    SwitchRow(0, 1, "Limit alerts", "A heads-up when a rule gets close to its limit, once per change", settings.limitAlerts) { value ->
        if (value && Build.VERSION.SDK_INT >= 33 && !context.granted(Manifest.permission.POST_NOTIFICATIONS)) {
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            vm.update { it.copy(limitAlerts = value) }
        }
    }
}

/**
 * How the app looks. The first day of the week lives here, not under Counting: it changes how
 * calendars are drawn, never which days count.
 */
@Composable
private fun AppearancePage(settings: Settings, vm: SettingsCommands) {
    val locale = LocalConfiguration.current.locales[0]
    var choosingWeek by remember { mutableStateOf(false) }
    fun dayName(day: Int) = if (day == 0) "From your language settings" else DayOfWeek.of(day).getDisplayName(TextStyle.FULL, locale)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("Theme")
        ChoiceGroup(listOf(ThemeMode.SYSTEM to "System", ThemeMode.LIGHT to "Light", ThemeMode.DARK to "Dark"), settings.theme, { value -> vm.update { it.copy(theme = value) } })
        Spacer(Modifier.size(8.dp))
        val rows = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) 2 else 1
        Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
            if (rows == 2) {
                SwitchRow(0, rows, "Wallpaper colours", "Match the app to your wallpaper", settings.dynamicColor) { value ->
                    vm.update { it.copy(dynamicColor = value) }
                }
            }
            SegmentedListItem(
                colors = segmentedColors(),
                verticalAlignment = Alignment.CenterVertically,
                onClick = { choosingWeek = true },
                shapes = ListItemDefaults.segmentedShapes(rows - 1, rows),
                leadingContent = { ShapeIcon(Icons.Rounded.CalendarViewWeek) },
                supportingContent = { Text(dayName(settings.weekStart)) },
            ) { Text("Week starts on") }
        }
    }
    if (choosingWeek) {
        var choice by remember { mutableIntStateOf(settings.weekStart) }
        AlertDialog(
            onDismissRequest = { choosingWeek = false },
            confirmButton = {
                TextButton(onClick = {
                    vm.update { it.copy(weekStart = choice) }
                    choosingWeek = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { choosingWeek = false }) { Text("Cancel") } },
            title = { Text("Week starts on") },
            text = {
                Column(Modifier.selectableGroup()) {
                    (0..7).forEach { day ->
                        Row(
                            Modifier.fillMaxWidth().selectable(selected = choice == day, role = Role.RadioButton) { choice = day }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = choice == day, onClick = null)
                            Spacer(Modifier.width(12.dp))
                            Text(dayName(day))
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun DataPage(settings: Settings, extras: SettingsExtras, vm: SettingsCommands) {
    val actions = LocalAppActions.current
    val scope = rememberCoroutineScope()
    var changesSheet by remember { mutableStateOf(false) }
    var pendingRestore by remember { mutableStateOf<String?>(null) }
    var confirmErase by remember { mutableStateOf(false) }
    val exportJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let(vm::exportBackup) }
    val exportCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri -> uri?.let(vm::exportSpreadsheet) }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch { pendingRestore = vm.read(uri) }
    }
    val chooseFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let(vm::chooseBackupFolder) }
    val changes = extras.changes

    Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        ActionRow(
            0,
            2,
            Icons.Rounded.History,
            "Recent changes",
            if (changes.isEmpty()) "Your last ${ChangeLog.LIMIT} changes to days, plans and flights, each one revertible"
            else "${changes.size} kept · last: ${changes.first().label}",
        ) { changesSheet = true }
        ActionRow(1, 2, Icons.Rounded.PhotoLibrary, "Import", "Past trips from photos, calendars, boarding passes, GPX or Google Timeline") { actions.import() }
    }
    SectionHeader("Backup", Modifier.padding(top = 16.dp))
    Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        ActionRow(
            0,
            4,
            Icons.Rounded.Folder,
            "Automatic backup",
            when {
                settings.backupFolder == null -> "Choose a folder, e.g. one synced by Syncthing or a cloud drive"
                settings.lastBackup != null -> "Weekly · last ${Format.ago(settings.lastBackup)} · tap to change folder"
                else -> "Weekly · tap to change folder"
            },
            trailing = {
                if (settings.backupFolder != null) {
                    Switch(checked = settings.autoBackup, onCheckedChange = { value -> vm.update { it.copy(autoBackup = value) } })
                }
            },
        ) { chooseFolder.launch(null) }
        ActionRow(1, 4, Icons.Rounded.Download, "Export backup", "Everything in one file, to keep or move to another phone") { exportJson.launch("sojourn-${LocalDate.now()}.json") }
        ActionRow(2, 4, Icons.Rounded.Upload, "Restore backup", "Merge a backup in, or replace everything") { restore.launch(arrayOf("application/json", "text/plain", "*/*")) }
        ActionRow(3, 4, Icons.Rounded.TableChart, "Export spreadsheet", "Every day and country as CSV") { exportCsv.launch("sojourn-days-${LocalDate.now()}.csv") }
    }
    Spacer(Modifier.size(16.dp))
    SegmentedListItem(
        colors = segmentedColors(),
        verticalAlignment = Alignment.CenterVertically,
        onClick = { confirmErase = true },
        shapes = ListItemDefaults.segmentedShapes(0, 1),
        leadingContent = {
            ShapeIcon(Icons.Rounded.DeleteForever, container = MaterialTheme.colorScheme.errorContainer, content = MaterialTheme.colorScheme.onErrorContainer)
        },
        supportingContent = { Text("Days, plans, rules and evidence. Settings stay.") },
    ) { Text("Erase all data", color = MaterialTheme.colorScheme.error) }

    if (changesSheet) RecentChangesSheet(changes = changes, onDismiss = { changesSheet = false }, onRevert = vm::revert)
    pendingRestore?.let { json ->
        var replace by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { pendingRestore = null },
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
                    pendingRestore = null
                    vm.restore(json, replace)
                }) { Text("Restore") }
            },
            dismissButton = { TextButton(onClick = { pendingRestore = null }) { Text("Cancel") } },
        )
    }
    if (confirmErase) {
        AlertDialog(
            onDismissRequest = { confirmErase = false },
            title = { Text("Erase all data?") },
            text = { Text("Every recorded day, plan, rule and piece of evidence will be deleted from this phone. Export a backup first if you might want them back.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmErase = false
                    vm.erase()
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
