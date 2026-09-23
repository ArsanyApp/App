package com.choice.autotap.license

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.choice.autotap.R
import kotlinx.coroutines.launch

object LicenseTags {
    const val CODE_INPUT = "activation_code_input"
    const val ACTIVATE_BUTTON = "activate_button"
    const val ERROR = "activation_error"
    const val REVOKED = "license_revoked"
    const val VERIFY = "license_verification_required"
}

/**
 * Shows [content] (the existing app) only while the license is usable; otherwise the activation,
 * revoked or "connect to verify" screen. Re-checks on every resume (heartbeat at most every 12 h).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun LicenseGate(controller: LicenseController, content: @Composable () -> Unit) {
    val status by controller.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var enteringNewCode by rememberSaveable { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        controller.reevaluate()
        scope.launch { controller.refreshIfDue() }
    }

    if (status.state.isUsable) {
        if (enteringNewCode) LaunchedEffect(Unit) { enteringNewCode = false }
        content()
        return
    }
    Surface(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }, color = MaterialTheme.colorScheme.background) {
        when {
            status.state == LicenseState.REVOKED && !enteringNewCode ->
                RevokedScreen(onEnterNewCode = { enteringNewCode = true })
            status.state == LicenseState.VERIFICATION_REQUIRED ->
                VerificationRequiredScreen(onRetry = { scope.launch { controller.refreshIfDue(force = true) } })
            else -> ActivationScreen(
                status = status,
                onActivate = { code -> scope.launch { controller.activate(code) } },
                onEdit = { controller.dismissError() },
                onBack = if (enteringNewCode) ({ enteringNewCode = false; controller.dismissError() }) else null,
            )
        }
    }
}

@Composable
private fun CenteredColumn(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) { content() }
    }
}

@Composable
private fun Title(sub: String) {
    Text(
        stringResource(R.string.license_app_title),
        style = MaterialTheme.typography.titleLarge.copy(letterSpacing = 2.sp, fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.primary,
    )
    Text(sub, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
}

@Composable
fun ActivationScreen(
    status: LicenseStatus,
    onActivate: (String) -> Unit,
    onEdit: () -> Unit,
    onBack: (() -> Unit)?,
) {
    var code by rememberSaveable { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    val loading = status.state == LicenseState.ACTIVATING
    val complete = ActivationCode.canonical(code) != null
    var triedMalformed by remember { mutableStateOf(false) }

    fun submit() {
        if (loading) return
        if (!complete) {
            triedMalformed = true
            return
        }
        onActivate(code)
    }

    fun edit(value: String) {
        code = ActivationCode.formatWhileTyping(value)
        triedMalformed = false
        onEdit()
    }

    CenteredColumn {
        Title(stringResource(R.string.license_activate_title))
        Text(stringResource(R.string.license_enter_code), style = MaterialTheme.typography.bodyLarge)
        OutlinedTextField(
            value = code,
            onValueChange = ::edit,
            enabled = !loading,
            singleLine = true,
            placeholder = { Text(stringResource(R.string.license_code_placeholder), fontFamily = FontFamily.Monospace) },
            // Codes are Latin: keep them left-to-right even in Arabic.
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 20.sp, textDirection = TextDirection.Ltr),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.fillMaxWidth().testTag(LicenseTags.CODE_INPUT),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { clipboard.getText()?.text?.let(::edit) },
                enabled = !loading,
            ) { Text(stringResource(R.string.license_paste)) }
            Button(
                onClick = ::submit,
                enabled = !loading && code.isNotBlank(),
                modifier = Modifier.testTag(LicenseTags.ACTIVATE_BUTTON),
            ) {
                if (loading) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.license_activating))
                } else {
                    Text(stringResource(R.string.license_activate_button))
                }
            }
        }
        val error = when {
            triedMalformed -> stringResource(R.string.license_error_malformed)
            status.state == LicenseState.ACTIVATION_ERROR -> errorText(status)
            else -> null
        }
        if (error != null) {
            Text(
                error,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag(LicenseTags.ERROR),
            )
        }
        if (onBack != null) TextButton(onClick = onBack) { Text(stringResource(R.string.license_back)) }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun errorText(status: LicenseStatus): String = when (status.error) {
    ActivationError.MALFORMED_CODE -> stringResource(R.string.license_error_malformed)
    ActivationError.INVALID_CODE -> stringResource(R.string.license_error_invalid)
    ActivationError.ALREADY_USED -> stringResource(R.string.license_error_already_used)
    ActivationError.RATE_LIMITED -> stringResource(R.string.license_error_rate_limited, (status.retryAfterSeconds ?: 60).toInt())
    ActivationError.NO_INTERNET -> stringResource(R.string.license_error_no_internet)
    ActivationError.NOT_CONFIGURED -> stringResource(R.string.license_error_not_configured)
    ActivationError.SERVER_ERROR, null -> stringResource(R.string.license_error_server)
}

@Composable
fun RevokedScreen(onEnterNewCode: () -> Unit) {
    CenteredColumn {
        Title(stringResource(R.string.license_revoked_title))
        Text(
            stringResource(R.string.license_revoked_body),
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag(LicenseTags.REVOKED),
        )
        OutlinedButton(onClick = onEnterNewCode) { Text(stringResource(R.string.license_enter_new_code)) }
    }
}

@Composable
fun VerificationRequiredScreen(onRetry: () -> Unit) {
    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    CenteredColumn {
        Title(stringResource(R.string.license_verify_title))
        Text(
            stringResource(R.string.license_verify_body),
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag(LicenseTags.VERIFY),
        )
        Button(
            onClick = {
                checking = true
                onRetry()
                scope.launch { kotlinx.coroutines.delay(1500); checking = false }
            },
            enabled = !checking,
        ) { Text(stringResource(if (checking) R.string.license_checking else R.string.license_retry)) }
    }
}
