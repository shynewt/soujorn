package app.sojourn.ui.reports

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.sojourn.container
import app.sojourn.data.Countries
import app.sojourn.data.CountrySet
import app.sojourn.data.db.DocumentKind
import app.sojourn.domain.Evidence
import app.sojourn.ui.components.ChoiceGroup
import app.sojourn.ui.components.FlagBadge
import app.sojourn.ui.components.SectionHeader
import app.sojourn.ui.components.segmentedColors
import java.time.LocalDate
import kotlinx.coroutines.launch

/**
 * A year's evidence, ready to hand over: days per country with how each is backed up, and a
 * pack to export with every photo, boarding pass, calendar event and booking kept for it.
 */
@Composable
fun EvidenceScreen(back: () -> Unit) {
    val book = rememberBook()
    val context = LocalContext.current
    val container = context.container
    val documents by container.repository.documents.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val today = LocalDate.now()
    // Early in the year, last year's certificate is the one being asked for.
    var year by rememberSaveable { mutableIntStateOf(if (today.monthValue <= 3) today.year - 1 else today.year) }
    var exporting by remember { mutableStateOf(false) }
    val from = LocalDate.of(year, 1, 1)
    val to = minOf(LocalDate.of(year, 12, 31), today)
    val inYear = documents.filter { it.day in from..to }

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val current = book ?: return@rememberLauncherForActivityResult
        uri ?: return@rememberLauncherForActivityResult
        exporting = true
        scope.launch {
            val result = runCatching {
                context.contentResolver.openOutputStream(uri, "wt")!!.use { container.evidence.writePack(it, year, current, documents) }
            }
            exporting = false
            snackbar.showSnackbar(
                result.fold(
                    { r -> "Saved: ${r.days} days, ${r.files} files" + if (r.missing.isNotEmpty()) ", ${r.missing.size} missing (listed in the pack)" else "" },
                    { "Couldn't save: ${it.message}" },
                ),
            )
        }
    }

    ReportScaffold(title = "Evidence pack", back = back) { padding ->
        Box(Modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                val years = (book?.years().orEmpty().filter { it <= today.year }.ifEmpty { listOf(today.year) }).take(4).reversed()
                ChoiceGroup(years.map { it to "$it" }, year, { year = it })
            }
            item {
                Text(
                    "Everything that shows where you were in $year, for a tax residence certificate or any other request: a summary page to read or print, every day as a spreadsheet, and the photos, boarding passes, calendar events and bookings kept for them.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                Button(onClick = { save.launch("sojourn-evidence-$year.zip") }, enabled = book != null && !exporting, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Icon(Icons.Rounded.FolderZip, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (exporting) "Making the pack…" else "Export the $year pack")
                }
            }
            item {
                val counts = DocumentKind.entries.map { kind -> kind to inYear.count { it.kind == kind } }.filter { it.second > 0 }
                Text(
                    if (counts.isEmpty()) "No documents kept for $year yet. Import photos, calendar flights or boarding passes to add them: days you already have are backed up, not changed."
                    else "Kept for $year: " + counts.joinToString(" · ") { (kind, n) -> "$n ${kind.label.lowercase()}${if (n == 1) "" else "s"}" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val current = book ?: return@LazyColumn
            val countries = current.summary(from, to).countries.filter { it.days > 0 }
            if (countries.isNotEmpty()) item { SectionHeader("Days counted, and what backs them up") }
            itemsIndexed(countries, key = { _, c -> c.country }) { index, item ->
                val evidence = current.evidence(from, to, CountrySet(setOf(item.country)))
                SegmentedListItem(
                    colors = segmentedColors(),
                    verticalAlignment = Alignment.CenterVertically,
                    shapes = ListItemDefaults.segmentedShapes(index, countries.size),
                    leadingContent = { FlagBadge(item.country, size = 40.dp) },
                    supportingContent = {
                        Text(Evidence.entries.filter { evidence[it] > 0 }.joinToString(" · ") { "${evidence[it]} ${it.label.lowercase()}" })
                    },
                    trailingContent = { Text("${item.days}", style = MaterialTheme.typography.titleLarge) },
                ) { Text(Countries.name(item.country)) }
            }
            item {
                Row(Modifier.padding(top = 8.dp)) {
                    Icon(Icons.Rounded.Info, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(
                            "Documented: a photo, flight, booking or exported location history. Tracked: the phone's location. By hand: entered in Sojourn. Assumed: nothing recorded, the same country before and after.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Photos stay in your gallery and are copied into the pack only when you export it, so delete them there and they're gone from the pack too.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(padding))
        }
    }
}
