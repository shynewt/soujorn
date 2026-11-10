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
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ConfirmationNumber
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FlightLand
import androidx.compose.material.icons.rounded.FlightTakeoff
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SwapVert
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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import app.sojourn.data.Countries
import app.sojourn.data.db.Flight
import app.sojourn.data.flights.Airlines
import app.sojourn.data.flights.Airport
import app.sojourn.data.flights.AirportDirectory
import app.sojourn.data.flights.FlightText
import app.sojourn.domain.RuleStatus
import app.sojourn.ui.components.Format
import app.sojourn.ui.components.ShapeIcon
import app.sojourn.ui.components.segmentedColors
import app.sojourn.ui.plan.RuleImpact
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.delay

/**
 * What's being typed into the flight editor. Kept apart from the sheet so the editor's content
 * can be drawn on its own, and saved across rotation as one piece.
 */
@Stable
class FlightForm private constructor(val id: Long) {
    var number by mutableStateOf("")
    var origin by mutableStateOf<String?>(null)
    var destination by mutableStateOf<String?>(null)
    var date by mutableStateOf<LocalDate?>(null)
    var dayShift by mutableIntStateOf(0)
    var departs by mutableStateOf<Int?>(null)
    var arrives by mutableStateOf<Int?>(null)
    var note by mutableStateOf("")
    var reference by mutableStateOf("")
    var passenger by mutableStateOf("")
    var seat by mutableStateOf("")

    constructor(initial: Flight?) : this(initial?.id ?: 0) {
        if (initial == null) return
        number = Airlines.format(initial.number)
        origin = initial.origin.ifBlank { null }
        destination = initial.destination.ifBlank { null }
        date = initial.departureDate.takeIf { it != LocalDate.MIN }
        dayShift = if (date == null) 0 else ChronoUnit.DAYS.between(initial.departureDate, initial.arrivalDate).toInt()
        departs = initial.departureMinute
        arrives = initial.arrivalMinute
        note = initial.note
        reference = initial.reference
        passenger = initial.passenger
        seat = initial.seat
    }

    /** The flight as it would be saved, or null while the airports or date are missing. */
    fun draft(airports: AirportDirectory?): Flight? {
        val from = origin?.let { airports?.get(it) } ?: return null
        val to = destination?.let { airports?.get(it) } ?: return null
        val day = date ?: return null
        if (from.code == to.code) return null
        return Flight(
            id = id,
            number = number.filter { it.isLetterOrDigit() }.uppercase(),
            origin = from.code,
            destination = to.code,
            originCountry = from.country,
            destinationCountry = to.country,
            departureDate = day,
            arrivalDate = day.plusDays(dayShift.toLong()),
            departureMinute = departs,
            arrivalMinute = arrives,
            note = note.trim(),
            reference = reference.trim().uppercase(),
            passenger = passenger.trim(),
            seat = seat.trim().uppercase(),
        )
    }

    companion object {
        val Saver = listSaver<FlightForm, Any?>(
            save = { listOf(it.id, it.number, it.origin, it.destination, it.date, it.dayShift, it.departs, it.arrives, it.note, it.reference, it.passenger, it.seat) },
            restore = { saved ->
                FlightForm(saved[0] as Long).apply {
                    number = saved[1] as String
                    origin = saved[2] as String?
                    destination = saved[3] as String?
                    date = saved[4] as LocalDate?
                    dayShift = saved[5] as Int
                    departs = saved[6] as Int?
                    arrives = saved[7] as Int?
                    note = saved[8] as String
                    reference = saved[9] as String
                    passenger = saved[10] as String
                    seat = saved[11] as String
                }
            },
        )
    }
}

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
    val form = rememberSaveable(saver = FlightForm.Saver) { FlightForm(initial) }
    // A new flight may come with its date (added from a day) and nothing else.
    val saved = initial?.takeIf { it.id != 0L }
    var picking by remember { mutableStateOf<String?>(null) }
    var pickingDate by remember { mutableStateOf(false) }
    var pickingTime by remember { mutableStateOf<String?>(null) }
    var pasteMessage by remember { mutableStateOf<String?>(null) }
    var impact by remember { mutableStateOf<List<RuleStatus>>(emptyList()) }

    val draft = form.draft(airports)
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
            pasteMessage = "Copy a booking confirmation or boarding pass text first."
            return
        }
        val guess = FlightText.parse(text, directory, today)
        val booking = BookingText.read(text, today)
        if (guess.isEmpty && booking.isEmpty) {
            pasteMessage = "No flight found in the copied text."
            return
        }
        guess.number?.let { form.number = it }
        guess.origin?.let { form.origin = it }
        guess.destination?.let { form.destination = it }
        guess.date?.let { form.date = it }
        booking.reference.ifEmpty { null }?.let { form.reference = it }
        booking.passenger.ifEmpty { null }?.let { form.passenger = it }
        booking.seat.ifEmpty { null }?.let { form.seat = it }
        pasteMessage = "Filled in from the copied text. Check the details."
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberBottomSheetState(SheetValue.Hidden, setOf(SheetValue.Hidden, SheetValue.Expanded))) {
        FlightEditorContent(
            form = form,
            isNew = saved == null,
            impact = impact,
            pasteMessage = pasteMessage,
            onPaste = ::paste,
            onPickAirport = { picking = it },
            onPickDate = { pickingDate = true },
            onPickTime = { pickingTime = it },
            onSave = onSave,
            onDelete = saved?.let { { onDelete(it) } },
            // A saved flight can gain its boarding pass as evidence at any time.
            evidence = { saved?.let { FlightEvidence(it) } },
            modifier = Modifier.verticalScroll(rememberScrollState()).imePadding(),
        )
    }

    picking?.let { which ->
        AirportPickerSheet(
            title = if (which == FROM) "Flying from" else "Flying to",
            recent = recentAirports,
            onDismiss = { picking = null },
            onPick = {
                if (which == FROM) form.origin = it.code else form.destination = it.code
                picking = null
            },
        )
    }
    if (pickingDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = (form.date ?: today).let(Format::toPickerMillis))
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { form.date = Format.fromPickerMillis(it) }
                    pickingDate = false
                }) { Text("Done") }
            },
            dismissButton = { TextButton(onClick = { pickingDate = false }) { Text("Cancel") } },
        ) { DatePicker(state) }
    }
    pickingTime?.let { which ->
        val current = if (which == DEPARTS) form.departs else form.arrives
        val state = rememberTimePickerState(initialHour = (current ?: 12 * 60) / 60, initialMinute = (current ?: 0) % 60)
        TimePickerDialog(
            onDismissRequest = { pickingTime = null },
            title = { Text(if (which == DEPARTS) "Departs (local time)" else "Arrives (local time)") },
            confirmButton = {
                TextButton(onClick = {
                    val minute = state.hour * 60 + state.minute
                    if (which == DEPARTS) form.departs = minute else form.arrives = minute
                    pickingTime = null
                }) { Text("Done") }
            },
            dismissButton = { TextButton(onClick = { pickingTime = null }) { Text("Cancel") } },
        ) { TimeInput(state) }
    }
}

private const val FROM = "from"
private const val TO = "to"
private const val DEPARTS = "departs"
private const val ARRIVES = "arrives"

/**
 * The flight editor's content, in the order a flight is thought of: where, when, what it does
 * to the days and rules, then the details (number, booking, note) and its evidence.
 */
@Composable
fun FlightEditorContent(
    form: FlightForm,
    isNew: Boolean,
    impact: List<RuleStatus>,
    pasteMessage: String?,
    onPaste: () -> Unit,
    onPickAirport: (String) -> Unit,
    onPickDate: () -> Unit,
    onPickTime: (String) -> Unit,
    onSave: (Flight) -> Unit,
    onDelete: (() -> Unit)?,
    modifier: Modifier = Modifier,
    evidence: @Composable () -> Unit = {},
) {
    val airports = LocalAirports.current
    val from = form.origin?.let { airports?.get(it) }
    val to = form.destination?.let { airports?.get(it) }
    val draft = form.draft(airports)
    Column(modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (isNew) "Add a flight" else "Edit flight", style = MaterialTheme.typography.headlineSmallEmphasized, modifier = Modifier.weight(1f))
            FilledTonalButton(onClick = onPaste) {
                Icon(Icons.Rounded.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Paste")
            }
        }
        Text(
            pasteMessage ?: "Paste a booking email or boarding pass text to fill this in.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Box(Modifier.padding(top = 4.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                AirportRow(0, "From", from, Icons.Rounded.FlightTakeoff) { onPickAirport(FROM) }
                AirportRow(1, "To", to, Icons.Rounded.FlightLand) { onPickAirport(TO) }
            }
            FilledTonalIconButton(
                onClick = {
                    val swap = form.origin
                    form.origin = form.destination
                    form.destination = swap
                },
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp),
            ) { Icon(Icons.Rounded.SwapVert, contentDescription = "Swap airports") }
        }

        Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
            SegmentedListItem(
                colors = segmentedColors(onSheet = true),
                verticalAlignment = Alignment.CenterVertically,
                onClick = onPickDate,
                shapes = ListItemDefaults.segmentedShapes(0, 3),
                leadingContent = { ShapeIcon(Icons.Rounded.Event) },
                overlineContent = { Text("Date") },
            ) { Text(form.date?.let(Format::long) ?: "Choose the day it leaves", style = MaterialTheme.typography.titleMedium) }
            TimeRow(1, "Departs", form.departs, onPick = { onPickTime(DEPARTS) }, onClear = { form.departs = null })
            TimeRow(2, "Arrives", form.arrives, onPick = { onPickTime(ARRIVES) }, onClear = { form.arrives = null })
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Lands", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0 to "Same day", 1 to "Next day", 2 to "2 days later", -1 to "Day before").forEach { (shift, label) ->
                    FilterChip(selected = form.dayShift == shift, onClick = { form.dayShift = shift }, label = { Text(label) })
                }
            }
        }

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
        RuleImpact("With this flight", impact)

        Text("Details", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        OutlinedTextField(
            value = form.number,
            onValueChange = { form.number = it.uppercase().take(8) },
            label = { Text("Flight number (optional)") },
            placeholder = { Text("TP 1234") },
            supportingText = Airlines.name(form.number)?.let { { Text(it) } },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        BookingFields(form)
        OutlinedTextField(
            value = form.note,
            onValueChange = { form.note = it },
            label = { Text("Note (optional)") },
            singleLine = true,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth(),
        )

        evidence()

        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (onDelete != null) OutlinedButton(onClick = onDelete) { Text("Delete") }
            Spacer(Modifier.weight(1f))
            Button(onClick = { draft?.let(onSave) }, enabled = draft != null) { Text(if (isNew) "Add flight" else "Save") }
        }
    }
}

/**
 * Booking reference, passenger and seat: worth keeping as proof of who flew, but rarely typed,
 * so folded away until one is known or asked for.
 */
@Composable
private fun BookingFields(form: FlightForm) {
    val known = form.reference.isNotBlank() || form.passenger.isNotBlank() || form.seat.isNotBlank()
    var open by rememberSaveable { mutableStateOf(false) }
    val shown = open || known
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!known) {
            SegmentedListItem(
                colors = segmentedColors(onSheet = true),
                verticalAlignment = Alignment.CenterVertically,
                onClick = { open = !open },
                shapes = ListItemDefaults.segmentedShapes(0, 1),
                leadingContent = { ShapeIcon(Icons.Rounded.ConfirmationNumber) },
                supportingContent = { Text("Reference, passenger, seat") },
                trailingContent = {
                    Icon(
                        Icons.Rounded.ExpandMore,
                        contentDescription = if (open) "Hide booking details" else "Show booking details",
                        modifier = Modifier.rotate(if (open) 180f else 0f),
                    )
                },
            ) { Text("Booking details", style = MaterialTheme.typography.titleMedium) }
        }
        AnimatedVisibility(shown) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = form.reference,
                        onValueChange = { form.reference = it.uppercase().take(8) },
                        label = { Text("Booking reference") },
                        placeholder = { Text("ABC123") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.weight(1.6f),
                    )
                    OutlinedTextField(
                        value = form.seat,
                        onValueChange = { form.seat = it.uppercase().take(4) },
                        label = { Text("Seat") },
                        placeholder = { Text("14C") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.weight(1f),
                    )
                }
                OutlinedTextField(
                    value = form.passenger,
                    onValueChange = { form.passenger = it },
                    label = { Text("Passenger") },
                    placeholder = { Text("As on the boarding pass") },
                    supportingText = form.passenger.takeIf { '/' in it }?.let { { Text(passengerName(it)) } },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun AirportRow(index: Int, label: String, airport: Airport?, icon: ImageVector, onClick: () -> Unit) {
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
