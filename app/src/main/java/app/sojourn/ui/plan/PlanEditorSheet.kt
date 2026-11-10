package app.sojourn.ui.plan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material3.Button
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.sojourn.data.Countries
import app.sojourn.data.db.Plan
import app.sojourn.domain.RuleStatus
import app.sojourn.ui.components.segmentedColors
import app.sojourn.ui.components.CountryPickerSheet
import app.sojourn.ui.components.DateRangeDialog
import app.sojourn.ui.components.FlagBadge
import app.sojourn.ui.components.Format
import app.sojourn.ui.components.ShapeIcon
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.delay

/** Creates or edits a planned stay, and shows what it does to every rule before saving. */
@Composable
fun PlanEditorSheet(
    initial: Plan,
    recents: List<String>,
    preview: suspend (Plan) -> List<RuleStatus>,
    onDismiss: () -> Unit,
    onSave: (Plan) -> Unit,
    onDelete: (Plan) -> Unit,
) {
    val isNew = initial.id == 0L
    var country by remember { mutableStateOf(initial.country) }
    var start by remember { mutableStateOf(initial.start.takeIf { it != LocalDate.MIN }) }
    var end by remember { mutableStateOf(initial.end.takeIf { it != LocalDate.MIN }) }
    var note by remember { mutableStateOf(initial.note) }
    var pickingCountry by remember { mutableStateOf(false) }
    var pickingDates by remember { mutableStateOf(false) }
    var impact by remember { mutableStateOf<List<RuleStatus>>(emptyList()) }

    val draft = if (country.isNotEmpty() && start != null && end != null) initial.copy(country = country, start = start!!, end = end!!, note = note.trim()) else null
    LaunchedEffect(draft?.country, draft?.start, draft?.end) {
        impact = if (draft == null) emptyList() else {
            delay(150)
            preview(draft).filter { it.forecast != null }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberBottomSheetState(SheetValue.Hidden, setOf(SheetValue.Hidden, SheetValue.Expanded))) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
        ) {
            Text(if (isNew) "Plan a stay" else "Edit stay", style = MaterialTheme.typography.headlineSmallEmphasized)
            Spacer(Modifier.height(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                SegmentedListItem(
                    colors = segmentedColors(onSheet = true),
                    verticalAlignment = Alignment.CenterVertically,
                    onClick = { pickingCountry = true },
                    shapes = ListItemDefaults.segmentedShapes(0, 2),
                    leadingContent = {
                        if (country.isEmpty()) ShapeIcon(Icons.Rounded.Public) else FlagBadge(country, size = 40.dp)
                    },
                    overlineContent = { Text("Country") },
                ) { Text(if (country.isEmpty()) "Choose a country" else Countries.name(country), style = MaterialTheme.typography.titleMedium) }
                SegmentedListItem(
                    colors = segmentedColors(onSheet = true),
                    verticalAlignment = Alignment.CenterVertically,
                    onClick = { pickingDates = true },
                    shapes = ListItemDefaults.segmentedShapes(1, 2),
                    leadingContent = { ShapeIcon(Icons.Rounded.DateRange) },
                    overlineContent = { Text("Dates") },
                    supportingContent = {
                        if (start != null && end != null) Text(Format.days(ChronoUnit.DAYS.between(start, end).toInt() + 1))
                    },
                ) {
                    Text(
                        if (start == null || end == null) "Choose dates" else Format.range(start!!, end!!),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("Note (optional)") },
                placeholder = { Text("Flat in Lisbon, conference…") },
                singleLine = true,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            RuleImpact("With this stay", impact, Modifier.padding(top = 12.dp))

            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!isNew) {
                    OutlinedButton(onClick = { onDelete(initial) }) { Text("Delete") }
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = { draft?.let(onSave) }, enabled = draft != null) { Text(if (isNew) "Add to plan" else "Save") }
            }
        }
    }

    if (pickingCountry) {
        CountryPickerSheet(
            title = "Where to?",
            suggestions = recents,
            onDismiss = { pickingCountry = false },
            onPick = {
                country = it
                pickingCountry = false
            },
        )
    }
    if (pickingDates) {
        DateRangeDialog(
            start = start,
            end = end,
            title = "When?",
            onDismiss = { pickingDates = false },
            onConfirm = { from, to ->
                start = from
                end = to
                pickingDates = false
            },
        )
    }
}
