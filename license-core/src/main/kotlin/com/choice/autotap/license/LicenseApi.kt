package com.choice.autotap.license

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

sealed interface ActivationResult {
    data class Success(val licenseToken: String) : ActivationResult
    data object AlreadyUsed : ActivationResult
    data object InvalidCode : ActivationResult
    data class RateLimited(val retryAfterSeconds: Long) : ActivationResult
    data object NetworkError : ActivationResult
    data object ServerError : ActivationResult
}

sealed interface HeartbeatResult {
    data class Valid(val licenseToken: String) : HeartbeatResult
    data object Revoked : HeartbeatResult
    /** The server does not recognize this token/installation pairing. */
    data object Invalid : HeartbeatResult
    data object NetworkError : HeartbeatResult
    data object ServerError : HeartbeatResult
}

/** Talks to the license Worker. The app never talks to D1 or holds any server credential. */
interface LicenseApi {
    suspend fun activate(activationCode: String, installationHash: String): ActivationResult
    suspend fun heartbeat(licenseToken: String, installationHash: String): HeartbeatResult
}

/**
 * HttpURLConnection implementation (no extra dependency). Blocking: call from a background dispatcher.
 * Only https:// base URLs are accepted, unless [allowInsecureHttp] is set for local emulator tests.
 */
class HttpLicenseApi(
    baseUrl: String,
    allowInsecureHttp: Boolean = false,
    private val timeoutMs: Int = 15_000,
) : LicenseApi {

    private val base: String? = baseUrl.trim().trimEnd('/').takeIf {
        it.startsWith("https://") || (allowInsecureHttp && it.startsWith("http://"))
    }

    val isConfigured: Boolean get() = base != null

    @Serializable private data class ActivateRequest(val activationCode: String, val installationId: String)
    @Serializable private data class HeartbeatRequest(val licenseToken: String, val installationId: String)

    @Serializable
    private data class Response(
        val status: String? = null,
        val licenseToken: String? = null,
        val error: String? = null,
        val retryAfterSeconds: Long? = null,
    )

    override suspend fun activate(activationCode: String, installationHash: String): ActivationResult {
        val body = json.encodeToString(ActivateRequest.serializer(), ActivateRequest(activationCode, installationHash))
        val (code, res) = post("/api/license/activate", body) ?: return ActivationResult.NetworkError
        return when {
            code == 200 && res?.status == "ACTIVE" && res.licenseToken != null -> ActivationResult.Success(res.licenseToken)
            res?.error == "ALREADY_USED" -> ActivationResult.AlreadyUsed
            res?.error == "INVALID_CODE" -> ActivationResult.InvalidCode
            code == 429 || res?.error == "RATE_LIMITED" -> ActivationResult.RateLimited(res?.retryAfterSeconds ?: 60)
            else -> ActivationResult.ServerError
        }
    }

    override suspend fun heartbeat(licenseToken: String, installationHash: String): HeartbeatResult {
        val body = json.encodeToString(HeartbeatRequest.serializer(), HeartbeatRequest(licenseToken, installationHash))
        val (code, res) = post("/api/license/heartbeat", body) ?: return HeartbeatResult.NetworkError
        return when {
            code == 200 && res?.status == "VALID" && res.licenseToken != null -> HeartbeatResult.Valid(res.licenseToken)
            code == 200 && res?.status == "REVOKED" -> HeartbeatResult.Revoked
            code == 200 && res?.status == "INVALID" -> HeartbeatResult.Invalid
            else -> HeartbeatResult.ServerError
        }
    }

    /** Returns (HTTP status, parsed body), or null on a network failure. */
    private fun post(path: String, body: String): Pair<Int, Response?>? {
        val url = base ?: return null
        return try {
            val conn = URL(url + path).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = timeoutMs
                conn.readTimeout = timeoutMs
                conn.doOutput = true
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("content-type", "application/json")
                conn.setRequestProperty("accept", "application/json")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
                code to runCatching { json.decodeFromString(Response.serializer(), text) }.getOrNull()
            } finally {
                conn.disconnect()
            }
        } catch (e: IOException) {
            null
        }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
