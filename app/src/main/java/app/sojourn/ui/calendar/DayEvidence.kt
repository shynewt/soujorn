package app.sojourn.ui.calendar

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material.icons.rounded.ConfirmationNumber
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.sojourn.data.Countries
import app.sojourn.data.EvidenceStore
import app.sojourn.data.db.Document
import app.sojourn.data.db.DocumentKind
import app.sojourn.data.db.Source
import app.sojourn.data.db.alternatives
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What a source is called on a day: "location", "photos"… */
fun Source.describe(): String = when (this) {
    Source.LOCATION -> "location"
    Source.NETWORK -> "mobile network"
    Source.PHOTO -> "photos"
    Source.MANUAL -> "entered by hand"
    Source.IMPORT -> "imported"
    Source.FLIGHT -> "a flight"
}

/**
 * The evidence kept for a day: one photo per country (tap another below it to show that one
 * instead), calendar events, bookings and boarding passes. Everything here goes into the
 * year's evidence pack.
 */
@Composable
fun DayEvidence(documents: List<Document>, onChoosePhoto: (Document, String) -> Unit, onDelete: (Document) -> Unit) {
    if (documents.isEmpty()) return
    Spacer(Modifier.height(16.dp))
    Text("Evidence", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(6.dp))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        documents.sortedBy { it.kind }.forEach { doc ->
            if (doc.kind == DocumentKind.PHOTO) PhotoEvidence(doc, onChoosePhoto, onDelete) else OtherEvidence(doc, onDelete)
        }
    }
}

@Composable
private fun PhotoEvidence(doc: Document, onChoose: (Document, String) -> Unit, onDelete: (Document) -> Unit) {
    val context = LocalContext.current
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                val uri = doc.uri
                Thumb(uri, 96.dp, Modifier.clickable(enabled = uri != null) {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                })
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text("Photo in ${Countries.flag(doc.country)} ${Countries.name(doc.country)}", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Linked from your gallery, not copied. The evidence pack includes it with its date and place.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { onDelete(doc) }) { Icon(Icons.Rounded.DeleteOutline, contentDescription = "Remove this photo from the evidence") }
            }
            val others = doc.alternatives
            if (others.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("Other photos that day: tap one to use it instead", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    others.forEach { other -> Thumb(other, 52.dp, Modifier.clickable { onChoose(doc, other) }) }
                }
            }
        }
    }
}

@Composable
private fun OtherEvidence(doc: Document, onDelete: (Document) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.clickable(enabled = doc.text.isNotBlank()) { open = !open }.padding(start = 12.dp, top = 4.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    when (doc.kind) {
                        DocumentKind.CALENDAR_EVENT -> Icons.Rounded.Event
                        DocumentKind.BOOKING -> Icons.Rounded.Email
                        else -> Icons.Rounded.ConfirmationNumber
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(doc.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                    Text(
                        "${Countries.flag(doc.country)} ${doc.kind.label}" + if (doc.kind == DocumentKind.BOARDING_PASS) " · file kept in Sojourn" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { onDelete(doc) }) { Icon(Icons.Rounded.DeleteOutline, contentDescription = "Remove from the evidence") }
            }
            AnimatedVisibility(open) {
                Text(
                    readable(doc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 20,
                    modifier = Modifier.padding(end = 12.dp, bottom = 8.dp),
                )
            }
        }
    }
}

/** A calendar event's fields as lines to read, or a booking's text as it is. */
private fun readable(doc: Document): String =
    if (doc.kind != DocumentKind.CALENDAR_EVENT) doc.text.trim()
    else doc.text.lines().filter { ':' in it && !it.startsWith("BEGIN") && !it.startsWith("END") && !it.startsWith("UID") }
        .joinToString("\n") { line ->
            val name = line.substringBefore(':').substringBefore(';')
            val value = line.substringAfter(':').replace("\\n", "\n").replace("\\,", ",").replace("\\;", ";")
            "${name.lowercase().replaceFirstChar(Char::uppercase)}: $value"
        }

@Composable
private fun Thumb(uri: String?, size: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { size.roundToPx() }
    val image by produceState<ImageBitmap?>(null, uri, px) {
        value = uri?.let { withContext(Dispatchers.IO) { EvidenceStore.thumbnail(context, it, px)?.asImageBitmap() } }
    }
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHighest).border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = image
        if (bitmap != null) {
            Image(bitmap, contentDescription = "Photo", contentScale = ContentScale.Crop, modifier = Modifier.size(size))
        } else {
            Icon(Icons.Rounded.BrokenImage, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(size / 3))
        }
    }
}
