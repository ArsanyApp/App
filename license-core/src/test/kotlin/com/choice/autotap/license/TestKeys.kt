package com.choice.autotap.license

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/** Test-only signer that produces tokens exactly like the Worker (P1363 r||s signatures). */
class TestSigner(val keyPair: KeyPair = generate()) {
    val publicKeyB64: String = Base64.getEncoder().encodeToString(keyPair.public.encoded)

    fun token(lid: String, ih: String, iat: Long, exp: Long, v: Int = 1): String {
        val payload = b64u("""{"v":$v,"lid":"$lid","ih":"$ih","iat":$iat,"exp":$exp}""".toByteArray())
        val sig = Signature.getInstance("SHA256withECDSA").run {
            initSign(keyPair.private)
            update(payload.toByteArray())
            sign()
        }
        return "$payload.${b64u(derToRaw(sig))}"
    }

    companion object {
        fun generate(): KeyPair = KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }

        fun b64u(b: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(b)

        fun derToRaw(der: ByteArray): ByteArray {
            var i = 2
            fun readInt(): ByteArray {
                check(der[i].toInt() == 0x02); val len = der[i + 1].toInt(); val v = der.copyOfRange(i + 2, i + 2 + len); i += 2 + len
                val trimmed = v.dropWhile { it.toInt() == 0 }.toByteArray()
                return ByteArray(32 - trimmed.size) + trimmed
            }
            return readInt() + readInt()
        }
    }
}
