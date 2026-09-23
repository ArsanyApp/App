package com.choice.autotap.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.choice.autotap.appGraph
import com.choice.autotap.data.SnippetEntity
import kotlinx.coroutines.launch

/** Saved texts that "Paste text" steps can insert. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SnippetsScreen(onBack: () -> Unit) {
    val graph = LocalContext.current.appGraph
    val scope = rememberCoroutineScope()
    val snippets by graph.snippets.snippets.collectAsStateWithLifecycle(initialValue = emptyList())
    var editing by remember { mutableStateOf<SnippetEntity?>(null) }

    Scaffold(
        topBar = { BackTopBar("Saved texts", onBack) },
        floatingActionButton = {
            FloatingActionButton(onClick = { editing = SnippetEntity(label = "", text = "") }) {
                Icon(Icons.Default.Add, contentDescription = "Add")
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (snippets.isEmpty()) item { Text("Save texts here (Arabic, emoji…) and insert them into “Paste text” steps.") }
            items(snippets, key = { it.id }) { s ->
                Card(onClick = { editing = s }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.label, style = MaterialTheme.typography.titleSmall)
                            Text(s.text, maxLines = 2, style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content))
                        }
                        IconButton(onClick = { scope.launch { graph.snippets.delete(s.id) } }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete")
                        }
                    }
                }
            }
        }
    }

    editing?.let { s ->
        var label by remember(s) { mutableStateOf(s.label) }
        var text by remember(s) { mutableStateOf(s.text) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(if (s.id == 0L) "New saved text" else "Edit saved text") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = label, onValueChange = { label = it }, label = { Text("Name") }, singleLine = true)
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text("Text") },
                        minLines = 3,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val toSave = s.copy(label = label.ifBlank { text.take(20) }, text = text)
                    editing = null
                    scope.launch { graph.snippets.save(toSave) }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
}
