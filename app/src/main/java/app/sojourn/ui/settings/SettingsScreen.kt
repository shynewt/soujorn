package app.sojourn.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Rule
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.sojourn.AppContainer
import app.sojourn.BuildConfig
import app.sojourn.data.Change
import app.sojourn.data.Countries
import app.sojourn.data.Settings
import app.sojourn.data.ThemeMode
import app.sojourn.data.TrackingMode
import app.sojourn.data.db.Source
import app.sojourn.domain.Reports
import app.sojourn.tracking.AutoBackup
import app.sojourn.tracking.Tracking
import app.sojourn.ui.LocalAppActions
import app.sojourn.ui.SettingsSection
import app.sojourn.ui.appViewModel
import app.sojourn.ui.components.Format
import app.sojourn.ui.components.ShapeIcon
import app.sojourn.ui.components.segmentedColors
import java.time.DayOfWeek
import java.time.format.TextStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What Settings shows besides the settings themselves. */
data class SettingsExtras(
    /** Countries with recorded days, offered first in the counting sheet. */
    val visited: List<String> = emptyList(),
    val changes: List<Change> = emptyList(),
    val activeRules: Int = 0,
)

/** A backup chosen to restore, with what it holds, while the user picks merge or replace. */
data class PendingRestore(val uri: Uri, val days: Int, val documents: Int, val hasEvidence: Boolean)

/** What the settings pages can do, so they can be drawn without a view model in screenshot tests. */
interface SettingsCommands {
    fun update(transform: (Settings) -> Settings)
    fun checkNow()
    fun exportBackup(uri: Uri)
    fun exportSpreadsheet(uri: Uri)

    /** Reads a backup file, for [restore] once the user says how; null if it can't be read. */
    suspend fun read(uri: Uri): PendingRestore?
    fun restore(backup: PendingRestore, replace: Boolean)
    fun chooseBackupFolder(uri: Uri)
    fun revert(change: Change)
    fun erase()
}

/**
 * Settings and the work behind its buttons (backups, restore, erase), so the screen only
 * shows things and passes taps on. Results come back as one-line [messages].
 */
class SettingsViewModel(val container: AppContainer) : ViewModel(), SettingsCommands {
    private val repository = container.repository
    private val context = container.context
    val settings: StateFlow<Settings?> = container.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val extras: StateFlow<SettingsExtras> = combine(
        repository.book.map { b -> b.firstDay?.let { b.summary(it, b.today).countries.map { c -> c.country } }.orEmpty() },
        repository.changes,
        repository.rules,
    ) { visited, changes, rules -> SettingsExtras(visited, changes, rules.count { it.enabled }) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsExtras())

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    override fun update(transform: (Settings) -> Settings) {
        viewModelScope.launch { container.settings.update(transform) }
    }

    override fun checkNow() {
        Tracking.checkNow(context)
        _messages.trySend("Checking where you are…")
    }

    override fun exportBackup(uri: Uri) {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri, "wt")!!.use { container.backups.write(it) } } }
                .onSuccess { written ->
                    val left = if (written.missing.isEmpty()) "" else " · ${written.missing.size} left out, listed in its README"
                    _messages.send("Backup saved: ${written.days} days, ${written.documents} documents$left")
                }
                .onFailure { _messages.send("Couldn't save: ${it.message}") }
        }
    }

    override fun exportSpreadsheet(uri: Uri) = write(uri, "Spreadsheet saved") { Reports.presenceCsv(repository.presenceRows()) }

    private fun write(uri: Uri, done: String, text: suspend () -> String) {
        viewModelScope.launch {
            runCatching {
                val content = text()
                withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(content.toByteArray()) } }
            }.onSuccess { _messages.send(done) }.onFailure { _messages.send("Couldn't save: ${it.message}") }
        }
    }

    override suspend fun read(uri: Uri): PendingRestore? =
        runCatching { container.backups.read(uri) }
            .onFailure { _messages.send("Not a Sojourn backup") }
            .getOrNull()
            ?.let { PendingRestore(uri, it.days, it.documents, it.hasEvidence) }

    override fun restore(backup: PendingRestore, replace: Boolean) {
        viewModelScope.launch {
            runCatching { container.backups.restore(backup.uri, replace) }
                .onSuccess {
                    val tables = it.tables
                    _messages.send("${if (replace) "Replaced with" else "Merged"} ${tables.days} days, ${tables.plans} plans, ${tables.rules} rules, ${it.documents} documents")
                }
                .onFailure { _messages.send("Couldn't restore: ${it.message}") }
        }
    }

    /** Keeps access to the chosen folder, turns weekly backups on and writes the first one. */
    override fun chooseBackupFolder(uri: Uri) {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        viewModelScope.launch {
            container.settings.update { it.copy(backupFolder = uri.toString(), autoBackup = true) }
            runCatching { withContext(Dispatchers.IO) { AutoBackup.writeNow(context) } }
                .onSuccess { _messages.send("Backed up to $it") }.onFailure { _messages.send("Couldn't back up: ${it.message}") }
        }
    }

    override fun revert(change: Change) {
        viewModelScope.launch {
            repository.revert(change)
            _messages.send("Reverted: ${change.label}")
        }
    }

    /** Erasing is a recorded change, so the app-wide snackbar already offers Undo. */
    override fun erase() {
        viewModelScope.launch { repository.eraseEverything() }
    }
}

/** The order categories are listed in. Rules sits between About you and Notifications. */
private val categories = listOf(SettingsSection.TRACKING, SettingsSection.COUNTING, SettingsSection.ABOUT_YOU, SettingsSection.NOTIFICATIONS, SettingsSection.APPEARANCE, SettingsSection.DATA)

internal fun SettingsSection.title(): String = when (this) {
    SettingsSection.TRACKING -> "Tracking"
    SettingsSection.COUNTING -> "Counting"
    SettingsSection.ABOUT_YOU -> "About you"
    SettingsSection.NOTIFICATIONS -> "Notifications"
    SettingsSection.APPEARANCE -> "Appearance"
    SettingsSection.DATA -> "Your data"
}

private fun SettingsSection.icon(): ImageVector = when (this) {
    SettingsSection.TRACKING -> Icons.Rounded.MyLocation
    SettingsSection.COUNTING -> Icons.AutoMirrored.Rounded.Rule
    SettingsSection.ABOUT_YOU -> Icons.Rounded.Person
    SettingsSection.NOTIFICATIONS -> Icons.Rounded.Notifications
    SettingsSection.APPEARANCE -> Icons.Rounded.Palette
    SettingsSection.DATA -> Icons.Rounded.Folder
}

/** One line on where each category stands now, so the list answers most questions by itself. */
@Composable
private fun SettingsSection.summary(settings: Settings, extras: SettingsExtras): String {
    val locale = LocalConfiguration.current.locales[0]
    return when (this) {
        SettingsSection.TRACKING -> when (settings.tracking) {
            TrackingMode.OFF -> "Off · you add days yourself"
            else -> listOfNotNull(
                if (settings.tracking == TrackingMode.LOCATION) "Automatic" else "Mobile network only",
                "every ${settings.intervalHours} h",
                settings.lastCheck?.let { "checked ${Format.ago(it)}" },
            ).joinToString(" · ")
        }
        SettingsSection.COUNTING -> listOf(
            if (settings.counting.isEmpty()) "Each country's own rules" else "${settings.counting.size} ${if (settings.counting.size == 1) "country" else "countries"} set by you",
            if (settings.fillGaps) "assumed days on" else "assumed days off",
        ).joinToString(" · ")
        SettingsSection.ABOUT_YOU -> listOfNotNull(
            settings.citizenships.takeIf { it.isNotEmpty() }?.let { codes -> "Citizen of " + codes.sortedBy { Countries.name(it) }.joinToString(", ") { Countries.name(it) } },
            settings.taxResidence?.let { "tax resident in ${Countries.name(it)}" },
        ).joinToString(" · ").replaceFirstChar(Char::uppercaseChar).ifEmpty { "Citizenship and tax residence not set" }
        SettingsSection.NOTIFICATIONS -> if (settings.limitAlerts) "Limit alerts on" else "Limit alerts off"
        SettingsSection.APPEARANCE -> listOfNotNull(
            when (settings.theme) {
                ThemeMode.SYSTEM -> "System theme"
                ThemeMode.LIGHT -> "Light"
                ThemeMode.DARK -> "Dark"
            },
            settings.weekStart.takeIf { it != 0 }?.let { "weeks start on " + DayOfWeek.of(it).getDisplayName(TextStyle.FULL, locale) },
        ).joinToString(" · ")
        SettingsSection.DATA -> when {
            settings.autoBackup && settings.backupFolder != null -> "Backed up weekly" + (settings.lastBackup?.let { ", last ${Format.ago(it)}" } ?: "")
            extras.changes.isEmpty() -> "No automatic backup"
            else -> "No automatic backup · ${extras.changes.size} recent changes"
        }
    }
}

/**
 * Settings as a list of categories, each opening its own page; [section] opens straight at
 * one. Phones show one pane at a time; wide screens show the list beside the open page.
 * Opened straight at a section (from Today, say), back returns to where you came from.
 */
@Composable
fun SettingsScreen(section: SettingsSection?, back: () -> Unit) {
    val vm = appViewModel { SettingsViewModel(it) }
    val settings by vm.settings.collectAsStateWithLifecycle()
    val extras by vm.extras.collectAsStateWithLifecycle()
    val current = settings ?: return
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }
    SettingsContent(current, extras, section, vm, back, snackbar)
}

/** Settings without its view model; see [SettingsScreen]. */
@Composable
fun SettingsContent(
    current: Settings,
    extras: SettingsExtras,
    section: SettingsSection?,
    vm: SettingsCommands,
    back: () -> Unit,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
) {
    var open by rememberSaveable { mutableStateOf(section) }
    val actions = LocalAppActions.current

    BoxWithConstraints {
        val wide = maxWidth >= 720.dp
        val shown = open ?: SettingsSection.TRACKING.takeIf { wide }
        val leave: () -> Unit = {
            if (open == null || wide || open == section) {
                back()
            } else {
                open = null
            }
        }
        BackHandler(enabled = !wide && open != null && open != section) { open = null }
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopAppBar(
                    title = { Text(if (wide || open == null) "Settings" else open!!.title(), style = MaterialTheme.typography.titleLargeEmphasized) },
                    navigationIcon = { IconButton(onClick = leave) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
                )
            },
        ) { padding ->
            val contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 32.dp)
            if (wide) {
                Row {
                    CategoryList(current, extras, shown, onOpen = { open = it }, onRules = { actions.rules() }, contentPadding, Modifier.width(360.dp))
                    VerticalDivider(Modifier.fillMaxHeight().padding(top = padding.calculateTopPadding()))
                    shown?.let { SettingsPage(it, current, extras, vm, contentPadding, Modifier.weight(1f)) }
                }
            } else {
                AnimatedContent(open, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "settings") { page ->
                    if (page == null) {
                        CategoryList(current, extras, null, onOpen = { open = it }, onRules = { actions.rules() }, contentPadding)
                    } else {
                        SettingsPage(page, current, extras, vm, contentPadding)
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryList(
    settings: Settings,
    extras: SettingsExtras,
    selected: SettingsSection?,
    onOpen: (SettingsSection) -> Unit,
    onRules: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    // Rules is a place of its own, not a page of Settings, so it's a row among the categories.
    val rows: List<SettingsSection?> = categories.take(3) + listOf(null) + categories.drop(3)
    LazyColumn(modifier, contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        rows.forEachIndexed { index, category ->
            item(key = category?.name ?: "rules") {
                SegmentedListItem(
                    colors = segmentedColors(),
                    verticalAlignment = Alignment.CenterVertically,
                    selected = category != null && category == selected,
                    onClick = { if (category == null) onRules() else onOpen(category) },
                    shapes = ListItemDefaults.segmentedShapes(index, rows.size),
                    leadingContent = { ShapeIcon(category?.icon() ?: Icons.Rounded.Shield) },
                    supportingContent = {
                        Text(
                            if (category == null) {
                                when (extras.activeRules) {
                                    0 -> "Visa limits and tax residency tests to watch"
                                    1 -> "1 rule watched"
                                    else -> "${extras.activeRules} rules watched"
                                }
                            } else {
                                category.summary(settings, extras)
                            },
                            maxLines = 2,
                        )
                    },
                ) { Text(category?.title() ?: "Rules", style = MaterialTheme.typography.titleMedium) }
            }
        }
        item(key = "privacy") { PrivacyNote() }
    }
}

@Composable
private fun PrivacyNote() {
    Column(Modifier.padding(top = 24.dp, start = 8.dp, end = 8.dp)) {
        Icon(Icons.Rounded.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(8.dp))
        Text("Everything stays on this phone", style = MaterialTheme.typography.titleSmall)
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

/** How the tracking chip on Today describes the last check, kept beside the settings it reflects. */
internal fun lastCheckText(settings: Settings): String = when {
    settings.tracking == TrackingMode.OFF -> "Tracking is off"
    settings.lastCheck == null -> "Waiting for the first check"
    settings.lastCheckSource == Source.NETWORK.name -> "Mobile network · ${Format.ago(settings.lastCheck)}"
    else -> "Location · ${Format.ago(settings.lastCheck)}"
}
