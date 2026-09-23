package com.choice.autotap.license

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** Claims signed by the server. Timestamps are server epoch milliseconds. */
@Serializable
data class LicenseClaims(
    val v: Int,
    /** License id. */
    val lid: String,
    /** Installation hash the license is bound to. */
    val ih: String,
    val iat: Long,
    /** End of the offline validity window. */
    val exp: Long,
) {
    /** Length of the offline window granted by the server (normally 7 days). */
    val offlineWindowMs: Long get() = (exp - iat).coerceAtLeast(0)
}

/**
 * Verifies license tokens: base64url(JSON claims) "." base64url(64-byte ECDSA P-256 r||s signature).
 * Holds only the server's PUBLIC key; the private key never leaves Cloudflare.
 */
class LicenseTokenVerifier(publicKeySpkiBase64: String) {

    private val publicKey: PublicKey? = runCatching {
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKeySpkiBase64.trim())))
    }.getOrNull()

    val isConfigured: Boolean get() = publicKey != null

    /** Returns the claims if the token's signature is authentic, otherwise null. Does not check expiry. */
    fun verify(token: String): LicenseClaims? {
        val key = publicKey ?: return null
        if (token.length > 4096) return null
        val parts = token.split('.')
        if (parts.size != 2) return null
        return runCatching {
            val raw = Base64.getUrlDecoder().decode(parts[1])
            if (raw.size != 64) return null
            val sig = Signature.getInstance("SHA256withECDSA")
            sig.initVerify(key)
            sig.update(parts[0].toByteArray(Charsets.US_ASCII))
            if (!sig.verify(rawToDer(raw))) return null
            val claims = json.decodeFromString(LicenseClaims.serializer(), String(Base64.getUrlDecoder().decode(parts[0]), Charsets.UTF_8))
            claims.takeIf { it.v == 1 && it.lid.isNotEmpty() && it.ih.isNotEmpty() }
        }.getOrNull()
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** WebCrypto emits r||s (IEEE P1363); Java's SHA256withECDSA expects ASN.1 DER. */
        fun rawToDer(raw: ByteArray): ByteArray {
            fun int(bytes: ByteArray): ByteArray = BigInteger(1, bytes).toByteArray()
            val r = int(raw.copyOfRange(0, 32))
            val s = int(raw.copyOfRange(32, 64))
            val body = byteArrayOf(0x02, r.size.toByte()) + r + byteArrayOf(0x02, s.size.toByte()) + s
            return byteArrayOf(0x30, body.size.toByte()) + body
        }
    }
}

/** Derives what the server sees from the locally generated random installation id. */
object InstallationHash {
    private const val DOMAIN = "choice-auto-tap/installation/v1:"

    fun of(rawInstallationId: String): String =
        MessageDigest.getInstance("SHA-256").digest((DOMAIN + rawInstallationId).toByteArray(Charsets.UTF_8))
            .joinToString("") { b -> HEX[(b.toInt() shr 4) and 15].toString() + HEX[b.toInt() and 15] }

    private const val HEX = "0123456789abcdef"
}
