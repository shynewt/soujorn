package app.sojourn.ui.calendar

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Today
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sojourn.data.CalendarView
import app.sojourn.data.Countries
import app.sojourn.data.RangeEdit
import app.sojourn.data.db.Document
import app.sojourn.data.db.Flight
import app.sojourn.domain.DayBook
import app.sojourn.domain.DayKind
import app.sojourn.ui.appViewModel
import app.sojourn.ui.components.ChoiceGroup
import app.sojourn.ui.components.CountryPickerSheet
import app.sojourn.ui.components.Format
import app.sojourn.ui.flights.FlightEditorSheet
import app.sojourn.ui.theme.CountryPalette
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.WeekFields
import kotlinx.coroutines.launch

private val FIRST_MONTH: YearMonth = YearMonth.of(2000, 1)
private const val MONTH_COUNT = 1212

@Composable
fun CalendarScreen() {
    val vm = appViewModel { CalendarViewModel(it) }
    val book by vm.book.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val locale = LocalConfiguration.current.locales[0]
    val firstDay = if (settings.weekStart in 1..7) DayOfWeek.of(settings.weekStart) else WeekFields.of(locale).firstDayOfWeek
    val lastEdit by vm.lastEdit.collectAsStateWithLifecycle()
    val documents by vm.documents.collectAsStateWithLifecycle()
    CalendarContent(book, firstDay, vm, settings.calendarView, lastEdit?.message, documents)
}

/** Recorded days in a range that hold a country other than [keep]: what setting [keep] would overwrite. */
internal fun overwritten(book: DayBook?, from: LocalDate, to: LocalDate, keep: String?): Map<String, Int> {
    book ?: return emptyMap()
    val counts = LinkedHashMap<String, Int>()
    for (day in book.between(from, minOf(to, book.today))) {
        if (day.kind != DayKind.RECORDED) continue
        day.countries.filter { it != keep }.forEach { counts[it] = (counts[it] ?: 0) + 1 }
    }
    return counts.entries.sortedByDescending { it.value }.associate { it.key to it.value }
}

private fun overwrittenText(counts: Map<String, Int>): String =
    counts.entries.joinToString { (country, days) -> "${Countries.flag(country)} ${Countries.name(country)} (${Format.days(days)})" }

/** The range being picked, and which change the user chose for it. */
private enum class Pending { SET, ADD, REMOVE }

@Composable
fun CalendarContent(
    book: DayBook?,
    firstDay: DayOfWeek,
    actions: CalendarActions,
    view: CalendarView = CalendarView.MONTH,
    lastEdit: String? = null,
    documents: List<Document> = emptyList(),
) {
    val today = book?.today ?: LocalDate.now()
    val todayPage = ChronoUnit.MONTHS.between(FIRST_MONTH, YearMonth.from(today)).toInt()
    val pager = rememberPagerState(initialPage = todayPage) { MONTH_COUNT }
    var year by rememberSaveable { mutableIntStateOf(today.year) }
    val scope = rememberCoroutineScope()
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()

    var anchor by remember { mutableStateOf<LocalDate?>(null) }
    var end by remember { mutableStateOf<LocalDate?>(null) }
    var openDay by remember { mutableStateOf<LocalDate?>(null) }
    var pending by remember { mutableStateOf<Pending?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var editingFlight by remember { mutableStateOf<Flight?>(null) }
    var confirmSet by remember { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(lastEdit) {
        val message = lastEdit ?: return@LaunchedEffect
        val result = snackbar.showSnackbar(message, actionLabel = "Undo", withDismissAction = true, duration = SnackbarDuration.Long)
        if (result == SnackbarResult.ActionPerformed) actions.undo() else actions.dismissUndo()
    }
    val selection: Selection? = anchor?.let { a -> (end ?: a).let { e -> if (e < a) e to a else a to e } }
    fun clearSelection() {
        anchor = null
        end = null
    }

    val hits = remember { DayHitTest() }
    val callbacks = remember {
        DayGestureCallbacks(
            onTap = { date -> if (anchor != null) end = date else openDay = date },
            onLongPress = { date ->
                anchor = date
                end = null
            },
            onDragTo = { date -> end = date },
        )
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Calendar", style = MaterialTheme.typography.titleLargeEmphasized) },
                scrollBehavior = scroll,
                actions = {
                    IconButton(onClick = {
                        year = today.year
                        scope.launch { pager.animateScrollToPage(todayPage) }
                    }) { Icon(Icons.Rounded.Today, contentDescription = "Go to today") }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.fillMaxSize()) {
                ChoiceGroup(
                    CalendarView.entries.map { it to it.label },
                    view,
                    actions::setView,
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                when (view) {
                    CalendarView.MONTH -> MonthView(book, firstDay, today, pager, selection, hits, callbacks, Modifier.weight(1f))
                    else -> YearView(view, year, { year = it }, book, firstDay, today, selection, hits, callbacks, Modifier.weight(1f))
                }
            }

            AnimatedVisibility(
                visible = selection != null,
                enter = fadeIn() + scaleIn(initialScale = 0.8f),
                exit = fadeOut() + scaleOut(targetScale = 0.8f),
                modifier = Modifier.align(Alignment.BottomCenter).offset(y = (-16).dp),
            ) {
                val range = selection ?: (today to today)
                val days = ChronoUnit.DAYS.between(range.first, range.second).toInt() + 1
                val present = book?.between(range.first, range.second)?.flatMap { it.countries }?.distinct().orEmpty()
                HorizontalFloatingToolbar(
                    expanded = true,
                    colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors(),
                    leadingContent = { ToolbarButton(Icons.Rounded.Close, "Cancel selection") { clearSelection() } },
                    trailingContent = {
                        if (present.isNotEmpty()) {
                            ToolbarButton(Icons.Rounded.RemoveCircleOutline, "Take a country off these days") { pending = Pending.REMOVE }
                            ToolbarButton(Icons.Rounded.DeleteSweep, "Clear these days") { confirmClear = true }
                        }
                        ToolbarButton(Icons.Rounded.AddCircleOutline, "Add a country to these days") { pending = Pending.ADD }
                        TooltipIcon("Set the country of these days") {
                            FilledIconButton(onClick = { pending = Pending.SET }) { Icon(Icons.Rounded.Flag, contentDescription = "Set the country of these days") }
                        }
                    },
                ) {
                    Column(Modifier.padding(horizontal = 8.dp)) {
                        Text(Format.days(days), style = MaterialTheme.typography.labelLarge)
                        Text(Format.range(range.first, range.second, today), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }

    openDay?.let { date ->
        DaySheet(date = date, book = book, actions = actions, onDismiss = { openDay = null }, onFlight = { editingFlight = it }, documents = documents)
    }
    editingFlight?.let { flight ->
        FlightEditorSheet(
            initial = flight,
            recentAirports = book?.flights.orEmpty().asReversed().flatMap { listOf(it.destination, it.origin) }.distinct().take(8),
            preview = actions::previewFlight,
            onDismiss = { editingFlight = null },
            onSave = {
                actions.saveFlight(it)
                editingFlight = null
            },
            onDelete = {
                actions.deleteFlight(it)
                editingFlight = null
            },
        )
    }
    val range = selection
    if (pending != null && range != null) {
        val (from, to) = range
        val present = book?.between(from, to)?.flatMap { it.countries }?.distinct().orEmpty()
        val span = Format.range(from, to, today)
        CountryPickerSheet(
            title = when (pending!!) {
                Pending.SET -> if (from > today) "Plan $span in…" else "Where were you, $span?"
                Pending.ADD -> "Add a country to $span"
                Pending.REMOVE -> "Take a country off $span"
            },
            suggestions = if (pending == Pending.REMOVE) present else book?.recentCountries().orEmpty(),
            only = if (pending == Pending.REMOVE) present else null,
            onDismiss = { pending = null },
            onPick = { country ->
                // Overwriting recorded days of other countries is asked first, with what goes.
                if (pending == Pending.SET && from != to && overwritten(book, from, to, country).isNotEmpty()) {
                    confirmSet = country
                    pending = null
                    return@CountryPickerSheet
                }
                val edit = when (pending!!) {
                    Pending.SET -> RangeEdit.SET
                    Pending.ADD -> RangeEdit.ADD
                    Pending.REMOVE -> RangeEdit.REMOVE
                }
                actions.edit(from, to, edit, country)
                pending = null
                clearSelection()
            },
        )
    }
    val setTo = confirmSet
    if (setTo != null && range != null) {
        val lost = overwritten(book, range.first, range.second, setTo)
        AlertDialog(
            onDismissRequest = { confirmSet = null },
            title = { Text("Replace recorded days?") },
            text = {
                Text(
                    "Setting ${Format.range(range.first, range.second, today)} (${Format.days(ChronoUnit.DAYS.between(range.first, range.second).toInt() + 1)}) " +
                        "to ${Countries.flag(setTo)} ${Countries.name(setTo)} replaces what was recorded there: ${overwrittenText(lost)}. " +
                        "To keep them and only fill the empty days, add the country instead.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    actions.edit(range.first, range.second, RangeEdit.SET, setTo)
                    confirmSet = null
                    clearSelection()
                }) { Text("Replace", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { confirmSet = null }) { Text("Cancel") }
                    TextButton(onClick = {
                        actions.edit(range.first, range.second, RangeEdit.ADD, setTo)
                        confirmSet = null
                        clearSelection()
                    }) { Text("Add instead") }
                }
            },
        )
    }
    if (confirmClear && range != null) {
        val recorded = range.first <= today
        val lost = overwritten(book, range.first, range.second, null)
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear ${Format.days(ChronoUnit.DAYS.between(range.first, range.second).toInt() + 1)}?") },
            text = {
                Text(
                    "${Format.range(range.first, range.second, today)}: " +
                        if (recorded) "every country recorded or planned on these days is removed${if (lost.isNotEmpty()) ", including ${overwrittenText(lost)}" else ""}. Days from flights stay until you change the flights."
                        else "every planned country on these days is removed, including stays your flights imply.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    actions.edit(range.first, range.second, RangeEdit.CLEAR, null)
                    confirmClear = false
                    clearSelection()
                }) { Text("Clear", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun TooltipIcon(label: String, content: @Composable () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) { content() }
}

@Composable
private fun ToolbarButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    TooltipIcon(label) { IconButton(onClick = onClick) { Icon(icon, contentDescription = label) } }
}

@Composable
private fun MonthView(
    book: DayBook?,
    firstDay: DayOfWeek,
    today: LocalDate,
    pager: androidx.compose.foundation.pager.PagerState,
    selection: Selection?,
    hits: DayHitTest,
    callbacks: DayGestureCallbacks,
    modifier: Modifier,
) {
    val scope = rememberCoroutineScope()
    val locale = LocalConfiguration.current.locales[0]
    val month = FIRST_MONTH.plusMonths(pager.currentPage.toLong())
    // Only the month on screen can be touched, whatever the pager keeps around it.
    hits.accepts = { YearMonth.from(it) == month && !pager.isScrollInProgress }
    DisposableEffect(hits) { onDispose { hits.accepts = { true } } }
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text(Format.month(month.atDay(1)), style = MaterialTheme.typography.headlineSmallEmphasized, modifier = Modifier.weight(1f))
            IconButton(onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } }) {
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, contentDescription = "Previous month")
            }
            IconButton(onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }) {
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = "Next month")
            }
        }
        Spacer(Modifier.height(8.dp))
        Surface(shape = RoundedCornerShape(32.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.padding(horizontal = 8.dp, vertical = 12.dp)) {
                Row(Modifier.clearAndSetSemantics { }) {
                    (0 until 7).forEach { i ->
                        Text(
                            firstDay.plus(i.toLong()).getDisplayName(TextStyle.SHORT, locale),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                HorizontalPager(state = pager, beyondViewportPageCount = 1, modifier = Modifier.dayGestures(hits, callbacks)) { page ->
                    MonthGrid(FIRST_MONTH.plusMonths(page.toLong()), firstDay, book, today, selection, hits, callbacks)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        MonthSummary(book, month, today)
        Text(
            "Long-press a day and drag to select several. Then set, add or take off a country on all of them.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 16.dp),
        )
        Spacer(Modifier.height(96.dp))
    }
}

/** A year at a glance, as month cards (Grid, Compact) or a row per month (Rows). */
@Composable
private fun YearView(
    view: CalendarView,
    year: Int,
    onYear: (Int) -> Unit,
    book: DayBook?,
    firstDay: DayOfWeek,
    today: LocalDate,
    selection: Selection?,
    hits: DayHitTest,
    callbacks: DayGestureCallbacks,
    modifier: Modifier,
) {
    val from = LocalDate.of(year, 1, 1)
    val to = LocalDate.of(year, 12, 31)
    val summary = remember(book, year) { book?.summary(from, to) }
    val ranked = summary?.countries.orEmpty().sortedByDescending { it.total }
    val colors = remember(ranked) { CountryPalette.assign(ranked.map { it.country }) }
    val legend = ranked.map { Triple(it.country, colors.getValue(it.country), it.total) }
    val header: @Composable () -> Unit = {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onYear(year - 1) }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, contentDescription = "Previous year") }
                Text("$year", style = MaterialTheme.typography.headlineSmallEmphasized, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                IconButton(onClick = { onYear(year + 1) }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = "Next year") }
            }
            YearLegend(legend, Modifier.padding(bottom = 8.dp))
        }
    }
    val footer: @Composable () -> Unit = {
        Text(
            "Each colour is a country. Travel days are split corner to corner, from where the day started to where it ended; planned days are pale with a dashed edge, and striped days are assumed (nothing recorded, the same country before and after). Long-press and drag to select days.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 112.dp),
        )
    }
    if (view == CalendarView.ROWS) {
        Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
            header()
            Box(Modifier.dayGestures(hits, callbacks)) { YearRows(year, book, today, colors, selection, hits, callbacks) }
            footer()
        }
        return
    }
    val compact = view == CalendarView.COMPACT
    LazyVerticalGrid(
        columns = GridCells.Adaptive(if (compact) 104.dp else 160.dp),
        modifier = modifier.fillMaxWidth().dayGestures(hits, callbacks),
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) { header() }
        items((1..12).map { YearMonth.of(year, it) }, key = { it.toString() }) { month ->
            MonthCard(month, firstDay, book, today, colors, selection, compact, hits, callbacks)
        }
        item(span = { GridItemSpan(maxLineSpan) }) { footer() }
    }
}

@Composable
private fun MonthSummary(book: DayBook?, month: YearMonth, today: LocalDate) {
    book ?: return
    val summary = remember(book, month) { book.summary(month.atDay(1), month.atEndOfMonth()) }
    val untracked = remember(book, month) { if (book.isEmpty) 0 else book.gaps(maxOf(month.atDay(1), book.firstRecorded ?: today), month.atEndOfMonth()).sumOf { it.days } }
    val assumed = remember(book, month) { book.between(month.atDay(1), month.atEndOfMonth()).count { it.kind == DayKind.INFERRED } }
    val faded = remember(book, month) {
        book.between(month.atDay(1), month.atEndOfMonth()).any { day -> day.countries.any { it !in day.transit && !book.countsFor(it, day) } }
    }
    if (summary.countries.isEmpty() && untracked == 0) return
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(horizontal = 4.dp),
    ) {
        summary.countries.forEach { item ->
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(start = 10.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(Countries.flag(item.country))
                Spacer(Modifier.width(8.dp))
                Text(
                    buildString {
                        append(item.days)
                        if (item.planned > 0) append(" + ${item.planned} planned")
                    },
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
        if (untracked > 0) {
            Box(Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.tertiaryContainer).padding(horizontal = 14.dp, vertical = 6.dp)) {
                Text("$untracked untracked", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onTertiaryContainer)
            }
        }
    }
    if (assumed > 0) {
        Text(
            "Striped days with a pale flag are assumed: nothing was recorded, and you were in the same country before and after. ${if (assumed == 1) "1 day" else "$assumed days"} this month. Tap one to confirm it or say where you were.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp),
        )
    }
    if (faded) {
        Text(
            "Faint flags: you were there, but that country doesn't count the day, like the day you leave Cyprus. Days are counted the way each country counts them.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
        )
    }
}
