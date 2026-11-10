package app.sojourn.ui.evidence

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.sojourn.AppContainer
import app.sojourn.container
import app.sojourn.data.Countries
import app.sojourn.data.EvidenceKeeper
import app.sojourn.data.db.Document
import app.sojourn.data.db.DocumentKind
import app.sojourn.data.db.Flight
import app.sojourn.data.flights.Airlines
import app.sojourn.data.travel.FoundTrip
import app.sojourn.data.travel.Names
import app.sojourn.data.travel.PassengerMatch
import app.sojourn.data.travel.TripKind
import app.sojourn.domain.DayBook
import app.sojourn.ui.EvidenceDraft
import app.sojourn.ui.appViewModel
import app.sojourn.ui.components.Format
import app.sojourn.ui.components.ShapeIcon
import app.sojourn.ui.components.segmentedColors
import app.sojourn.ui.imports.asFlight
import java.io.Serializable
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What saving a new piece of evidence came to. */
sealed interface AddOutcome {
    data object Saved : AddOutcome
    data object Duplicate : AddOutcome
    data class OtherFlight(val found: String) : AddOutcome
    data object Failed : AddOutcome
}

/** A new piece of evidence, as filled in. */
data class EvidenceForm(
    val kind: DocumentKind,
    val file: Uri?,
    val fileName: String?,
    val country: String,
    val day: LocalDate,
    val until: LocalDate?,
    val title: String,
    val note: String,
    val flightId: Long?,
    val pass: PassRead? = null,
)

/** A boarding pass as read from its file. */
data class PassRead(
    val trip: FoundTrip,
    /** The flight it would be saved as; null when an airport isn't known. */
    val flight: Flight?,
    val saved: Flight?,
    val passenger: PassengerMatch,
) {
    val departureCountry: String? get() = flight?.originCountry
    val arrivalCountry: String? get() = flight?.destinationCountry

    /** Kept with its flight on both its days; another traveller's pass stays a plain document. */
    val goesWithFlight: Boolean get() = flight != null && passenger != PassengerMatch.DIFFERENT
}

sealed interface PassReading {
    data object Idle : PassReading
    data object Reading : PassReading
    data class Read(val pass: PassRead) : PassReading
    data object Unreadable : PassReading
}

private fun EvidenceKeeper.Attached.outcome(): AddOutcome = when (this) {
    is EvidenceKeeper.Attached.Kept -> AddOutcome.Saved
    EvidenceKeeper.Attached.AlreadyKept -> AddOutcome.Duplicate
    is EvidenceKeeper.Attached.OtherFlight -> AddOutcome.OtherFlight(found)
}

/** Keeps new evidence: copies its file into the app once, and never the same file twice for a day. */
class AddEvidenceViewModel(private val container: AppContainer) : ViewModel() {
    private val repository = container.repository

    data class Context(val book: DayBook? = null, val taxResidence: String? = null)

    val context = combine(repository.book, repository.settings) { book, settings -> Context(book, settings.taxResidence) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Context())

    private val _pass = MutableStateFlow<PassReading>(PassReading.Idle)
    val pass: StateFlow<PassReading> = _pass
    private var reading: Job? = null

    /** Reads [file] as a boarding pass, so the form can fill itself in from it. */
    fun read(file: String?) {
        reading?.cancel()
        if (file == null) {
            _pass.value = PassReading.Idle
            return
        }
        _pass.value = PassReading.Reading
        reading = viewModelScope.launch {
            val read = runCatching { withContext(Dispatchers.IO) { readPass(Uri.parse(file)) } }.getOrNull()
            _pass.value = read?.let(PassReading::Read) ?: PassReading.Unreadable
        }
    }

    private suspend fun readPass(uri: Uri): PassRead? {
        val names = repository.settingsStore.current().travellerNames
        val flights = container.travel.files(listOf(uri)).trips.filter { it.kind == TripKind.FLIGHT }
        // A family's PDF holds a pass each: the user's own is the one that counts.
        val trip = flights.firstOrNull { Names.match(it.passenger, names) == PassengerMatch.MATCH } ?: flights.firstOrNull() ?: return null
        val airports = container.airports
        val flight = trip.asFlight(airports[trip.from]?.country, airports[trip.to]?.country)
        return PassRead(trip, flight, flight?.let { repository.savedFlight(it) }, Names.match(trip.passenger, names))
    }

    /** Saves [form] where the app outlives the form, then reports back through [done]. */
    fun save(form: EvidenceForm, done: (AddOutcome) -> Unit) {
        viewModelScope.launch { done(runCatching { keep(form) }.getOrDefault(AddOutcome.Failed)) }
    }

    private suspend fun keep(form: EvidenceForm): AddOutcome {
        val store = container.evidence
        val pass = form.pass
        // A pass read as the user's flight goes with that flight, saving it first if it's new,
        // as Import would: one change, undone together.
        if (form.kind == DocumentKind.BOARDING_PASS && form.flightId == null && form.file != null && pass != null && pass.goesWithFlight) {
            val read = pass.flight!!
            val label = listOf("Added boarding pass", Airlines.format(read.number), "${read.origin}→${read.destination}").filter { it.isNotBlank() }.joinToString(" ")
            return repository.recordChange(label) {
                val flight = repository.savedFlight(read) ?: repository.importFlights(listOf(read)).let { repository.savedFlight(read) }
                    ?: return@recordChange AddOutcome.Failed
                container.keeper.attach(flight, form.file, form.note).outcome()
            }
        }
        // A flight's boarding pass is read and matched like one added from the flight itself.
        if (form.kind == DocumentKind.BOARDING_PASS && form.flightId != null && form.file != null) {
            val flight = repository.flights.first().firstOrNull { it.id == form.flightId }
            if (flight != null) return container.keeper.attach(flight, form.file, form.note).outcome()
        }
        // A boarding pass for a flight already saved goes with that flight, on both its days.
        if (form.kind == DocumentKind.BOARDING_PASS && form.flightId == null && form.file != null) {
            when (val result = container.keeper.attachToSaved(form.file)) {
                is EvidenceKeeper.Attached.Kept -> return AddOutcome.Saved
                EvidenceKeeper.Attached.AlreadyKept -> return AddOutcome.Duplicate
                else -> Unit
            }
        }
        val stored = form.file?.let { uri ->
            val hash = store.hash(uri) ?: return AddOutcome.Failed
            val same = repository.documents(form.day, form.country, form.kind).any { existing -> existing.file?.let { store.hashOf(it) } == hash }
            if (same) return AddOutcome.Duplicate
            store.keep(uri, form.fileName ?: form.kind.name.lowercase()) ?: return AddOutcome.Failed
        }
        val document = Document(
            day = form.day,
            country = form.country,
            kind = form.kind,
            title = form.title,
            file = stored,
            until = form.until?.takeIf { form.kind.period && it != form.day },
            flightId = form.flightId,
            note = form.note,
        )
        return if (repository.addDocument(document)) AddOutcome.Saved else AddOutcome.Duplicate
    }
}

/** Kinds that are worth keeping without a file: a note can say what they show. */
private val DocumentKind.fileOptional: Boolean get() = this == DocumentKind.OTHER

/**
 * Adds a piece of evidence: its kind (each says what it proves), the file, country, a day or
 * a period, a title and a note.
 *
 * Full-screen rather than a bottom sheet: it's a form of six parts with text entry, which on a
 * phone would leave a sheet scrolling under the keyboard; Material's guidance gives forms like
 * this a full-screen dialog. It opens on the choice of kind when the caller didn't say.
 */
@Composable
fun AddEvidenceSheet(draft: EvidenceDraft, onDismiss: () -> Unit) {
    val vm = appViewModel { AddEvidenceViewModel(it) }
    val known by vm.context.collectAsStateWithLifecycle()
    val app = LocalContext.current.container
    var values by rememberSaveable { mutableStateOf(FormValues(draft.kind, null, draft.country, draft.day ?: LocalDate.now(), draft.until)) }
    var saving by rememberSaveable { mutableStateOf(false) }
    var problem by rememberSaveable { mutableStateOf<String?>(null) }
    val fileName by produceState<String?>(null, values.file) {
        value = values.file?.let { withContext(Dispatchers.IO) { app.travel.displayName(Uri.parse(it)) } }
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) values = values.copy(file = uri.toString()) }
    val pickPicture = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) values = values.copy(file = uri.toString()) }
    val country = values.country ?: known.book?.get(values.day)?.last ?: known.taxResidence
    val pass by vm.pass.collectAsStateWithLifecycle()
    LaunchedEffect(values.file, values.kind) { vm.read(values.file.takeIf { values.kind == DocumentKind.BOARDING_PASS }) }
    // Filled in once per file, so what the user changes afterwards stays, rotation included.
    var filledFrom by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(pass) {
        val read = (pass as? PassReading.Read)?.pass ?: return@LaunchedEffect
        if (filledFrom == values.file) return@LaunchedEffect
        filledFrom = values.file
        values = values.copy(
            day = read.trip.departure,
            country = read.departureCountry ?: values.country,
            title = values.title.ifBlank { app.keeper.title(DocumentKind.BOARDING_PASS, read.trip) },
            note = if (read.goesWithFlight) values.note else values.note.ifBlank { passDetails(read.trip) },
        )
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        AddEvidenceLayout(
            values = values,
            onChange = { values = it },
            fileName = fileName,
            country = country,
            recents = known.book?.recentCountries().orEmpty(),
            saving = saving,
            problem = problem,
            onClose = onDismiss,
            onPickFile = { pickFile.launch(arrayOf("application/pdf", "image/*", "application/vnd.apple.pkpass", "*/*")) },
            onPickPicture = { pickPicture.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            pass = pass,
            onSave = { kind, title ->
                saving = true
                problem = null
                val read = (pass as? PassReading.Read)?.pass
                val form = EvidenceForm(kind, values.file?.let(Uri::parse), fileName, country!!, values.day, values.end, title, values.note.trim(), draft.flightId, read)
                vm.save(form) { outcome ->
                    saving = false
                    when (outcome) {
                        AddOutcome.Saved -> onDismiss()
                        AddOutcome.Duplicate -> problem = "That's already kept for this day and country."
                        is AddOutcome.OtherFlight -> problem = "That's the pass for ${outcome.found}, not this flight, so it wasn't added."
                        AddOutcome.Failed -> problem = "Couldn't read that file. Try choosing it again."
                    }
                }
            },
        )
    }
}

/** What the form holds while it's filled in; kept across rotation. */
data class FormValues(
    val kind: DocumentKind?,
    val file: String?,
    /** The country chosen, or null to take the day's own. */
    val country: String?,
    val day: LocalDate,
    val until: LocalDate?,
    val title: String = "",
    val note: String = "",
) : Serializable {
    /** The last day covered, for kinds that cover a period. */
    val end: LocalDate? get() = if (kind?.period == true) until ?: day else null

    val defaultTitle: String get() = kind?.let { "${it.label}, ${Format.range(day, end ?: day)}" }.orEmpty()
}

/** The form itself, apart from where its values live, so it can be shown in screenshot tests. */
@Composable
fun AddEvidenceLayout(
    values: FormValues,
    onChange: (FormValues) -> Unit,
    fileName: String?,
    country: String?,
    recents: List<String>,
    saving: Boolean,
    problem: String?,
    onClose: () -> Unit,
    onPickFile: () -> Unit,
    onPickPicture: () -> Unit,
    pass: PassReading = PassReading.Idle,
    onSave: (DocumentKind, String) -> Unit,
) {
    val current = values.kind
    val canSave = current != null && country != null && !saving && pass != PassReading.Reading &&
        (values.file != null || current.fileOptional && (values.note.isNotBlank() || values.title.isNotBlank()))
    val withFlight = (pass as? PassReading.Read)?.pass?.takeIf { it.goesWithFlight && current == DocumentKind.BOARDING_PASS }
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(if (current == null) "What is it?" else "Add evidence", style = MaterialTheme.typography.titleLargeEmphasized) },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, contentDescription = "Close without saving") } },
                actions = {
                    if (current != null) {
                        Button(
                            enabled = canSave,
                            onClick = { onSave(current, values.title.trim().ifBlank { values.defaultTitle }) },
                            modifier = Modifier.padding(end = 8.dp),
                        ) { Text(if (saving) "Saving…" else "Save") }
                    }
                },
            )
        },
    ) { padding ->
        if (current == null) {
            KindChoice(padding) { onChange(values.copy(kind = it)) }
            return@Scaffold
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(padding).padding(horizontal = 16.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SegmentedListItem(
                colors = segmentedColors(onSheet = true),
                verticalAlignment = Alignment.CenterVertically,
                onClick = { onChange(values.copy(kind = null)) },
                shapes = ListItemDefaults.segmentedShapes(0, 1),
                leadingContent = { ShapeIcon(current.icon) },
                overlineContent = { Text("Kind") },
                supportingContent = { Text(current.hint) },
                trailingContent = { StrengthTag(current.strength) },
            ) { Text(current.label, style = MaterialTheme.typography.titleMedium) }

            FilePart(values.file, fileName, current.fileOptional, onClear = { onChange(values.copy(file = null)) }, onFile = onPickFile, onPicture = onPickPicture)

            if (current == DocumentKind.BOARDING_PASS) PassPart(pass)

            // A pass read as the user's flight says where and when itself, on both its days.
            if (withFlight == null) {
                WhereAndWhen(
                    country = country,
                    day = values.day,
                    until = values.end,
                    period = current.period,
                    recents = recents,
                    onCountry = { onChange(values.copy(country = it)) },
                    onDates = { from, to -> onChange(values.copy(day = from, until = to)) },
                )
                OutlinedTextField(
                    value = values.title,
                    onValueChange = { onChange(values.copy(title = it)) },
                    label = { Text("Title") },
                    placeholder = { Text(values.defaultTitle) },
                    supportingText = if (values.title.isBlank()) ({ Text("Left empty: “${values.defaultTitle}”") }) else null,
                    singleLine = true,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            OutlinedTextField(
                value = values.note,
                onValueChange = { onChange(values.copy(note = it)) },
                label = { Text(if (current.fileOptional && values.file == null) "Note: what it shows" else "Note (optional)") },
                placeholder = { Text("Who issued it, what it shows…") },
                minLines = 2,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            problem?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
            Text(
                "The file is copied into Sojourn, so it stays even if the original goes, and goes into the year's evidence pack. Nothing leaves your phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun clock(minute: Int?): String? = minute?.let { "%02d:%02d".format(it / 60, it % 60) }

/** What a pass says beyond its flight: passenger, seat and booking. */
internal fun passDetails(trip: FoundTrip): String = listOfNotNull(
    trip.passenger?.takeIf { it.isNotBlank() },
    trip.seat?.takeIf { it.isNotBlank() }?.let { "Seat $it" },
    trip.reference?.takeIf { it.isNotBlank() }?.let { "Booking $it" },
).joinToString(" · ")

/** What was read from a boarding pass, and where saving it will keep it. */
@Composable
private fun PassPart(pass: PassReading) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    when (pass) {
        PassReading.Idle -> Unit
        PassReading.Reading -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp)) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text("Reading the pass…", style = MaterialTheme.typography.bodyMedium, color = muted)
        }
        PassReading.Unreadable -> Text(
            "No flight could be read from this file. Say its day and country below.",
            style = MaterialTheme.typography.bodyMedium,
            color = muted,
            modifier = Modifier.padding(start = 4.dp),
        )
        is PassReading.Read -> {
            val read = pass.pass
            val trip = read.trip
            val route = listOf(Airlines.format(trip.number), "${trip.from} → ${trip.to}").filter { it.isNotBlank() }.joinToString(" · ")
            val times = listOfNotNull(clock(trip.departureMinute), clock(trip.arrivalMinute)).joinToString(" → ")
            val dates = if (trip.arrival == trip.departure) Format.long(trip.departure) else "${Format.long(trip.departure)} → ${Format.long(trip.arrival)}"
            val ends = listOfNotNull(read.departureCountry, read.arrivalCountry).distinct()
                .joinToString(" and ") { "${Countries.flag(it)} ${Countries.name(it)}" }
            val outcome = when {
                read.passenger == PassengerMatch.DIFFERENT ->
                    "It's in ${trip.passenger}'s name, not yours, so it's kept as a document without adding the flight."
                read.flight == null -> "One of its airports isn't known, so it's kept as a document. Check its day and country below."
                read.saved != null -> "Goes with your saved flight, as evidence for both ends: $ends."
                else -> "Saving adds this flight too, and keeps the pass as evidence for both ends: $ends."
            }
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Read from the pass", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    Text(route, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    Text(listOf(dates, times).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    passDetails(trip).takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                    Spacer(Modifier.size(4.dp))
                    Text(outcome, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
        }
    }
}

/** Every kind that can be added, by group, each with how much it proves and what it shows. */
@Composable
private fun KindChoice(padding: PaddingValues, onPick: (DocumentKind) -> Unit) {
    val groups = DocumentKind.ADDABLE.groupBy { it.group }
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 32.dp),
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
    ) {
        groups.forEach { (group, kinds) ->
            item(key = "g-${group.name}") {
                Text(group.label, style = MaterialTheme.typography.titleMediumEmphasized, modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 4.dp))
            }
            itemsIndexed(kinds, key = { _, k -> "k-${k.name}" }) { index, kind ->
                SegmentedListItem(
                    colors = segmentedColors(onSheet = true),
                    verticalAlignment = Alignment.CenterVertically,
                    onClick = { onPick(kind) },
                    shapes = ListItemDefaults.segmentedShapes(index, kinds.size),
                    leadingContent = { ShapeIcon(kind.icon) },
                    supportingContent = { Text(kind.hint) },
                    trailingContent = { StrengthTag(kind.strength) },
                ) { Text(kind.label) }
            }
        }
    }
}

/** The chosen file with its picture or name, or the two ways to choose one. */
@Composable
private fun FilePart(file: String?, name: String?, optional: Boolean, onClear: () -> Unit, onFile: () -> Unit, onPicture: () -> Unit) {
    if (file != null) {
        SegmentedListItem(
            colors = segmentedColors(onSheet = true),
            verticalAlignment = Alignment.CenterVertically,
            onClick = onFile,
            shapes = ListItemDefaults.segmentedShapes(0, 1),
            leadingContent = { PhotoThumb(file, 56.dp, fallback = Icons.Rounded.Description) },
            overlineContent = { Text("File") },
            trailingContent = { IconButton(onClick = onClear) { Icon(Icons.Rounded.Close, contentDescription = "Remove the file") } },
        ) { Text(name ?: "Chosen file", maxLines = 2) }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            if (optional) "File (optional)" else "File",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onFile, modifier = Modifier.weight(1f)) {
                Icon(Icons.Rounded.AttachFile, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("A document")
            }
            OutlinedButton(onClick = onPicture, modifier = Modifier.weight(1f)) {
                Icon(Icons.Rounded.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("A picture")
            }
        }
        Text(
            "A PDF, a photo or scan, or a Wallet pass.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}
