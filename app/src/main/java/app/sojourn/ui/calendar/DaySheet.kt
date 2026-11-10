package app.sojourn.ui.calendar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DoNotDisturbOn
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.unit.dp
import app.sojourn.data.Countries
import app.sojourn.data.CountrySet
import app.sojourn.data.RangeEdit
import app.sojourn.data.db.Document
import app.sojourn.data.db.Flight
import app.sojourn.domain.Day
import app.sojourn.domain.DayBook
import app.sojourn.domain.DayKind
import app.sojourn.domain.PlanOrigin
import app.sojourn.ui.components.CountryPickerSheet
import app.sojourn.ui.components.FlagBadge
import app.sojourn.ui.components.Format
import app.sojourn.ui.components.Tag
import app.sojourn.ui.components.segmentedColors
import app.sojourn.ui.flights.FlightCard
import java.time.LocalDate

/**
 * One day: which countries, in what order, and for each whether that country counts the day
 * by its own rules. Every country can be taken off, past or planned, unless a flight put it
 * there, in which case the flight is the thing to change.
 */
@Composable
fun DaySheet(
    date: LocalDate,
    book: DayBook?,
    actions: CalendarActions,
    onDismiss: () -> Unit,
    onFlight: (Flight) -> Unit = {},
    documents: List<Document> = emptyList(),
) {
    val day = book?.get(date)
    val today = book?.today ?: LocalDate.now()
    var picking by remember { mutableStateOf(false) }
    var replacing by remember { mutableStateOf(false) }
    val future = date > today
    val flights = book?.flightsOn(date).orEmpty()
    val flightCountries = flights.flatMap { listOfNotNull(it.originCountry.takeIf { _ -> it.departureDate == date }, it.destinationCountry.takeIf { _ -> it.arrivalDate == date }) }.toSet()

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Format.long(date), style = MaterialTheme.typography.titleLargeEmphasized, modifier = Modifier.weight(1f).semantics { heading() })
                when (day?.kind) {
                    DayKind.PLANNED -> Tag("Planned")
                    DayKind.INFERRED -> Tag("Assumed")
                    DayKind.RECORDED -> Tag("Recorded", container = MaterialTheme.colorScheme.primaryContainer, content = MaterialTheme.colorScheme.onPrimaryContainer)
                    null -> if (!future) Tag("Untracked", container = MaterialTheme.colorScheme.tertiaryContainer, content = MaterialTheme.colorScheme.onTertiaryContainer)
                }
            }
            Spacer(Modifier.height(16.dp))

            if (day == null) {
                Text(
                    if (future) "Nothing planned for this day yet." else "No country recorded for this day.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                if (day.isTravel) {
                    Route(day, canSwap = day.kind == DayKind.RECORDED && day.countries.size == 2) {
                        actions.reorder(date, day.path.reversed().fold(mutableListOf<String>()) { acc, c -> if (acc.lastOrNull() != c) acc += c; acc })
                    }
                    Spacer(Modifier.height(12.dp))
                }
                Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                    day.countries.forEachIndexed { index, country ->
                        CountryRow(
                            day = day,
                            country = country,
                            book = book,
                            index = index,
                            fromFlight = country in flightCountries,
                            onRemove = { actions.edit(date, date, RangeEdit.REMOVE, country) },
                            onConfirm = { actions.edit(date, date, RangeEdit.ADD, country) },
                        )
                    }
                }
                if (day.kind == DayKind.INFERRED) {
                    TextButton(onClick = { replacing = true }) { Text("I was somewhere else") }
                }
            }

            if (flights.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text("Flights", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    flights.forEach { flight -> FlightCard(flight, today, container = MaterialTheme.colorScheme.surfaceContainerHigh) { onFlight(flight) } }
                }
            }

            DayEvidence(documents.filter { it.day == date }, actions::choosePhoto, actions::deleteDocument)

            val quick = book?.recentCountries(4).orEmpty().filter { it !in day?.countries.orEmpty() }
            if (quick.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text(if (future) "Also plan" else "Also there", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    quick.forEach { code ->
                        SuggestionChip(
                            onClick = { actions.edit(date, date, RangeEdit.ADD, code) },
                            label = { Text("${Countries.flag(code)}  ${Countries.name(code)}") },
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = { picking = true }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (future) "Plan a country" else "Add a country")
                }
                if (day != null && day.kind != DayKind.INFERRED) {
                    OutlinedButton(onClick = {
                        actions.edit(date, date, RangeEdit.CLEAR, null)
                        onDismiss()
                    }) { Text("Clear day") }
                }
            }
        }
    }
    if (picking || replacing) {
        CountryPickerSheet(
            title = if (future) "Plan ${Format.short(date, today)}" else "Where were you on ${Format.short(date, today)}?",
            suggestions = book?.recentCountries().orEmpty(),
            exclude = if (replacing) emptySet() else day?.countries.orEmpty().toSet(),
            onDismiss = {
                picking = false
                replacing = false
            },
            onPick = {
                actions.edit(date, date, if (replacing) RangeEdit.SET else RangeEdit.ADD, it)
                picking = false
                replacing = false
            },
        )
    }
}

/** "🇨🇾 → 🇪🇸" with a swap for when the order is wrong, which changes what counts. */
@Composable
private fun Route(day: Day, canSwap: Boolean, onSwap: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.large) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Travel day", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    day.path.forEachIndexed { i, country ->
                        if (i > 0) Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = "then", modifier = Modifier.padding(horizontal = 4.dp).size(16.dp))
                        Text(Countries.flag(country), style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
            if (canSwap) {
                TextButton(onClick = onSwap) {
                    Icon(Icons.Rounded.SwapHoriz, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Other way round")
                }
            }
        }
    }
}

@Composable
private fun CountryRow(day: Day, country: String, book: DayBook?, index: Int, fromFlight: Boolean, onRemove: () -> Unit, onConfirm: () -> Unit) {
    val transit = country in day.transit
    val counting = book?.policies?.of(country)
    val counts = book?.countsFor(country, day) == true
    val explanation = when {
        transit -> "Airside transit: doesn't count"
        counting == null -> ""
        !day.isTravel -> if (day.kind == DayKind.INFERRED) "Assumed: you were here before and after" else "Counts as a day here"
        else -> counting.explain(day.path, CountrySet(setOf(country)), day.transit) + " here"
    }
    val source = when {
        day.kind == DayKind.PLANNED -> when (day.origins[country]) {
            PlanOrigin.FLIGHT -> "From a flight"
            PlanOrigin.BETWEEN_FLIGHTS -> "Between your flights"
            else -> "Planned"
        }
        // Every source behind a recorded day, e.g. "From location, photos".
        day.kind == DayKind.RECORDED -> day.sources[country].orEmpty().takeIf { it.isNotEmpty() }
            ?.let { sources -> "From " + sources.joinToString(", ") { it.describe() } }
            ?: if (fromFlight) "From a flight" else null
        fromFlight -> "From a flight"
        else -> null
    }
    SegmentedListItem(
        colors = segmentedColors(onSheet = true),
        verticalAlignment = Alignment.CenterVertically,
        shapes = ListItemDefaults.segmentedShapes(index, day.countries.size),
        leadingContent = { FlagBadge(country, size = 40.dp, alpha = if (counts) 1f else 0.5f) },
        supportingContent = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (counts) Icons.Rounded.CheckCircle else Icons.Rounded.DoNotDisturbOn,
                        contentDescription = null,
                        tint = if (counts) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(explanation)
                }
                // Say how this country counts when it matters: a travel day it doesn't count.
                if (day.isTravel && !counts && counting != null && !transit) {
                    Text("${Countries.name(country)}: ${counting.summary.replaceFirstChar(Char::lowercaseChar)}", style = MaterialTheme.typography.bodySmall)
                }
                source?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        },
        trailingContent = {
            when {
                day.kind == DayKind.INFERRED -> IconButton(onClick = onConfirm) {
                    Icon(Icons.Rounded.Check, contentDescription = "Confirm ${Countries.name(country)}")
                }
                // A flight's own departure or arrival is changed by editing the flight.
                fromFlight || day.origins[country] == PlanOrigin.FLIGHT -> Unit
                else -> IconButton(onClick = onRemove) {
                    Icon(Icons.Rounded.RemoveCircleOutline, contentDescription = "Remove ${Countries.name(country)}")
                }
            }
        },
    ) { Text(Countries.name(country), style = MaterialTheme.typography.titleMedium) }
}
