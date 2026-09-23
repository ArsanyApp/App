package com.choice.autotap.license

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LicenseControllerTest {

    private val signer = TestSigner()
    private var now = 1_800_000_000_000L
    private val hour = 60L * 60 * 1000
    private val store = MemoryStore()
    private val api = FakeApi(signer) { now }
    private val code = ActivationCode.fromBytes(ByteArray(11) { (it * 7).toByte() })

    private fun controller(dispatcher: kotlinx.coroutines.CoroutineDispatcher) =
        LicenseController(store, api, LicenseTokenVerifier(signer.publicKeyB64), { now }, dispatcher)

    @Test
    fun `activation succeeds, is remembered, and is not asked again`() = runTest {
        val c = controller(StandardTestDispatcher(testScheduler))
        assertEquals(LicenseState.NOT_ACTIVATED, c.status.value.state)
        assertEquals(LicenseState.ACTIVE, c.activate(code.lowercase()).state)
        // A new controller (app restart) reads the stored license.
        val restarted = controller(StandardTestDispatcher(testScheduler))
        assertEquals(LicenseState.ACTIVE, restarted.status.value.state)
        assertEquals(LicenseState.ACTIVE, restarted.activate(code).state)
        assertEquals(1, api.activateCalls)
    }

    @Test
    fun `malformed code is rejected without a network call`() = runTest {
        val c = controller(StandardTestDispatcher(testScheduler))
        val s = c.activate("CAT-1234")
        assertEquals(LicenseState.ACTIVATION_ERROR, s.state)
        assertEquals(ActivationError.MALFORMED_CODE, s.error)
        assertEquals(0, api.activateCalls)
        c.dismissError()
        assertEquals(LicenseState.NOT_ACTIVATED, c.status.value.state)
    }

    @Test
    fun `server errors map to clear activation errors`() = runTest {
        val c = controller(StandardTestDispatcher(testScheduler))
        val cases = mapOf(
            ActivationResult.AlreadyUsed to ActivationError.ALREADY_USED,
            ActivationResult.InvalidCode to ActivationError.INVALID_CODE,
            ActivationResult.RateLimited(120) to ActivationError.RATE_LIMITED,
            ActivationResult.NetworkError to ActivationError.NO_INTERNET,
            ActivationResult.ServerError to ActivationError.SERVER_ERROR,
        )
        for ((result, error) in cases) {
            api.activationResult = result
            assertEquals(error, c.activate(code).error)
        }
        assertEquals(120L, run { api.activationResult = ActivationResult.RateLimited(120); c.activate(code).retryAfterSeconds })
        assertEquals(null, store.stored)
    }

    @Test
    fun `a token for another installation or signed by another key is refused`() = runTest {
        val c = controller(StandardTestDispatcher(testScheduler))
        api.activationResult = ActivationResult.Success(signer.token("L1", InstallationHash.of("someone-else"), now, now + FakeApi.WEEK))
        assertEquals(ActivationError.SERVER_ERROR, c.activate(code).error)
        api.activationResult = ActivationResult.Success(TestSigner().token("L1", InstallationHash.of(store.installationId()), now, now + FakeApi.WEEK))
        assertEquals(ActivationError.SERVER_ERROR, c.activate(code).error)
    }

    @Test
    fun `rapid repeated submissions trigger a single request`() = runTest {
        val c = controller(StandardTestDispatcher(testScheduler))
        val results = (1..5).map { async { c.activate(code) } }.map { it.await() }
        assertEquals(1, api.activateCalls)
        assert(results.all { it.state == LicenseState.ACTIVE })
    }

    @Test
    fun `heartbeat is only sent when due, and renews the offline window`() = runTest {
        val c = controller(StandardTestDispatcher(testScheduler))
        c.activate(code)
        c.refreshIfDue()
        assertEquals(0, api.heartbeatCalls)
        now += 13 * hour
        assertEquals(LicenseState.ACTIVE, c.refreshIfDue().state)
        assertEquals(1, api.heartbeatCalls)
        assertEquals(now, store.stored!!.lastVerifiedAt)
    }

    @Test
    fun `offline grace keeps the app usable, then requires verification, then recovers online`() = runTest {
        val c = controller(StandardTestDispatcher(testScheduler))
        c.activate(code)
        api.online = false
        now += 2 * 24 * hour
        assertEquals(LicenseState.OFFLINE_GRACE, c.refreshIfDue().state)
        assert(c.isUsable)
        now += 6 * 24 * hour
        assertEquals(LicenseState.VERIFICATION_REQUIRED, c.refreshIfDue().state)
        assert(!c.isUsable)
        api.online = true
        assertEquals(LicenseState.ACTIVE, c.refreshIfDue().state)
    }

    @Test
    fun `revocation takes effect at the next successful heartbeat and sticks`() = runTest {
        val c = controller(StandardTestDispatcher(testScheduler))
        c.activate(code)
        api.heartbeatResult = HeartbeatResult.Revoked
        assertEquals(LicenseState.REVOKED, c.refreshIfDue(force = true).state)
        api.heartbeatResult = null
        assertEquals(LicenseState.REVOKED, controller(StandardTestDispatcher(testScheduler)).status.value.state)
        // A revoked code is refused by the server...
        api.activationResult = ActivationResult.InvalidCode
        assertEquals(ActivationError.INVALID_CODE, c.activate(code).error)
        assertEquals(LicenseState.REVOKED, controller(StandardTestDispatcher(testScheduler)).status.value.state)
        // ...but a new code from the owner re-licenses the same phone.
        api.activationResult = null
        assertEquals(LicenseState.ACTIVE, c.activate(ActivationCode.fromBytes(ByteArray(11) { 9 })).state)
    }

    @Test
    fun `a successful verification clears an earlier wrong clock`() = runTest {
        val c = controller(StandardTestDispatcher(testScheduler))
        c.activate(code)
        now += 10L * 24 * hour          // clock set far ahead...
        api.online = false
        c.refreshIfDue(force = true)
        now -= 10L * 24 * hour          // ...then corrected
        assertEquals(LicenseState.VERIFICATION_REQUIRED, c.refreshIfDue().state)
        api.online = true
        assertEquals(LicenseState.ACTIVE, c.refreshIfDue().state)
    }

    @Test
    fun `server not recognizing the installation clears the license`() = runTest {
        val c = controller(StandardTestDispatcher(testScheduler))
        c.activate(code)
        api.heartbeatResult = HeartbeatResult.Invalid
        assertEquals(LicenseState.NOT_ACTIVATED, c.refreshIfDue(force = true).state)
    }

    @Test
    fun `missing server configuration is reported, not crashed`() = runTest {
        val c = LicenseController(store, api, LicenseTokenVerifier(""), { now }, StandardTestDispatcher(testScheduler))
        assertEquals(ActivationError.NOT_CONFIGURED, c.activate(code).error)
        assertEquals(0, api.activateCalls)
    }
}
