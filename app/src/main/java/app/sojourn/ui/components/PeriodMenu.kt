package app.sojourn.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * The period a view covers, chosen from a menu ("2026 ▾"). A menu scrolls, so ten years of use
 * fit as well as one; every yearly view in the app chooses its year this way.
 */
@Composable
fun <T> PeriodMenu(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, description: String, modifier: Modifier = Modifier) {
    val label = options.firstOrNull { it.first == selected }?.second.orEmpty()
    if (options.size <= 1) {
        Text(label, style = MaterialTheme.typography.titleMedium, modifier = modifier.padding(horizontal = 12.dp))
        return
    }
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        TextButton(onClick = { open = true }, modifier = Modifier.semantics { contentDescription = "$label. $description" }) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            Icon(Icons.Rounded.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (value, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = {
                        open = false
                        onSelect(value)
                    },
                    trailingIcon = if (value == selected) {
                        { Icon(Icons.Rounded.Check, contentDescription = "Selected") }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

/** A year from [years], newest first, with "All time" too when [allTime] is given. */
@Composable
fun YearMenu(year: Int?, years: List<Int>, onYear: (Int?) -> Unit, allTime: Boolean = false, modifier: Modifier = Modifier) {
    val options: List<Pair<Int?, String>> = years.sortedDescending().map { it to "$it" } + if (allTime) listOf(null to "All time") else emptyList()
    PeriodMenu(options, year, onYear, description = "Choose the year", modifier = modifier)
}
