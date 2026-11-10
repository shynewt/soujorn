package app.sojourn.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.sojourn.data.Change
import app.sojourn.data.ChangeLog
import app.sojourn.ui.components.Format
import app.sojourn.ui.components.segmentedColors

/**
 * The last changes to days, plans and flights, newest first. Any one can be reverted, which
 * puts back what it removed and takes away what it added, leaving later changes alone.
 */
@Composable
fun RecentChangesSheet(changes: List<Change>, onDismiss: () -> Unit, onRevert: (Change) -> Unit) {
    var confirming by remember { mutableStateOf<Change?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Text("Recent changes", style = MaterialTheme.typography.titleLargeEmphasized)
            Text(
                "Your last ${ChangeLog.LIMIT} changes to days, plans and flights. Older ones are let go. Tracking's own checks aren't listed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
            if (changes.isEmpty()) {
                Text("Nothing changed yet.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 32.dp))
            }
        }
        LazyColumn(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
        ) {
            itemsIndexed(changes, key = { _, change -> change.id }) { index, change ->
                SegmentedListItem(
                    colors = segmentedColors(onSheet = true),
                    shapes = ListItemDefaults.segmentedShapes(index, changes.size),
                    supportingContent = { Text("${Format.ago(change.at)} · ${change.summary}") },
                    trailingContent = {
                        if (change.reverted) {
                            Text("Reverted", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.outline)
                        } else {
                            TextButton(onClick = { confirming = change }) { Text("Revert") }
                        }
                    },
                ) { Text(change.label) }
            }
        }
    }
    confirming?.let { change ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text("Revert this change?") },
            text = {
                Text(
                    "“${change.label}” (${change.summary}): what it removed comes back and what it added goes. " +
                        "Later changes stay, but days they also changed go back to how this one found them. Reverting is listed too, so it can be reverted in turn.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onRevert(change)
                    confirming = null
                }) { Text("Revert") }
            },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text("Cancel") } },
        )
    }
}
