package app.sojourn.ui.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.EditCalendar
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.sojourn.AppContainer
import app.sojourn.R
import app.sojourn.data.Countries
import app.sojourn.data.CountryGroup
import app.sojourn.data.TrackingMode
import app.sojourn.domain.RulePreset
import app.sojourn.ui.components.segmentedColors
import app.sojourn.ui.appViewModel
import app.sojourn.ui.components.CountryPickerSheet
import app.sojourn.ui.components.ShapeIcon
import app.sojourn.ui.settings.TrackingSetup
import kotlinx.coroutines.launch

class OnboardingViewModel(private val container: AppContainer) : ViewModel() {
    fun finish(answers: OnboardingAnswers) {
        viewModelScope.launch {
            answers.presets.forEach { preset ->
                val country = when {
                    !preset.needsCountry -> null
                    preset == RulePreset.CONTINUITY -> answers.residencePermits.sorted().firstOrNull() ?: return@forEach
                    else -> answers.taxResidence ?: return@forEach
                }
                container.repository.saveRule(preset.template(country))
            }
            container.settings.update {
                it.copy(
                    onboarded = true,
                    tracking = answers.tracking,
                    citizenships = answers.citizenships,
                    residencePermits = answers.residencePermits,
                    taxResidence = answers.taxResidence,
                    usTaxpayer = "US" in answers.citizenships,
                )
            }
        }
    }
}


data class OnboardingAnswers(
    val tracking: TrackingMode,
    val citizenships: Set<String>,
    val residencePermits: Set<String>,
    val taxResidence: String?,
    val presets: Set<RulePreset>,
)

@Composable
fun OnboardingScreen() {
    val vm = appViewModel { OnboardingViewModel(it) }
    OnboardingContent(onFinish = vm::finish)
}

@Composable
fun OnboardingContent(
    onFinish: (OnboardingAnswers) -> Unit,
    initialPage: Int = 0,
) {
    val pager = rememberPagerState(initialPage) { 3 }
    val scope = rememberCoroutineScope()
    var tracking by remember { mutableStateOf(TrackingMode.LOCATION) }
    var citizenships by remember { mutableStateOf(setOf<String>()) }
    var permits by remember { mutableStateOf(setOf<String>()) }
    var residence by remember { mutableStateOf<String?>(null) }
    var presets by remember { mutableStateOf(setOf<RulePreset>()) }
    var touchedPresets by remember { mutableStateOf(false) }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    // Suggest rules from the passports until the user picks themselves.
    val suggested = buildSet {
        // Free movement covers EU, EEA and Swiss passports; a Schengen residence permit doesn't.
        if (citizenships.isNotEmpty() && citizenships.none { it in CountryGroup.FREE_MOVEMENT }) add(RulePreset.SCHENGEN)
        if (permits.isNotEmpty()) add(RulePreset.CONTINUITY)
        if ("US" in citizenships) add(RulePreset.FEIE)
    }
    val chosen = if (touchedPresets) presets else suggested

    fun finish() {
        if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        onFinish(OnboardingAnswers(tracking, citizenships, permits, residence, chosen))
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            HorizontalPager(state = pager, modifier = Modifier.weight(1f), userScrollEnabled = true) { page ->
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
                ) {
                    when (page) {
                        0 -> Welcome()
                        1 -> {
                            Title("How should Sojourn keep track?", "You can change this any time in settings.")
                            TrackingSetup(tracking, onMode = { tracking = it })
                        }
                        else -> AboutYou(
                            citizenships = citizenships,
                            onCitizenships = { citizenships = it },
                            permits = permits,
                            onPermits = { permits = it },
                            residence = residence,
                            onResidence = { residence = it },
                            suggested = suggested,
                            chosen = chosen,
                            onToggle = { preset ->
                                touchedPresets = true
                                presets = if (preset in chosen) chosen - preset else chosen + preset
                            },
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                    repeat(3) { index ->
                        val active = pager.currentPage == index
                        val width by animateDpAsState(if (active) 24.dp else 8.dp, label = "dot")
                        val color by animateColorAsState(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, label = "dot")
                        Box(Modifier.height(8.dp).width(width).clip(RoundedCornerShape(50)).background(color))
                    }
                }
                if (pager.currentPage < 2) {
                    if (pager.currentPage > 0) TextButton(onClick = { scope.launch { pager.animateScrollToPage(2) } }) { Text("Skip") }
                    Button(onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }) {
                        Text(if (pager.currentPage == 0) "Get started" else "Next")
                    }
                } else {
                    Button(onClick = ::finish) { Text("Start") }
                }
            }
        }
    }
}

@Composable
fun SojournMark(size: Dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(RoundedCornerShape(size * 0.28f))) {
        // The adaptive icon canvas is 108 wide with the visible part in the middle 72.
        val scale = Modifier.fillMaxSize().graphicsLayer(scaleX = 1.5f, scaleY = 1.5f)
        Image(painterResource(R.drawable.ic_launcher_background), contentDescription = null, modifier = scale)
        Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = "Sojourn", modifier = scale)
    }
}

@Composable
private fun ColumnScope.Welcome() {
    Spacer(Modifier.height(32.dp))
    SojournMark(112.dp, Modifier.align(Alignment.CenterHorizontally))
    Spacer(Modifier.height(24.dp))
    Text(
        "Sojourn",
        style = MaterialTheme.typography.displayMediumEmphasized,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        "Know where you've been.\nPlan where you're going.",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(36.dp))
    Feature(Icons.Rounded.Public, "Every day, every country", "Counts your days per country on its own, gently on the battery.", MaterialShapes.Cookie9Sided.toShape())
    Feature(Icons.Rounded.Shield, "Limits and residency", "Schengen 90/180, tax residency days, residence permits, visa-free stays and your own rules.", MaterialShapes.Clover4Leaf.toShape())
    Feature(Icons.Rounded.EditCalendar, "Plan the year ahead", "See what a trip does to every limit before you book it.", MaterialShapes.Gem.toShape())
    Feature(Icons.Rounded.Lock, "Yours alone", "No internet access, no account. Only countries are stored, never coordinates.", MaterialShapes.Pentagon.toShape())
}

@Composable
private fun Feature(icon: ImageVector, title: String, body: String, shape: androidx.compose.ui.graphics.Shape) {
    Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        ShapeIcon(icon, size = 48.dp, shape = shape)
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Title(title: String, subtitle: String) {
    Text(title, style = MaterialTheme.typography.headlineMediumEmphasized)
    Spacer(Modifier.height(4.dp))
    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(24.dp))
}

@Composable
private fun AboutYou(
    citizenships: Set<String>,
    onCitizenships: (Set<String>) -> Unit,
    permits: Set<String>,
    onPermits: (Set<String>) -> Unit,
    residence: String?,
    onResidence: (String?) -> Unit,
    suggested: Set<RulePreset>,
    chosen: Set<RulePreset>,
    onToggle: (RulePreset) -> Unit,
) {
    var picking by remember { mutableStateOf<String?>(null) }
    Title("A little about you", "This picks the rules that matter to you. It stays on your phone.")
    Text("Passports", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        citizenships.forEach { code ->
            InputChip(
                selected = true,
                onClick = { onCitizenships(citizenships - code) },
                label = { Text("${Countries.flag(code)} ${Countries.name(code)}") },
                trailingIcon = { Icon(Icons.Rounded.Close, contentDescription = "Remove", modifier = Modifier.size(16.dp)) },
            )
        }
        AssistChip(onClick = { picking = "citizenship" }, label = { Text("Add passport") }, leadingIcon = { Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)) })
    }
    Spacer(Modifier.height(20.dp))
    Text("Residence permits", style = MaterialTheme.typography.titleMedium)
    Text(
        "Optional. Where you hold a residence permit or long-stay visa; days there don't count towards visitor limits like Schengen 90/180.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        permits.forEach { code ->
            InputChip(
                selected = true,
                onClick = { onPermits(permits - code) },
                label = { Text("${Countries.flag(code)} ${Countries.name(code)}") },
                trailingIcon = { Icon(Icons.Rounded.Close, contentDescription = "Remove", modifier = Modifier.size(16.dp)) },
            )
        }
        AssistChip(onClick = { picking = "permit" }, label = { Text("Add permit") }, leadingIcon = { Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)) })
    }
    Spacer(Modifier.height(20.dp))
    Text("Tax residence", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    AssistChip(
        onClick = { picking = "residence" },
        label = { Text(residence?.let { "${Countries.flag(it)} ${Countries.name(it)}" } ?: "Choose a country") },
    )
    Spacer(Modifier.height(24.dp))
    Text("Rules to watch", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    val options = buildList {
        // Free movement means Schengen 90/180 never applies to EU, EEA and Swiss passports.
        if (citizenships.none { it in CountryGroup.FREE_MOVEMENT }) add(RulePreset.SCHENGEN)
        if (residence != null) add(RulePreset.KEEP_RESIDENCY)
        if (permits.isNotEmpty()) add(RulePreset.CONTINUITY)
        if ("US" in citizenships) add(RulePreset.FEIE)
    }
    Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        options.forEachIndexed { index, preset ->
            SegmentedListItem(
                colors = segmentedColors(),
                verticalAlignment = Alignment.CenterVertically,
                checked = preset in chosen,
                onCheckedChange = { onToggle(preset) },
                shapes = ListItemDefaults.segmentedShapes(index, options.size),
                supportingContent = {
                    Text(
                        when (preset) {
                            RulePreset.KEEP_RESIDENCY -> "Enough days each year in ${Countries.name(residence!!)} to stay tax resident"
                            RulePreset.CONTINUITY -> "Never away from ${Countries.name(permits.sorted().first())} more than 6 months in a row"
                            else -> preset.description
                        },
                    )
                },
                trailingContent = { Switch(checked = preset in chosen, onCheckedChange = null) },
            ) { Text(preset.title) }
        }
    }
    Spacer(Modifier.height(8.dp))
    Text("More rules, like visa-free stays, can be added later.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

    picking?.let { which ->
        CountryPickerSheet(
            title = when (which) {
                "citizenship" -> "Add a passport"
                "permit" -> "Add a residence permit"
                else -> "Tax residence"
            },
            exclude = when (which) {
                "citizenship" -> citizenships
                "permit" -> permits
                else -> emptySet()
            },
            onDismiss = { picking = null },
            onPick = {
                when (which) {
                    "citizenship" -> onCitizenships(citizenships + it)
                    "permit" -> onPermits(permits + it)
                    else -> onResidence(it)
                }
                picking = null
            },
        )
    }
}
