package app.sojourn.ui.calendar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.sojourn.data.Countries
import app.sojourn.domain.DayKind
import app.sojourn.ui.LocalAppActions
import app.sojourn.ui.SettingsSection
import app.sojourn.ui.components.CountryPickerSheet
import app.sojourn.ui.components.FlagBadge
import app.sojourn.ui.components.Format
import java.time.LocalDate

/**
 * A whole stay from the list: how many days it was and how its country counts them, with
 * changing its country or removing it in one go. Days one by one are the day sheet's job.
 */
@Composable
fun StaySheet(
    stay: StayItem,
    recents: List<String>,
    today: LocalDate,
    onDismiss: () -> Unit,
    onChange: (String) -> Unit,
    onRemove: () -> Unit,
) {
    val app = LocalAppActions.current
    val leg = stay.leg
    var picking by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FlagBadge(leg.country, size = 64.dp)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(Countries.name(leg.country), style = MaterialTheme.typography.headlineSmallEmphasized)
                    Text(Format.range(leg.start, leg.presentEnd, today), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(16.dp))
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Fact("Days there", Format.days(leg.present))
                    Fact("Days ${Countries.name(leg.country)} counts", Format.days(leg.counted))
                    if (leg.planned > 0) Fact("Of them planned", Format.days(leg.planned))
                    Fact("${stay.yearLabel} so far", Format.days(stay.yearTotal))
                    Text(
                        "${Countries.name(leg.country)} counts days this way: ${stay.counting.title.replaceFirstChar(Char::lowercaseChar)}. ${stay.counting.summary}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { app.settings(SettingsSection.COUNTING) }, modifier = Modifier.align(Alignment.End)) { Text("Counting settings") }
                }
            }
            if (leg.kind == DayKind.INFERRED) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Nothing was recorded on these days. They're assumed because you were here right before and after. You can turn this off in the counting settings.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                if (leg.kind != DayKind.INFERRED) {
                    OutlinedButton(onClick = onRemove) { Text("Remove") }
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = { picking = true }) { Text("Change country") }
            }
        }
    }
    if (picking) {
        CountryPickerSheet(
            title = if (leg.kind == DayKind.PLANNED) "Where to instead?" else "Where were you instead?",
            suggestions = recents,
            exclude = setOf(leg.country),
            onDismiss = { picking = false },
            onPick = {
                picking = false
                onChange(it)
            },
        )
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Row {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.titleSmall)
    }
}
