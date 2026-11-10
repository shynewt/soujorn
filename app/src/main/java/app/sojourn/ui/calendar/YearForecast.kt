package app.sojourn.ui.calendar

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.sojourn.data.Countries
import app.sojourn.domain.DayBook
import app.sojourn.domain.DayKind
import app.sojourn.domain.Summary
import app.sojourn.ui.components.BarSegment
import app.sojourn.ui.components.LegendDot
import app.sojourn.ui.components.StackedBar
import app.sojourn.ui.countryColor
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** A year by country: lived and planned, with what's still open and what nobody recorded. */
data class YearForecast(
    val year: Int,
    val summary: Summary,
    /** Days to come that nothing is planned on. */
    val open: Int,
    /** Past days of the year with no country. */
    val untracked: Int,
)

fun yearForecast(book: DayBook, year: Int): YearForecast {
    val today = book.today
    val from = LocalDate.of(year, 1, 1)
    val to = LocalDate.of(year, 12, 31)
    val start = maxOf(from, today.plusDays(1))
    val futureDays = if (start > to) 0 else ChronoUnit.DAYS.between(start, to).toInt() + 1
    val planned = if (start > to) 0 else book.between(start, to).count { it.kind == DayKind.PLANNED }
    val untracked = if (book.isEmpty || today < from) 0 else book.gaps(maxOf(from, book.firstRecorded ?: today), minOf(to, today)).sumOf { it.days }
    return YearForecast(year, book.summary(from, to), futureDays - planned, untracked)
}

/**
 * The year's days per country under the grid: what was lived and what's planned, as one bar in
 * the country colours and a line per country. Untracked days lead to the list to fill them in.
 * It sits below the grid so the grid stays the thing you see first.
 */
@Composable
fun YearForecastCard(book: DayBook, year: Int, onUntracked: () -> Unit, modifier: Modifier = Modifier) {
    val forecast = remember(book, year) { yearForecast(book, year) }
    val summary = forecast.summary
    val ranked = summary.countries.sortedByDescending { it.total }
    if (ranked.isEmpty() && forecast.untracked == 0) return
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("$year by country", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Text(
                buildList {
                    if (summary.trackedDays > 0) add("${summary.trackedDays} lived")
                    if (summary.plannedDays > 0) add("${summary.plannedDays} planned")
                    if (forecast.open > 0) add("${forecast.open} open")
                }.joinToString(" · ").ifEmpty { "Nothing recorded" } + if (summary.trackedDays + summary.plannedDays + forecast.open > 0) " days" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            val rest = MaterialTheme.colorScheme.surfaceContainerHighest
            val segments = ranked.flatMap {
                val color = countryColor(it.country)
                listOf(BarSegment(color, it.days.toFloat()), BarSegment(color, it.planned.toFloat(), planned = true))
            } + BarSegment(rest, (forecast.open + forecast.untracked).toFloat())
            StackedBar(segments, height = 12.dp)
            Spacer(Modifier.height(8.dp))
            ranked.forEach { item ->
                Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    LegendDot(countryColor(item.country))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "${Countries.flag(item.country)}  ${Countries.name(item.country)}",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (item.planned > 0) {
                        Text(
                            if (item.days > 0) "${item.days} + ${item.planned} planned" else "${item.planned} planned",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 10.dp),
                        )
                    }
                    Text("${item.total}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                }
            }
            if (forecast.untracked > 0) {
                Surface(
                    onClick = onUntracked,
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Row(Modifier.padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${forecast.untracked} untracked", style = MaterialTheme.typography.labelLarge)
                        Icon(Icons.Rounded.ChevronRight, contentDescription = "Fill in from the list", modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}
