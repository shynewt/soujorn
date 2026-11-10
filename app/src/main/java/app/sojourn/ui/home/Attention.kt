package app.sojourn.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.sojourn.data.Countries
import app.sojourn.data.db.DocumentKind
import app.sojourn.data.db.Plan
import app.sojourn.ui.EvidenceDraft
import app.sojourn.ui.LocalAppActions
import app.sojourn.ui.SettingsSection
import app.sojourn.ui.components.FlagBadge
import app.sojourn.ui.components.Format
import app.sojourn.ui.components.ShapeIcon
import app.sojourn.ui.components.levelColors
import app.sojourn.ui.components.levelIcon
import app.sojourn.ui.components.levelLabel
import app.sojourn.ui.components.segmentedColors
import java.time.LocalDate

/**
 * The things that need you, as one group of rows. Each row fixes its item in one tap; past
 * plans are answered in place, the rest can be dismissed.
 */
@Composable
internal fun AttentionList(
    items: List<Attention>,
    state: HomeState,
    onConfirmPlan: (Plan) -> Unit,
    onDeletePlan: (Plan) -> Unit,
    onDismiss: (Attention) -> Unit,
) {
    val actions = LocalAppActions.current
    Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        items.forEachIndexed { index, item ->
            val shapes = ListItemDefaults.segmentedShapes(index, items.size)
            val dismiss: @Composable () -> Unit = {
                IconButton(onClick = { onDismiss(item) }) { Icon(Icons.Rounded.Close, contentDescription = "Dismiss") }
            }
            when (item) {
                is Attention.RuleNear -> {
                    val status = item.status
                    val colors = levelColors(status.level)
                    SegmentedListItem(
                        colors = segmentedColors(),
                        verticalAlignment = Alignment.CenterVertically,
                        onClick = { actions.rules(status.rule.id) },
                        shapes = shapes,
                        leadingContent = { ShapeIcon(levelIcon(status.level), container = colors.container, content = colors.accent) },
                        overlineContent = { Text("${status.rule.name} · ${levelLabel(status.level).lowercase()}") },
                        supportingContent = { Text(status.note ?: status.detail, maxLines = 2) },
                        trailingContent = dismiss,
                    ) { Text(status.headline) }
                }
                is Attention.PastPlan -> {
                    val plan = item.plan
                    SegmentedListItem(
                        colors = segmentedColors(),
                        verticalAlignment = Alignment.CenterVertically,
                        shapes = shapes,
                        leadingContent = { FlagBadge(plan.country, size = 40.dp) },
                        overlineContent = { Text("Planned · ${Format.range(plan.start, minOf(plan.end, state.today.minusDays(1)), state.today)}") },
                        supportingContent = {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                // "No" deletes the plan; the app-wide snackbar offers Undo.
                                TextButton(onClick = { onConfirmPlan(plan) }) { Text("Yes, I was") }
                                TextButton(onClick = { onDeletePlan(plan) }) { Text("No") }
                            }
                        },
                    ) { Text("Were you in ${Countries.name(plan.country)}?") }
                }
                is Attention.Untracked -> SegmentedListItem(
                    colors = segmentedColors(),
                    verticalAlignment = Alignment.CenterVertically,
                    onClick = { actions.calendar(list = true) },
                    shapes = shapes,
                    leadingContent = { ShapeIcon(Icons.AutoMirrored.Rounded.HelpOutline) },
                    supportingContent = { Text("Fill them in so every count stays right") },
                    trailingContent = dismiss,
                ) { Text("${Format.days(item.days)} untracked this year") }
                is Attention.Certificate -> SegmentedListItem(
                    colors = segmentedColors(),
                    verticalAlignment = Alignment.CenterVertically,
                    onClick = {
                        actions.addEvidence(
                            EvidenceDraft(
                                day = LocalDate.of(item.year, 1, 1),
                                until = LocalDate.of(item.year, 12, 31),
                                country = item.country,
                                kind = DocumentKind.RESIDENCE_CERTIFICATE,
                            ),
                        )
                    },
                    shapes = shapes,
                    leadingContent = { ShapeIcon(Icons.Rounded.VerifiedUser) },
                    supportingContent = {
                        Text("From ${Countries.name(item.country)}: it proves where you're tax resident if another country counts your days")
                    },
                    trailingContent = dismiss,
                ) { Text("Add your ${item.year} tax residence certificate") }
                Attention.TrackingOff -> SegmentedListItem(
                    colors = segmentedColors(),
                    verticalAlignment = Alignment.CenterVertically,
                    onClick = { actions.settings(SettingsSection.TRACKING) },
                    shapes = shapes,
                    leadingContent = { ShapeIcon(Icons.Rounded.PauseCircle) },
                    supportingContent = { Text("Days are only recorded when you add them") },
                    trailingContent = dismiss,
                ) { Text("Tracking is off") }
            }
        }
    }
}
