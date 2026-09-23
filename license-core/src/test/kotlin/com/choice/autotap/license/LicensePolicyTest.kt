package com.choice.autotap.license

import org.junit.Assert.assertEquals
import org.junit.Test

/** Offline grace behavior (test 9). */
class LicensePolicyTest {

    private val signer = TestSigner()
    private val verifier = LicenseTokenVerifier(signer.publicKeyB64)
    private val ih = InstallationHash.of("install-1")
    private val hour = 60L * 60 * 1000
    private val day = 24 * hour
    private val t0 = 1_800_000_000_000L
    // Server clock deliberately differs from the device clock: only exp - iat (7 days) matters.
    private val token = signer.token("L1", ih, iat = t0 + 5 * hour, exp = t0 + 5 * hour + 7 * day)
    private val stored = StoredLicense(token, lastVerifiedAt = t0)

    private fun state(now: Long, s: StoredLicense? = stored, installation: String = ih) =
        LicensePolicy.evaluate(s, now, verifier, installation).state

    @Test
    fun `nothing stored means not activated`() {
        assertEquals(LicenseState.NOT_ACTIVATED, state(t0, null))
    }

    @Test
    fun `fresh verification is ACTIVE, then OFFLINE_GRACE, then VERIFICATION_REQUIRED after 7 days`() {
        assertEquals(LicenseState.ACTIVE, state(t0))
        assertEquals(LicenseState.ACTIVE, state(t0 + 23 * hour))
        assertEquals(LicenseState.OFFLINE_GRACE, state(t0 + 25 * hour))
        assertEquals(LicenseState.OFFLINE_GRACE, state(t0 + 7 * day))
        assertEquals(LicenseState.VERIFICATION_REQUIRED, state(t0 + 7 * day + 1))
        assertEquals(LicenseState.VERIFICATION_REQUIRED, state(t0 + 30 * day))
    }

    @Test
    fun `offline grace is usable, verification-required is not`() {
        assert(LicenseState.OFFLINE_GRACE.isUsable)
        assert(LicenseState.ACTIVE.isUsable)
        assert(!LicenseState.VERIFICATION_REQUIRED.isUsable)
        assert(!LicenseState.REVOKED.isUsable)
        assert(!LicenseState.NOT_ACTIVATED.isUsable)
    }

    @Test
    fun `moving the clock back forces an online check`() {
        assertEquals(LicenseState.VERIFICATION_REQUIRED, state(t0 - day))
        val seenLater = stored.copy(maxSeenAt = t0 + 5 * day)
        assertEquals(LicenseState.VERIFICATION_REQUIRED, state(t0 + 2 * day, seenLater))
        assertEquals(LicenseState.OFFLINE_GRACE, state(t0 + 5 * day, seenLater))
    }

    @Test
    fun `revoked flag, forged token and copied token from another installation are not usable`() {
        assertEquals(LicenseState.REVOKED, state(t0, stored.copy(revoked = true)))
        assertEquals(LicenseState.NOT_ACTIVATED, state(t0, stored.copy(token = TestSigner().token("L1", ih, t0, t0 + 7 * day))))
        assertEquals(LicenseState.NOT_ACTIVATED, state(t0, installation = InstallationHash.of("other-phone")))
    }

    @Test
    fun `heartbeat becomes due after 12 hours`() {
        assert(!LicensePolicy.heartbeatDue(stored, t0 + 11 * hour))
        assert(LicensePolicy.heartbeatDue(stored, t0 + 12 * hour))
        assert(LicensePolicy.heartbeatDue(stored, t0 - hour))
    }
}
