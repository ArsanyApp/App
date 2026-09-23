package com.choice.autotap.license

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import java.net.HttpURLConnection
import java.net.URL

/**
 * End-to-end A–F with the real Kotlin client against the real Worker
 * (license-server/scripts/local-server.sh). Skipped when LICENSE_URL is not set.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class LicenseServerE2ETest {

    private val url = System.getenv("LICENSE_URL")
    private val adminToken = System.getenv("ADMIN_TOKEN")
    private val publicKey = System.getenv("LICENSE_PUBLIC_KEY")

    @Before
    fun requireServer() = assumeTrue("LICENSE_URL not set", !url.isNullOrBlank())

    private fun admin(method: String, path: String, body: String? = null): String {
        val conn = URL(url + path).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.setRequestProperty("authorization", "Bearer $adminToken")
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("content-type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray()) }
        }
        check(conn.responseCode == 200) { "admin $path -> ${conn.responseCode}" }
        return conn.inputStream.readBytes().toString(Charsets.UTF_8)
    }

    private fun newLicense(): Pair<String, String> {
        val l = Json.parseToJsonElement(admin("POST", "/api/admin/licenses", """{"count":1,"note":"kotlin e2e"}"""))
            .jsonObject["licenses"]!!.jsonArray[0].jsonObject
        return l["id"]!!.jsonPrimitive.content to l["activationCode"]!!.jsonPrimitive.content
    }

    private fun controller(store: LicenseStore, api: LicenseApi = HttpLicenseApi(url, allowInsecureHttp = true), now: () -> Long = System::currentTimeMillis) =
        LicenseController(store, api, LicenseTokenVerifier(publicKey), now, Dispatchers.IO)

    @Test
    fun `full flow A to F`() = runBlocking {
        val phoneA = MemoryStore()
        val phoneB = MemoryStore()
        val (idA, codeA) = newLicense()

        // A: activate installation A
        val a = controller(phoneA)
        assertEquals(LicenseState.ACTIVE, a.activate(codeA).state)

        // B: same code on installation B
        val b = controller(phoneB)
        val rejected = b.activate(codeA)
        assertEquals(LicenseState.ACTIVATION_ERROR, rejected.state)
        assertEquals(ActivationError.ALREADY_USED, rejected.error)

        // C: installation A revalidates (forced heartbeat)
        assertEquals(LicenseState.ACTIVE, a.refreshIfDue(force = true).state)

        // F: network disabled after activation -> still usable within the grace window, blocked after it
        var fakeNow = System.currentTimeMillis()
        val offline = controller(phoneA, HttpLicenseApi("http://127.0.0.1:9", allowInsecureHttp = true)) { fakeNow }
        fakeNow += 3L * 24 * 60 * 60 * 1000
        assertEquals(LicenseState.OFFLINE_GRACE, offline.refreshIfDue(force = true).state)
        fakeNow += 5L * 24 * 60 * 60 * 1000
        assertEquals(LicenseState.VERIFICATION_REQUIRED, offline.refreshIfDue(force = true).state)
        // Back online: recovers.
        assertEquals(LicenseState.ACTIVE, a.refreshIfDue(force = true).state)

        // D: revoke A -> heartbeat -> REVOKED
        admin("POST", "/api/license/revoke", """{"id":"$idA"}""")
        assertEquals(LicenseState.REVOKED, a.refreshIfDue(force = true).state)

        // E: new license B activates installation B
        val (_, codeB) = newLicense()
        b.dismissError()
        assertEquals(LicenseState.ACTIVE, b.activate(codeB).state)

        // Unknown and malformed codes
        val c = controller(MemoryStore())
        assertEquals(ActivationError.INVALID_CODE, c.activate("CAT-0000-0000-0000").error)
        assertEquals(ActivationError.MALFORMED_CODE, c.activate("CAT-12").error)
    }

    @Test
    fun `https is required unless explicitly allowed`() {
        assert(!HttpLicenseApi("http://example.com").isConfigured)
        assert(HttpLicenseApi("https://example.com").isConfigured)
    }
}
