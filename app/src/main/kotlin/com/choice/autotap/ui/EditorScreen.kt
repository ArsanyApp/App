package com.choice.autotap.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.choice.autotap.appGraph
import com.choice.autotap.model.GlobalActionType
import com.choice.autotap.model.LoopShift
import com.choice.autotap.model.Macro
import com.choice.autotap.model.MacroEditing
import com.choice.autotap.model.MacroStep
import com.choice.autotap.model.NormalizedPoint
import com.choice.autotap.model.StepAction
import com.choice.autotap.model.TimeoutPolicy
import com.choice.autotap.model.describe
import com.choice.autotap.service.AutoTapBridge
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
fun EditorScreen(macroId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val graph = context.appGraph
    val scope = rememberCoroutineScope()
    val service by AutoTapBridge.service.collectAsStateWithLifecycle()
    var macro by remember { mutableStateOf<Macro?>(null) }
    var editing by remember { mutableStateOf<MacroStep?>(null) }
    var saveJob by remember { mutableStateOf<Job?>(null) }
    val latest by rememberUpdatedState(macro)

    // Reload on every resume: the on-screen marker editor may have changed the steps meanwhile.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        scope.launch { macro = graph.macros.get(macroId) }
    }
    LaunchedEffect(macroId) { AutoTapBridge.activeMacroId.value = macroId }
    DisposableEffect(Unit) {
        onDispose { latest?.let { m -> graph.appScope.launch { graph.macros.save(m) } } }
    }

    fun update(transform: (Macro) -> Macro) {
        val m = macro ?: return
        val updated = transform(m)
        macro = updated
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(300)
            graph.macros.save(updated)
        }
    }

    fun updateSteps(transform: (List<MacroStep>) -> List<MacroStep>) = update { it.copy(steps = transform(it.steps)) }

    fun launchOnService(action: (com.choice.autotap.service.AutoTapAccessibilityService) -> Unit) {
        val m = macro ?: return
        val s = service ?: run {
            Toast.makeText(context, "Enable the accessibility service first (Setup)", Toast.LENGTH_LONG).show()
            return
        }
        saveJob?.cancel()
        scope.launch {
            graph.macros.save(m)
            action(s)
            context.findActivity()?.moveTaskToBack(true)
        }
    }

    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        val steps = macro?.steps ?: return@rememberReorderableLazyListState
        val fromIndex = steps.indexOfFirst { it.id == from.key }
        val toIndex = steps.indexOfFirst { it.id == to.key }
        if (fromIndex >= 0 && toIndex >= 0) updateSteps { MacroEditing.move(it, fromIndex, toIndex) }
    }

    Scaffold(
        topBar = {
            BackTopBar(macro?.name ?: "Macro", onBack) {
                TextButton(onClick = { launchOnService { it.openMarkerEditor(macroId) } }) { Text("Markers") }
                IconButton(onClick = { launchOnService { it.startPlayback(macroId) } }) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Run")
                }
            }
        },
    ) { padding ->
        val m = macro
        if (m == null) {
            Text("Loading…", Modifier.padding(padding).padding(16.dp))
            return@Scaffold
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 48.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "name") {
                var name by remember(m.id) { mutableStateOf(m.name) }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; update { mm -> mm.copy(name = it) } },
                    label = { Text("Macro name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item(key = "loop") { LoopSettingsCard(m, ::update) }
            item(key = "timeout") { TimeoutCard(m, ::update) }
            item(key = "steps_header") {
                var addMenu by remember { mutableStateOf(false) }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    Text("Steps (${m.steps.size})", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Button(onClick = { addMenu = true }) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Text("Add step")
                    }
                    DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                        newStepTemplates().forEach { (label, action) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = {
                                addMenu = false
                                val step = MacroStep(action = action)
                                updateSteps { it + step }
                                editing = step
                            })
                        }
                    }
                }
                Text(
                    "Drag ≡ to reorder. Coordinates are stored as % of the screen. Use “Markers” to position them over the target app.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            itemsIndexed(m.steps, key = { _, s -> s.id }) { index, step ->
                ReorderableItem(reorderState, key = step.id) { isDragging ->
                    StepRow(
                        index = index,
                        step = step,
                        dragging = isDragging,
                        handle = {
                            IconButton(onClick = {}, modifier = Modifier.draggableHandle()) {
                                Icon(Icons.Default.Menu, contentDescription = "Drag to reorder")
                            }
                        },
                        onToggle = { enabled -> updateSteps { MacroEditing.replace(it, step.copy(enabled = enabled)) } },
                        onEdit = { editing = step },
                        onDuplicate = { updateSteps { MacroEditing.duplicate(it, index) } },
                        onDelete = { updateSteps { MacroEditing.delete(it, index) } },
                        onUp = { updateSteps { MacroEditing.move(it, index, index - 1) } },
                        onDown = { updateSteps { MacroEditing.move(it, index, index + 1) } },
                    )
                }
            }
        }
    }

    editing?.let { step ->
        StepEditDialog(
            step = step,
            onDismiss = { editing = null },
            onSave = { edited ->
                editing = null
                updateSteps { MacroEditing.replace(it, edited) }
            },
        )
    }
}

private fun newStepTemplates(): List<Pair<String, StepAction>> {
    val c = NormalizedPoint(0.5f, 0.5f)
    return listOf(
        "Tap" to StepAction.Tap(c),
        "Long-press" to StepAction.LongPress(c),
        "Double tap" to StepAction.DoubleTap(c),
        "Swipe / scroll" to StepAction.Swipe(NormalizedPoint(0.5f, 0.75f), NormalizedPoint(0.5f, 0.3f)),
        "Wait" to StepAction.Wait(1000, 1000),
        "Back" to StepAction.Global(GlobalActionType.BACK),
        "Home" to StepAction.Global(GlobalActionType.HOME),
        "Recents" to StepAction.Global(GlobalActionType.RECENTS),
        "Paste text" to StepAction.PasteText(text = "", target = c),
        "Launch app" to StepAction.LaunchApp(packageName = "", label = "Choose app…"),
        "Wait until text appears" to StepAction.WaitForText(text = ""),
    )
}

@Composable
private fun StepRow(
    index: Int,
    step: MacroStep,
    dragging: Boolean,
    handle: @Composable () -> Unit,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
) {
    val colors = if (dragging) {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    } else {
        CardDefaults.cardColors()
    }
    Card(colors = colors, modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, end = 4.dp)) {
            handle()
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(
                    "${index + 1}. ${step.action.describe()}",
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                )
                val extras = buildList {
                    add("then ${step.delayAfterMs}ms" + if (step.randomDelayMs > 0) " +0–${step.randomDelayMs}" else "")
                    if (step.randomOffsetPx > 0) add("±${step.randomOffsetPx}px")
                    if (!step.enabled) add("disabled")
                    if (step.note.isNotBlank()) add(step.note)
                }
                Text(extras.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            }
            Checkbox(checked = step.enabled, onCheckedChange = onToggle)
            Column {
                IconButton(onClick = onUp) { Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move up") }
                IconButton(onClick = onDown) { Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move down") }
            }
            Column {
                IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, contentDescription = "Edit") }
                IconButton(onClick = onDuplicate) { Icon(Icons.Default.AddCircle, contentDescription = "Duplicate") }
            }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "Delete") }
        }
    }
}

@Composable
private fun LoopSettingsCard(m: Macro, update: ((Macro) -> Macro) -> Unit) {
    val loop = m.loop
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Loop settings", style = MaterialTheme.typography.titleMedium)
            SwitchRow("Repeat forever", loop.isInfinite) { inf ->
                update { it.copy(loop = it.loop.copy(repeatCount = if (inf) 0 else 1)) }
            }
            if (!loop.isInfinite) {
                IntField("Repeat count", loop.repeatCount, Modifier.fillMaxWidth()) { v ->
                    update { it.copy(loop = it.loop.copy(repeatCount = v.coerceAtLeast(1))) }
                }
            }
            Text("Delay between loops (random range, seconds)", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LongField("Min s", loop.loopDelayMinMs / 1000, Modifier.weight(1f)) { v ->
                    update { it.copy(loop = it.loop.copy(loopDelayMinMs = v * 1000)) }
                }
                LongField("Max s", loop.loopDelayMaxMs / 1000, Modifier.weight(1f)) { v ->
                    update { it.copy(loop = it.loop.copy(loopDelayMaxMs = v * 1000)) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LongField("Stop after (min, 0 = never)", loop.stopAfterMs / 60_000, Modifier.weight(1f)) { v ->
                    update { it.copy(loop = it.loop.copy(stopAfterMs = v * 60_000)) }
                }
                LongField("Start countdown s", loop.startDelayMs / 1000, Modifier.weight(1f)) { v ->
                    update { it.copy(loop = it.loop.copy(startDelayMs = v * 1000)) }
                }
            }
            HorizontalDivider()
            SwitchRow("Shift coordinates every loop", loop.shift.enabled) { on ->
                update { it.copy(loop = it.loop.copy(shift = it.loop.shift.copy(enabled = on))) }
            }
            if (loop.shift.enabled) {
                Text(
                    "Each loop moves targets by this many pixels (e.g. Y = -150 walks up a list). Per step you can opt out.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IntField("ΔX px / loop", loop.shift.dxPx, Modifier.weight(1f)) { v ->
                        update { it.copy(loop = it.loop.copy(shift = LoopShift(true, v, it.loop.shift.dyPx))) }
                    }
                    IntField("ΔY px / loop", loop.shift.dyPx, Modifier.weight(1f)) { v ->
                        update { it.copy(loop = it.loop.copy(shift = LoopShift(true, it.loop.shift.dxPx, v))) }
                    }
                }
            }
        }
    }
}

@Composable
private fun TimeoutCard(m: Macro, update: ((Macro) -> Macro) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("If “wait until text” times out", style = MaterialTheme.typography.titleMedium)
            val options = listOf(
                TimeoutPolicy.SKIP_LOOP to "Skip the rest of this loop",
                TimeoutPolicy.RETRY_LOOP to "Retry the loop from step 1",
                TimeoutPolicy.STOP to "Stop the run",
            )
            options.forEach { (policy, label) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(selected = m.onWaitTimeout == policy, onClick = { update { it.copy(onWaitTimeout = policy) } }),
                ) {
                    RadioButton(selected = m.onWaitTimeout == policy, onClick = { update { it.copy(onWaitTimeout = policy) } })
                    Text(label)
                }
            }
            if (m.onWaitTimeout == TimeoutPolicy.RETRY_LOOP) {
                IntField("Max retries per loop", m.timeoutRetries, Modifier.fillMaxWidth()) { v ->
                    update { it.copy(timeoutRetries = v.coerceAtLeast(0)) }
                }
            }
        }
    }
}
