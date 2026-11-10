package app.sojourn.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
import app.sojourn.data.Countries
import app.sojourn.data.Settings
import app.sojourn.domain.CountryRules
import app.sojourn.domain.DayCounting
import app.sojourn.ui.components.CountryPickerSheet
import app.sojourn.ui.components.FlagBadge
import app.sojourn.ui.components.Tag
import app.sojourn.ui.components.segmentedColors
import app.sojourn.ui.rules.CountingDialog

/**
 * How each country counts a travel day, for the countries you've been to and any you set
 * yourself. Sojourn's built-in rules are the default; a choice of yours wins over them.
 */
@Composable
fun CountingSheet(settings: Settings, visited: List<String>, onDismiss: () -> Unit, onSet: (String, DayCounting?) -> Unit) {
    var editing by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    val policies = settings.policies
    val countries = (settings.counting.keys + visited).distinct().sortedBy { Countries.name(it) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxHeight(0.92f)) {
            Text("Day counting by country", style = MaterialTheme.typography.titleLargeEmphasized, modifier = Modifier.padding(horizontal = 24.dp))
            Text(
                "Countries disagree about travel days. Rules for a country use its counting unless the rule says otherwise.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                itemsIndexed(countries, key = { _, c -> c }) { index, country ->
                    val custom = policies.isCustom(country)
                    val known = CountryRules[country]
                    SegmentedListItem(
                        colors = segmentedColors(onSheet = true),
                        onClick = { editing = country },
                        shapes = ListItemDefaults.segmentedShapes(index, countries.size),
                        leadingContent = { FlagBadge(country, size = 40.dp) },
                        supportingContent = {
                            Text(
                                when {
                                    custom -> "Your choice"
                                    known == null -> "Sojourn has no rules for ${Countries.name(country)} yet; using your default"
                                    known.countingStated -> "${Countries.name(country)}'s rule"
                                    else -> "The law doesn't say; the usual practice"
                                },
                            )
                        },
                        trailingContent = { if (custom) Tag("Custom") },
                    ) { Text("${Countries.name(country)}: ${policies.of(country).title.replaceFirstChar(Char::lowercaseChar)}") }
                }
                item {
                    TextButton(onClick = { adding = true }, modifier = Modifier.padding(vertical = 8.dp)) {
                        Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Another country")
                    }
                }
            }
        }
    }
    editing?.let { country ->
        val own = CountryRules[country]?.counting ?: policies.fallback
        CountingDialog(
            title = Countries.name(country),
            own = own,
            ownLabel = if (CountryRules[country] != null) "Sojourn's rule" else "Your default",
            selected = settings.counting[country],
            onDismiss = { editing = null },
            onPick = {
                onSet(country, it)
                editing = null
            },
        )
    }
    if (adding) {
        CountryPickerSheet(
            title = "Which country?",
            exclude = countries.toSet(),
            onDismiss = { adding = false },
            onPick = {
                adding = false
                editing = it
            },
        )
    }
}

/**
 * The names the user travels under. Bookings found in calendars or emails for someone else
 * (a flight bought for grandparents) are left out by comparing passenger names with these.
 */
@Composable
fun NamesDialog(names: Set<String>, onDismiss: () -> Unit, onSave: (Set<String>) -> Unit) {
    var list by remember { mutableStateOf(names.toList()) }
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Your name on tickets") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Add your name as airlines write it, e.g. \"Jane Doe\". Middle names and order don't matter. Kept on this phone only.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    list.forEach { name ->
                        InputChip(
                            selected = false,
                            onClick = { list = list - name },
                            label = { Text(name) },
                            trailingIcon = { Icon(Icons.Rounded.Close, contentDescription = "Remove $name", modifier = Modifier.size(16.dp)) },
                        )
                    }
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Name") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    trailingIcon = {
                        TextButton(enabled = text.isNotBlank(), onClick = {
                            list = (list + text.trim()).distinct()
                            text = ""
                        }) { Text("Add") }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(0.dp))
            }
        },
        confirmButton = { TextButton(onClick = { onSave((list + listOfNotNull(text.trim().ifBlank { null })).toSet()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
