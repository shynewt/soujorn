package app.sojourn.ui.calendar

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.sojourn.container
import app.sojourn.data.Countries
import app.sojourn.data.db.Document
import app.sojourn.data.db.DocumentKind
import app.sojourn.data.db.Source
import app.sojourn.data.db.alternatives
import app.sojourn.ui.EvidenceDraft
import app.sojourn.ui.LocalAppActions
import app.sojourn.ui.evidence.DocumentRow
import app.sojourn.ui.evidence.DocumentSheet
import app.sojourn.ui.evidence.EvidenceThumb
import app.sojourn.ui.evidence.Kept
import app.sojourn.ui.evidence.PhotoThumb
import app.sojourn.ui.evidence.bundle
import java.time.LocalDate

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
 * instead), and every other document as the same row the Evidence tab shows, opening the same
 * sheet to view, correct or remove it. Everything here goes into the year's evidence pack.
 * Given [date], it also offers to add evidence for that day, in [country] if known.
 */
@Composable
fun DayEvidence(
    documents: List<Document>,
    onChoosePhoto: (Document, String) -> Unit,
    onDelete: (Document) -> Unit,
    date: LocalDate? = null,
    country: String? = null,
) {
    if (documents.isEmpty() && date == null) return
    val container = LocalContext.current.container
    val flights by container.repository.flights.collectAsState(initial = emptyList())
    val actions = LocalAppActions.current
    var open by remember { mutableStateOf<Kept?>(null) }
    Spacer(Modifier.height(16.dp))
    Text("Evidence", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(6.dp))
    val (photos, others) = documents.partition { it.kind == DocumentKind.PHOTO }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        photos.forEach { PhotoEvidence(it, onChoosePhoto, onDelete) { open = Kept(listOf(it)) } }
        val kept = bundle(others).sortedBy { it.kind }
        Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
            kept.forEachIndexed { index, item ->
                DocumentRow(item, index, kept.size, onClick = { open = item }, flight = flights.firstOrNull { it.id == item.first.flightId }, onSheet = true)
            }
        }
    }
    if (date != null) {
        TextButton(onClick = { actions.addEvidence(EvidenceDraft(day = date, country = country)) }) {
            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Add evidence for this day")
        }
    }
    open?.let { item -> DocumentSheet(item, flights.firstOrNull { it.id == item.first.flightId }, onDismiss = { open = null }) }
}

@Composable
private fun PhotoEvidence(doc: Document, onChoose: (Document, String) -> Unit, onDelete: (Document) -> Unit, onOpen: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                EvidenceThumb(doc, 96.dp, Modifier.clickable(onClickLabel = "Open the photo", onClick = onOpen))
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
                    others.forEach { other -> PhotoThumb(other, 52.dp, Modifier.clickable { onChoose(doc, other) }) }
                }
            }
        }
    }
}
