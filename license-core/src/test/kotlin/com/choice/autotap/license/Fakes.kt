package com.choice.autotap.license

import java.util.UUID

class MemoryStore(private var id: String = UUID.randomUUID().toString()) : LicenseStore {
    var stored: StoredLicense? = null
    override fun installationId() = id
    override fun load() = stored
    override fun save(license: StoredLicense) { stored = license }
    override fun clear() { stored = null }
}

class FakeApi(private val signer: TestSigner, private val serverNow: () -> Long) : LicenseApi {
    var activationResult: ActivationResult? = null
    var heartbeatResult: HeartbeatResult? = null
    var activateCalls = 0
    var heartbeatCalls = 0
    var online = true

    override suspend fun activate(activationCode: String, installationHash: String): ActivationResult {
        activateCalls++
        if (!online) return ActivationResult.NetworkError
        return activationResult ?: ActivationResult.Success(signer.token("L1", installationHash, serverNow(), serverNow() + WEEK))
    }

    override suspend fun heartbeat(licenseToken: String, installationHash: String): HeartbeatResult {
        heartbeatCalls++
        if (!online) return HeartbeatResult.NetworkError
        return heartbeatResult ?: HeartbeatResult.Valid(signer.token("L1", installationHash, serverNow(), serverNow() + WEEK))
    }

    companion object {
        const val WEEK = 7L * 24 * 60 * 60 * 1000
    }
}
