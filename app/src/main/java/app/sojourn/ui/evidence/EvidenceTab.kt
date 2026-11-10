package app.sojourn.ui.evidence

import app.sojourn.ui.components.YearMenu
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFloatingActionButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sojourn.data.Countries
import app.sojourn.data.db.Document
import app.sojourn.data.db.DocumentKind
import app.sojourn.domain.Evidence
import app.sojourn.ui.EvidenceDraft
import app.sojourn.ui.LocalAppActions
import app.sojourn.ui.appViewModel
import app.sojourn.ui.components.BarSegment
import app.sojourn.ui.components.EmptyState
import app.sojourn.ui.components.FlagBadge
import app.sojourn.ui.components.Format
import app.sojourn.ui.components.LegendDot
import app.sojourn.ui.components.SectionHeader
import app.sojourn.ui.components.ShapeIcon
import app.sojourn.ui.components.StackedBar
import app.sojourn.ui.components.segmentedColors
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** The Evidence tab: the year's vault, its coverage, checklist, documents and exports. */
@Composable
fun EvidenceTab() {
    val vm = appViewModel { EvidenceViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    EvidenceContent(state, onYear = vm::choose)
}

/** What's open over the tab: a document, or a month of photos. */
private sealed interface Opened {
    data class One(val kept: Kept) : Opened
    data class Photos(val month: PhotoMonth) : Opened
}

@Composable
fun EvidenceContent(state: EvidenceState, onYear: (Int) -> Unit) {
    val actions = LocalAppActions.current
    var opened by remember { mutableStateOf<Opened?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Evidence", style = MaterialTheme.typography.titleLargeEmphasized) },
                actions = { YearMenu(state.year, state.years, { it?.let(onYear) }) },
            )
        },
        floatingActionButton = {
            MediumFloatingActionButton(onClick = { actions.addEvidence(EvidenceDraft(day = defaultDay(state))) }) {
                Icon(Icons.Rounded.Add, contentDescription = "Add evidence", modifier = Modifier.size(FloatingActionButtonDefaults.MediumIconSize))
            }
        },
    ) { padding ->
        if (state.loading) return@Scaffold
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 112.dp),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
        ) {
            if (state.isEmpty) {
                item {
                    EmptyState(
                        Icons.Rounded.TravelExplore,
                        "Nothing for ${state.year} yet",
                        "Sojourn can find your flights in the calendar, boarding passes and photos, and keep them as evidence. Nothing leaves your phone.",
                        action = { Button(onClick = actions::import) { Text("Find evidence") } },
                    )
                }
            }
            if (state.coverage.isNotEmpty()) {
                item { SectionHeader("How well ${state.year} is backed up") }
                item { CoverageLegend() }
                itemsIndexed(state.coverage, key = { _, c -> "c-${c.country}" }) { index, row ->
                    CoverageRow(row, index, state.coverage.size) { actions.calendar() }
                }
                item {
                    Text(
                        "Documented and tracked days show you were there that day. Assumed days are taken from the days around them, and one proven day elsewhere breaks them.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 6.dp),
                    )
                }
            }
            if (!state.isEmpty) {
                item { SectionHeader("For the whole year", Modifier.padding(top = 12.dp)) }
                itemsIndexed(state.checklist, key = { _, c -> "k-${c.kind}" }) { index, check ->
                    ChecklistRow(check, index, state.checklist.size) {
                        actions.addEvidence(EvidenceDraft(day = LocalDate.of(state.year, 1, 1), until = LocalDate.of(state.year, 12, 31), country = check.country, kind = check.kind))
                    }
                }
            }
            state.groups.forEach { group ->
                item(key = "g-${group.group}") {
                    Text(
                        "${group.group.label} · ${group.size}",
                        style = MaterialTheme.typography.titleMediumEmphasized,
                        modifier = Modifier.padding(start = 4.dp, top = 20.dp, bottom = 4.dp),
                    )
                }
                val count = group.kept.size + group.photos.size
                itemsIndexed(group.photos, key = { _, m -> "p-${m.month}" }) { index, month ->
                    PhotoMonthRow(month, index, count) { opened = Opened.Photos(month) }
                }
                itemsIndexed(group.kept, key = { _, k -> "d-${k.first.id}" }) { index, kept ->
                    DocumentRow(
                        kept,
                        group.photos.size + index,
                        count,
                        onClick = { opened = Opened.One(kept) },
                        flight = kept.first.flightId?.let(state.flights::get),
                        today = state.today,
                    )
                }
            }
            // An empty year has one thing to do, offered above.
            if (state.isEmpty) return@LazyColumn
            item { SectionHeader("Reports", Modifier.padding(top = 20.dp)) }
            val reports = buildList {
                add(Report(Icons.Rounded.FolderZip, "${state.year} evidence pack", "A summary page, every day, and every document, as one file to hand over") { actions.evidencePack(state.year) })
                add(Report(Icons.Rounded.History, "Travel history", "Every trip abroad with its dates, for visa and citizenship forms", actions::history))
                if (state.usTaxpayer) add(Report(Icons.AutoMirrored.Rounded.ReceiptLong, "Form 2555", "Days abroad for the foreign earned income exclusion", actions::feie))
                add(Report(Icons.Rounded.TravelExplore, "Find evidence", "Calendar flights, boarding passes and photos", actions::import))
            }
            itemsIndexed(reports, key = { _, r -> "r-${r.title}" }) { index, report ->
                SegmentedListItem(
                    colors = segmentedColors(),
                    verticalAlignment = Alignment.CenterVertically,
                    onClick = report.open,
                    shapes = ListItemDefaults.segmentedShapes(index, reports.size),
                    leadingContent = { ShapeIcon(report.icon) },
                    supportingContent = { Text(report.text) },
                ) { Text(report.title) }
            }
        }
    }
    when (val open = opened) {
        is Opened.One -> DocumentSheet(open.kept, open.kept.first.flightId?.let(state.flights::get), onDismiss = { opened = null })
        is Opened.Photos -> PhotosSheet(open.month, onDismiss = { opened = null }, onOpen = { opened = Opened.One(Kept(listOf(it))) })
        null -> Unit
    }
}

private data class Report(val icon: ImageVector, val title: String, val text: String, val open: () -> Unit)

/** A new piece of evidence starts on today in this year, or on the year's last day before. */
private fun defaultDay(state: EvidenceState): LocalDate =
    if (state.year == state.today.year) state.today else LocalDate.of(state.year, 12, 31)

/** The colours of the evidence tiers: presence in the primary hue, strongest fullest; each is also named in words. */
@Composable
private fun tierColor(evidence: Evidence): Color = when (evidence) {
    Evidence.DOCUMENTED -> MaterialTheme.colorScheme.primary
    Evidence.TRACKED -> MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
    Evidence.MANUAL -> MaterialTheme.colorScheme.tertiary
    Evidence.ASSUMED -> MaterialTheme.colorScheme.outline
}

@Composable
private fun CoverageLegend() {
    FlowRow(
        Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Evidence.entries.forEach { tier ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                LegendDot(tierColor(tier))
                Spacer(Modifier.width(6.dp))
                Text(tier.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** A country's days and a bar of how they're backed up, with the same numbers in words. */
@Composable
private fun CoverageRow(row: Coverage, index: Int, count: Int, onClick: () -> Unit) {
    val words = Evidence.entries.filter { row.evidence[it] > 0 }.joinToString(" · ") { "${row.evidence[it]} ${it.label.lowercase()}" }
    SegmentedListItem(
        colors = segmentedColors(),
        verticalAlignment = Alignment.CenterVertically,
        onClick = onClick,
        shapes = ListItemDefaults.segmentedShapes(index, count),
        leadingContent = { FlagBadge(row.country, size = 40.dp) },
        trailingContent = { Text("${row.days}", style = MaterialTheme.typography.titleLarge) },
        supportingContent = {
            Column(Modifier.padding(top = 6.dp)) {
                StackedBar(
                    Evidence.entries.map { BarSegment(tierColor(it), row.evidence[it].toFloat(), planned = it == Evidence.ASSUMED) },
                    height = 10.dp,
                    gap = 2.dp,
                )
                Spacer(Modifier.height(4.dp))
                Text(words.ifEmpty { "Nothing recorded" }, style = MaterialTheme.typography.bodySmall)
            }
        },
    ) { Text(Countries.name(row.country)) }
}

@Composable
private fun ChecklistRow(check: CheckItem, index: Int, count: Int, onAdd: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val highlight = check.prominent && !check.kept
    SegmentedListItem(
        colors = if (highlight) ListItemDefaults.segmentedColors(containerColor = scheme.tertiaryContainer, contentColor = scheme.onTertiaryContainer, supportingContentColor = scheme.onTertiaryContainer)
        else segmentedColors(),
        verticalAlignment = Alignment.CenterVertically,
        // Kept ones can take another: a second bill, a renewed lease.
        onClick = onAdd,
        shapes = ListItemDefaults.segmentedShapes(index, count),
        leadingContent = {
            Icon(
                if (check.kept) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                contentDescription = if (check.kept) "Kept" else "Not kept yet",
                tint = if (check.kept) scheme.primary else if (highlight) scheme.onTertiaryContainer else scheme.outline,
            )
        },
        supportingContent = { Text(if (check.kept) "Kept for the year. Tap to add another." else check.why) },
        trailingContent = if (check.kept) null else ({ FilledTonalButton(onClick = onAdd) { Text("Add") } }),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(check.title, modifier = Modifier.weight(1f, fill = false))
            check.country?.takeIf { check.kind == DocumentKind.RESIDENCE_CERTIFICATE }?.let {
                Spacer(Modifier.width(6.dp))
                Text(Countries.flag(it))
            }
        }
    }
}

@Composable
private fun PhotoMonthRow(month: PhotoMonth, index: Int, count: Int, onClick: () -> Unit) {
    val name = monthName(month)
    SegmentedListItem(
        colors = segmentedColors(),
        verticalAlignment = Alignment.CenterVertically,
        onClick = onClick,
        shapes = ListItemDefaults.segmentedShapes(index, count),
        leadingContent = { ShapeIcon(Icons.Rounded.PhotoLibrary) },
        supportingContent = {
            val days = month.photos.map { it.day }.distinct().size
            Text("${Format.days(days)} with a photo · ${month.countries.joinToString(" ") { Countries.flag(it) }}", maxLines = 1)
        },
        trailingContent = { StrengthTag(DocumentKind.PHOTO.strength) },
    ) { Text("Photos, $name") }
}

/** "May": the month alone, as the photos' rows are already within the chosen year. */
private fun monthName(month: PhotoMonth): String =
    month.month.month.getDisplayName(TextStyle.FULL_STANDALONE, Locale.getDefault()).replaceFirstChar { it.titlecase(Locale.getDefault()) }

/** A month's photos as a grid; each opens to view, change or remove it. */
@Composable
private fun PhotosSheet(month: PhotoMonth, onDismiss: () -> Unit, onOpen: (Document) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            "Photos, ${Format.month(month.month.atDay(1))}",
            style = MaterialTheme.typography.titleLargeEmphasized,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Text(
            "Linked from your gallery, one per day and country. They're copied only into the evidence pack.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        LazyVerticalGrid(
            GridCells.Adaptive(96.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(month.photos, key = { it.id }) { photo ->
                Column(
                    Modifier.clickable(onClickLabel = "Open the photo of ${Format.short(photo.day)}") { onOpen(photo) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    EvidenceThumb(photo, 96.dp)
                    Text("${photo.day.dayOfMonth} ${Countries.flag(photo.country)}", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}
