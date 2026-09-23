package com.choice.autotap.license

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class ActivationCodeTest {

    /** Same vectors as the server's test suite (license-server/test/vectors.json). */
    private val vectors = Json.parseToJsonElement(File("../license-server/test/vectors.json").readText()).jsonObject

    @Test
    fun `matches the server algorithm byte for byte`() {
        for (g in vectors["generated"]!!.jsonArray) {
            val bytes = g.jsonObject["bytes"]!!.jsonArray.map { it.jsonPrimitive.int.toByte() }.toByteArray()
            assertEquals(g.jsonObject["code"]!!.jsonPrimitive.content, ActivationCode.fromBytes(bytes))
        }
        for (v in vectors["valid"]!!.jsonArray) {
            assertEquals(v.jsonObject["body"]!!.jsonPrimitive.content, ActivationCode.normalize(v.jsonObject["input"]!!.jsonPrimitive.content))
        }
        for (bad in vectors["invalid"]!!.jsonArray) {
            assertNull(bad.jsonPrimitive.content, ActivationCode.normalize(bad.jsonPrimitive.content))
        }
    }

    @Test
    fun `malformed codes and single typos are rejected locally`() {
        val code = ActivationCode.fromBytes(ByteArray(11) { (it * 37 + 5).toByte() })
        val body = ActivationCode.normalize(code)!!
        for (pos in body.indices) for (c in ActivationCode.ALPHABET) {
            if (c == body[pos]) continue
            assertNull(ActivationCode.normalize(body.substring(0, pos) + c + body.substring(pos + 1)))
        }
        assertNull(ActivationCode.canonical("CAT-1234"))
        assertNull(ActivationCode.canonical("hello world"))
    }

    @Test
    fun `canonical form normalizes case spaces and look-alike letters`() {
        val code = ActivationCode.fromBytes(ByteArray(11) { 0 }) // CAT-0000-0000-0000
        assertEquals(code, ActivationCode.canonical(" cat oooo-0000 OOOO "))
    }

    @Test
    fun `formatting while typing inserts dashes and caps length`() {
        assertEquals("", ActivationCode.formatWhileTyping(""))
        assertEquals("CAT-7K4P", ActivationCode.formatWhileTyping("7k4p"))
        assertEquals("CAT-7K4P-X9", ActivationCode.formatWhileTyping("cat-7k4p x9"))
        assertEquals("CAT-7K4P-X92M-Q3TD", ActivationCode.formatWhileTyping("CAT-7K4P-X92M-Q3TD-EXTRA"))
    }
}
