package com.choice.autotap.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.choice.autotap.appGraph
import com.choice.autotap.player.LoopOutcome
import com.choice.autotap.player.RunLog
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun LogsScreen(onBack: () -> Unit) {
    val graph = LocalContext.current.appGraph
    val scope = rememberCoroutineScope()
    val logs by graph.logs.recent.collectAsStateWithLifecycle(initialValue = emptyList())
    Scaffold(
        topBar = {
            BackTopBar("Run log", onBack) {
                TextButton(onClick = { scope.launch { graph.logs.clear() } }) { Text("Clear") }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (logs.isEmpty()) item { Text("No runs yet.") }
            items(logs) { log -> RunLogCard(log) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RunLogCard(log: RunLog) {
    var expanded by remember { mutableStateOf(false) }
    val time = DateFormat.getTimeInstance(DateFormat.MEDIUM)
    Card(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(log.macroName, fontWeight = FontWeight.Bold)
            Text("${formatTime(log.startedAt)} → ${time.format(Date(log.endedAt))}", style = MaterialTheme.typography.bodySmall)
            val timeouts = log.loops.sumOf { it.timeouts.size }
            Text(
                "${log.loops.size} loops · ended: ${log.endReason.name.lowercase().replace('_', ' ')}" +
                    (if (timeouts > 0) " · $timeouts text timeouts" else "") +
                    (log.errorMessage?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (expanded) {
                log.loops.forEach { l ->
                    val outcome = when (l.outcome) {
                        LoopOutcome.COMPLETED -> "done"
                        LoopOutcome.SKIPPED_AFTER_TIMEOUT -> "skipped"
                        LoopOutcome.STOPPED -> "stopped"
                    }
                    val extra = buildList {
                        if (l.timeouts.isNotEmpty()) add("timeout: " + l.timeouts.joinToString { "“$it”" })
                        if (l.retries > 0) add("${l.retries} retries")
                        if (l.failedActions > 0) add("${l.failedActions} failed gestures")
                    }.joinToString(" · ")
                    Text(
                        "#${l.loopNumber}  ${time.format(Date(l.startedAt))}–${time.format(Date(l.endedAt))}  " +
                            "${l.stepsDone}/${l.stepsTotal} steps · $outcome" + if (extra.isNotEmpty()) " · $extra" else "",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}
