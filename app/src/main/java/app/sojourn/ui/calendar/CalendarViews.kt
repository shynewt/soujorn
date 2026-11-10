package app.sojourn.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Flight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.sojourn.data.Countries
import app.sojourn.domain.Day
import app.sojourn.domain.DayBook
import app.sojourn.domain.DayKind
import app.sojourn.ui.components.Format
import app.sojourn.ui.components.LegendDot
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle

/** The selected range, earliest day first. */
typealias Selection = Pair<LocalDate, LocalDate>

private fun Selection?.has(date: LocalDate) = this != null && date >= first && date <= second

/** What a screen reader says for a day: its date, countries in order, and what kind of day it is. */
fun describeDay(date: LocalDate, day: Day?, book: DayBook?, today: LocalDate): String = buildString {
    append(Format.long(date))
    if (date == today) append(", today")
    val countries = day?.countries.orEmpty()
    when {
        countries.isEmpty() -> if (date <= today && book?.firstRecorded?.let { date >= it } == true) append(", untracked")
        else -> {
            append(": ")
            append(day!!.path.joinToString(" to ") { Countries.name(it) })
            val uncounted = countries.filter { it !in day.transit && book?.countsFor(it, day) == false }
            if (uncounted.isNotEmpty()) append(", not counted for ${uncounted.joinToString { Countries.name(it) }}")
            when (day.kind) {
                DayKind.PLANNED -> append(", planned")
                DayKind.INFERRED -> append(", assumed")
                DayKind.RECORDED -> Unit
            }
        }
    }
    if (book?.flightsOn(date)?.isNotEmpty() == true) append(", flight")
}

/**
 * Diagonal stripes, the mark of an assumed day: nothing was recorded, Sojourn takes it you
 * stayed where you were on both sides. Drawn the same way in every view so it reads at a glance.
 */
fun DrawScope.hatch(color: Color, spacing: Float = 5.dp.toPx(), width: Float = 1.5.dp.toPx()) {
    clipRect {
        var x = -size.height
        while (x < size.width) {
            drawLine(color, Offset(x, size.height), Offset(x + size.height, 0f), strokeWidth = width)
            x += spacing
        }
    }
}

// ---- Month view ----------------------------------------------------------------------------

@Composable
fun MonthGrid(
    month: YearMonth,
    firstDay: DayOfWeek,
    book: DayBook?,
    today: LocalDate,
    selection: Selection?,
    hits: DayHitTest,
    callbacks: DayGestureCallbacks,
) {
    val offset = (month.atDay(1).dayOfWeek.value - firstDay.value + 7) % 7
    Column {
        for (week in 0 until 6) {
            Row {
                for (column in 0 until 7) {
                    val dayNumber = week * 7 + column - offset + 1
                    if (dayNumber < 1 || dayNumber > month.lengthOfMonth()) {
                        Spacer(Modifier.weight(1f).height(58.dp))
                        continue
                    }
                    val date = month.atDay(dayNumber)
                    val inSelection = selection.has(date)
                    val shape: Shape = if (inSelection) {
                        val startCap = date == selection!!.first || column == 0
                        val endCap = date == selection.second || column == 6
                        RoundedCornerShape(
                            topStartPercent = if (startCap) 50 else 0,
                            bottomStartPercent = if (startCap) 50 else 0,
                            topEndPercent = if (endCap) 50 else 0,
                            bottomEndPercent = if (endCap) 50 else 0,
                        )
                    } else {
                        RoundedCornerShape(16.dp)
                    }
                    MonthDayCell(
                        date = date,
                        book = book,
                        today = today,
                        selected = inSelection,
                        shape = shape,
                        modifier = Modifier
                            .weight(1f)
                            .height(58.dp)
                            .padding(vertical = 2.dp)
                            .clip(shape)
                            .dayCell(date, hits, callbacks, describeDay(date, book?.get(date), book, today)),
                    )
                }
            }
        }
    }
}

@Composable
private fun MonthDayCell(date: LocalDate, book: DayBook?, today: LocalDate, selected: Boolean, shape: Shape, modifier: Modifier) {
    val day = book?.get(date)
    val planned = day?.kind == DayKind.PLANNED
    val assumed = day?.kind == DayKind.INFERRED
    val dashed = MaterialTheme.colorScheme.outline
    val stripes = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
    Box(
        modifier
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, shape)
            .then(
                if (assumed && !selected) {
                    Modifier.drawBehind { hatch(stripes, spacing = 6.dp.toPx(), width = 1.dp.toPx()) }
                } else if (planned && !selected) {
                    Modifier.drawBehind {
                        drawRoundRect(
                            dashed,
                            cornerRadius = CornerRadius(16.dp.toPx()),
                            style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
                        )
                    }
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (book?.flightsOn(date)?.isNotEmpty() == true) {
            Icon(
                Icons.Rounded.Flight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 2.dp, end = 2.dp).size(12.dp).rotate(45f),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val isToday = date == today
            Box(
                Modifier
                    .size(26.dp)
                    .then(if (isToday) Modifier.clip(MaterialShapes.Cookie4Sided.toShape()).background(MaterialTheme.colorScheme.primary) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "${date.dayOfMonth}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                    fontStyle = if (assumed && !isToday) FontStyle.Italic else FontStyle.Normal,
                    color = when {
                        isToday -> MaterialTheme.colorScheme.onPrimary
                        assumed -> MaterialTheme.colorScheme.onSurfaceVariant
                        date > today -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                )
            }
            val countries = day?.countries.orEmpty().filter { it !in day!!.transit }
            // A country that was there but doesn't count the day (leaving Cyprus) is drawn faint.
            fun weight(country: String) = if (day != null && book?.countsFor(country, day) == false) 0.35f else 1f
            Box(Modifier.height(20.dp), contentAlignment = Alignment.Center) {
                val base = if (planned) 0.55f else if (assumed) 0.5f else 1f
                when {
                    countries.isEmpty() && date <= today && book != null && !book.isEmpty && date >= (book.firstRecorded ?: today) ->
                        Box(Modifier.size(4.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.outlineVariant))
                    countries.size == 1 -> Text(Countries.flag(countries[0]), fontSize = 15.sp, modifier = Modifier.alpha(base * weight(countries[0])))
                    countries.size > 1 -> Row {
                        val first = day!!.path.first().takeIf { it in countries } ?: countries.first()
                        val last = day.last.takeIf { it in countries } ?: countries.last()
                        Text(Countries.flag(first), fontSize = 11.sp, modifier = Modifier.alpha(base * weight(first)))
                        Text(Countries.flag(last), fontSize = 11.sp, modifier = Modifier.offset(x = (-3).dp, y = 3.dp).alpha(base * weight(last)))
                    }
                }
            }
        }
    }
}

// ---- Year views ----------------------------------------------------------------------------

/**
 * A day as a coloured tile: the country's colour, split corner to corner on travel days (where
 * the day started, top left; where it ended, bottom right), light and dashed when planned.
 */
@Composable
private fun Modifier.dayTile(day: Day?, colors: Map<String, Color>, untracked: Boolean, rows: Boolean): Modifier {
    val empty = if (untracked || rows) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent
    val outline = MaterialTheme.colorScheme.outline
    val other = MaterialTheme.colorScheme.outline
    return drawBehind {
        val countries = day?.path?.filter { it !in day.transit }.orEmpty()
        if (countries.isEmpty()) {
            drawRect(empty)
            return@drawBehind
        }
        val assumed = day!!.kind == DayKind.INFERRED
        // Assumed days are the country's colour in stripes over a pale wash, never solid.
        val alpha = if (day.kind == DayKind.PLANNED) 0.3f else if (assumed) 0.25f else 0.9f
        val first = (colors[countries.first()] ?: other).copy(alpha = alpha)
        val last = (colors[countries.last()] ?: other).copy(alpha = alpha)
        if (first == last) {
            drawRect(first)
        } else {
            drawPath(Path().apply { moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(0f, size.height); close() }, first)
            drawPath(Path().apply { moveTo(size.width, 0f); lineTo(size.width, size.height); lineTo(0f, size.height); close() }, last)
        }
        if (assumed) hatch((colors[day.last] ?: other).copy(alpha = 0.85f), spacing = 4.dp.toPx(), width = 1.2.dp.toPx())
        if (day.kind == DayKind.PLANNED) {
            drawRect(outline, style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx()))))
        }
    }
}

@Composable
private fun numberColor(day: Day?, colors: Map<String, Color>): Color {
    val country = day?.last ?: return MaterialTheme.colorScheme.onSurfaceVariant
    if (day.kind == DayKind.PLANNED || day.kind == DayKind.INFERRED) return MaterialTheme.colorScheme.onSurface
    val fill = colors[country] ?: MaterialTheme.colorScheme.outline
    return if (fill.luminance() > 0.45f) Color.Black else Color.White
}

/** One month as a card of square day tiles, for the year view. */
@Composable
fun MonthCard(
    month: YearMonth,
    firstDay: DayOfWeek,
    book: DayBook?,
    today: LocalDate,
    colors: Map<String, Color>,
    selection: Selection?,
    hits: DayHitTest,
    callbacks: DayGestureCallbacks,
) {
    val locale = LocalConfiguration.current.locales[0]
    val offset = (month.atDay(1).dayOfWeek.value - firstDay.value + 7) % 7
    val gap = 3.dp
    val first = book?.firstRecorded
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(12.dp)) {
            Text(month.month.getDisplayName(TextStyle.FULL, locale), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 8.dp))
            Row(Modifier.clearAndSetSemantics { }) {
                (0 until 7).forEach { i ->
                    Text(
                        firstDay.plus(i.toLong()).getDisplayName(TextStyle.NARROW, locale),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            val weeks = (offset + month.lengthOfMonth() + 6) / 7
            for (week in 0 until weeks) {
                Row(horizontalArrangement = Arrangement.spacedBy(gap), modifier = Modifier.padding(bottom = gap)) {
                    for (column in 0 until 7) {
                        val n = week * 7 + column - offset + 1
                        if (n < 1 || n > month.lengthOfMonth()) {
                            Spacer(Modifier.weight(1f).aspectRatio(1f))
                            continue
                        }
                        val date = month.atDay(n)
                        val day = book?.get(date)
                        val untracked = day == null && date <= today && first != null && date >= first
                        val selected = selection.has(date)
                        val shape = RoundedCornerShape(6.dp)
                        Box(
                            Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .clip(shape)
                                .dayTile(day, colors, untracked, rows = false)
                                .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
                                .then(if (date == today) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, shape) else Modifier)
                                .dayCell(date, hits, callbacks, describeDay(date, day, book, today)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "$n",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 11.sp,
                                    lineHeight = 11.sp,
                                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                                    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
                                ),
                                maxLines = 1,
                                softWrap = false,
                                fontWeight = if (date == today) FontWeight.Bold else FontWeight.Normal,
                                color = numberColor(day, colors),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The whole year as a heat map: a row per month, a column per day of the month. */
@Composable
fun YearRows(
    year: Int,
    book: DayBook?,
    today: LocalDate,
    colors: Map<String, Color>,
    selection: Selection?,
    hits: DayHitTest,
    callbacks: DayGestureCallbacks,
) {
    val locale = LocalConfiguration.current.locales[0]
    val first = book?.firstRecorded
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 12.dp)) {
            Row(Modifier.clearAndSetSemantics { }) {
                Spacer(Modifier.width(36.dp))
                (1..31).forEach { n ->
                    Text(
                        if (n == 1 || n % 5 == 0) "$n" else "",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 8.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            (1..12).forEach { m ->
                val month = YearMonth.of(year, m)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 1.dp)) {
                    Text(
                        month.month.getDisplayName(TextStyle.SHORT, locale).uppercase(locale),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(36.dp).clearAndSetSemantics { },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(1.dp), modifier = Modifier.weight(1f)) {
                        (1..31).forEach { n ->
                            if (n > month.lengthOfMonth()) {
                                Spacer(Modifier.weight(1f).height(26.dp))
                                return@forEach
                            }
                            val date = month.atDay(n)
                            val day = book?.get(date)
                            val untracked = day == null && date <= today && first != null && date >= first
                            val shape = RoundedCornerShape(3.dp)
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(26.dp)
                                    .clip(shape)
                                    .dayTile(day, colors, untracked, rows = true)
                                    .then(if (selection.has(date)) Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
                                    .then(if (date == today) Modifier.border(1.5.dp, MaterialTheme.colorScheme.onSurface, shape) else Modifier)
                                    .dayCell(date, hits, callbacks, describeDay(date, day, book, today)),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Countries of the year with their colours and counted days, scrolling sideways. */
@Composable
fun YearLegend(entries: List<Triple<String, Color, Int>>, modifier: Modifier = Modifier) {
    if (entries.isEmpty()) return
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        entries.forEach { (country, color, days) ->
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(start = 10.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LegendDot(color)
                Spacer(Modifier.width(6.dp))
                Text("${Countries.flag(country)} $days", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
