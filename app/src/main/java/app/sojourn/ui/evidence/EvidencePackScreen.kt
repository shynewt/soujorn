package app.sojourn.ui.evidence

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.sojourn.AppContainer
import app.sojourn.data.Countries
import app.sojourn.data.CountrySet
import app.sojourn.data.EvidenceStore
import app.sojourn.data.Settings
import app.sojourn.data.db.Document
import app.sojourn.data.db.DocumentKind
import app.sojourn.data.db.EvidenceGroup
import app.sojourn.data.db.Rule
import app.sojourn.domain.DayBook
import app.sojourn.domain.DayKind
import app.sojourn.ui.EvidenceDraft
import app.sojourn.ui.LocalAppActions
import app.sojourn.ui.appViewModel
import app.sojourn.ui.components.Format
import app.sojourn.ui.components.SectionHeader
import app.sojourn.ui.components.ShapeIcon
import app.sojourn.ui.components.segmentedColors
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Something the pack would be better with, and how to fix it. */
sealed interface PackGap {
    /** Days counted for [country] with nothing recorded, only assumed from the days around. */
    data class Assumed(val country: String, val days: Int) : PackGap

    /** Days for [country] entered by hand only. */
    data class ByHand(val country: String, val days: Int) : PackGap

    /** No tax residence certificate from [home], while [elsewhere] has a residency rule. */
    data class NoCertificate(val home: String, val elsewhere: List<String>) : PackGap
}

/** What a year's pack will hold, before it's made. */
data class PackPreview(
    val year: Int,
    val days: Int = 0,
    val countries: Int = 0,
    val groups: List<Pair<EvidenceGroup, Int>> = emptyList(),
    val photos: Int = 0,
    val gaps: List<PackGap> = emptyList(),
    val ready: Boolean = false,
)

/** Works out [year]'s pack: its days, documents by group, and what's missing or weak. */
fun packPreview(year: Int, book: DayBook, documents: List<Document>, settings: Settings, rules: List<Rule>): PackPreview {
    val from = LocalDate.of(year, 1, 1)
    val to = minOf(LocalDate.of(year, 12, 31), book.today)
    val inYear = documents.filter { it.overlaps(from, LocalDate.of(year, 12, 31)) }
    val summary = book.summary(from, to).countries.filter { it.days > 0 }.sortedByDescending { it.days }
    val gaps = buildList {
        val home = settings.taxResidence
        val elsewhere = residentElsewhere(settings, rules)
        if (home != null && elsewhere.isNotEmpty() && inYear.none { it.kind == DocumentKind.RESIDENCE_CERTIFICATE }) add(PackGap.NoCertificate(home, elsewhere))
        summary.forEach { item ->
            val evidence = book.evidence(from, to, CountrySet(setOf(item.country)))
            if (evidence.assumed > 0) add(PackGap.Assumed(item.country, evidence.assumed))
            if (evidence.manual > 0) add(PackGap.ByHand(item.country, evidence.manual))
        }
    }
    val groups = EvidenceGroup.entries.mapNotNull { group ->
        val n = bundle(inYear.filter { it.kind.group == group && it.kind != DocumentKind.PHOTO }).size
        (group to n).takeIf { n > 0 }
    }
    return PackPreview(
        year = year,
        days = book.between(from, to).count { it.kind != DayKind.PLANNED },
        countries = summary.size,
        groups = groups,
        photos = inYear.count { it.kind == DocumentKind.PHOTO },
        gaps = gaps,
        ready = true,
    )
}

/** Where making the pack is up to. */
sealed interface PackExport {
    data object Idle : PackExport
    data object Working : PackExport
    data class Done(val result: EvidenceStore.PackResult, val uri: Uri) : PackExport
    data class Failed(val reason: String) : PackExport
}

/** [year]'s pack: what it'll hold, and making it where it outlives the screen. */
class EvidencePackViewModel(private val container: AppContainer, private val year: Int) : ViewModel() {
    private val repository = container.repository

    val preview: StateFlow<PackPreview> = combine(repository.book, repository.documents, repository.settings, repository.rules) { book, documents, settings, rules ->
        packPreview(year, book, documents, settings, rules)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PackPreview(year))

    private val _export = MutableStateFlow<PackExport>(PackExport.Idle)
    val export: StateFlow<PackExport> = _export.asStateFlow()

    fun write(uri: Uri) {
        _export.value = PackExport.Working
        viewModelScope.launch {
            _export.value = runCatching {
                val book = repository.book.first()
                val documents = repository.documents.first()
                container.context.contentResolver.openOutputStream(uri, "wt")!!.use { container.evidence.writePack(it, year, book, documents) }
            }.fold({ PackExport.Done(it, uri) }, { PackExport.Failed(it.message ?: "the file couldn't be written") })
        }
    }
}

/** Exports [year]'s evidence pack, after showing what it holds and what it's missing. */
@Composable
fun EvidencePackScreen(year: Int, back: () -> Unit) {
    val vm = appViewModel { EvidencePackViewModel(it, year) }
    val preview by vm.preview.collectAsStateWithLifecycle()
    val export by vm.export.collectAsStateWithLifecycle()
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> uri?.let(vm::write) }
    EvidencePackContent(preview, export, back, onExport = { save.launch("sojourn-evidence-$year.zip") })
}

@Composable
fun EvidencePackContent(preview: PackPreview, export: PackExport, back: () -> Unit, onExport: () -> Unit) {
    val actions = LocalAppActions.current
    val context = LocalContext.current
    val year = preview.year
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("$year evidence pack", style = MaterialTheme.typography.titleLargeEmphasized) },
                navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
        ) {
            item {
                Text(
                    "Everything that shows where you were in $year, for a tax residence certificate or an audit, as one file to keep or hand over.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                )
            }
            item { SectionHeader("What goes in") }
            val contents = buildList {
                add(Triple(Icons.Rounded.CalendarMonth, "${Format.days(preview.days)} in ${preview.countries} ${if (preview.countries == 1) "country" else "countries"}", "Each day with where you were, whether it counts, and what shows it, as a summary page and a spreadsheet"))
                preview.groups.forEach { (group, n) -> add(Triple(groupIcon(group), group.label, if (n == 1) "1 document" else "$n documents")) }
                if (preview.photos > 0) add(Triple(DocumentKind.PHOTO.icon, "Photos", "${preview.photos}, copied from your gallery with their dates and places"))
            }
            itemsIndexed(contents, key = { _, c -> "in-${c.second}" }) { index, (icon, title, text) ->
                SegmentedListItem(
                    colors = segmentedColors(),
                    verticalAlignment = Alignment.CenterVertically,
                    shapes = ListItemDefaults.segmentedShapes(index, contents.size),
                    leadingContent = { ShapeIcon(icon) },
                    supportingContent = { Text(text) },
                ) { Text(title) }
            }

            item { SectionHeader("Worth a look first", Modifier.padding(top = 12.dp)) }
            if (preview.ready && preview.gaps.isEmpty()) {
                item {
                    SegmentedListItem(
                        colors = segmentedColors(),
                        verticalAlignment = Alignment.CenterVertically,
                        shapes = ListItemDefaults.segmentedShapes(0, 1),
                        leadingContent = { Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                    ) { Text("Nothing missing that Sojourn can see.") }
                }
            }
            itemsIndexed(preview.gaps, key = { _, g -> "gap-$g" }) { index, gap ->
                val (title, text, fix) = when (gap) {
                    is PackGap.NoCertificate -> Triple(
                        "No tax residence certificate",
                        "${Countries.name(gap.elsewhere.first())} may count you as resident; a certificate from ${Countries.name(gap.home)} is the strongest answer.",
                        "Add" to { actions.addEvidence(EvidenceDraft(day = LocalDate.of(year, 1, 1), until = LocalDate.of(year, 12, 31), country = gap.home, kind = DocumentKind.RESIDENCE_CERTIFICATE)) },
                    )
                    is PackGap.Assumed -> Triple(
                        "${Countries.flag(gap.country)} ${Format.days(gap.days)} assumed",
                        "Counted for ${Countries.name(gap.country)} with nothing recorded, only the days around them.",
                        "Find" to actions::import,
                    )
                    is PackGap.ByHand -> Triple(
                        "${Countries.flag(gap.country)} ${Format.days(gap.days)} entered by hand",
                        "A photo, ticket or receipt from those days would back them up.",
                        "See" to { actions.calendar() },
                    )
                }
                SegmentedListItem(
                    colors = segmentedColors(),
                    verticalAlignment = Alignment.CenterVertically,
                    shapes = ListItemDefaults.segmentedShapes(index, preview.gaps.size),
                    leadingContent = { Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary) },
                    supportingContent = { Text(text) },
                    trailingContent = { TextButton(onClick = fix.second) { Text(fix.first) } },
                ) { Text(title) }
            }

            item {
                Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = onExport,
                        enabled = preview.ready && export != PackExport.Working,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                    ) {
                        Icon(Icons.Rounded.FolderZip, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (export == PackExport.Working) "Making the pack…" else "Export the $year pack")
                    }
                    if (export == PackExport.Working) LinearProgressIndicator(Modifier.fillMaxWidth())
                    when (export) {
                        is PackExport.Done -> ExportResult(export) {
                            val share = Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, export.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            runCatching { context.startActivity(Intent.createChooser(share, "$year evidence pack")) }
                        }
                        is PackExport.Failed -> Text("Couldn't save the pack: ${export.reason}.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                        else -> Unit
                    }
                }
            }
            item {
                Row(Modifier.padding(top = 16.dp, start = 4.dp, end = 4.dp)) {
                    Icon(Icons.Rounded.Info, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Photos stay in your gallery and are copied into the pack only when you make it, so a photo deleted there is missing here too. The pack is saved where you choose; nothing is sent anywhere.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ExportResult(done: PackExport.Done, onShare: () -> Unit) {
    val result = done.result
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Saved", style = MaterialTheme.typography.titleMedium)
            Text("${Format.days(result.days)} and ${if (result.files == 1) "1 file" else "${result.files} files"}.", style = MaterialTheme.typography.bodyMedium)
            if (result.missing.isNotEmpty()) {
                Text("Not included, and listed in the pack:", style = MaterialTheme.typography.bodyMedium)
                result.missing.take(8).forEach { Text("· $it", style = MaterialTheme.typography.bodySmall) }
                if (result.missing.size > 8) Text("and ${result.missing.size - 8} more", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(onClick = onShare) {
                Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Share")
            }
        }
    }
}

private fun groupIcon(group: EvidenceGroup) = when (group) {
    EvidenceGroup.TRAVEL -> DocumentKind.BOARDING_PASS.icon
    EvidenceGroup.PRESENCE -> DocumentKind.CARD_STATEMENT.icon
    EvidenceGroup.HOME -> DocumentKind.LEASE.icon
    EvidenceGroup.TIES -> DocumentKind.WORK.icon
    EvidenceGroup.OTHER -> DocumentKind.OTHER.icon
}
