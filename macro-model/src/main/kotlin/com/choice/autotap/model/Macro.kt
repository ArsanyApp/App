package com.choice.autotap.model

import kotlinx.serialization.Serializable

/** What to do when a "wait until text appears" step times out. */
@Serializable
enum class TimeoutPolicy {
    /** Abandon the rest of the current loop and continue with the next loop. */
    SKIP_LOOP,

    /** Restart the current loop from its first step (up to [Macro.timeoutRetries] times), then skip it. */
    RETRY_LOOP,

    /** Stop the whole run. */
    STOP,
}

/**
 * Optional per-loop coordinate shift. On loop k (0-based) every point of a step with
 * [MacroStep.applyLoopShift] is moved by (k * dxPx, k * dyPx) pixels. Useful for walking
 * down a list that does not reorder.
 */
@Serializable
data class LoopShift(
    val enabled: Boolean = false,
    val dxPx: Int = 0,
    val dyPx: Int = 0,
)

/**
 * @param repeatCount number of loops; 0 means infinite.
 * @param loopDelayMinMs / [loopDelayMaxMs] random pause between two loops.
 * @param stopAfterMs stop the run after this much wall time; 0 = no limit.
 * @param startDelayMs countdown before the first loop, so you can switch to the target app.
 */
@Serializable
data class LoopSettings(
    val repeatCount: Int = 1,
    val loopDelayMinMs: Long = 20_000,
    val loopDelayMaxMs: Long = 45_000,
    val stopAfterMs: Long = 0,
    val startDelayMs: Long = 3_000,
    val shift: LoopShift = LoopShift(),
) {
    val isInfinite: Boolean get() = repeatCount <= 0
}

@Serializable
data class Macro(
    val id: Long = 0,
    val name: String,
    val steps: List<MacroStep> = emptyList(),
    val loop: LoopSettings = LoopSettings(),
    val onWaitTimeout: TimeoutPolicy = TimeoutPolicy.SKIP_LOOP,
    val timeoutRetries: Int = 2,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)
