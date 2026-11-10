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
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.ui.text.style.TextOverflow
import app.sojourn.ui.AppTab
import app.sojourn.ui.LocalAppActions
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
                title = { Text("Import", style = MaterialTheme.typography.titleLargeEmphasized) },
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

                is ImportPhase.Done -> ImportDone(current, onMore = vm::reset)
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

/**
 * Where trips and days can come from, in the order most people have them: calendar flights,
 * boarding passes, photos, location files. Each says what it keeps as evidence, since an import
 * is also the quickest way to fill the evidence vault.
 */
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
    val actions = LocalAppActions.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Fill in where you've been and where you're going. Everything is read on this phone and nothing leaves it; you approve each trip and day before it's added.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp, end = 8.dp), verticalAlignment = Alignment.Top) {
                Icon(Icons.Rounded.Verified, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.padding(top = 2.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Imports keep evidence too: each boarding pass, booking and calendar event is kept with its flight, and photos and tracks back up their days.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    TextButton(onClick = { actions.tab(AppTab.EVIDENCE) }) { Text("Open Evidence") }
                }
            }
        }
        if (error != null) {
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                Text(error, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
            }
        }
        SourceCard(
            icon = Icons.Rounded.CalendarMonth,
            shape = MaterialShapes.Cookie6Sided.toShape(),
            title = "Calendar flights",
            body = "Flights Gmail, airlines and TripIt put in your calendars.",
            keeps = "Keeps each event as evidence",
            helpLabel = "Flights missing?",
            help = listOf(
                "In Gmail, smart features must be on; in Google Calendar › Settings › Events from Gmail, show events.",
                "In newer Google Calendar versions, also turn on Settings › General › Share Google Calendar data with other apps.",
                "Gmail skips emails forwarded from another account; import those flights' boarding passes instead.",
            ),
        ) {
            Button(onClick = onCalendar, modifier = Modifier.fillMaxWidth()) { Text("Find flights") }
        }
        SourceCard(
            icon = Icons.Rounded.ConfirmationNumber,
            shape = MaterialShapes.Gem.toShape(),
            title = "Boarding passes",
            body = "Screenshots, PDFs, Wallet passes, a zip or a folder.",
            keeps = "Keeps each file as evidence",
            helpLabel = "Passes in Google Wallet?",
            help = listOf(
                "Google Wallet doesn't let other apps read passes, so open each one and take a screenshot.",
                "Choose all the screenshots at once. Copies of the same flight are merged into one.",
            ),
        ) {
            Button(onClick = onScreenshots, modifier = Modifier.fillMaxWidth()) { Text("Choose screenshots") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onFile, modifier = Modifier.weight(1f)) { Text("Files or a zip") }
                OutlinedButton(onClick = onFolder, modifier = Modifier.weight(1f)) { Text("A folder") }
            }
        }
        SourceCard(
            icon = Icons.Rounded.PhotoLibrary,
            shape = MaterialShapes.Cookie9Sided.toShape(),
            title = "Photos",
            body = "Where your photos were taken, day by day.",
            keeps = "Links a photo to each day as evidence",
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
            body = "GPX, OwnTracks, Dawarich, Google Timeline and other tracks.",
            keeps = "Backs up the days it covers",
            helpLabel = "Where's my Google Timeline?",
            help = listOf(
                "On your phone, open Settings › Location › Location services › Timeline.",
                "Tap “Export Timeline data” and save the file.",
                "Choose that file here. Older Google Takeout exports (Records.json or the whole zip) work as well, and so do TCX, FIT, KML and GeoJSON tracks.",
            ),
        ) {
            Button(onClick = onFile, modifier = Modifier.fillMaxWidth()) { Text("Choose files") }
        }
    }
}

@Composable
private fun SourceCard(
    icon: ImageVector,
    shape: Shape,
    title: String,
    body: String,
    keeps: String,
    helpLabel: String? = null,
    help: List<String> = emptyList(),
    content: @Composable ColumnScope.() -> Unit,
) {
    var open by rememberSaveable(title) { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ShapeIcon(icon, size = 48.dp, shape = shape)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Verified, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(keeps, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            content()
            if (helpLabel != null) {
                // One child of the card, so the folded steps leave no gap below the button.
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    TextButton(onClick = { open = !open }) { Text(if (open) "Hide" else helpLabel) }
                    AnimatedVisibility(open) {
                        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            help.forEachIndexed { index, step -> HelpStep(index + 1, step) }
                        }
                    }
                }
            }
        }
    }
}

/**
 * What an import did, in plain lines (added, kept as evidence, already there), and where to go
 * next: the Calendar at the first day it touched, or Evidence.
 */
@Composable
fun ImportDone(done: ImportPhase.Done, onMore: () -> Unit) {
    val actions = LocalAppActions.current
    fun trips(n: Int) = if (n == 1) "1 trip" else "$n trips"
    fun flights(n: Int) = if (n == 1) "1 flight" else "$n flights"
    val added = if (done.trips) trips(done.added) else Format.days(done.added)
    val lines = buildList {
        if (done.added > 0) add("${if (done.trips) "Trips" else "Days"} are in your Calendar, and every count and rule has been updated.")
        if (done.backedUp > 0) add("Backed up ${Format.days(done.backedUp)} you already had.")
        if (done.evidence > 0) {
            add(
                if (done.trips) "Kept ${done.evidence} ${if (done.evidence == 1) "document" else "documents"} as evidence, each with its flight."
                else "Linked ${done.evidence} ${if (done.evidence == 1) "photo" else "photos"} to their days as evidence.",
            )
        } else if (done.trips) {
            add("Their files were already kept as evidence, so nothing was copied twice.")
        }
        if (done.skipped > 0) add("${flights(done.skipped).replaceFirstChar(Char::titlecase)} ${if (done.skipped == 1) "was" else "were"} already saved and left as they were.")
        if (done.duplicatesRemoved > 0) add("Merged ${flights(done.duplicatesRemoved)} an earlier import had saved twice.")
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ShapeIcon(
            Icons.Rounded.Done,
            size = 88.dp,
            shape = MaterialShapes.SoftBurst.toShape(),
            container = MaterialTheme.colorScheme.primaryContainer,
            content = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        Text(
            when {
                done.added > 0 -> "Added $added"
                done.backedUp > 0 || done.evidence > 0 -> "Evidence kept"
                else -> "Nothing new"
            },
            style = MaterialTheme.typography.headlineSmallEmphasized,
            textAlign = TextAlign.Center,
        )
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                lines.ifEmpty { listOf("Everything found was already in Sojourn.") }.forEach { line ->
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(Icons.Rounded.Done, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(line, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
        Button(onClick = { actions.calendar(day = done.first) }, modifier = Modifier.fillMaxWidth()) { Text("Open Calendar") }
        if (done.evidence > 0 || done.backedUp > 0 || done.trips) {
            OutlinedButton(onClick = { actions.tab(AppTab.EVIDENCE) }, modifier = Modifier.fillMaxWidth()) { Text("Open Evidence") }
        }
        TextButton(onClick = onMore) { Text("Import something else") }
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
    val actions = LocalAppActions.current
    var open by rememberSaveable { mutableStateOf(events.size <= 3) }
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(start = 16.dp, top = 16.dp, end = 8.dp, bottom = 8.dp)) {
            Text(
                "${events.size} flight ${if (events.size == 1) "event" else "events"} couldn't be read: the airports weren't clear. Add ${if (events.size == 1) "it" else "each"} as a flight by hand.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(end = 8.dp),
            )
            AnimatedVisibility(open) {
                Column(Modifier.padding(top = 4.dp)) {
                    events.sortedBy { it.date }.forEach { event ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(event.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(Format.long(event.date), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = { actions.flight(date = event.date) }) { Text("Add flight") }
                        }
                    }
                }
            }
            if (events.size > 3) {
                TextButton(onClick = { open = !open }) { Text(if (open) "Hide them" else "Show them") }
            }
        }
    }
}

/** The trip review as it looks for [items], for screenshots. */
@Composable
fun TripsPreview(items: List<TripItem>, unread: List<UnreadEvent> = emptyList()) {
    TripsList(
        ImportPhase.Trips(items, "From 214 calendar events", items.indices.filter { items[it].selectedByDefault }.toSet(), namesUnknown = false, unread = unread),
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
                                    Text("For ${app.sojourn.ui.flights.passengerName(trip.passenger.orEmpty())}, not you")
                                }
                                trip.passenger != null -> Text("For ${app.sojourn.ui.flights.passengerName(trip.passenger)}")
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
