package com.choice.autotap.license

/** What the app shows / allows. */
enum class LicenseState {
    /** No license on this installation: show the activation screen. */
    NOT_ACTIVATED,

    /** Activation request in flight. */
    ACTIVATING,

    /** Verified with the server within [LicensePolicy.VERIFIED_FRESH_MS]. App fully usable. */
    ACTIVE,

    /** Server not reachable recently, but still inside the offline window. App fully usable. */
    OFFLINE_GRACE,

    /** Offline window used up (or the device clock was moved back): connect to the internet to continue. */
    VERIFICATION_REQUIRED,

    /** The owner revoked this license. */
    REVOKED,

    /** Last activation attempt failed; see [LicenseStatus.error]. */
    ACTIVATION_ERROR,
    ;

    val isUsable: Boolean get() = this == ACTIVE || this == OFFLINE_GRACE
}

enum class ActivationError { MALFORMED_CODE, INVALID_CODE, ALREADY_USED, RATE_LIMITED, NO_INTERNET, SERVER_ERROR, NOT_CONFIGURED }

data class LicenseStatus(
    val state: LicenseState,
    val error: ActivationError? = null,
    /** Device time until which offline use is allowed (for ACTIVE / OFFLINE_GRACE). */
    val offlineUntil: Long? = null,
    val retryAfterSeconds: Long? = null,
)

/** Everything persisted on the device (encrypted at rest by the Android store). */
data class StoredLicense(
    val token: String,
    /** Device time of the last successful server verification (activation or heartbeat). */
    val lastVerifiedAt: Long,
    /** Latest device time ever observed, to detect the clock being moved backwards. */
    val maxSeenAt: Long = lastVerifiedAt,
    val revoked: Boolean = false,
)

/**
 * Pure decision logic: given what is stored and the current device time, which state are we in?
 *
 * Offline grace: after each successful verification the app may run offline for the window the
 * server granted in the token (exp - iat = 7 days), measured on the device clock from the moment
 * of verification, so server/device clock differences don't matter. Moving the clock back by more
 * than [CLOCK_SKEW_MS] below the last verification or the latest time seen forces an online check.
 */
object LicensePolicy {
    /** Considered "freshly verified" (ACTIVE rather than OFFLINE_GRACE) for this long. */
    const val VERIFIED_FRESH_MS = 24L * 60 * 60 * 1000

    /** Background re-verification is attempted when the last one is older than this. */
    const val HEARTBEAT_INTERVAL_MS = 12L * 60 * 60 * 1000

    const val CLOCK_SKEW_MS = 10L * 60 * 1000

    fun evaluate(stored: StoredLicense?, now: Long, verifier: LicenseTokenVerifier, installationHash: String): LicenseStatus {
        if (stored == null) return LicenseStatus(LicenseState.NOT_ACTIVATED)
        if (stored.revoked) return LicenseStatus(LicenseState.REVOKED)
        val claims = verifier.verify(stored.token)
        if (claims == null || claims.ih != installationHash) return LicenseStatus(LicenseState.NOT_ACTIVATED)
        val offlineUntil = stored.lastVerifiedAt + claims.offlineWindowMs
        val clockWentBack = now + CLOCK_SKEW_MS < stored.lastVerifiedAt || now + CLOCK_SKEW_MS < stored.maxSeenAt
        return when {
            clockWentBack -> LicenseStatus(LicenseState.VERIFICATION_REQUIRED)
            now - stored.lastVerifiedAt <= VERIFIED_FRESH_MS -> LicenseStatus(LicenseState.ACTIVE, offlineUntil = offlineUntil)
            now <= offlineUntil -> LicenseStatus(LicenseState.OFFLINE_GRACE, offlineUntil = offlineUntil)
            else -> LicenseStatus(LicenseState.VERIFICATION_REQUIRED)
        }
    }

    fun heartbeatDue(stored: StoredLicense, now: Long): Boolean =
        now - stored.lastVerifiedAt >= HEARTBEAT_INTERVAL_MS || now < stored.lastVerifiedAt
}
