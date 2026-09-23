package com.choice.autotap.license

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Device storage. The Android implementation encrypts everything with an Android Keystore key. */
interface LicenseStore {
    /** Random id created on first use of this installation; stable until the app data is cleared. */
    fun installationId(): String
    fun load(): StoredLicense?
    fun save(license: StoredLicense)
    fun clear()
}

/**
 * Orchestrates activation and periodic verification. Independent of the macro engine: the app only
 * reads [status] and calls [activate] / [refreshIfDue].
 */
class LicenseController(
    private val store: LicenseStore,
    private val api: LicenseApi,
    private val verifier: LicenseTokenVerifier,
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val configured: Boolean = true,
) {
    private val mutex = Mutex()
    private val installationHash: String by lazy { InstallationHash.of(store.installationId()) }

    private val _status = MutableStateFlow(evaluate())
    val status: StateFlow<LicenseStatus> = _status.asStateFlow()

    val isUsable: Boolean get() = _status.value.state.isUsable

    /** Re-reads storage and the clock (e.g. when the app comes to the foreground). */
    fun reevaluate(): LicenseStatus = evaluate().also { update(it) }

    /**
     * Activates with [input]. Also used after a revocation: the owner can hand out a new code and the
     * server decides whether it is accepted for this installation.
     */
    suspend fun activate(input: String): LicenseStatus = mutex.withLock {
        if (_status.value.state.isUsable) return@withLock _status.value
        if (!configured || !verifier.isConfigured) return@withLock fail(ActivationError.NOT_CONFIGURED)
        val code = ActivationCode.canonical(input) ?: return@withLock fail(ActivationError.MALFORMED_CODE)
        _status.value = LicenseStatus(LicenseState.ACTIVATING)
        when (val r = withContext(io) { api.activate(code, installationHash) }) {
            is ActivationResult.Success -> {
                val claims = verifier.verify(r.licenseToken)
                if (claims == null || claims.ih != installationHash) return@withLock fail(ActivationError.SERVER_ERROR)
                val now = clock()
                store.save(StoredLicense(r.licenseToken, lastVerifiedAt = now, maxSeenAt = now))
                evaluate().also { update(it) }
            }
            ActivationResult.AlreadyUsed -> fail(ActivationError.ALREADY_USED)
            ActivationResult.InvalidCode -> fail(ActivationError.INVALID_CODE)
            is ActivationResult.RateLimited -> fail(ActivationError.RATE_LIMITED, r.retryAfterSeconds)
            ActivationResult.NetworkError -> fail(ActivationError.NO_INTERNET)
            ActivationResult.ServerError -> fail(ActivationError.SERVER_ERROR)
        }
    }

    /** Contacts the server if the last verification is older than the heartbeat interval (or [force]). */
    suspend fun refreshIfDue(force: Boolean = false): LicenseStatus = mutex.withLock {
        val stored = store.load() ?: return@withLock evaluate().also { update(it) }
        if (stored.revoked) return@withLock evaluate().also { update(it) }
        val now = clock()
        if (now > stored.maxSeenAt) store.save(stored.copy(maxSeenAt = now))
        if (!force && !LicensePolicy.heartbeatDue(stored, now) && evaluate().state == LicenseState.ACTIVE) {
            return@withLock evaluate().also { update(it) }
        }
        when (val r = withContext(io) { api.heartbeat(stored.token, installationHash) }) {
            is HeartbeatResult.Valid -> {
                val claims = verifier.verify(r.licenseToken)
                if (claims != null && claims.ih == installationHash) {
                    val t = clock()
                    // The server just vouched for the license: restart clock-rollback tracking from now.
                    store.save(StoredLicense(r.licenseToken, lastVerifiedAt = t, maxSeenAt = t))
                }
            }
            HeartbeatResult.Revoked -> store.save(stored.copy(revoked = true))
            // Server no longer recognizes this installation (e.g. data restored elsewhere): start over.
            HeartbeatResult.Invalid -> store.clear()
            HeartbeatResult.NetworkError, HeartbeatResult.ServerError -> Unit // keep offline grace
        }
        evaluate().also { update(it) }
    }

    /** Leaves the error state and returns to the activation form. */
    fun dismissError() {
        if (_status.value.state == LicenseState.ACTIVATION_ERROR) update(evaluate())
    }

    private fun fail(error: ActivationError, retryAfter: Long? = null): LicenseStatus =
        LicenseStatus(LicenseState.ACTIVATION_ERROR, error, retryAfterSeconds = retryAfter).also { update(it) }

    private fun update(s: LicenseStatus) {
        _status.value = s
    }

    private fun evaluate(): LicenseStatus = LicensePolicy.evaluate(store.load(), clock(), verifier, installationHash)
}
