package app.sojourn.ui.flights

import android.content.ClipboardManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.FlightLand
import androidx.compose.material.icons.rounded.FlightTakeoff
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePickerDialog
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import app.sojourn.data.Countries
import app.sojourn.data.db.Flight
import app.sojourn.data.flights.Airlines
import app.sojourn.data.flights.Airport
import app.sojourn.data.flights.FlightText
import app.sojourn.domain.Level
import app.sojourn.domain.RuleStatus
import app.sojourn.ui.components.Format
import app.sojourn.ui.components.ShapeIcon
import app.sojourn.ui.components.levelColor
import app.sojourn.ui.components.segmentedColors
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.delay

/** Creates or edits a flight; the countries, travel days and the stay after it follow from the airports. */
@Composable
fun FlightEditorSheet(
    initial: Flight?,
    recentAirports: List<String>,
    preview: suspend (Flight) -> List<RuleStatus>,
    onDismiss: () -> Unit,
    onSave: (Flight) -> Unit,
    onDelete: (Flight) -> Unit,
) {
    val airports = LocalAirports.current
    val context = LocalContext.current
    val today = LocalDate.now()
    var number by rememberSaveable { mutableStateOf(initial?.number?.let(Airlines::format).orEmpty()) }
    // A new flight may come with its date (added from a day) and nothing else.
    val isNew = initial == null || initial.id == 0L
    var origin by rememberSaveable { mutableStateOf(initial?.origin?.ifBlank { null }) }
    var destination by rememberSaveable { mutableStateOf(initial?.destination?.ifBlank { null }) }
    var date by rememberSaveable { mutableStateOf(initial?.departureDate) }
    var dayShift by rememberSaveable { mutableIntStateOf(initial?.let { ChronoUnit.DAYS.between(it.departureDate, it.arrivalDate).toInt() } ?: 0) }
    var departs by rememberSaveable { mutableStateOf(initial?.departureMinute) }
    var arrives by rememberSaveable { mutableStateOf(initial?.arrivalMinute) }
    var note by rememberSaveable { mutableStateOf(initial?.note.orEmpty()) }
    var picking by remember { mutableStateOf<String?>(null) }
    var pickingDate by remember { mutableStateOf(false) }
    var pickingTime by remember { mutableStateOf<String?>(null) }
    var pasteMessage by remember { mutableStateOf<String?>(null) }
    var impact by remember { mutableStateOf<List<RuleStatus>>(emptyList()) }

    val from = origin?.let { airports?.get(it) }
    val to = destination?.let { airports?.get(it) }
    val draft = if (from != null && to != null && date != null && from.code != to.code) {
        Flight(
            id = initial?.id ?: 0,
            number = number.filter { it.isLetterOrDigit() }.uppercase(),
            origin = from.code,
            destination = to.code,
            originCountry = from.country,
            destinationCountry = to.country,
            departureDate = date!!,
            arrivalDate = date!!.plusDays(dayShift.toLong()),
            departureMinute = departs,
            arrivalMinute = arrives,
            note = note.trim(),
        )
    } else {
        null
    }
    LaunchedEffect(draft?.origin, draft?.destination, draft?.departureDate, draft?.arrivalDate) {
        impact = if (draft == null) emptyList() else {
            delay(150)
            preview(draft).filter { it.applies && it.forecast != null }
        }
    }

    fun paste() {
        val text = context.getSystemService(ClipboardManager::class.java)?.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(context)?.toString()
        val directory = airports
        if (text.isNullOrBlank() || directory == null) {
            pasteMessage = "Copy a booking confirmation or boarding pass text first"
            return
        }
        val guess = FlightText.parse(text, directory, today)
        if (guess.isEmpty) {
            pasteMessage = "No flight found in the copied text"
            return
        }
        guess.number?.let { number = it }
        guess.origin?.let { origin = it }
        guess.destination?.let { destination = it }
        guess.date?.let { date = it }
        pasteMessage = "Filled in from the copied text. Check the details."
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberBottomSheetState(SheetValue.Hidden, setOf(SheetValue.Hidden, SheetValue.Expanded))) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 20.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (isNew) "Add a flight" else "Edit flight", style = MaterialTheme.typography.headlineSmallEmphasized, modifier = Modifier.weight(1f))
                FilledTonalButton(onClick = ::paste) {
                    Icon(Icons.Rounded.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Paste")
                }
            }
            AnimatedVisibility(pasteMessage != null) {
                Text(pasteMessage.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedTextField(
                value = number,
                onValueChange = { number = it.uppercase().take(8) },
                label = { Text("Flight number (optional)") },
                placeholder = { Text("TP 1234") },
                supportingText = Airlines.name(number)?.let { { Text(it) } },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            Box {
                Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                    AirportRow(0, "From", from, Icons.Rounded.FlightTakeoff) { picking = "from" }
                    AirportRow(1, "To", to, Icons.Rounded.FlightLand) { picking = "to" }
                }
                FilledTonalIconButton(
                    onClick = {
                        val swap = origin
                        origin = destination
                        destination = swap
                    },
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp),
                ) { Icon(Icons.Rounded.SwapVert, contentDescription = "Swap airports") }
            }

            Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                SegmentedListItem(
                    colors = segmentedColors(onSheet = true),
                    verticalAlignment = Alignment.CenterVertically,
                    onClick = { pickingDate = true },
                    shapes = ListItemDefaults.segmentedShapes(0, 3),
                    leadingContent = { ShapeIcon(Icons.Rounded.Event) },
                    overlineContent = { Text("Date") },
                ) { Text(date?.let(Format::long) ?: "Choose the day it leaves", style = MaterialTheme.typography.titleMedium) }
                TimeRow(1, "Departs", departs, onPick = { pickingTime = "departs" }, onClear = { departs = null })
                TimeRow(2, "Arrives", arrives, onPick = { pickingTime = "arrives" }, onClear = { arrives = null })
            }
            Text("Lands", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0 to "Same day", 1 to "Next day", 2 to "2 days later", -1 to "Day before").forEach { (shift, label) ->
                    FilterChip(selected = dayShift == shift, onClick = { dayShift = shift }, label = { Text(label) })
                }
            }
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("Note (optional)") },
                singleLine = true,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            if (draft != null) {
                Text(
                    if (draft.originCountry == draft.destinationCountry) {
                        "A domestic flight in ${Countries.name(draft.originCountry)}."
                    } else if (draft.departureDate == draft.arrivalDate) {
                        "${Format.short(draft.departureDate)} counts as a travel day in ${Countries.name(draft.originCountry)} and ${Countries.name(draft.destinationCountry)}."
                    } else {
                        "${Countries.name(draft.originCountry)} on ${Format.short(draft.departureDate)}, ${Countries.name(draft.destinationCountry)} on ${Format.short(draft.arrivalDate)}."
                    } + " The days until your next flight out of ${Countries.name(draft.destinationCountry)} are planned there.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (from != null && to != null && from.code == to.code) {
                Text("From and to are the same airport.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            AnimatedVisibility(impact.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("With this flight", style = MaterialTheme.typography.titleMedium)
                    impact.forEach { status ->
                        val forecast = status.forecast ?: return@forEach
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                when (forecast.level) {
                                    Level.GOOD -> Icons.Rounded.CheckCircle
                                    Level.CAUTION -> Icons.Rounded.WarningAmber
                                    Level.ALERT -> Icons.Rounded.ErrorOutline
                                },
                                contentDescription = null,
                                tint = levelColor(forecast.level),
                                modifier = Modifier.size(22.dp),
                            )
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(status.rule.name, style = MaterialTheme.typography.labelLarge)
                                Text(forecast.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }

            // A saved flight can gain its boarding pass as evidence at any time.
            if (initial != null && initial.id != 0L) FlightEvidence(initial)

            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (initial != null && !isNew) OutlinedButton(onClick = { onDelete(initial) }) { Text("Delete") }
                Spacer(Modifier.weight(1f))
                Button(onClick = { draft?.let(onSave) }, enabled = draft != null) { Text(if (isNew) "Add flight" else "Save") }
            }
        }
    }

    picking?.let { which ->
        AirportPickerSheet(
            title = if (which == "from") "Flying from" else "Flying to",
            recent = recentAirports,
            onDismiss = { picking = null },
            onPick = {
                if (which == "from") origin = it.code else destination = it.code
                picking = null
            },
        )
    }
    if (pickingDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = (date ?: today).let(Format::toPickerMillis))
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { date = Format.fromPickerMillis(it) }
                    pickingDate = false
                }) { Text("Done") }
            },
            dismissButton = { TextButton(onClick = { pickingDate = false }) { Text("Cancel") } },
        ) { DatePicker(state) }
    }
    pickingTime?.let { which ->
        val current = if (which == "departs") departs else arrives
        val state = rememberTimePickerState(initialHour = (current ?: 12 * 60) / 60, initialMinute = (current ?: 0) % 60)
        TimePickerDialog(
            onDismissRequest = { pickingTime = null },
            title = { Text(if (which == "departs") "Departs (local time)" else "Arrives (local time)") },
            confirmButton = {
                TextButton(onClick = {
                    val minute = state.hour * 60 + state.minute
                    if (which == "departs") departs = minute else arrives = minute
                    pickingTime = null
                }) { Text("Done") }
            },
            dismissButton = { TextButton(onClick = { pickingTime = null }) { Text("Cancel") } },
        ) { TimeInput(state) }
    }
}

@Composable
private fun AirportRow(index: Int, label: String, airport: Airport?, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    SegmentedListItem(
        colors = segmentedColors(onSheet = true),
        verticalAlignment = Alignment.CenterVertically,
        onClick = onClick,
        shapes = ListItemDefaults.segmentedShapes(index, 2),
        leadingContent = { ShapeIcon(icon) },
        overlineContent = { Text(label) },
        supportingContent = airport?.let { { Text("${Countries.flag(it.country)} ${it.name}", maxLines = 1) } },
        // Room on the right for the swap button.
        contentPadding = PaddingValues(start = 16.dp, end = 72.dp, top = 12.dp, bottom = 12.dp),
    ) {
        Text(
            airport?.let { "${it.code} · ${it.place}" } ?: "Choose an airport",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (airport != null) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun TimeRow(index: Int, label: String, minute: Int?, onPick: () -> Unit, onClear: () -> Unit) {
    SegmentedListItem(
        colors = segmentedColors(onSheet = true),
        verticalAlignment = Alignment.CenterVertically,
        onClick = onPick,
        shapes = ListItemDefaults.segmentedShapes(index, 3),
        leadingContent = { ShapeIcon(Icons.Rounded.Schedule) },
        overlineContent = { Text("$label, local time") },
        trailingContent = if (minute != null) {
            { IconButton(onClick = onClear) { Icon(Icons.Rounded.Close, contentDescription = "Clear time") } }
        } else {
            null
        },
    ) { Text(formatMinute(minute) ?: "Optional", style = MaterialTheme.typography.titleMedium) }
}

/** Search by code, city, airport name or country; recent airports first while the field is empty. */
@Composable
fun AirportPickerSheet(title: String, recent: List<String>, onDismiss: () -> Unit, onPick: (Airport) -> Unit) {
    val airports = LocalAirports.current
    var query by rememberSaveable { mutableStateOf("") }
    val results = remember(query, airports) {
        when {
            airports == null -> emptyList()
            query.isBlank() -> recent.mapNotNull { airports[it] }
            else -> airports.search(query)
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberBottomSheetState(SheetValue.Hidden, setOf(SheetValue.Hidden, SheetValue.Expanded))) {
        Column(Modifier.fillMaxHeight(0.92f).imePadding()) {
            Text(title, style = MaterialTheme.typography.titleLargeEmphasized, modifier = Modifier.padding(horizontal = 24.dp))
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                placeholder = { Text("City, airport or code") },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            if (query.isBlank() && results.isNotEmpty()) {
                Text("Recent", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 24.dp, top = 16.dp))
            }
            LazyColumn(contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)) {
                items(results, key = { it.code }) { airport ->
                    ListItem(
                        onClick = { onPick(airport) },
                        leadingContent = { Text(Countries.flag(airport.country), style = MaterialTheme.typography.headlineSmall) },
                        supportingContent = { Text("${airport.name} · ${Countries.name(airport.country)}", maxLines = 1) },
                        trailingContent = { Text(airport.code, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary) },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        modifier = Modifier.padding(horizontal = 8.dp),
                    ) { Text(airport.place, style = MaterialTheme.typography.titleMedium) }
                }
            }
        }
    }
}
