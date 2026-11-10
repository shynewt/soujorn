package app.sojourn.ui.flights

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ConfirmationNumber
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.NoteAdd
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.sojourn.container
import app.sojourn.data.EvidenceKeeper
import app.sojourn.data.db.DocumentKind
import app.sojourn.data.db.Flight
import kotlinx.coroutines.launch

/**
 * What backs up a saved flight in the evidence pack, and a way to add its boarding pass
 * afterwards: a PDF, a screenshot or a Wallet pass. The same file isn't kept twice, and a pass
 * for another flight is turned away.
 */
@Composable
fun FlightEvidence(flight: Flight) {
    val container = LocalContext.current.container
    val documents by container.repository.documents.collectAsState(initial = emptyList())
    val kept = remember(documents, flight) { container.keeper.forFlight(flight, documents) }
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        busy = true
        message = null
        scope.launch {
            message = when (val result = runCatching { container.keeper.attach(flight, uri) }.getOrNull()) {
                is EvidenceKeeper.Attached.Kept -> "Kept for this flight: it's in its days' evidence and the evidence pack."
                EvidenceKeeper.Attached.AlreadyKept -> "That file is already kept for this flight."
                is EvidenceKeeper.Attached.OtherFlight -> "That's the pass for ${result.found}, not this flight, so it wasn't added."
                null -> "Couldn't read that file."
            }
            busy = false
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Evidence", style = MaterialTheme.typography.titleMedium)
        // A file backs both of the flight's days; it's listed once.
        val shown = kept.distinctBy { it.file ?: (it.kind to it.text) }
        if (shown.isEmpty()) {
            Text(
                "Nothing kept for this flight yet. Its boarding pass makes its days documented in the evidence pack.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        shown.forEach { doc ->
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
                Spacer(Modifier.width(12.dp))
                Text(doc.title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 2)
                IconButton(onClick = {
                    scope.launch { kept.filter { it.file == doc.file && it.kind == doc.kind && it.text == doc.text }.forEach { container.repository.deleteDocument(it) } }
                }) { Icon(Icons.Rounded.DeleteOutline, contentDescription = "Remove from the evidence") }
            }
        }
        OutlinedButton(
            onClick = { pick.launch(arrayOf("application/pdf", "image/*", "application/vnd.apple.pkpass", "application/octet-stream")) },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Rounded.NoteAdd, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (busy) "Reading…" else "Add boarding pass")
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp)) }
    }
}
