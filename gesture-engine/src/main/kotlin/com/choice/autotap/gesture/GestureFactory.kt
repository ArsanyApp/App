package com.choice.autotap.gesture

import android.accessibilityservice.GestureDescription
import android.graphics.Path
import com.choice.autotap.model.PixelPoint

/** Builds [GestureDescription]s for taps, long-presses, double taps and swipes. */
object GestureFactory {

    const val TAP_DURATION_MS = 60L

    fun tap(p: PixelPoint): GestureDescription = single(p, TAP_DURATION_MS)

    fun longPress(p: PixelPoint, durationMs: Long): GestureDescription = single(p, clampDuration(durationMs))

    /** Two strokes on one timeline: tap, gap of [intervalMs], tap. */
    fun doubleTap(p: PixelPoint, intervalMs: Long): GestureDescription {
        val path = pointPath(p)
        return GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, TAP_DURATION_MS))
            .addStroke(GestureDescription.StrokeDescription(path, TAP_DURATION_MS + intervalMs.coerceAtLeast(1), TAP_DURATION_MS))
            .build()
    }

    fun swipe(start: PixelPoint, end: PixelPoint, durationMs: Long): GestureDescription {
        val path = Path().apply {
            moveTo(start.x, start.y)
            lineTo(end.x, end.y)
        }
        return GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, clampDuration(durationMs)))
            .build()
    }

    /** Total time the gesture takes, used to size the dispatch timeout. */
    fun durationOf(gesture: GestureDescription): Long {
        var end = 0L
        for (i in 0 until gesture.strokeCount) {
            val s = gesture.getStroke(i)
            end = maxOf(end, s.startTime + s.duration)
        }
        return end
    }

    private fun single(p: PixelPoint, durationMs: Long): GestureDescription =
        GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(pointPath(p), 0, durationMs))
            .build()

    private fun pointPath(p: PixelPoint) = Path().apply { moveTo(p.x, p.y) }

    private fun clampDuration(ms: Long): Long = ms.coerceIn(1, GestureDescription.getMaxGestureDuration() - 1)
}
