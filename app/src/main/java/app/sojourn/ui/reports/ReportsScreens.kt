package app.sojourn.ui.reports

import app.sojourn.ui.components.PeriodMenu
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.sojourn.container
import app.sojourn.data.Countries
import app.sojourn.domain.DayBook
import app.sojourn.domain.FeieReport
import app.sojourn.domain.Meter
import app.sojourn.domain.Reports
import app.sojourn.ui.LocalAppActions
import app.sojourn.ui.components.DaysMeter
import app.sojourn.ui.components.FlagBadge
import app.sojourn.ui.components.Format
import app.sojourn.ui.components.SectionHeader
import app.sojourn.ui.components.segmentedColors
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun rememberBook(): DayBook? {
    val repository = LocalContext.current.container.repository
    return produceState<DayBook?>(null) { repository.book.collectLatest { value = it } }.value
}

@Composable
internal fun ReportScaffold(title: String, back: () -> Unit, actions: @Composable () -> Unit = {}, content: @Composable (PaddingValues) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, style = MaterialTheme.typography.titleLargeEmphasized) },
                navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
                actions = { actions() },
            )
        },
        content = content,
    )
}

private fun share(context: Context, subject: String, text: String) {
    val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, subject).putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(intent, subject))
}

/** Form 2555's physical presence test for a tax year, with the travel table for Part III. */
@Composable
fun FeieScreen(back: () -> Unit) {
    val book = rememberBook()
    val context = LocalContext.current
    val today = LocalDate.now()
    // Before the filing season ends, last year's return is the one being prepared.
    var year by rememberSaveable { mutableIntStateOf(if (today.monthValue <= 6) today.year - 1 else today.year) }
    val report = produceState<FeieReport?>(null, book, year) {
        value = book?.let { withContext(Dispatchers.Default) { Reports.feie(it, year) } }
    }.value
    val scope = rememberCoroutineScope()
    val saveCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val data = report ?: return@rememberLauncherForActivityResult
        uri ?: return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            context.contentResolver.openOutputStream(uri)?.use { it.write(Reports.feieCsv(data).toByteArray()) }
        }
    }
    FeieContent(
        report = report,
        year = year,
        years = (today.year downTo today.year - 2).toList(),
        onYear = { year = it },
        onShare = { share(context, "Form 2555 – ${it.taxYear}", Reports.feieText(it)) },
        onSaveCsv = { saveCsv.launch("form-2555-${it.taxYear}.csv") },
        back = back,
    )
}

/** Form 2555 as it looks for [report]; stateless, so screenshot tests can draw it. */
@Composable
fun FeieContent(
    report: FeieReport?,
    year: Int,
    years: List<Int>,
    onYear: (Int) -> Unit,
    onShare: (FeieReport) -> Unit,
    onSaveCsv: (FeieReport) -> Unit,
    back: () -> Unit,
) {
    val actions = LocalAppActions.current
    var exporting by remember { mutableStateOf(false) }
    ReportScaffold(
        title = "Form 2555",
        back = back,
        actions = {
            PeriodMenu(years.map { it to "$it" }, year, onYear, description = "Choose the tax year")
            Box {
                IconButton(onClick = { exporting = true }, enabled = report != null) { Icon(Icons.Rounded.Share, contentDescription = "Share or save") }
                DropdownMenu(expanded = exporting, onDismissRequest = { exporting = false }) {
                    DropdownMenuItem(
                        text = { Text("Share summary") },
                        leadingIcon = { Icon(Icons.Rounded.Share, contentDescription = null) },
                        onClick = {
                            exporting = false
                            report?.let(onShare)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Save as CSV") },
                        leadingIcon = { Icon(Icons.Rounded.Download, contentDescription = null) },
                        onClick = {
                            exporting = false
                            report?.let(onSaveCsv)
                        },
                    )
                }
            }
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (report == null) return@LazyColumn
            item { FeieHero(report) }
            if (report.untrackedDays > 0) {
                item {
                    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.tertiaryContainer) {
                        Row(Modifier.padding(start = 16.dp, top = 16.dp, end = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.Top) {
                            Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "${Format.days(report.untrackedDays)} in this period ${if (report.untrackedDays == 1) "has" else "have"} no country. Fill them in for an accurate count.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                )
                                TextButton(onClick = { actions.calendar(day = report.period.start, list = true) }) { Text("Fill them in") }
                            }
                        }
                    }
                }
            }
            item { SectionHeader("Part III, line 18: travel in the period") }
            if (report.rows.isEmpty()) {
                item { Text("No stays recorded in this period.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp)) }
            }
            itemsIndexed(report.rows, key = { _, row -> "${row.country}-${row.arrived}" }) { index, row ->
                SegmentedListItem(
                    colors = segmentedColors(),
                    verticalAlignment = Alignment.CenterVertically,
                    shapes = ListItemDefaults.segmentedShapes(index, report.rows.size),
                    leadingContent = { FlagBadge(row.country, size = 40.dp) },
                    supportingContent = { Text(Format.range(row.arrived, row.left)) },
                    trailingContent = {
                        Column(horizontalAlignment = Alignment.End) {
                            Text("${row.fullDays}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                            Text("full days", style = MaterialTheme.typography.labelSmall)
                        }
                    },
                ) { Text(Countries.name(row.country)) }
            }
            item {
                Row(Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp)) {
                    Icon(Icons.Rounded.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "A full day abroad is one with no time in the US. Days in the US on business and US income (columns e and f) are left for you to add. This is a helper, not tax advice.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun FeieHero(report: FeieReport) {
    val ok = report.qualifies
    val color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
    Surface(shape = RoundedCornerShape(32.dp), color = if (ok) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Physical presence test · tax year ${report.taxYear}", style = MaterialTheme.typography.labelLarge)
                    Text(
                        if (ok) "Qualifies" else "${FeieReport.REQUIRED_DAYS - report.fullDaysAbroad} days short",
                        style = MaterialTheme.typography.headlineMediumEmphasized,
                        color = color,
                    )
                }
                if (ok) Icon(Icons.Rounded.CheckCircle, contentDescription = "Qualifies", tint = color, modifier = Modifier.size(36.dp))
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "${report.fullDaysAbroad} of ${FeieReport.REQUIRED_DAYS} full days abroad",
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(14.dp))
            DaysMeter(
                Meter(report.fullDaysAbroad, 0, FeieReport.REQUIRED_DAYS, listOf(Meter.Mark(FeieReport.REQUIRED_DAYS, "330 days")), minimum = true),
                color,
                description = "${report.fullDaysAbroad} of ${FeieReport.REQUIRED_DAYS} full days abroad",
            )
            Spacer(Modifier.height(14.dp))
            Text("Line 16: the 12-month period", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${Format.medium(report.period.start)} – ${Format.medium(report.period.end)}", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "${report.qualifyingDaysInYear} of these days fall in ${report.taxYear}, which sets how much of the exclusion applies.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Where you've been, by country, for visa, residence and citizenship forms. */
@Composable
fun HistoryScreen(back: () -> Unit) {
    val book = rememberBook()
    val context = LocalContext.current
    val settings = produceState(app.sojourn.data.Settings(), Unit) { context.container.settings.settings.collectLatest { value = it } }.value
    var years by rememberSaveable { mutableIntStateOf(5) }
    var excludeHome by rememberSaveable { mutableStateOf(true) }
    val today = LocalDate.now()
    val from = if (years == 0) book?.firstRecorded ?: today else today.minusYears(years.toLong())
    val home = if (excludeHome) settings.citizenships else emptySet()
    val history = remember(book, from, home) { book?.let { Reports.travelHistory(it, from, home) }.orEmpty() }
    HistoryContent(
        history = history,
        from = from,
        untracked = remember(book, from) { book?.let { untrackedSince(it, from) } ?: 0 },
        years = years,
        onYears = { years = it },
        citizenships = settings.citizenships,
        excludeHome = excludeHome,
        onExcludeHome = { excludeHome = it },
        onShare = { share(context, "Travel history", Reports.travelHistoryText(history, from)) },
        back = back,
    )
}

/** Days since [from] with no country, which may hide trips. */
fun untrackedSince(book: DayBook, from: LocalDate): Int {
    if (book.isEmpty) return 0
    return book.gaps(maxOf(from, book.firstRecorded ?: book.today), book.today).sumOf { it.days }
}

/** Travel history as it looks for [history]; stateless, so screenshot tests can draw it. */
@Composable
fun HistoryContent(
    history: List<Reports.VisitedCountry>,
    from: LocalDate,
    untracked: Int,
    years: Int,
    onYears: (Int) -> Unit,
    citizenships: Set<String>,
    excludeHome: Boolean,
    onExcludeHome: (Boolean) -> Unit,
    onShare: () -> Unit,
    back: () -> Unit,
) {
    val actions = LocalAppActions.current
    val today = LocalDate.now()
    ReportScaffold(
        title = "Travel history",
        back = back,
        actions = {
            PeriodMenu(listOf(1 to "1 year", 5 to "5 years", 10 to "10 years", 0 to "All"), years, onYears, description = "Choose how far back")
            IconButton(onClick = onShare) { Icon(Icons.Rounded.Share, contentDescription = "Share") }
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
        ) {
            item {
                Column(Modifier.padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Visa and residence applications, citizenship files and Global Entry renewals ask where you've been, often over the last five years.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                    if (citizenships.isNotEmpty()) {
                        FilterChip(
                            selected = excludeHome,
                            onClick = { onExcludeHome(!excludeHome) },
                            label = { Text("Leave out ${citizenships.joinToString { Countries.name(it) }}") },
                        )
                    }
                    Text(
                        "${history.size} ${if (history.size == 1) "country" else "countries"} · ${history.sumOf { it.trips.size }} trips since ${Format.medium(from)}",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                    if (untracked > 0) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${Format.days(untracked)} ${if (untracked == 1) "has" else "have"} no country, so trips may be missing.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f).padding(start = 4.dp),
                            )
                            TextButton(onClick = { actions.calendar(list = true) }) { Text("Fill in") }
                        }
                    }
                }
            }
            itemsIndexed(history, key = { _, it -> it.country }) { index, visited ->
                SegmentedListItem(
                    colors = segmentedColors(),
                    verticalAlignment = Alignment.CenterVertically,
                    shapes = ListItemDefaults.segmentedShapes(index, history.size),
                    leadingContent = { FlagBadge(visited.country, size = 44.dp) },
                    supportingContent = {
                        Text(
                            visited.trips.take(3).joinToString("\n") { Format.range(it.start, it.end, today) } +
                                if (visited.trips.size > 3) "\n+${visited.trips.size - 3} more" else "",
                        )
                    },
                    trailingContent = {
                        Column(horizontalAlignment = Alignment.End) {
                            Text("${visited.trips.size}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                            Text(if (visited.trips.size == 1) "trip" else "trips", style = MaterialTheme.typography.labelSmall)
                        }
                    },
                ) { Text(Countries.name(visited.country), style = MaterialTheme.typography.titleMedium) }
            }
        }
    }
}
