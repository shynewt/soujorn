package app.sojourn.ui.flights

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.NoteAdd
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.sojourn.container
import app.sojourn.data.EvidenceKeeper
import app.sojourn.data.db.Flight
import app.sojourn.ui.evidence.DocumentRow
import app.sojourn.ui.evidence.DocumentSheet
import app.sojourn.ui.evidence.Kept
import app.sojourn.ui.evidence.bundle
import kotlinx.coroutines.launch

/**
 * What backs up a saved flight in the evidence pack, and a way to add its boarding pass
 * afterwards: a PDF, a screenshot or a Wallet pass. The same file isn't kept twice, and a pass
 * for another flight is turned away. Each document opens the same sheet as in the Evidence tab.
 */
@Composable
fun FlightEvidence(flight: Flight) {
    val container = LocalContext.current.container
    val documents by container.repository.documents.collectAsState(initial = emptyList())
    // A file backs both of the flight's days; it's listed once.
    val kept = remember(documents, flight) { bundle(container.keeper.forFlight(flight, documents)) }
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf<Kept?>(null) }
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
        if (kept.isEmpty()) {
            Text(
                "Nothing kept for this flight yet. Its boarding pass makes its days documented in the evidence pack.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
            kept.forEachIndexed { index, item -> DocumentRow(item, index, kept.size, onClick = { open = item }, onSheet = true) }
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
    open?.let { DocumentSheet(it, flight = null, onDismiss = { open = null }) }
}
