package app.sojourn.ui.rules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.sojourn.data.Countries
import app.sojourn.data.CountryGroup
import app.sojourn.data.CountrySet
import app.sojourn.data.db.Rule
import app.sojourn.data.db.RuleType
import app.sojourn.data.db.RuleWindow
import app.sojourn.domain.CountingPolicies
import app.sojourn.domain.CountryRules
import app.sojourn.domain.DayCounting
import app.sojourn.domain.Goal
import app.sojourn.domain.ResidencyParams
import app.sojourn.domain.ResidencyTest
import app.sojourn.domain.TestKind
import app.sojourn.ui.components.ChoiceGroup
import app.sojourn.ui.components.CountryPickerSheet
import app.sojourn.ui.components.FlagBadge
import app.sojourn.ui.components.segmentedColors
import java.time.Month
import java.time.format.TextStyle

@Composable
fun RuleEditorSheet(
    initial: Rule,
    recents: List<String>,
    onDismiss: () -> Unit,
    onSave: (Rule) -> Unit,
    onDelete: (Rule) -> Unit,
    policies: CountingPolicies = CountingPolicies(),
) {
    val isNew = initial.id == 0L
    val special = initial.type == RuleType.US_PRESENCE
    val residency = initial.type == RuleType.RESIDENCY
    var name by remember { mutableStateOf(initial.name) }
    var type by remember { mutableStateOf(initial.type) }
    var except by remember { mutableStateOf(initial.countries.startsWith("!")) }
    var parts by remember { mutableStateOf(initial.countries.removePrefix("!").split(',').map { it.trim() }.filter { it.isNotEmpty() }) }
    var days by remember { mutableStateOf(initial.days.toString()) }
    var window by remember { mutableStateOf(initial.window) }
    var windowDays by remember { mutableStateOf(initial.windowDays.toString()) }
    var yearStart by remember { mutableIntStateOf(initial.yearStart) }
    var enabled by remember { mutableStateOf(initial.enabled) }
    var forVisitors by remember { mutableStateOf(initial.forVisitors) }
    var counting by remember { mutableStateOf(initial.counting?.let { runCatching { DayCounting.valueOf(it) }.getOrNull() }) }
    var params by remember { mutableStateOf(ResidencyParams.decode(initial.params) ?: if (residency) ResidencyParams.fromCatalogue(initial.countries, Goal.AVOID) else null) }
    var picking by remember { mutableStateOf(false) }
    var choosingCounting by remember { mutableStateOf(false) }

    val dayCount = days.toIntOrNull()?.takeIf { it > 0 }
    val windowCount = windowDays.toIntOrNull()?.takeIf { it > 0 }
    val valid = name.isNotBlank() && (residency || special || (parts.isNotEmpty() && dayCount != null && (window != RuleWindow.ROLLING || windowCount != null)))
    val set = CountrySet.parse((if (except) "!" else "") + parts.joinToString(","))

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberBottomSheetState(SheetValue.Hidden, setOf(SheetValue.Hidden, SheetValue.Expanded))) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 20.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(if (isNew) "New rule" else "Edit rule", style = MaterialTheme.typography.headlineSmallEmphasized)
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            when {
                special -> Text(
                    "Counts this year's US days, plus a third of last year's and a sixth of the year before. 183 or more, with at least 31 this year, makes you a US tax resident.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                residency -> params?.let { current ->
                    ResidencyEditor(current, yearStart, onYearStart = { yearStart = it }, onChange = { params = it })
                }
                else -> {
                    Label("Count")
                    ChoiceGroup(listOf(RuleType.LIMIT to "At most", RuleType.MINIMUM to "At least"), type, { type = it })
                    OutlinedTextField(
                        value = days,
                        onValueChange = { days = it.filter(Char::isDigit).take(4) },
                        label = { Text("Days") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Label("Where")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                        CountryGroup.entries.forEach { group ->
                            FilterChip(
                                selected = group.key in parts,
                                onClick = { parts = if (group.key in parts) parts - group.key else parts + group.key },
                                label = { Text(group.label) },
                            )
                        }
                        parts.filter { CountryGroup.byKey(it) == null }.forEach { code ->
                            InputChip(
                                selected = false,
                                onClick = { parts = parts - code },
                                label = { Text("${Countries.flag(code)} ${Countries.name(code)}") },
                                trailingIcon = { Icon(Icons.Rounded.Close, contentDescription = "Remove ${Countries.name(code)}", modifier = Modifier.size(16.dp)) },
                            )
                        }
                        TextButton(onClick = { picking = true }) {
                            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Add country")
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Anywhere except these", style = MaterialTheme.typography.bodyLarge)
                            Text("Counts days spent outside them", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = except, onCheckedChange = { except = it })
                    }

                    Label("Over")
                    ChoiceGroup(listOf(RuleWindow.ROLLING to "Rolling", RuleWindow.YEAR to "Year", RuleWindow.STAY to "Per stay"), window, { window = it })
                    when (window) {
                        RuleWindow.ROLLING -> OutlinedTextField(
                            value = windowDays,
                            onValueChange = { windowDays = it.filter(Char::isDigit).take(4) },
                            label = { Text("Window length in days") },
                            supportingText = { Text("Any ${windowDays.ifBlank { "N" }} consecutive days, sliding with every day") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        RuleWindow.YEAR -> YearStartPicker(yearStart) { yearStart = it }
                        RuleWindow.STAY -> Text(
                            "Counts one uninterrupted stay; leaving resets it.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (!special) {
                val own = if (residency) policies.of(params?.country.orEmpty()) else policies.forSet(set)
                SegmentedListItem(
                    colors = segmentedColors(onSheet = true),
                    onClick = { choosingCounting = true },
                    shapes = ListItemDefaults.segmentedShapes(0, 1),
                    overlineContent = { Text("How days count") },
                    supportingContent = { Text((counting ?: own).summary) },
                ) { Text(counting?.title ?: "${own.title} (${if (residency || set.codes.size == 1) "the country's own" else "the usual"})") }
            }

            if (!special && !residency) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Visitor limit", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Skips countries you're a citizen of or hold a residence permit for, like a visa rule. Leave off for tax rules, which count every day.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = forVisitors, onCheckedChange = { forVisitors = it })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Active", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(checked = enabled, onCheckedChange = { enabled = it })
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!isNew) OutlinedButton(onClick = { onDelete(initial) }) { Text("Delete") }
                Spacer(Modifier.weight(1f))
                Button(
                    enabled = valid,
                    onClick = {
                        val p = params
                        onSave(
                            when {
                                residency && p != null -> initial.copy(
                                    name = name.trim(),
                                    days = p.tests.firstOrNull { it.kind == TestKind.DAYS }?.days ?: initial.days,
                                    yearStart = yearStart,
                                    enabled = enabled,
                                    counting = counting?.name,
                                    params = p.encode(),
                                )
                                special -> initial.copy(name = name.trim(), enabled = enabled)
                                else -> initial.copy(
                                    name = name.trim(),
                                    type = type,
                                    countries = (if (except) "!" else "") + parts.joinToString(","),
                                    days = dayCount ?: initial.days,
                                    window = window,
                                    windowDays = windowCount ?: initial.windowDays,
                                    yearStart = yearStart,
                                    enabled = enabled,
                                    forVisitors = forVisitors,
                                    counting = counting?.name,
                                )
                            },
                        )
                    },
                ) { Text("Save") }
            }
        }
    }
    if (picking) {
        CountryPickerSheet(
            title = "Add a country",
            suggestions = recents,
            exclude = parts.toSet(),
            onDismiss = { picking = false },
            onPick = {
                parts = parts + it
                picking = false
            },
        )
    }
    if (choosingCounting) {
        val own = if (residency) policies.of(params?.country.orEmpty()) else policies.forSet(set)
        CountingDialog(
            title = "How days count",
            own = own,
            ownLabel = if (residency || set.codes.size == 1) "The country's own" else "The usual for these countries",
            selected = counting,
            onDismiss = { choosingCounting = false },
            onPick = {
                counting = it
                choosingCounting = false
            },
        )
    }
}

/** A single-choice dialog of counting policies, with "the country's own" first. */
@Composable
fun CountingDialog(title: String, own: DayCounting?, ownLabel: String, selected: DayCounting?, onDismiss: () -> Unit, onPick: (DayCounting?) -> Unit) {
    var choice by remember { mutableStateOf(selected) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.selectableGroup().verticalScroll(rememberScrollState())) {
                val options: List<DayCounting?> = (if (own != null) listOf(null) else emptyList<DayCounting?>()) + DayCounting.entries
                options.forEach { option ->
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = choice == option, role = Role.RadioButton) { choice = option }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        RadioButton(selected = choice == option, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(if (option == null) "$ownLabel: ${own!!.title.replaceFirstChar(Char::lowercaseChar)}" else option.title, style = MaterialTheme.typography.bodyLarge)
                            Text((option ?: own!!).summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(choice) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A residency rule: its goal, each test (on or off, with its threshold), and the questions behind them. */
@Composable
private fun ResidencyEditor(params: ResidencyParams, yearStart: Int, onYearStart: (Int) -> Unit, onChange: (ResidencyParams) -> Unit) {
    val country = params.country
    val known = CountryRules[country]
    val uri = LocalUriHandler.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FlagBadge(country, size = 48.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(Countries.name(country), style = MaterialTheme.typography.titleLarge)
                known?.let { Text(it.confidence.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        ChoiceGroup(listOf(Goal.AVOID to "Avoid residency", Goal.KEEP to "Keep residency"), params.goal, { onChange(params.copy(goal = it)) })
        Text(
            if (params.goal == Goal.AVOID) "You'll be warned before any test is met. Each test is checked on its own: meeting one is enough to become resident."
            else "You'll see how far you are from meeting at least one test this year.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        known?.notes?.takeIf { it.isNotBlank() }?.let {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.large) {
                Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
            }
        }

        Label("Tax year")
        YearStartPicker(yearStart, onYearStart)

        Label("Tests")
        Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
            params.tests.forEachIndexed { index, test ->
                TestRow(test, index, params.tests.size) { updated -> onChange(params.copy(tests = params.tests.toMutableList().also { it[index] = updated })) }
            }
        }

        val questions = params.questions()
        if (questions.isNotEmpty()) {
            Label("About you")
            Text(
                "Some tests depend on facts only you know. Until you answer, Sojourn assumes they might apply and says so.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            questions.forEach { question ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(question.text, style = MaterialTheme.typography.bodyMedium)
                    ChoiceGroup(
                        listOf(true to "Yes", false to "No", null to "Not sure"),
                        params.answers[question.id],
                        { answer -> onChange(params.copy(answers = if (answer == null) params.answers - question.id else params.answers + (question.id to answer))) },
                    )
                }
            }
        }

        params.sporadic?.let { sporadic ->
            Label("Sporadic absences")
            Text(
                "Spain adds trips abroad to your Spanish days when you leave and come back, unless you can prove tax residence in another country, usually with its tax residence certificate: then no absence is added. An absence over 183 days in a row isn't sporadic (Supreme Court, 2017). Sojourn shows this count beside the plain one.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Do you have a tax residence certificate from another country for this year?", style = MaterialTheme.typography.bodyMedium)
                ChoiceGroup(
                    listOf(true to "Yes", false to "No", null to "Not sure"),
                    sporadic.certificate,
                    { onChange(params.copy(sporadic = sporadic.copy(certificate = it))) },
                )
                Text(
                    "Yes: no absence is added, but the count is still shown. No or not sure: they're added, as Hacienda would.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Use it for this rule", style = MaterialTheme.typography.bodyLarge)
                    Text("The stricter count, as the tax agency may see it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = sporadic.apply, onCheckedChange = { onChange(params.copy(sporadic = sporadic.copy(apply = it))) })
            }
            NumberField("Longest sporadic absence, days", sporadic.maxRun) { onChange(params.copy(sporadic = sporadic.copy(maxRun = it))) }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onChange(ResidencyParams.fromCatalogue(country, params.goal).copy(answers = params.answers)) }) {
                Icon(Icons.Rounded.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Reset to Sojourn's rules")
            }
            Spacer(Modifier.weight(1f))
            known?.source?.takeIf { it.isNotBlank() }?.let { source -> TextButton(onClick = { uri.openUri(source) }) { Text("Source") } }
        }
        Text(
            "A guide based on public sources, checked in October 2026. Tax residency also depends on treaties and facts Sojourn can't see; for decisions, ask a tax adviser.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TestRow(test: ResidencyTest, index: Int, count: Int, onChange: (ResidencyTest) -> Unit) {
    SegmentedListItem(
        colors = segmentedColors(onSheet = true),
        shapes = ListItemDefaults.segmentedShapes(index, count),
        checked = test.enabled,
        onCheckedChange = { onChange(test.copy(enabled = it)) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(describe(test))
                if (test.note.isNotBlank()) Text(test.note, style = MaterialTheme.typography.bodySmall)
                if (test.enabled && test.kind != TestKind.DECLARED && test.kind != TestKind.UK_TIES) {
                    NumberField(if (test.strict) "Days (more than ${test.days - 1})" else "Days", test.days) { onChange(test.copy(days = it)) }
                }
            }
        },
        trailingContent = { Switch(checked = test.enabled, onCheckedChange = null) },
    ) { Text(test.name + if (test.indicative) " (a guide only)" else "") }
}

private fun describe(test: ResidencyTest): String = when (test.kind) {
    TestKind.DAYS -> buildString {
        append(if (test.below) "Fewer than ${test.days} days" else test.threshold().replaceFirstChar(Char::uppercaseChar))
        append(if (test.windowDays == null) " in the tax year" else " in any ${test.windowDays} days")
        test.conditions.forEach { append(", and ${it.text.replaceFirstChar(Char::lowercaseChar)}") }
    }
    TestKind.CONTINUOUS -> "${test.days} days in a row" + if (test.maxBreak > 0) ", short trips of up to ${test.maxBreak} days don't break it" else ""
    TestKind.MULTI_YEAR -> "${test.days} days over ${test.weights.size} tax years, weighted ${test.weights.joinToString(" + ") { w -> if (w == 1.0) "1" else "%.2f".format(w) }}" +
        (if (test.currentMin > 0) ", with at least ${test.currentMin} this year" else "")
    TestKind.UK_TIES -> "From 16 to 183 days depending on your UK ties and whether you lived in the UK in the last 3 years"
    TestKind.DECLARED -> "Met if you answer yes below"
}

@Composable
private fun NumberField(label: String, value: Int, onValue: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            text = input.filter(Char::isDigit).take(4)
            text.toIntOrNull()?.takeIf { it > 0 }?.let(onValue)
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun YearStartPicker(value: Int, onChange: (Int) -> Unit) {
    val presets = listOf(101 to "Calendar year", 406 to "6 Apr (UK)", 701 to "1 Jul (AU)", 401 to "1 Apr (IN, NZ)", 301 to "1 Mar (ZA)")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            presets.forEach { (start, label) ->
                FilterChip(selected = value == start, onClick = { onChange(start) }, label = { Text(label) })
            }
        }
        if (presets.none { it.first == value }) {
            val month = Month.of(value / 100).getDisplayName(TextStyle.FULL, LocalConfiguration.current.locales[0])
            Text("Starts on ${value % 100} $month", style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(0.dp))
    }
}
