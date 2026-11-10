package app.sojourn.ui.reports

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import app.sojourn.domain.Meter
import app.sojourn.ui.components.DaysMeter
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
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
import app.sojourn.domain.Reports
import app.sojourn.ui.components.segmentedColors
import app.sojourn.ui.components.FlagBadge
import app.sojourn.ui.components.ChoiceGroup
import app.sojourn.ui.components.Format
import app.sojourn.ui.components.SectionHeader
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
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val saveCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val data = report ?: return@rememberLauncherForActivityResult
        uri ?: return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            context.contentResolver.openOutputStream(uri)?.use { it.write(Reports.feieCsv(data).toByteArray()) }
        }
    }

    ReportScaffold(
        title = "Form 2555",
        back = back,
        actions = {
            IconButton(onClick = { report?.let { share(context, "Form 2555 – ${it.taxYear}", Reports.feieText(it)) } }) {
                Icon(Icons.Rounded.Share, contentDescription = "Share")
            }
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                val years = (today.year - 2..today.year).toList()
                ChoiceGroup(years.mapIndexed { index, y -> y to ("Tax year $y".takeIf { index == years.lastIndex } ?: "$y") }, year, { year = it })
            }
            if (report == null) return@LazyColumn
            item { FeieHero(report) }
            if (report.untrackedDays > 0) {
                item {
                    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.tertiaryContainer) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
                            Spacer(Modifier.width(12.dp))
                            Text(
                                "${Format.days(report.untrackedDays)} in this period have no country. Fill them in for an accurate count.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                        }
                    }
                }
            }
            item { SectionHeader("Part III, line 18 – travel in the period") }
            if (report.rows.isEmpty()) {
                item { Text("No stays recorded in this period.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            itemsIndexed(report.rows, key = { _, row -> "${row.country}-${row.arrived}" }) { index, row ->
                SegmentedListItem(
                    colors = segmentedColors(),
                    verticalAlignment = Alignment.CenterVertically,
                    shapes = ListItemDefaults.segmentedShapes(index, report.rows.size),
                    leadingContent = { FlagBadge(row.country, size = 40.dp) },
                    supportingContent = { Text("Arrived ${Format.medium(row.arrived)} · left ${Format.medium(row.left)}") },
                    trailingContent = {
                        Column(horizontalAlignment = Alignment.End) {
                            Text("${row.fullDays}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                            Text("full days", style = MaterialTheme.typography.labelSmall)
                        }
                    },
                ) { Text(Countries.name(row.country)) }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
                    FilledTonalButton(onClick = { saveCsv.launch("form-2555-${report.taxYear}.csv") }) {
                        Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Save as CSV")
                    }
                    OutlinedButton(onClick = { share(context, "Form 2555 – ${report.taxYear}", Reports.feieText(report)) }) { Text("Share summary") }
                }
            }
            item {
                Row(Modifier.padding(top = 8.dp)) {
                    Icon(Icons.Rounded.Info, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(18.dp))
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
                    Text("Physical presence test", style = MaterialTheme.typography.labelLarge)
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
            Text("Line 16 – 12-month period", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

    ReportScaffold(
        title = "Travel history",
        back = back,
        actions = {
            IconButton(onClick = { share(context, "Travel history", Reports.travelHistoryText(history, from)) }) {
                Icon(Icons.Rounded.Share, contentDescription = "Share")
            }
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
        ) {
            item {
                Column(Modifier.padding(bottom = 12.dp)) {
                    Text(
                        "Visa and residence applications, citizenship files and Global Entry renewals ask where you've been, often over the last five years.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(1 to "1 year", 5 to "5 years", 10 to "10 years", 0 to "All").forEach { (value, label) ->
                            FilterChip(selected = years == value, onClick = { years = value }, label = { Text(label) })
                        }
                    }
                    if (settings.citizenships.isNotEmpty()) {
                        FilterChip(
                            selected = excludeHome,
                            onClick = { excludeHome = !excludeHome },
                            label = { Text("Leave out ${settings.citizenships.joinToString { Countries.name(it) }}") },
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${history.size} countries · ${history.sumOf { it.trips.size }} trips since ${Format.medium(from)}",
                        style = MaterialTheme.typography.titleMedium,
                    )
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
