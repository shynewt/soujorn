package app.sojourn.ui.evidence

import androidx.compose.runtime.Composable
import app.sojourn.ui.EvidenceDraft

/** Adds a piece of evidence: its kind, file, country, day or period, title and note. */
@Composable
fun AddEvidenceSheet(draft: EvidenceDraft, onDismiss: () -> Unit) {
    // Built in the Evidence step of the redesign.
    onDismiss()
}
