package com.choice.autotap.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ParseRangeTest {
    @Test
    fun parses() {
        assertEquals(1500L to 1500L, MarkerEditor.parseRange("1500"))
        assertEquals(1000L to 3000L, MarkerEditor.parseRange("1000-3000"))
        assertEquals(1000L to 3000L, MarkerEditor.parseRange("3000 – 1000"))
        assertNull(MarkerEditor.parseRange("abc"))
        assertNull(MarkerEditor.parseRange(""))
    }
}
