package com.choice.autotap

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.choice.autotap.license.ActivationResult
import com.choice.autotap.license.HttpLicenseApi
import com.choice.autotap.license.LicenseState
import com.choice.autotap.license.LicenseTags
import com.choice.autotap.ui.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Emulator end-to-end tests driven by .github/workflows/android.yml against a real local license
 * Worker (reachable from the emulator at http://10.0.2.2:8787). Each method is one step of the
 * scenario; the workflow runs them in order with `am instrument -e class ...#method -e code ...`.
 */
@RunWith(AndroidJUnit4::class)
class LicenseE2ETest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val args = InstrumentationRegistry.getArguments()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val license get() = (context.applicationContext as ChoiceApp).graph.license
    private val timeout = 20_000L

    private fun string(id: Int, vararg a: Any) = context.getString(id, *a)

    /** Explains a failed wait: license state + everything on screen. */
    private fun diagnostics(): String {
        val s = license.status.value
        val texts = runCatching {
            rule.onAllNodes(androidx.compose.ui.test.isRoot()).fetchSemanticsNodes().joinToString(" | ") { root ->
                root.children.flatMap { collectText(it) }.joinToString(" / ")
            }
        }.getOrDefault("?")
        return "license=${s.state} error=${s.error} retryAfter=${s.retryAfterSeconds} screen=[$texts]"
    }

    private fun collectText(node: androidx.compose.ui.semantics.SemanticsNode): List<String> {
        val own = node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.map { it.text }.orEmpty() +
            node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.EditableText)?.let { listOf("[${it.text}]") }.orEmpty()
        return own + node.children.flatMap { collectText(it) }
    }

    private fun waitOrExplain(what: String, condition: () -> Boolean) {
        try {
            rule.waitUntil(timeout, condition)
        } catch (e: Throwable) {
            throw AssertionError("Timed out waiting for $what. ${diagnostics()}", e)
        }
    }

    private fun waitForHome() = waitOrExplain("Home screen") {
        rule.onAllNodes(hasText("New macro", substring = true)).fetchSemanticsNodes().isNotEmpty()
    }

    private fun waitForTag(tag: String) = waitOrExplain(tag) {
        rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    }

    private fun enterCode(code: String) {
        waitForTag(LicenseTags.CODE_INPUT)
        rule.onNodeWithTag(LicenseTags.CODE_INPUT).performTextInput(code)
        rule.onNodeWithTag(LicenseTags.ACTIVATE_BUTTON).performClick()
    }

    /** Fresh install: the activation screen is shown instead of the app. */
    @Test
    fun showsActivationScreenWhenNotActivated() {
        waitForTag(LicenseTags.CODE_INPUT)
        assertEquals(LicenseState.NOT_ACTIVATED, license.status.value.state)
        assertTrue(rule.onAllNodes(hasText("New macro")).fetchSemanticsNodes().isEmpty())
    }

    /** Test A / E: a valid unused code activates and the existing Home screen appears. */
    @Test
    fun activateSucceeds() {
        enterCode(args.getString("code")!!)
        waitForHome()
        assertEquals(LicenseState.ACTIVE, license.status.value.state)
    }

    /** Test C: after a restart the app opens directly and a forced re-validation passes. */
    @Test
    fun activatedAppOpensWithoutCodeAndRevalidates() {
        waitForHome()
        val status = runBlocking { license.refreshIfDue(force = true) }
        assertEquals(LicenseState.ACTIVE, status.state)
        waitForHome()
    }

    /** Test F: server unreachable after activation -> the app stays usable (offline grace). */
    @Test
    fun staysUsableWhileOffline() {
        // Prove the license server really is unreachable from the device right now.
        val probe = runBlocking {
            HttpLicenseApi(BuildConfig.LICENSE_API_URL, BuildConfig.LICENSE_ALLOW_INSECURE).activate("CAT-0000-0000-0000", "0".repeat(64))
        }
        assertEquals(ActivationResult.NetworkError, probe)
        waitForHome()
        val status = runBlocking { license.refreshIfDue(force = true) }
        assertTrue("state=${status.state}", status.state.isUsable)
        waitForHome()
    }

    /** Test D: after the owner revokes, the next heartbeat locks the app with the revoked screen. */
    @Test
    fun revokedAfterHeartbeat() {
        val status = runBlocking { license.refreshIfDue(force = true) }
        assertEquals(LicenseState.REVOKED, status.state)
        waitForTag(LicenseTags.REVOKED)
        rule.onNodeWithTag(LicenseTags.REVOKED).assertExists()
        assertTrue(rule.onAllNodes(hasText("New macro")).fetchSemanticsNodes().isEmpty())
    }

    /** Test B: a code already activated elsewhere (or revoked) is rejected with a clear message. */
    @Test
    fun activationRejected() {
        enterCode(args.getString("code")!!)
        waitForTag(LicenseTags.ERROR)
        val expected = when (args.getString("expected")) {
            "ALREADY_USED" -> string(R.string.license_error_already_used)
            else -> string(R.string.license_error_invalid)
        }
        rule.onNodeWithTag(LicenseTags.ERROR).assert(hasText(expected))
        assertEquals(LicenseState.ACTIVATION_ERROR, license.status.value.state)
    }
}
