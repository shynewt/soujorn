package app.sojourn.ui.calendar

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.TableRows
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
import app.sojourn.domain.DateRange
import app.sojourn.domain.DayBook
import app.sojourn.domain.DayKind
import app.sojourn.ui.CalendarRequest
import app.sojourn.ui.LocalAppActions
import app.sojourn.ui.LocalCalendarRequests
import app.sojourn.ui.LocalCountryColors
import app.sojourn.ui.appViewModel
import app.sojourn.ui.countryColor
import app.sojourn.ui.components.ChoiceGroup
import app.sojourn.ui.components.CountryPickerSheet
import app.sojourn.ui.components.Format
import app.sojourn.ui.components.YearMenu
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.WeekFields
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

private val FIRST_MONTH: YearMonth = YearMonth.of(2000, 1)
private const val MONTH_COUNT = 1212

private fun pageOf(date: LocalDate) = ChronoUnit.MONTHS.between(FIRST_MONTH, YearMonth.from(date)).toInt()

@Composable
fun CalendarScreen() {
    val vm = appViewModel { CalendarViewModel(it) }
    val book by vm.book.collectAsStateWithLifecycle()
    val stays by vm.stays.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val documents by vm.documents.collectAsStateWithLifecycle()
    val locale = LocalConfiguration.current.locales[0]
    val firstDay = if (settings.weekStart in 1..7) DayOfWeek.of(settings.weekStart) else WeekFields.of(locale).firstDayOfWeek
    val shell = LocalCalendarRequests.current
    val request by (shell?.calendarRequests ?: remember { MutableStateFlow(null) }).collectAsStateWithLifecycle()
    CalendarContent(book, stays, firstDay, vm, settings.calendarView, documents, request, onRequestHandled = { shell?.calendarHandled() })
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

/**
 * The three ways to look at the days. The year's two layouts (month cards, a row per month) are
 * one choice inside Year rather than two more buttons: they show the same thing at two densities.
 */
private enum class Mode(val label: String) { MONTH("Month"), YEAR("Year"), LIST("List") }

private val CalendarView.mode: Mode
    get() = when (this) {
        CalendarView.MONTH -> Mode.MONTH
        CalendarView.GRID, CalendarView.COMPACT, CalendarView.ROWS -> Mode.YEAR
        CalendarView.LIST -> Mode.LIST
    }

/**
 * The record of days as a month, a year or the list of stays, all opening the same day sheet.
 * Long-press and drag in the month or year selects a range for the floating toolbar. Other
 * screens send [request]s here to show a day or the list; each is acknowledged once acted on.
 */
@Composable
fun CalendarContent(
    book: DayBook?,
    stays: StayListState,
    firstDay: DayOfWeek,
    actions: CalendarActions,
    view: CalendarView = CalendarView.MONTH,
    documents: List<Document> = emptyList(),
    request: CalendarRequest? = null,
    onRequestHandled: () -> Unit = {},
) {
    val app = LocalAppActions.current
    val today = book?.today ?: LocalDate.now()
    val todayPage = pageOf(today)
    val pager = rememberPagerState(initialPage = todayPage) { MONTH_COUNT }
    val list = rememberLazyListState()
    var year by rememberSaveable { mutableIntStateOf(today.year) }
    // The year layout last used, so Year comes back as it was after a visit to Month or List.
    var rows by rememberSaveable { mutableStateOf(view == CalendarView.ROWS) }
    LaunchedEffect(view) { if (view.mode == Mode.YEAR) rows = view == CalendarView.ROWS }
    val mode = view.mode
    val scope = rememberCoroutineScope()
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()

    var anchor by remember { mutableStateOf<LocalDate?>(null) }
    var end by remember { mutableStateOf<LocalDate?>(null) }
    var openDay by remember { mutableStateOf<LocalDate?>(null) }
    var pending by remember { mutableStateOf<Pending?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmSet by remember { mutableStateOf<String?>(null) }
    var filling by remember { mutableStateOf<DateRange?>(null) }
    var stay by remember { mutableStateOf<StayItem?>(null) }
    var legend by remember { mutableStateOf(false) }
    val selection: Selection? = anchor?.let { a -> (end ?: a).let { e -> if (e < a) e to a else a to e } }
    fun clearSelection() {
        anchor = null
        end = null
    }

    fun show(next: Mode) {
        clearSelection()
        actions.setView(
            when (next) {
                Mode.MONTH -> CalendarView.MONTH
                Mode.YEAR -> if (rows) CalendarView.ROWS else CalendarView.GRID
                Mode.LIST -> CalendarView.LIST
            },
        )
    }

    LaunchedEffect(request) {
        val asked = request ?: return@LaunchedEffect
        val day = asked.day
        when {
            asked.list -> if (mode != Mode.LIST) show(Mode.LIST)
            day != null -> {
                if (mode != Mode.MONTH) show(Mode.MONTH)
                year = day.year
                pager.requestScrollToPage(pageOf(day))
                openDay = day
            }
        }
        onRequestHandled()
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
        topBar = {
            TopAppBar(
                title = { Text("Calendar", style = MaterialTheme.typography.titleLargeEmphasized) },
                scrollBehavior = scroll,
                actions = {
                    IconButton(onClick = {
                        year = today.year
                        scope.launch {
                            when (mode) {
                                Mode.MONTH -> pager.animateScrollToPage(todayPage)
                                Mode.YEAR -> Unit
                                Mode.LIST -> list.animateScrollToItem(stays.nowIndex())
                            }
                        }
                    }) { Icon(Icons.Rounded.Today, contentDescription = "Go to today") }
                    IconButton(onClick = { legend = true }) { Icon(Icons.AutoMirrored.Rounded.HelpOutline, contentDescription = "How to read the calendar") }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.fillMaxSize()) {
                ChoiceGroup(Mode.entries.map { it to it.label }, mode, ::show, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                when (mode) {
                    Mode.MONTH -> MonthView(book, firstDay, today, pager, selection, hits, callbacks, onUntracked = { show(Mode.LIST) }, Modifier.weight(1f))
                    Mode.YEAR -> YearView(
                        rows = rows,
                        onRows = { actions.setView(if (it) CalendarView.ROWS else CalendarView.GRID) },
                        year = year,
                        onYear = { year = it },
                        book = book,
                        firstDay = firstDay,
                        today = today,
                        selection = selection,
                        hits = hits,
                        callbacks = callbacks,
                        onUntracked = { show(Mode.LIST) },
                        modifier = Modifier.weight(1f),
                    )
                    Mode.LIST -> StayListView(
                        stays,
                        list,
                        onFill = { filling = it },
                        onStay = { stay = it },
                        onCrossing = { crossing -> crossing.flights.singleOrNull()?.let { app.flight(it) } ?: run { openDay = crossing.date } },
                        modifier = Modifier.weight(1f),
                    )
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

    openDay?.let { date -> DaySheet(date = date, book = book, actions = actions, onDismiss = { openDay = null }, documents = documents) }
    if (legend) LegendSheet(onDismiss = { legend = false })
    filling?.let { gap ->
        CountryPickerSheet(
            title = "Where were you, ${Format.range(gap.start, gap.end, today)}?",
            suggestions = stays.recents,
            onDismiss = { filling = null },
            onPick = {
                actions.edit(gap.start, gap.end, RangeEdit.SET, it)
                filling = null
            },
        )
    }
    stay?.let { item ->
        StaySheet(
            stay = item,
            recents = stays.recents,
            today = today,
            onDismiss = { stay = null },
            onChange = {
                actions.changeStay(item.leg, it)
                stay = null
            },
            onRemove = {
                actions.edit(item.leg.start, item.leg.end, RangeEdit.REMOVE, item.leg.country)
                stay = null
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

/** How to pick several days, said once under the month or year it applies to. */
@Composable
private fun RangeHint(modifier: Modifier = Modifier) {
    Text(
        "Long-press a day and drag to select several.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

@Composable
private fun MonthView(
    book: DayBook?,
    firstDay: DayOfWeek,
    today: LocalDate,
    pager: PagerState,
    selection: Selection?,
    hits: DayHitTest,
    callbacks: DayGestureCallbacks,
    onUntracked: () -> Unit,
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
        MonthSummary(book, month, today, onUntracked)
        RangeHint(Modifier.padding(horizontal = 8.dp, vertical = 16.dp))
        Spacer(Modifier.height(96.dp))
    }
}

/** A year at a glance, as month cards or a row per month. */
@Composable
private fun YearView(
    rows: Boolean,
    onRows: (Boolean) -> Unit,
    year: Int,
    onYear: (Int) -> Unit,
    book: DayBook?,
    firstDay: DayOfWeek,
    today: LocalDate,
    selection: Selection?,
    hits: DayHitTest,
    callbacks: DayGestureCallbacks,
    onUntracked: () -> Unit,
    modifier: Modifier,
) {
    val from = LocalDate.of(year, 1, 1)
    val to = LocalDate.of(year, 12, 31)
    val summary = remember(book, year) { book?.summary(from, to) }
    // Countries only planned so far have no ranked colour yet; give them the steady one the
    // forecast bar uses, so the grid, the chips and the bar agree.
    val ranked = LocalCountryColors.current
    val colors = ranked + summary?.countries.orEmpty().filter { it.country !in ranked }.associate { it.country to countryColor(it.country) }
    val other = MaterialTheme.colorScheme.outline
    val legend = summary?.countries.orEmpty().sortedByDescending { it.total }.map { Triple(it.country, colors[it.country] ?: other, it.total) }
    // Every year with days, and the next one to plan in; the menu scrolls, so ten years fit.
    val years = remember(book) {
        val known = book?.years() ?: listOf(today.year)
        (maxOf(known.max(), today.year + 1) downTo known.min()).toList()
    }
    val header: @Composable () -> Unit = {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onYear(year - 1) }, enabled = year > years.last()) {
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, contentDescription = "Previous year")
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    YearMenu(year, (years + year).distinct(), onYear = { it?.let(onYear) })
                }
                IconButton(onClick = { onYear(year + 1) }, enabled = year < years.first()) {
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = "Next year")
                }
                IconButton(onClick = { onRows(!rows) }) {
                    Icon(
                        if (rows) Icons.Rounded.GridView else Icons.Rounded.TableRows,
                        contentDescription = if (rows) "Show months as cards" else "Show months as rows",
                    )
                }
            }
            YearLegend(legend, Modifier.padding(bottom = 8.dp))
        }
    }
    val footer: @Composable () -> Unit = {
        Column(Modifier.padding(top = 8.dp, bottom = 112.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            book?.let { YearForecastCard(it, year, onUntracked) }
            DayKey()
            RangeHint()
        }
    }
    if (rows) {
        Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
            header()
            Box(Modifier.dayGestures(hits, callbacks)) { YearRows(year, book, today, colors, selection, hits, callbacks) }
            footer()
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(160.dp),
        modifier = modifier.fillMaxWidth().dayGestures(hits, callbacks),
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) { header() }
        items((1..12).map { YearMonth.of(year, it) }, key = { it.toString() }) { month ->
            MonthCard(month, firstDay, book, today, colors, selection, hits, callbacks)
        }
        item(span = { GridItemSpan(maxLineSpan) }) { footer() }
    }
}

/** The month's days per country, and its untracked days as a way into the list to fill them in. */
@Composable
private fun MonthSummary(book: DayBook?, month: YearMonth, today: LocalDate, onUntracked: () -> Unit) {
    book ?: return
    val summary = remember(book, month) { book.summary(month.atDay(1), month.atEndOfMonth()) }
    val untracked = remember(book, month) { if (book.isEmpty) 0 else book.gaps(maxOf(month.atDay(1), book.firstRecorded ?: today), month.atEndOfMonth()).sumOf { it.days } }
    if (summary.countries.isEmpty() && untracked == 0) return
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 4.dp),
    ) {
        summary.countries.forEach { item ->
            Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Row(Modifier.padding(start = 10.dp, end = 14.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
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
        }
        if (untracked > 0) {
            Surface(onClick = onUntracked, shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer) {
                Row(Modifier.padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("$untracked untracked", style = MaterialTheme.typography.labelLarge)
                    Icon(Icons.Rounded.ChevronRight, contentDescription = "Fill in from the list", modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}
