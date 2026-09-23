package com.choice.autotap.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.choice.autotap.service.AutoTapBridge

/** Onboarding: checks every permission and deep-links to the right settings page. */
@Composable
fun SetupScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    val service by AutoTapBridge.service.collectAsStateWithLifecycle()
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }

    // Reading `refresh` makes these re-evaluate whenever the screen resumes.
    val accessibility = refresh >= 0 && Permissions.isAccessibilityEnabled(context)
    val overlay = refresh >= 0 && Permissions.canDrawOverlays(context)
    val battery = refresh >= 0 && Permissions.isIgnoringBatteryOptimizations(context)
    val notifications = refresh >= 0 && Permissions.hasNotificationPermission(context)

    Scaffold(topBar = { BackTopBar("Permissions & setup", onBack) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SetupItem(
                title = "1. Accessibility service",
                done = accessibility,
                description = "Required. Lets the app tap, swipe, type and show its floating controls in any app. " +
                    "Settings → Accessibility → Installed apps → Choice Auto Tap → On." +
                    (if (accessibility && service == null) "\nEnabled, but not connected yet — toggle it off and on again." else ""),
                action = "Open accessibility settings",
                onAction = { Permissions.openAccessibilitySettings(context) },
                secondaryAction = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) "App info (allow restricted settings)" else null,
                onSecondary = { Permissions.openAppInfo(context) },
                hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !accessibility) {
                    "Android 13+: if the switch is greyed out (“Restricted setting”), open App info → ⋮ → “Allow restricted settings”, then try again."
                } else {
                    null
                },
            )
            SetupItem(
                title = "2. Display over other apps",
                done = overlay,
                description = "Recommended. Lets the app keep its playback service in the foreground when a run is started from the floating bubble over another app.",
                action = "Open overlay settings",
                onAction = { Permissions.openOverlaySettings(context) },
            )
            SetupItem(
                title = "3. Battery optimization exemption",
                done = battery,
                description = "Recommended. Stops Android from killing long or infinite runs.",
                action = "Allow unrestricted battery",
                onAction = { Permissions.requestIgnoreBatteryOptimizations(context) },
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                SetupItem(
                    title = "4. Notifications",
                    done = notifications,
                    description = "Recommended. Shows run progress with an emergency STOP button.",
                    action = "Allow notifications",
                    onAction = { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                )
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Emergency stop", fontWeight = FontWeight.Bold)
                    Text("• Press volume-down 3 times quickly\n• Tap STOP in the notification\n• Tap ■ on the floating bubble")
                }
            }
        }
    }
}

@Composable
private fun SetupItem(
    title: String,
    done: Boolean,
    description: String,
    action: String,
    onAction: () -> Unit,
    secondaryAction: String? = null,
    onSecondary: () -> Unit = {},
    hint: String? = null,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(
                    if (done) "✓ Done" else "Not set",
                    color = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
            Text(description, style = MaterialTheme.typography.bodyMedium)
            hint?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!done) Button(onClick = onAction) { Text(action) } else OutlinedButton(onClick = onAction) { Text(action) }
            }
            secondaryAction?.let { OutlinedButton(onClick = onSecondary) { Text(it) } }
        }
    }
}
