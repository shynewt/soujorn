package app.sojourn.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.sojourn.data.Countries
import java.text.Normalizer
import java.time.LocalDate

/**
 * A searchable list of every country, with recent ones on top for one-tap picks. Matching
 * ignores accents and also accepts the two-letter code, so "cote" finds Côte d'Ivoire.
 */
@Composable
fun CountryPickerSheet(
    title: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
    suggestions: List<String> = emptyList(),
    exclude: Set<String> = emptySet(),
    /** Limits the choice to these countries, e.g. those present on the days being changed. */
    only: List<String>? = null,
) {
    val state = rememberBottomSheetState(SheetValue.Hidden, setOf(SheetValue.Hidden, SheetValue.Expanded))
    var query by rememberSaveable { mutableStateOf("") }
    val all = remember { Countries.sortedByName() }
    val filtered = remember(query) {
        val needle = fold(query.trim())
        all.filter { it !in exclude && (only == null || it in only) && (needle.isEmpty() || fold(Countries.name(it)).contains(needle) || it.equals(needle, ignoreCase = true)) }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        Column(Modifier.fillMaxHeight(0.92f).imePadding()) {
            Text(title, style = MaterialTheme.typography.titleLargeEmphasized, modifier = Modifier.padding(horizontal = 24.dp))
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                placeholder = { Text("Search countries") },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            val quick = suggestions.filter { it !in exclude }
            if (query.isBlank() && quick.isNotEmpty() && only == null) {
                FlowRow(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    quick.forEach { code ->
                        SuggestionChip(onClick = { onPick(code) }, label = { Text("${Countries.flag(code)}  ${Countries.name(code)}") })
                    }
                }
            }
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                items(filtered, key = { it }) { code ->
                    ListItem(
                        onClick = { onPick(code) },
                        leadingContent = { Text(Countries.flag(code), style = MaterialTheme.typography.headlineSmall) },
                        trailingContent = { Text(code, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline) },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        modifier = Modifier.padding(horizontal = 8.dp),
                    ) { Text(Countries.name(code), fontWeight = FontWeight.Medium) }
                }
            }
        }
    }
}

private fun fold(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase()

/** The Material date range picker in a dialog, speaking [LocalDate]. */
@Composable
fun DateRangeDialog(
    onDismiss: () -> Unit,
    onConfirm: (LocalDate, LocalDate) -> Unit,
    start: LocalDate? = null,
    end: LocalDate? = null,
    title: String = "Select dates",
) {
    val state = rememberDateRangePickerState(
        initialSelectedStartDateMillis = start?.let(Format::toPickerMillis),
        initialSelectedEndDateMillis = end?.let(Format::toPickerMillis),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = state.selectedStartDateMillis != null,
                onClick = {
                    val from = Format.fromPickerMillis(state.selectedStartDateMillis ?: return@TextButton)
                    val to = state.selectedEndDateMillis?.let(Format::fromPickerMillis) ?: from
                    onConfirm(from, to)
                },
            ) { Text("Done") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        DateRangePicker(
            state = state,
            title = { Text(title, modifier = Modifier.padding(start = 24.dp, top = 16.dp)) },
            showModeToggle = true,
            modifier = Modifier.weight(1f),
        )
    }
}
