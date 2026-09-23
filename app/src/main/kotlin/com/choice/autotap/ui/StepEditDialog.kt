package com.choice.autotap.ui

import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.choice.autotap.appGraph
import com.choice.autotap.model.GlobalActionType
import com.choice.autotap.model.MacroStep
import com.choice.autotap.model.NormalizedPoint
import com.choice.autotap.model.PasteMode
import com.choice.autotap.model.StepAction
import com.choice.autotap.model.typeLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun StepEditDialog(step: MacroStep, onDismiss: () -> Unit, onSave: (MacroStep) -> Unit) {
    var draft by remember(step.id) { mutableStateOf(step) }
    fun setAction(a: StepAction) {
        draft = draft.copy(action = a)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit: ${draft.action.typeLabel()}") },
        confirmButton = { TextButton(onClick = { onSave(draft) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (val a = draft.action) {
                    is StepAction.Tap -> PointFields("Target", a.point) { setAction(a.copy(point = it)) }
                    is StepAction.LongPress -> {
                        PointFields("Target", a.point) { setAction(a.copy(point = it)) }
                        LongField("Hold duration (ms)", a.durationMs, Modifier.fillMaxWidth()) { setAction(a.copy(durationMs = it)) }
                    }
                    is StepAction.DoubleTap -> {
                        PointFields("Target", a.point) { setAction(a.copy(point = it)) }
                        LongField("Gap between taps (ms)", a.intervalMs, Modifier.fillMaxWidth()) { setAction(a.copy(intervalMs = it)) }
                    }
                    is StepAction.Swipe -> {
                        PointFields("Start", a.start) { setAction(a.copy(start = it)) }
                        PointFields("End", a.end) { setAction(a.copy(end = it)) }
                        LongField("Duration (ms)", a.durationMs, Modifier.fillMaxWidth()) { setAction(a.copy(durationMs = it)) }
                    }
                    is StepAction.Wait -> {
                        Text("Same min and max = fixed wait; different = random wait in range.", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            LongField("Min ms", a.minMs, Modifier.weight(1f)) { setAction(a.copy(minMs = it)) }
                            LongField("Max ms", a.maxMs, Modifier.weight(1f)) { setAction(a.copy(maxMs = it)) }
                        }
                    }
                    is StepAction.Global -> GlobalActionType.entries.forEach { type ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().selectable(a.action == type) { setAction(a.copy(action = type)) },
                        ) {
                            RadioButton(selected = a.action == type, onClick = { setAction(a.copy(action = type)) })
                            Text(type.name.lowercase().replaceFirstChar { it.uppercase() })
                        }
                    }
                    is StepAction.PasteText -> PasteFields(a, ::setAction)
                    is StepAction.LaunchApp -> LaunchAppFields(a, ::setAction)
                    is StepAction.WaitForText -> {
                        OutlinedTextField(
                            value = a.text,
                            onValueChange = { setAction(a.copy(text = it)) },
                            label = { Text("Text to wait for") },
                            textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        LongField("Timeout (ms)", a.timeoutMs, Modifier.fillMaxWidth()) { setAction(a.copy(timeoutMs = it)) }
                        LongField("Check every (ms)", a.pollIntervalMs, Modifier.fillMaxWidth()) { setAction(a.copy(pollIntervalMs = it)) }
                        SwitchRow("Exact match (otherwise “contains”)", a.exactMatch) { setAction(a.copy(exactMatch = it)) }
                    }
                }

                HorizontalDivider()
                Text("Timing & humanizing", style = MaterialTheme.typography.titleSmall)
                LongField("Delay after step (ms)", draft.delayAfterMs, Modifier.fillMaxWidth()) { draft = draft.copy(delayAfterMs = it) }
                LongField("Extra random delay 0–N (ms)", draft.randomDelayMs, Modifier.fillMaxWidth()) {
                    draft = draft.copy(randomDelayMs = it)
                }
                if (draft.action.points.isNotEmpty()) {
                    IntField("Random offset ±px", draft.randomOffsetPx, Modifier.fillMaxWidth()) { draft = draft.copy(randomOffsetPx = it) }
                    SwitchRow("Apply per-loop coordinate shift", draft.applyLoopShift) { draft = draft.copy(applyLoopShift = it) }
                }
                SwitchRow("Enabled", draft.enabled) { draft = draft.copy(enabled = it) }
                OutlinedTextField(
                    value = draft.note,
                    onValueChange = { draft = draft.copy(note = it) },
                    label = { Text("Note") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    )
}

@Composable
private fun PointFields(label: String, point: NormalizedPoint, onChange: (NormalizedPoint) -> Unit) {
    Text(label, style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PercentField("X", point.x, Modifier.weight(1f)) { onChange(point.copy(x = it)) }
        PercentField("Y", point.y, Modifier.weight(1f)) { onChange(point.copy(y = it)) }
    }
}

@Composable
private fun PasteFields(a: StepAction.PasteText, setAction: (StepAction) -> Unit) {
    val context = LocalContext.current
    val snippets by context.appGraph.snippets.snippets.collectAsStateWithLifecycle(initialValue = emptyList())
    var snippetMenu by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = a.text,
        onValueChange = { setAction(a.copy(text = it)) },
        label = { Text("Text (Arabic, emoji… all fine)") },
        textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
        minLines = 2,
        modifier = Modifier.fillMaxWidth(),
    )
    Row {
        OutlinedButton(onClick = { snippetMenu = true }, enabled = snippets.isNotEmpty()) {
            Text(if (snippets.isEmpty()) "No saved texts" else "Insert saved text")
        }
        DropdownMenu(expanded = snippetMenu, onDismissRequest = { snippetMenu = false }) {
            snippets.forEach { s ->
                DropdownMenuItem(text = { Text(s.label) }, onClick = {
                    snippetMenu = false
                    setAction(a.copy(text = s.text))
                })
            }
        }
    }
    Text("Method", style = MaterialTheme.typography.labelLarge)
    listOf(
        PasteMode.SET_TEXT to "Direct: set text on the focused field (recommended)",
        PasteMode.LONG_PRESS_POPUP to "Long-press target, then tap the “Paste” popup",
    ).forEach { (mode, label) ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().selectable(a.mode == mode) { setAction(a.copy(mode = mode)) },
        ) {
            RadioButton(selected = a.mode == mode, onClick = { setAction(a.copy(mode = mode)) })
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
    }
    SwitchRow(
        if (a.mode == PasteMode.SET_TEXT) "Tap the field first to focus it" else "Target field (long-pressed)",
        a.target != null,
    ) { on -> setAction(a.copy(target = if (on) a.target ?: NormalizedPoint(0.5f, 0.5f) else null)) }
    a.target?.let { t -> PointFields("Field position", t) { setAction(a.copy(target = it)) } }
    if (a.mode == PasteMode.SET_TEXT) {
        SwitchRow("Append to existing text", a.append) { setAction(a.copy(append = it)) }
    } else {
        LongField("Long-press duration (ms)", a.longPressMs, Modifier.fillMaxWidth()) { setAction(a.copy(longPressMs = it)) }
        OutlinedTextField(
            value = a.popupLabels.joinToString(", "),
            onValueChange = { v -> setAction(a.copy(popupLabels = v.split(',').map { it.trim() }.filter { it.isNotEmpty() })) },
            label = { Text("Popup button labels (comma separated)") },
            modifier = Modifier.fillMaxWidth(),
        )
        LongField("Wait for popup (ms)", a.popupTimeoutMs, Modifier.fillMaxWidth()) { setAction(a.copy(popupTimeoutMs = it)) }
        SwitchRow("Fallback: tap a fixed “Paste” position", a.popupPoint != null) { on ->
            setAction(a.copy(popupPoint = if (on) a.popupPoint ?: NormalizedPoint(0.5f, 0.45f) else null))
        }
        a.popupPoint?.let { p -> PointFields("Paste button position", p) { setAction(a.copy(popupPoint = it)) } }
    }
}

@Composable
private fun LaunchAppFields(a: StepAction.LaunchApp, setAction: (StepAction) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    Text(if (a.packageName.isEmpty()) "No app chosen" else "${a.label}\n${a.packageName}")
    OutlinedButton(onClick = { picking = true }) { Text("Choose from installed apps") }
    if (picking) {
        AppPickerDialog(onDismiss = { picking = false }) { pkg, label ->
            picking = false
            setAction(a.copy(packageName = pkg, label = label))
        }
    }
}

private data class AppEntry(val packageName: String, val label: String)

@Composable
fun AppPickerDialog(onDismiss: () -> Unit, onPick: (String, String) -> Unit) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<AppEntry>?>(null) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
                .map { AppEntry(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
                .distinctBy { it.packageName }
                .sortedBy { it.label.lowercase() }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Choose app") },
        text = {
            Column {
                OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Search") }, singleLine = true)
                val list = apps
                if (list == null) {
                    Text("Loading…", Modifier.padding(16.dp))
                } else {
                    val filtered = list.filter { query.isBlank() || it.label.contains(query, true) || it.packageName.contains(query, true) }
                    LazyColumn(Modifier.heightIn(max = 400.dp)) {
                        items(filtered, key = { it.packageName }) { app ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(app.packageName, app.label) }
                                    .padding(vertical = 10.dp),
                            ) {
                                Text(app.label)
                                Text(app.packageName, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        },
    )
}
