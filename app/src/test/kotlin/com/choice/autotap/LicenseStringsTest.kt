package com.choice.autotap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Test 11: every licensing string exists in English and Arabic with the same placeholders. */
class LicenseStringsTest {

    private fun strings(path: String): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(path))
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).associate { i ->
            val n = nodes.item(i)
            n.attributes.getNamedItem("name").nodeValue to n.textContent
        }.filterKeys { it.startsWith("license_") }
    }

    private val en = strings("src/main/res/values/strings.xml")
    private val ar = strings("src/main/res/values-ar/strings.xml")
    private val placeholder = Regex("%\\d+\\$[sd]")

    @Test
    fun `english and arabic define the same licensing strings`() {
        assertTrue(en.size >= 20)
        assertEquals(en.keys, ar.keys)
    }

    @Test
    fun `placeholders match and no string is empty`() {
        for ((key, value) in en) {
            assertTrue(key, value.isNotBlank() && ar.getValue(key).isNotBlank())
            assertEquals(key, placeholder.findAll(value).map { it.value }.toList(), placeholder.findAll(ar.getValue(key)).map { it.value }.toList())
        }
    }

    @Test
    fun `arabic strings are actually arabic, except the brand and code format`() {
        val latinOnly = setOf("license_app_title", "license_code_placeholder")
        for ((key, value) in ar) {
            if (key in latinOnly) continue
            assertTrue("$key should contain Arabic script: $value", value.any { it in '؀'..'ۿ' })
        }
    }

    @Test
    fun `required user-facing messages are present`() {
        assertEquals("Activation code already used.", en["license_error_already_used"])
        assertEquals("License revoked", en["license_revoked_title"])
        assertEquals("Please contact the software owner.", en["license_revoked_body"])
    }
}
