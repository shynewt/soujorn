package app.sojourn.ui.evidence

import androidx.compose.runtime.Composable
import app.sojourn.ui.reports.EvidenceScreen

/** The Evidence tab: the year's vault, its coverage, checklist, documents and exports. */
@Composable
fun EvidenceTab() {
    // Built in the Evidence step of the redesign; the pack screen stands in until then.
    EvidenceScreen(back = {})
}
