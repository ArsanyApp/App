package com.choice.autotap.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.choice.autotap.model.Macro
import com.choice.autotap.service.AutoTapBridge
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onEdit: (Long) -> Unit, onLogs: () -> Unit, onSnippets: () -> Unit, onSetup: () -> Unit) {
    val context = LocalContext.current
    val graph = context.appGraph
    val scope = rememberCoroutineScope()
    val macros by graph.macros.macros.collectAsStateWithLifecycle(initialValue = emptyList())
    val service by AutoTapBridge.service.collectAsStateWithLifecycle()
    val playback by AutoTapBridge.playback.collectAsStateWithLifecycle()
    var setupOk by remember { mutableStateOf(true) }
    var menu by remember { mutableStateOf(false) }
    var pendingExport by remember { mutableStateOf<Long?>(null) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        setupOk = Permissions.isAccessibilityEnabled(context) && Permissions.canDrawOverlays(context) &&
            Permissions.isIgnoringBatteryOptimizations(context)
    }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val target = uri ?: return@rememberLauncherForActivityResult
        val id = pendingExport
        scope.launch {
            val json = if (id != null) graph.macros.export(id) else graph.macros.exportAll()
            runCatching {
                context.contentResolver.openOutputStream(target)?.use { it.write(json.orEmpty().toByteArray(Charsets.UTF_8)) }
            }.onSuccess { toast("Exported") }.onFailure { toast("Export failed: ${it.message}") }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val source = uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val text = context.contentResolver.openInputStream(source)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
                graph.macros.import(text)
            }.onSuccess { toast("Imported $it macro(s)") }.onFailure { toast("Import failed: ${it.message}") }
        }
    }

    /** Runs an action on the accessibility service, then gets out of the way. */
    fun withService(action: (com.choice.autotap.service.AutoTapAccessibilityService) -> Unit) {
        val s = service
        if (s == null) {
            toast("Enable the Choice Auto Tap accessibility service first")
            onSetup()
            return
        }
        action(s)
        context.findActivity()?.moveTaskToBack(true)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Choice Auto Tap") },
                actions = {
                    IconButton(onClick = onLogs) { Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Run log") }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Saved texts") }, onClick = { menu = false; onSnippets() })
                        DropdownMenuItem(text = { Text("Import macros (JSON)") }, onClick = {
                            menu = false
                            importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                        })
                        DropdownMenuItem(text = { Text("Export all macros") }, onClick = {
                            menu = false
                            pendingExport = null
                            exportLauncher.launch("choice-auto-tap-macros.json")
                        })
                        DropdownMenuItem(text = { Text("Permissions & setup") }, onClick = { menu = false; onSetup() })
                        DropdownMenuItem(text = { Text("Show floating bubble") }, onClick = {
                            menu = false
                            service?.showBubble() ?: toast("Accessibility service is off")
                        })
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { scope.launch { onEdit(graph.macros.create("Macro ${macros.size + 1}")) } },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("New macro") },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!setupOk || service == null) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Setup needed", fontWeight = FontWeight.Bold)
                            Text("Enable the accessibility service, overlay permission and battery exemption so macros can run in other apps.")
                            Spacer(Modifier.padding(4.dp))
                            Button(onClick = onSetup) { Text("Open setup") }
                        }
                    }
                }
            }
            if (playback.isActive) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(playback.macroName, fontWeight = FontWeight.Bold)
                                Text(playback.shortLabel())
                            }
                            TextButton(onClick = { service?.togglePause() }) { Text("Pause/Resume") }
                            Button(onClick = { service?.stopPlayback() }) { Text("Stop") }
                        }
                    }
                }
            }
            if (macros.isEmpty()) {
                item {
                    Text(
                        "No macros yet. Tap “New macro”, or use ✎ on the floating bubble to place markers over any app.",
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
            items(macros, key = { it.id }) { macro ->
                MacroCard(
                    macro = macro,
                    onRun = {
                        AutoTapBridge.activeMacroId.value = macro.id
                        withService { it.startPlayback(macro.id) }
                    },
                    onMarkers = {
                        AutoTapBridge.activeMacroId.value = macro.id
                        withService { it.openMarkerEditor(macro.id) }
                    },
                    onEdit = { onEdit(macro.id) },
                    onDuplicate = { scope.launch { graph.macros.duplicate(macro.id) } },
                    onExport = {
                        pendingExport = macro.id
                        exportLauncher.launch("${macro.name}.json")
                    },
                    onDelete = { scope.launch { graph.macros.delete(macro.id) } },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MacroCard(
    macro: Macro,
    onRun: () -> Unit,
    onMarkers: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    Card(onClick = onEdit, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(macro.name, style = MaterialTheme.typography.titleMedium)
                    val loops = if (macro.loop.isInfinite) "∞ loops" else "${macro.loop.repeatCount}× loops"
                    Text(
                        "${macro.steps.size} steps · $loops · gap ${macro.loop.loopDelayMinMs / 1000}–${macro.loop.loopDelayMaxMs / 1000}s",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Duplicate") }, onClick = { menu = false; onDuplicate() })
                    DropdownMenuItem(text = { Text("Export JSON") }, onClick = { menu = false; onExport() })
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; confirmDelete = true })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                Button(onClick = onRun) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Run")
                }
                OutlinedButton(onClick = onMarkers) { Text("Markers") }
                OutlinedButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Edit")
                }
            }
        }
    }
    if (confirmDelete) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete “${macro.name}”?") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
