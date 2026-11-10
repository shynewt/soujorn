package app.sojourn.ui.evidence

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Flight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.sojourn.AppContainer
import app.sojourn.data.db.Document
import app.sojourn.data.db.DocumentKind
import app.sojourn.data.db.Flight
import app.sojourn.ui.LocalAppActions
import app.sojourn.ui.appViewModel
import app.sojourn.ui.components.ShapeIcon
import app.sojourn.ui.components.segmentedColors
import java.time.LocalDate
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Changes to kept evidence, made where the app outlives the sheet that asked, so closing the
 * sheet straight after saving doesn't cancel the save.
 */
class DocumentEdits(container: AppContainer) : ViewModel() {
    private val repository = container.repository

    /** Countries most recently visited, offered first when changing a document's country. */
    val recents = repository.book.map { it.recentCountries() }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Saves [kept]'s new title and note on every copy, and its dates and country when it's on one day. */
    fun update(kept: Kept, title: String, note: String, day: LocalDate, until: LocalDate?, country: String) {
        viewModelScope.launch {
            // A file kept for two days (a pass) changes on both, even when opened from one day.
            val all = kept.documents.flatMap { repository.copiesOf(it) }.distinctBy { it.id }
            all.forEach { doc ->
                val moved = if (all.size == 1) doc.copy(day = day, until = until?.takeIf { it != day }, country = country) else doc
                repository.updateDocument(moved.copy(title = title, note = note))
            }
        }
    }

    fun remove(kept: Kept) {
        viewModelScope.launch { kept.documents.flatMap { repository.copiesOf(it) }.distinctBy { it.id }.forEach { repository.deleteDocument(it) } }
    }

    fun choosePhoto(document: Document, uri: String) {
        viewModelScope.launch { repository.choosePhoto(document, uri) }
    }
}

/**
 * A piece of evidence opened: a look at it, the app that reads it, the flight it backs up,
 * its title, note, dates and country to correct, and a way to remove it.
 */
@Composable
fun DocumentSheet(kept: Kept, flight: Flight?, onDismiss: () -> Unit) {
    val edits = appViewModel { DocumentEdits(it) }
    val recents by edits.recents.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val actions = LocalAppActions.current
    val doc = kept.first
    val single = kept.documents.size == 1
    var title by rememberSaveable(doc.id) { mutableStateOf(doc.title) }
    var note by rememberSaveable(doc.id) { mutableStateOf(doc.note) }
    var day by rememberSaveable(doc.id) { mutableStateOf(doc.day) }
    var until by rememberSaveable(doc.id) { mutableStateOf(doc.until) }
    var country by rememberSaveable(doc.id) { mutableStateOf(doc.country) }
    var confirmRemove by remember { mutableStateOf(false) }
    var openFailed by remember { mutableStateOf(false) }
    var showText by remember { mutableStateOf(false) }
    val changed = title.trim() != doc.title || note.trim() != doc.note || day != doc.day || until != doc.until || country != doc.country

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberBottomSheetState(SheetValue.Hidden, setOf(SheetValue.Hidden, SheetValue.Expanded))) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 20.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                EvidenceThumb(doc, 96.dp)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(doc.kind.label, style = MaterialTheme.typography.titleLargeEmphasized)
                    StrengthTag(doc.kind.strength)
                    Text(doc.kind.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (doc.canOpen) {
                FilledTonalButton(onClick = { openFailed = !openDocument(context, doc) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (doc.kind == DocumentKind.PHOTO) "Open in the gallery" else "Open")
                }
                if (openFailed) {
                    Text(
                        if (doc.uri != null) "This photo is no longer in your gallery." else "Nothing on this phone opens this kind of file.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (doc.text.isNotBlank() && doc.kind != DocumentKind.OTHER) {
                TextButton(onClick = { showText = !showText }) { Text(if (showText) "Hide what it says" else "Show what it says") }
                AnimatedVisibility(showText) {
                    Text(readable(doc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 30)
                }
            }
            flight?.let {
                SegmentedListItem(
                    colors = segmentedColors(onSheet = true),
                    verticalAlignment = Alignment.CenterVertically,
                    onClick = {
                        onDismiss()
                        actions.flight(it)
                    },
                    shapes = ListItemDefaults.segmentedShapes(0, 1),
                    leadingContent = { ShapeIcon(Icons.Rounded.Flight) },
                    overlineContent = { Text("Backs up the flight") },
                    supportingContent = it.passenger.ifBlank { null }?.let { name -> { Text(name) } },
                ) { Text(flightLine(it), style = MaterialTheme.typography.titleMedium) }
            }

            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Title") },
                singleLine = true,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("Note") },
                placeholder = { Text("What it shows, who issued it…") },
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            if (single) {
                WhereAndWhen(
                    country = country,
                    day = day,
                    until = until,
                    period = doc.kind.period,
                    recents = recents,
                    onCountry = { country = it },
                    onDates = { from, to ->
                        day = from
                        until = to
                    },
                )
            } else {
                Text(
                    "Kept for each day of its flight, so its days and countries follow the flight.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { confirmRemove = true }) {
                    Icon(Icons.Rounded.DeleteOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.weight(1f))
                Button(
                    enabled = changed && title.isNotBlank(),
                    onClick = {
                        edits.update(kept, title.trim(), note.trim(), day, until, country)
                        onDismiss()
                    },
                ) { Text("Save") }
            }
        }
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove from the evidence?") },
            text = {
                Text(
                    if (doc.kind == DocumentKind.PHOTO) "The photo stays in your gallery; it just won't back up this day or go into the pack."
                    else "The copy Sojourn kept is deleted and won't go into the pack. Days already recorded stay as they are.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    edits.remove(kept)
                    confirmRemove = false
                    onDismiss()
                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
    }
}
