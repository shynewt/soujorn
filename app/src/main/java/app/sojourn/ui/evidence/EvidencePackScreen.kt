package app.sojourn.ui.evidence

import androidx.compose.runtime.Composable
import app.sojourn.ui.reports.EvidenceScreen

/** Exports [year]'s evidence pack, after checking what it's missing. */
@Composable
fun EvidencePackScreen(year: Int, back: () -> Unit) {
    // Built in the Evidence step of the redesign; the old pack screen stands in until then.
    EvidenceScreen(back = back)
}
