package com.choice.autotap.license

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LicenseTokenTest {

    @Test
    fun `accepts tokens signed by WebCrypto exactly like the Worker`() {
        val fixture = Json.parseToJsonElement(javaClass.getResource("/webcrypto-tokens.json")!!.readText()).jsonObject
        val verifier = LicenseTokenVerifier(fixture["publicKey"]!!.jsonPrimitive.content)
        val ih = fixture["installationHash"]!!.jsonPrimitive.content
        assertEquals(ih, InstallationHash.of(fixture["installationId"]!!.jsonPrimitive.content))
        for (t in fixture["tokens"]!!.jsonArray) {
            val claims = verifier.verify(t.jsonPrimitive.content)
            assertNotNull(claims)
            assertEquals("fixture-license", claims!!.lid)
            assertEquals(ih, claims.ih)
            assertEquals(7L * 24 * 60 * 60 * 1000, claims.offlineWindowMs)
        }
    }

    @Test
    fun `rejects tampered, foreign, malformed and wrong-version tokens`() {
        val signer = TestSigner()
        val verifier = LicenseTokenVerifier(signer.publicKeyB64)
        val good = signer.token("L1", "ih", 1000, 2000)
        assertNotNull(verifier.verify(good))

        val (payload, sig) = good.split('.')
        val forgedPayload = TestSigner.b64u("""{"v":1,"lid":"L1","ih":"ih","iat":1000,"exp":999999999}""".toByteArray())
        assertNull(verifier.verify("$forgedPayload.$sig"))
        assertNull(verifier.verify(TestSigner().token("L1", "ih", 1000, 2000)))
        assertNull(verifier.verify("$payload."))
        assertNull(verifier.verify("garbage"))
        assertNull(verifier.verify(""))
        assertNull(verifier.verify(signer.token("L1", "ih", 1000, 2000, v = 2)))
    }

    @Test
    fun `an unconfigured or invalid public key verifies nothing`() {
        val signer = TestSigner()
        assertFalse(LicenseTokenVerifier("").isConfigured)
        assertNull(LicenseTokenVerifier("").verify(signer.token("L", "ih", 1, 2)))
        assertNull(LicenseTokenVerifier("bm90IGEga2V5").verify(signer.token("L", "ih", 1, 2)))
    }

    @Test
    fun `installation hash is a stable 64-char hex digest`() {
        val h = InstallationHash.of("abc")
        assertEquals(64, h.length)
        assertEquals(h, InstallationHash.of("abc"))
        assert(h.all { it in "0123456789abcdef" })
    }
}
