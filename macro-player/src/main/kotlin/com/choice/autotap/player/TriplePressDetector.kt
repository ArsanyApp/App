package com.choice.autotap.player

/**
 * Detects [count] presses within [windowMs] (e.g. volume-down pressed 3 times = emergency stop).
 * Call [onPress] on every key-down; it returns true when the pattern completes.
 */
class TriplePressDetector(
    private val count: Int = 3,
    private val windowMs: Long = 1500,
) {
    private val presses = ArrayDeque<Long>()

    fun onPress(timeMs: Long): Boolean {
        presses.addLast(timeMs)
        while (presses.isNotEmpty() && timeMs - presses.first() > windowMs) presses.removeFirst()
        if (presses.size >= count) {
            presses.clear()
            return true
        }
        return false
    }

    fun reset() = presses.clear()
}
