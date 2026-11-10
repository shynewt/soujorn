package app.sojourn.ui.imports

import android.Manifest
import android.annotation.SuppressLint
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.toShape
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import app.sojourn.data.db.Source
import java.time.LocalDate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sojourn.data.Countries
import app.sojourn.ui.components.segmentedColors
import app.sojourn.ui.LocalIncomingFiles
import app.sojourn.ui.LocalIncomingText
import app.sojourn.ui.settings.NamesDialog
import app.sojourn.data.travel.PassengerMatch
import app.sojourn.data.travel.TripKind
import app.sojourn.data.travel.UnreadEvent
import android.content.pm.PackageManager
import androidx.activity.result.PickVisualMediaRequest
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.ConfirmationNumber
import androidx.compose.material.icons.rounded.Flight
import androidx.compose.material.icons.rounded.Train
import androidx.compose.material.icons.rounded.DirectionsBus
import androidx.compose.material.icons.rounded.DirectionsBoat
import androidx.compose.material.icons.rounded.Hotel
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.PersonOff
import androidx.compose.material3.OutlinedButton
import app.sojourn.ui.appViewModel
import app.sojourn.ui.components.DateRangeDialog
import app.sojourn.ui.components.EmptyState
import app.sojourn.ui.components.FlagBadge
import app.sojourn.ui.components.Format
import app.sojourn.ui.components.ShapeIcon

// Each newer permission is only added on the release that knows it.
@SuppressLint("InlinedApi")
private val PERMISSIONS = buildList {
    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.READ_MEDIA_IMAGES) else add(Manifest.permission.READ_EXTERNAL_STORAGE)
    if (Build.VERSION.SDK_INT >= 34) add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    if (Build.VERSION.SDK_INT >= 29) add(Manifest.permission.ACCESS_MEDIA_LOCATION)
}.toTypedArray()

@Composable
fun ImportScreen(back: () -> Unit) {
    val vm = appViewModel { ImportViewModel(it) }
    val phase by vm.phase.collectAsStateWithLifecycle()
    var range by remember { mutableStateOf(vm.defaultRange()) }
    var pickingDates by remember { mutableStateOf(false) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val canRead = result.entries.any { (permission, granted) -> granted && permission.startsWith("android.permission.READ_") }
        if (canRead) vm.scanPhotos(range.first, range.second) else vm.denied()
    }
    // Location files come with all kinds of MIME types depending on the app that saved them.
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments(), vm::scanFiles)
    // The photo picker needs no permission: only the screenshots chosen are shared.
    val screenshots = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(), vm::scanFiles)
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree(), vm::scanFolder)
    // Far enough back for residence and citizenship applications, which ask for up to ten years.
    val calendarRange = remember { LocalDate.now().minusYears(10) to LocalDate.now().plusYears(2) }
    val calendar = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.scanCalendar(calendarRange.first, calendarRange.second) else vm.calendarDenied()
    }
    val context = LocalContext.current
    var namesFor by remember { mutableStateOf(false) }
    val incomingText = LocalIncomingText.current
    LaunchedEffect(incomingText) {
        incomingText.collect { shared ->
            if (shared != null) {
                vm.scanText(shared.first, shared.second)
                incomingText.value = null
            }
        }
    }
    val incoming = LocalIncomingFiles.current
    LaunchedEffect(incoming) {
        incoming.collect { uris ->
            if (uris.isNotEmpty()) {
                vm.scanFiles(uris)
                incoming.value = emptyList()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Import history", style = MaterialTheme.typography.titleLargeEmphasized) },
                navigationIcon = {
                    IconButton(onClick = { if (phase is ImportPhase.Idle || phase is ImportPhase.Done) back() else vm.reset() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        AnimatedContent(phase, contentKey = { it::class }, modifier = Modifier.padding(padding), label = "phase") { current ->
            when (current) {
                is ImportPhase.Idle -> ImportSources(
                    range = range,
                    error = current.error,
                    onDates = { pickingDates = true },
                    onPhotos = { permissions.launch(PERMISSIONS) },
                    onFile = { files.launch(arrayOf("*/*")) },
                    onCalendar = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED) {
                            vm.scanCalendar(calendarRange.first, calendarRange.second)
                        } else {
                            calendar.launch(Manifest.permission.READ_CALENDAR)
                        }
                    },
                    onScreenshots = { screenshots.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    onFolder = { folder.launch(null) },
                )

                is ImportPhase.Scanning -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                        Text(current.title, style = MaterialTheme.typography.titleLargeEmphasized)
                        Text(current.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(16.dp))
                        val progress = current.progress
                        if (progress == null) {
                            LinearWavyProgressIndicator(Modifier.fillMaxWidth())
                        } else {
                            LinearWavyProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        }
                        Spacer(Modifier.height(16.dp))
                        TextButton(onClick = vm::reset) { Text("Cancel") }
                    }
                }

                is ImportPhase.Review -> Review(current, vm)

                is ImportPhase.Trips -> TripsReview(current, vm, onAddName = { namesFor = true })

                is ImportPhase.Done -> EmptyState(
                    icon = Icons.Rounded.Done,
                    title = when {
                        current.what != "days" && current.added == 0 && current.evidence > 0 -> "Kept ${current.evidence} as evidence"
                        current.what != "days" -> "Added ${current.added} ${current.what}"
                        current.added == 0 && current.backedUp > 0 -> "Backed up ${Format.days(current.backedUp)}"
                        else -> "Added ${Format.days(current.added)}"
                    },
                    body = buildString {
                        append("They're in your calendar and timeline now, and every count has been updated.")
                        if (current.backedUp > 0 && current.added > 0) append(" ${Format.days(current.backedUp)} you already had are now backed up by this as evidence.")
                        else if (current.backedUp > 0) append(" They now count as documented in your evidence.")
                        if (current.skipped > 0) append(" ${current.skipped} ${if (current.skipped == 1) "flight was" else "flights were"} already saved, so ${if (current.skipped == 1) "it was" else "they were"} left out.")
                        if (current.evidence > 0) {
                            append(" ${current.evidence} ${if (current.evidence == 1) "document was" else "documents were"} kept as evidence for ${if (current.added == 0) "flights you already had" else "these days"}: they're in the day sheets and the evidence pack.")
                        } else if (current.what != "days") {
                            append(" Their files were already kept as evidence, so nothing was copied again.")
                        }
                        if (current.duplicatesRemoved > 0) append(" ${current.duplicatesRemoved} duplicate ${if (current.duplicatesRemoved == 1) "flight" else "flights"} from before ${if (current.duplicatesRemoved == 1) "was" else "were"} merged.")
                    },
                    action = { Button(onClick = back) { Text("Done") } },
                )
            }
        }
    }
    if (namesFor) {
        val settings by vm.settings.collectAsStateWithLifecycle()
        NamesDialog(settings.travellerNames, onDismiss = { namesFor = false }) {
            vm.setNames(it)
            namesFor = false
        }
    }
    if (pickingDates) {
        DateRangeDialog(
            start = range.first,
            end = range.second,
            onDismiss = { pickingDates = false },
            onConfirm = { from, to ->
                range = from to to
                pickingDates = false
            },
        )
    }
}

@Composable
fun ImportSources(
    range: Pair<LocalDate, LocalDate>,
    error: String?,
    onDates: () -> Unit,
    onPhotos: () -> Unit,
    onFile: () -> Unit,
    onCalendar: () -> Unit = {},
    onScreenshots: () -> Unit = {},
    onFolder: () -> Unit = {},
) {
    var help by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Fill in where you've been and where you're going. Everything is read on the phone, which Sojourn can't take online, only trips and countries are kept, and you approve each one.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        if (error != null) {
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                Text(error, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
            }
        }
        var calendarHelp by rememberSaveable { mutableStateOf(false) }
        SourceCard(
            icon = Icons.Rounded.CalendarMonth,
            shape = MaterialShapes.Cookie6Sided.toShape(),
            title = "Flights in your calendar",
            body = "Gmail puts flights from booking emails in Google Calendar, and airlines and TripIt add them too. Sojourn reads them from the calendars on this phone.",
        ) {
            Button(onClick = onCalendar, modifier = Modifier.fillMaxWidth()) { Text("Find flights") }
            TextButton(onClick = { calendarHelp = !calendarHelp }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(if (calendarHelp) "Hide" else "Flights missing?")
            }
            AnimatedVisibility(calendarHelp) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HelpStep(1, "In Gmail, smart features must be on; in Google Calendar › Settings › Events from Gmail, show events.")
                    HelpStep(2, "In newer Google Calendar versions, also turn on Settings › General › Share Google Calendar data with other apps.")
                    HelpStep(3, "Gmail skips emails forwarded from another account; share those flights' boarding passes to Sojourn instead.")
                }
            }
        }
        SourceCard(
            icon = Icons.Rounded.ConfirmationNumber,
            shape = MaterialShapes.Gem.toShape(),
            title = "Boarding passes",
            body = "Google Wallet doesn't let other apps read your passes, so take a screenshot of each one. PDF boarding passes and Apple Wallet passes work too: choose many at once, a zip of them, or a whole folder. You pick which to add, copies of the same flight are merged, and every file is kept for the evidence pack.",
        ) {
            Button(onClick = onScreenshots, modifier = Modifier.fillMaxWidth()) { Text("Choose screenshots") }
            OutlinedButton(onClick = onFile, modifier = Modifier.fillMaxWidth()) { Text("Choose PDFs, passes or a zip") }
            OutlinedButton(onClick = onFolder, modifier = Modifier.fillMaxWidth()) { Text("Choose a folder") }
        }
        SourceCard(
            icon = Icons.Rounded.PhotoLibrary,
            shape = MaterialShapes.Cookie9Sided.toShape(),
            title = "Photos",
            body = "Most photos remember where they were taken.",
        ) {
            Surface(onClick = onDates, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.DateRange, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Taken", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(Format.range(range.first, range.second), style = MaterialTheme.typography.bodyLarge)
                    }
                    Text("Change", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
            }
            Button(onClick = onPhotos, modifier = Modifier.fillMaxWidth()) { Text("Scan photos") }
        }
        SourceCard(
            icon = Icons.Rounded.Route,
            shape = MaterialShapes.Clover4Leaf.toShape(),
            title = "Location files",
            body = "GPX, TCX, FIT, KML or GeoJSON tracks from Strava, Komoot, Garmin and others, or your Google Timeline export. Zips work too, and so does sharing a file to Sojourn.",
        ) {
            Button(onClick = onFile, modifier = Modifier.fillMaxWidth()) { Text("Choose files") }
            TextButton(onClick = { help = !help }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(if (help) "Hide" else "Where's my Google Timeline?")
            }
            AnimatedVisibility(help) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HelpStep(1, "On your phone, open Settings › Location › Location services › Timeline.")
                    HelpStep(2, "Tap “Export Timeline data” and save the file.")
                    HelpStep(3, "Choose that file here. Older Google Takeout exports (Records.json or the whole zip) work as well.")
                }
            }
        }
    }
}

@Composable
private fun SourceCard(
    icon: ImageVector,
    shape: Shape,
    title: String,
    body: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ShapeIcon(icon, size = 48.dp, shape = shape)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            content()
        }
    }
}

@Composable
private fun HelpStep(number: Int, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(24.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text("$number", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun Review(review: ImportPhase.Review, vm: ImportViewModel) {
    if (review.stays.isEmpty()) {
        EmptyState(
            icon = Icons.Rounded.SearchOff,
            title = "No locations found",
            body = if (review.source == Source.PHOTO) {
                "None of the photos in this range had a location. Cameras only save it when location tagging is on."
            } else {
                "The file had no dated locations in the past."
            },
            action = { TextButton(onClick = vm::reset) { Text("Try something else") } },
        )
        return
    }
    val selected = review.stays.filterIndexed { index, _ -> index in review.selected }
    val adding = vm.daysToAdd(selected, review.onlyEmpty)
    val backing = vm.daysToBack(selected)
    Column {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
        ) {
            item {
                Column(Modifier.padding(bottom = 12.dp)) {
                    Text("Found ${review.stays.size} stays", style = MaterialTheme.typography.headlineSmallEmphasized)
                    Text(
                        review.origin,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Only untracked days", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Days you already have keep their country. Where it's the same country, ${if (review.source == Source.PHOTO) "the photos back them up" else "this backs them up"} as evidence.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = review.onlyEmpty, onCheckedChange = vm::setOnlyEmpty)
                    }
                }
            }
            itemsIndexed(review.stays, key = { _, stay -> "${stay.country}-${stay.start}" }) { index, stay ->
                SegmentedListItem(
                    colors = segmentedColors(),
                    verticalAlignment = Alignment.CenterVertically,
                    checked = index in review.selected,
                    onCheckedChange = { vm.toggle(index) },
                    shapes = ListItemDefaults.segmentedShapes(index, review.stays.size),
                    leadingContent = { FlagBadge(stay.country, size = 40.dp) },
                    supportingContent = {
                        Text(
                            "${Format.range(stay.start, stay.end)} · " + listOfNotNull(
                                stay.newDays.takeIf { it > 0 }?.let { "${Format.days(it)} new" },
                                stay.backed.takeIf { it > 0 }?.let { "backs up ${Format.days(it)}" },
                            ).ifEmpty { listOf("all days known") }.joinToString(" · "),
                        )
                    },
                    trailingContent = { Checkbox(checked = index in review.selected, onCheckedChange = null) },
                ) { Text(Countries.name(stay.country)) }
            }
        }
        Button(
            onClick = { vm.import(selected, review.onlyEmpty) },
            enabled = adding > 0 || backing > 0,
            modifier = Modifier.fillMaxWidth().padding(16.dp).height(56.dp),
        ) {
            Text(
                when {
                    adding > 0 && backing > 0 -> "Add ${Format.days(adding)} · back up ${Format.days(backing)}"
                    adding > 0 -> "Add ${Format.days(adding)}"
                    backing > 0 -> "Back up ${Format.days(backing)}"
                    else -> "Nothing new to add"
                },
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Flight events whose airports couldn't be made out, so none goes missing without a word. */
@Composable
private fun UnreadEvents(events: List<UnreadEvent>) {
    var open by rememberSaveable { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "${events.size} flight ${if (events.size == 1) "event" else "events"} couldn't be read: the airports weren't clear. Add ${if (events.size == 1) "it" else "them"} by hand in Plan › Flights.",
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = { open = !open }) { Text(if (open) "Hide" else "Show ${if (events.size == 1) "it" else "them"}") }
            AnimatedVisibility(open) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    events.sortedBy { it.date }.forEach { event ->
                        Text("${Format.long(event.date)} · ${event.title}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** The trip review as it looks for [items], for screenshots. */
@Composable
fun TripsPreview(items: List<TripItem>) {
    TripsList(
        ImportPhase.Trips(items, "From 214 calendar events", items.indices.filter { items[it].selectedByDefault }.toSet(), namesUnknown = false),
        onToggle = {}, onImport = {}, onReset = {}, onAddName = {},
    )
}

@Composable
private fun TripsReview(trips: ImportPhase.Trips, vm: ImportViewModel, onAddName: () -> Unit) =
    TripsList(trips, vm::toggleTrip, vm::importTrips, vm::reset, onAddName)

@Composable
private fun TripsList(trips: ImportPhase.Trips, onToggle: (Int) -> Unit, onImport: () -> Unit, onReset: () -> Unit, onAddName: () -> Unit) {
    if (trips.items.isEmpty() && trips.unread.isEmpty()) {
        EmptyState(
            icon = Icons.Rounded.SearchOff,
            title = "No trips found",
            body = "Nothing here looked like a flight, train, bus, ferry or hotel booking. ${trips.origin}." +
                if ("file" in trips.origin) " If it's a boarding pass, a screenshot of just its barcode may read where the whole page didn't." else "",
            action = { TextButton(onClick = onReset) { Text("Try something else") } },
        )
        return
    }
    val count = trips.selected.size
    Column {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
        ) {
            item {
                Column(Modifier.padding(bottom = 12.dp)) {
                    Text("Found ${trips.items.size} ${if (trips.items.size == 1) "trip" else "trips"}", style = MaterialTheme.typography.headlineSmallEmphasized)
                    Text(trips.origin, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (trips.duplicatesRemoved > 0) {
                        Text(
                            "Merged ${trips.duplicatesRemoved} ${if (trips.duplicatesRemoved == 1) "flight" else "flights"} saved twice by an earlier import.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (trips.unread.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        UnreadEvents(trips.unread)
                    }
                    if (trips.namesUnknown) {
                        Spacer(Modifier.height(12.dp))
                        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.tertiaryContainer) {
                            Column(Modifier.padding(16.dp)) {
                                Text(
                                    "Some bookings name their passengers. Add your name to leave out trips you booked for someone else.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                )
                                TextButton(onClick = onAddName) { Text("Add my name") }
                            }
                        }
                    }
                }
            }
            itemsIndexed(trips.items, key = { index, item -> "$index-${item.trip.key}" }) { index, item ->
                val trip = item.trip
                val checked = index in trips.selected
                SegmentedListItem(
                    colors = segmentedColors(),
                    verticalAlignment = Alignment.CenterVertically,
                    checked = checked,
                    onCheckedChange = { onToggle(index) },
                    enabled = item.usable,
                    shapes = ListItemDefaults.segmentedShapes(index, trips.items.size),
                    leadingContent = {
                        ShapeIcon(
                            when (trip.kind) {
                                TripKind.FLIGHT -> Icons.Rounded.Flight
                                TripKind.TRAIN -> Icons.Rounded.Train
                                TripKind.BUS -> Icons.Rounded.DirectionsBus
                                TripKind.FERRY -> Icons.Rounded.DirectionsBoat
                                TripKind.CAR -> Icons.Rounded.DirectionsCar
                                TripKind.STAY -> Icons.Rounded.Hotel
                            },
                        )
                    },
                    overlineContent = { Text(listOfNotNull(trip.kind.label, trip.number.ifBlank { null }).joinToString(" · ")) },
                    supportingContent = {
                        Column {
                            Text(
                                buildString {
                                    append(Format.range(trip.departure, trip.arrival))
                                    trip.departureMinute?.let { append(" · %02d:%02d".format(it / 60, it % 60)) }
                                },
                            )
                            when {
                                !item.usable -> Text("Country unknown, so it can't be added")
                                item.duplicate -> Text(if (trip.rawKind != null) "Already in Sojourn · kept as evidence for it" else "Already in Sojourn")
                                item.passengerMatch == PassengerMatch.DIFFERENT -> Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Rounded.PersonOff, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("For ${trip.passenger}, not you")
                                }
                                trip.passenger != null -> Text("For ${trip.passenger}")
                            }
                            if (trip.source.isNotBlank()) Text(trip.source, maxLines = 1, style = MaterialTheme.typography.bodySmall)
                            if (item.othersLeftOut > 0) {
                                Text(
                                    "${item.othersLeftOut} other ${if (item.othersLeftOut == 1) "passenger's pass" else "passengers' passes"} for this flight left out",
                                    maxLines = 1,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            if (item.copies.isNotEmpty()) {
                                Text("Found ${item.copies.size + 1} times, kept as one", maxLines = 1, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    },
                    trailingContent = { Checkbox(checked = checked, onCheckedChange = null, enabled = item.usable) },
                ) {
                    Text(
                        if (trip.kind == TripKind.STAY) {
                            "${trip.from.ifBlank { "Stay" }} · ${item.toCountry?.let { Countries.flag(it) + " " + Countries.name(it) } ?: ""}"
                        } else {
                            "${item.fromCountry?.let(Countries::flag).orEmpty()} ${trip.from}  →  ${item.toCountry?.let(Countries::flag).orEmpty()} ${trip.to}"
                        },
                    )
                }
            }
        }
        // Flights already saved still gain the files they were found in as evidence.
        val evidence = trips.items.count { it.duplicate && it.usable && it.trip.rawKind != null }
        Button(
            onClick = onImport,
            enabled = count > 0 || evidence > 0,
            modifier = Modifier.fillMaxWidth().padding(16.dp).height(56.dp),
        ) {
            Text(
                when {
                    count > 0 -> "Add $count ${if (count == 1) "trip" else "trips"}"
                    evidence > 0 -> "Keep as evidence for ${if (evidence == 1) "the flight" else "$evidence flights"} you have"
                    else -> "Nothing chosen"
                },
                textAlign = TextAlign.Center,
            )
        }
    }
}
