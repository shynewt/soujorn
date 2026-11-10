package app.sojourn.ui.evidence

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material.icons.rounded.ConfirmationNumber
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ElectricBolt
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.FamilyRestroom
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Hotel
import androidx.compose.material.icons.rounded.LocalHospital
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Receipt
import androidx.compose.material.icons.rounded.RequestQuote
import androidx.compose.material.icons.rounded.Train
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.Work
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import app.sojourn.container
import app.sojourn.data.Countries
import app.sojourn.data.EvidenceStore
import app.sojourn.data.db.Document
import app.sojourn.data.db.DocumentKind
import app.sojourn.data.db.Flight
import app.sojourn.data.db.Strength
import app.sojourn.data.flights.Airlines
import app.sojourn.ui.components.CountryPickerSheet
import app.sojourn.ui.components.DateRangeDialog
import app.sojourn.ui.components.FlagBadge
import app.sojourn.ui.components.Format
import app.sojourn.ui.components.ShapeIcon
import app.sojourn.ui.components.Tag
import app.sojourn.ui.components.segmentedColors
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The one icon each kind of evidence goes by, wherever it's listed. */
val DocumentKind.icon: ImageVector
    get() = when (this) {
        DocumentKind.PHOTO -> Icons.Rounded.PhotoCamera
        DocumentKind.BOARDING_PASS -> Icons.Rounded.ConfirmationNumber
        DocumentKind.CALENDAR_EVENT -> Icons.Rounded.Event
        DocumentKind.BOOKING -> Icons.Rounded.Email
        DocumentKind.TICKET -> Icons.Rounded.Train
        DocumentKind.STAY -> Icons.Rounded.Hotel
        DocumentKind.CARD_STATEMENT -> Icons.Rounded.CreditCard
        DocumentKind.MEDICAL -> Icons.Rounded.LocalHospital
        DocumentKind.APPOINTMENT -> Icons.Rounded.AccountBalance
        DocumentKind.RECEIPT -> Icons.Rounded.Receipt
        DocumentKind.LEASE -> Icons.Rounded.Home
        DocumentKind.UTILITY_BILL -> Icons.Rounded.ElectricBolt
        DocumentKind.RESIDENCE_CERTIFICATE -> Icons.Rounded.Verified
        DocumentKind.TAX_RETURN -> Icons.Rounded.RequestQuote
        DocumentKind.WORK -> Icons.Rounded.Work
        DocumentKind.REGISTRATION -> Icons.Rounded.Badge
        DocumentKind.FAMILY -> Icons.Rounded.FamilyRestroom
        DocumentKind.OTHER -> Icons.Rounded.Description
    }

/** What a kind of evidence shows, in a line, so choosing one says what it's worth. */
val DocumentKind.hint: String
    get() = when (this) {
        DocumentKind.PHOTO -> "Taken there that day; backs up other evidence."
        DocumentKind.BOARDING_PASS -> "You boarded that flight: both ends of the trip, that day."
        DocumentKind.CALENDAR_EVENT -> "A plan in your calendar; backs up other evidence."
        DocumentKind.BOOKING -> "A trip booked in your name."
        DocumentKind.TICKET -> "You travelled by train, bus or ferry that day."
        DocumentKind.STAY -> "Nights at a hotel or rental, with the dates on the invoice."
        DocumentKind.CARD_STATEMENT -> "Payments in person show where you were, day by day."
        DocumentKind.MEDICAL -> "A dated visit to a doctor, dentist or hospital."
        DocumentKind.APPOINTMENT -> "You were there in person to sign or show ID."
        DocumentKind.RECEIPT -> "A shop or restaurant receipt; backs up other evidence."
        DocumentKind.LEASE -> "A home there: shows ties rather than days."
        DocumentKind.UTILITY_BILL -> "Electricity, water or internet used at home over its period."
        DocumentKind.RESIDENCE_CERTIFICATE -> "The tax authority says where you're resident that year."
        DocumentKind.TAX_RETURN -> "Where you declared and paid tax."
        DocumentKind.WORK -> "Where you work and are paid."
        DocumentKind.REGISTRATION -> "You're registered as living there."
        DocumentKind.FAMILY -> "Your partner or children live or study there."
        DocumentKind.OTHER -> "Anything else worth keeping; say in the note what it shows."
    }

/** A kind's strength as a small label, set apart by wording as well as colour. */
@Composable
fun StrengthTag(strength: Strength, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val (container, content) = when (strength) {
        Strength.STRONG -> scheme.primaryContainer to scheme.onPrimaryContainer
        Strength.MEDIUM -> scheme.secondaryContainer to scheme.onSecondaryContainer
        Strength.WEAK -> scheme.surfaceContainerHighest to scheme.onSurfaceVariant
        Strength.TIES -> scheme.tertiaryContainer to scheme.onTertiaryContainer
    }
    Tag(strength.label, modifier, container = container, content = content)
}

/** "3 Mar", or "1 Jan – 31 Dec 2025" for a period. */
fun Document.dates(today: LocalDate = LocalDate.now()): String = Format.range(day, until ?: day, today)

/** "FR 8337 · BCN → BGY · ref ABC123" */
fun flightLine(flight: Flight): String = listOfNotNull(
    Airlines.format(flight.number).ifBlank { null },
    "${flight.origin} → ${flight.destination}",
    flight.reference.ifBlank { null }?.let { "ref $it" },
).joinToString(" · ")

/**
 * One piece of evidence, however many days it's kept on: a boarding pass is kept for its
 * flight's departure and arrival day, but it's one file, listed, edited and removed once.
 */
data class Kept(val documents: List<Document>) {
    val first: Document get() = documents.first()
    val kind: DocumentKind get() = first.kind
}

/** [documents] as pieces of evidence: the same file or text for the same flight or title goes together. */
fun bundle(documents: List<Document>): List<Kept> = documents
    .groupBy { doc ->
        if (doc.kind == DocumentKind.PHOTO) "photo:${doc.id}"
        else listOf(doc.kind, doc.file ?: doc.text.hashCode(), doc.flightId ?: doc.title, doc.until).joinToString("|")
    }
    .values.map { Kept(it.sortedBy(Document::day)) }

/** A calendar event's fields as lines to read, or a booking's text as it is. */
fun readable(doc: Document): String =
    if (doc.kind != DocumentKind.CALENDAR_EVENT) doc.text.trim()
    else doc.text.lines().filter { ':' in it && !it.startsWith("BEGIN") && !it.startsWith("END") && !it.startsWith("UID") }
        .joinToString("\n") { line ->
            val name = line.substringBefore(':').substringBefore(';')
            val value = line.substringAfter(':').replace("\\n", "\n").replace("\\,", ",").replace("\\;", ";")
            "${name.lowercase().replaceFirstChar(Char::uppercase)}: $value"
        }

/** Whether there's anything to open in another app: a gallery photo or a kept file. */
val Document.canOpen: Boolean get() = uri != null || file != null

/**
 * Opens a document in the app that reads it: a photo in the gallery, a kept file through the
 * evidence folder's FileProvider with a one-off read grant. False if nothing could open it.
 */
fun openDocument(context: Context, doc: Document): Boolean {
    val intent = when {
        doc.uri != null -> Intent(Intent.ACTION_VIEW, doc.uri.toUri())
        doc.file != null -> {
            val file = context.container.evidence.file(doc.file)
            if (!file.exists()) return false
            val uri = runCatching { FileProvider.getUriForFile(context, "${context.packageName}.files", file) }.getOrNull() ?: return false
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, EvidenceStore.mimeType(file.name))
        }
        else -> return false
    }
    return runCatching { context.startActivity(intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }.isSuccess
}

/**
 * A small picture of a document: the photo or kept picture itself, or its kind's icon for a
 * PDF, a text or a photo no longer in the gallery.
 */
@Composable
fun EvidenceThumb(doc: Document, size: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { size.roundToPx() }
    val image by produceState<ImageBitmap?>(null, doc.uri, doc.file, px) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                when {
                    doc.uri != null -> EvidenceStore.thumbnail(context, doc.uri, px)
                    doc.file != null -> EvidenceStore.thumbnailOf(context.container.evidence.file(doc.file), px)
                    else -> null
                }?.asImageBitmap()
            }.getOrNull()
        }
    }
    ThumbFrame(image, if (doc.kind == DocumentKind.PHOTO) Icons.Rounded.BrokenImage else doc.kind.icon, size, modifier)
}

/** A picture by its address: a day's other photos to choose from, a file just picked. */
@Composable
fun PhotoThumb(uri: String, size: Dp, modifier: Modifier = Modifier, fallback: ImageVector = Icons.Rounded.BrokenImage) {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { size.roundToPx() }
    val image by produceState<ImageBitmap?>(null, uri, px) {
        value = withContext(Dispatchers.IO) { EvidenceStore.thumbnail(context, uri, px)?.asImageBitmap() }
    }
    ThumbFrame(image, fallback, size, modifier)
}

@Composable
private fun ThumbFrame(image: ImageBitmap?, fallback: ImageVector, size: Dp, modifier: Modifier) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHighest).border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Image(image, contentDescription = "Photo", contentScale = ContentScale.Crop, modifier = Modifier.size(size))
        } else {
            Icon(fallback, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(size / 3))
        }
    }
}

/**
 * A piece of evidence as a list row: its kind's icon, title, date or period, country, the
 * flight it backs up, and how much it proves. The same row in the Evidence tab, a day and a
 * flight.
 */
@Composable
fun DocumentRow(
    kept: Kept,
    index: Int,
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    flight: Flight? = null,
    onSheet: Boolean = false,
    today: LocalDate = LocalDate.now(),
) {
    val doc = kept.first
    val days = kept.documents.map { it.day }.distinct()
    val countries = kept.documents.map { it.country }.distinct()
    SegmentedListItem(
        colors = segmentedColors(onSheet),
        verticalAlignment = Alignment.CenterVertically,
        onClick = onClick,
        shapes = ListItemDefaults.segmentedShapes(index, count),
        modifier = modifier,
        leadingContent = { ShapeIcon(doc.kind.icon) },
        supportingContent = {
            Column {
                val whenText = if (doc.until != null) doc.dates(today) else days.joinToString(", ") { Format.short(it, today) }
                Text("$whenText · ${countries.joinToString(" ") { Countries.flag(it) }} ${if (countries.size == 1) Countries.name(countries.single()) else ""}".trim(), maxLines = 1)
                flight?.let { Text(flightLine(it), maxLines = 1) }
                if (doc.note.isNotBlank()) Text(doc.note, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        trailingContent = { StrengthTag(doc.kind.strength) },
    ) { Text(if (flight != null) doc.kind.label else doc.title, maxLines = 2) }
}

/**
 * Where and when a piece of evidence is: its country, and its day, or its period for kinds
 * that cover one. Opens the country picker and the date pickers itself.
 */
@Composable
fun WhereAndWhen(
    country: String?,
    day: LocalDate,
    until: LocalDate?,
    period: Boolean,
    recents: List<String>,
    onCountry: (String) -> Unit,
    onDates: (LocalDate, LocalDate?) -> Unit,
) {
    var picking by rememberSaveable { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        SegmentedListItem(
            colors = segmentedColors(onSheet = true),
            verticalAlignment = Alignment.CenterVertically,
            onClick = { picking = "country" },
            shapes = ListItemDefaults.segmentedShapes(0, 2),
            leadingContent = { if (country == null) ShapeIcon(Icons.Rounded.Public) else FlagBadge(country, size = 40.dp) },
            overlineContent = { Text("Country") },
        ) { Text(country?.let(Countries::name) ?: "Choose a country", style = MaterialTheme.typography.titleMedium) }
        SegmentedListItem(
            colors = segmentedColors(onSheet = true),
            verticalAlignment = Alignment.CenterVertically,
            onClick = { picking = "dates" },
            shapes = ListItemDefaults.segmentedShapes(1, 2),
            leadingContent = { ShapeIcon(if (period) Icons.Rounded.DateRange else Icons.Rounded.Event) },
            overlineContent = { Text(if (period) "Period it covers" else "Day") },
            supportingContent = if (period) ({ Text(Format.days((until ?: day).toEpochDay().minus(day.toEpochDay()).toInt() + 1)) }) else null,
        ) { Text(if (period) Format.range(day, until ?: day) else Format.long(day), style = MaterialTheme.typography.titleMedium) }
    }
    when (picking) {
        "country" -> CountryPickerSheet(
            title = "Which country does it show?",
            suggestions = recents,
            onDismiss = { picking = null },
            onPick = {
                onCountry(it)
                picking = null
            },
        )
        "dates" -> if (period) {
            DateRangeDialog(
                onDismiss = { picking = null },
                onConfirm = { from, to ->
                    onDates(from, to)
                    picking = null
                },
                start = day,
                end = until ?: day,
                title = "Period it covers",
            )
        } else {
            DayDialog(day, onDismiss = { picking = null }) {
                onDates(it, null)
                picking = null
            }
        }
    }
}

/** The Material date picker in a dialog, for one day. */
@Composable
fun DayDialog(day: LocalDate, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    val state = rememberDatePickerState(initialSelectedDateMillis = Format.toPickerMillis(day))
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { state.selectedDateMillis?.let { onPick(Format.fromPickerMillis(it)) } }) { Text("Done") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) { DatePicker(state) }
}
