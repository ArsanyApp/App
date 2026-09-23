package com.choice.autotap.model

/** A click captured by the accessibility service while recording. Bounds are in screen pixels. */
data class RecordedClick(
    val timestampMs: Long,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val isLongClick: Boolean,
    val packageName: String? = null,
)

/**
 * Converts recorded click events into macro steps. Each click becomes a tap / long-press at the
 * center of the clicked node's bounds; the time until the next click becomes the step's delay.
 */
object RecordingConverter {

    const val LAST_STEP_DELAY_MS = 500L
    const val MIN_DELAY_MS = 100L

    fun convert(clicks: List<RecordedClick>, screen: ScreenSize, longPressMs: Long = StepAction.DEFAULT_LONG_PRESS_MS): List<MacroStep> {
        val sorted = clicks.sortedBy { it.timestampMs }
        return sorted.mapIndexed { i, c ->
            val center = CoordinateScaler.toNormalized(
                (c.left + c.right) / 2f,
                (c.top + c.bottom) / 2f,
                screen,
            )
            val action: StepAction =
                if (c.isLongClick) StepAction.LongPress(center, longPressMs) else StepAction.Tap(center)
            val gestureMs = if (c.isLongClick) longPressMs else 0L
            val delay = sorted.getOrNull(i + 1)
                ?.let { next -> (next.timestampMs - c.timestampMs - gestureMs).coerceAtLeast(MIN_DELAY_MS) }
                ?: LAST_STEP_DELAY_MS
            MacroStep(action = action, delayAfterMs = delay, note = c.packageName.orEmpty())
        }
    }
}
