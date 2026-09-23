package com.choice.autotap.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TriplePressDetectorTest {

    @Test
    fun `three quick presses trigger`() {
        val d = TriplePressDetector()
        assertFalse(d.onPress(0))
        assertFalse(d.onPress(300))
        assertTrue(d.onPress(600))
        // Counter resets after triggering.
        assertFalse(d.onPress(700))
    }

    @Test
    fun `slow presses do not trigger`() {
        val d = TriplePressDetector(windowMs = 1500)
        assertFalse(d.onPress(0))
        assertFalse(d.onPress(1000))
        assertFalse(d.onPress(2000))
        assertTrue(d.onPress(2400))
    }
}
