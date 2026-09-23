package com.choice.autotap.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackTopBar(title: String, onBack: () -> Unit, actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {}) {
    TopAppBar(
        title = { Text(title, maxLines = 1) },
        navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        },
        actions = actions,
    )
}

/** Integer/long field that keeps the raw text while typing and reports valid values only. */
@Composable
fun LongField(label: String, value: Long, modifier: Modifier = Modifier, onChange: (Long) -> Unit) {
    var text by remember { mutableStateOf(value.toString()) }
    // Only overwrite what the user typed when the value changed from outside.
    LaunchedEffect(value) { if (text.trim().toLongOrNull() != value) text = value.toString() }
    OutlinedTextField(
        value = text,
        onValueChange = { t ->
            text = t
            t.trim().toLongOrNull()?.let(onChange)
        },
        label = { Text(label) },
        singleLine = true,
        isError = text.trim().toLongOrNull() == null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

@Composable
fun IntField(label: String, value: Int, modifier: Modifier = Modifier, onChange: (Int) -> Unit) =
    LongField(label, value.toLong(), modifier) { onChange(it.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()) }

/** Percentage (0–100) field backed by a 0..1 fraction. */
@Composable
fun PercentField(label: String, fraction: Float, modifier: Modifier = Modifier, onChange: (Float) -> Unit) {
    fun format(f: Float) = String.format(Locale.US, "%.2f", f * 100)
    fun parse(t: String) = t.trim().replace(',', '.').toFloatOrNull()?.let { (it / 100f).coerceIn(0f, 1f) }
    var text by remember { mutableStateOf(format(fraction)) }
    LaunchedEffect(fraction) {
        val current = parse(text)
        if (current == null || kotlin.math.abs(current - fraction) > 0.00005f) text = format(fraction)
    }
    OutlinedTextField(
        value = text,
        onValueChange = { t ->
            text = t
            parse(t)?.let(onChange)
        },
        label = { Text(label) },
        suffix = { Text("%") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

fun formatTime(ms: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(ms))

fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
