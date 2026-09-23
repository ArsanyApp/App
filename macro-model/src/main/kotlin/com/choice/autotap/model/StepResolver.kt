package com.choice.autotap.model

import kotlin.random.Random

/** A step turned into concrete pixels / milliseconds for one specific execution. */
sealed interface ResolvedAction {
    data class Tap(val point: PixelPoint) : ResolvedAction
    data class LongPress(val point: PixelPoint, val durationMs: Long) : ResolvedAction
    data class DoubleTap(val point: PixelPoint, val intervalMs: Long) : ResolvedAction
    data class Swipe(val start: PixelPoint, val end: PixelPoint, val durationMs: Long) : ResolvedAction
    data class Wait(val durationMs: Long) : ResolvedAction
    data class Global(val action: GlobalActionType) : ResolvedAction
    data class PasteText(
        val text: String,
        val mode: PasteMode,
        val target: PixelPoint?,
        val append: Boolean,
        val longPressMs: Long,
        val popupLabels: List<String>,
        val popupPoint: PixelPoint?,
        val popupTimeoutMs: Long,
    ) : ResolvedAction
    data class LaunchApp(val packageName: String) : ResolvedAction
    data class WaitForText(val text: String, val timeoutMs: Long, val exactMatch: Boolean, val pollIntervalMs: Long) :
        ResolvedAction
}

/**
 * Turns a [MacroStep] into a [ResolvedAction] for a given loop and screen:
 * percentage -> pixels, + loop shift, + random jitter, clamped to the screen.
 */
class StepResolver(private val random: Random = Random.Default) {

    fun resolve(step: MacroStep, loopIndex: Int, screen: ScreenSize, shift: LoopShift): ResolvedAction {
        val shiftX = if (shift.enabled && step.applyLoopShift) shift.dxPx.toFloat() * loopIndex else 0f
        val shiftY = if (shift.enabled && step.applyLoopShift) shift.dyPx.toFloat() * loopIndex else 0f

        fun px(p: NormalizedPoint): PixelPoint {
            val base = CoordinateScaler.toPixels(p, screen)
            val jitterX = jitter(step.randomOffsetPx)
            val jitterY = jitter(step.randomOffsetPx)
            return CoordinateScaler.clampToScreen(
                PixelPoint(base.x + shiftX + jitterX, base.y + shiftY + jitterY),
                screen,
            )
        }

        return when (val a = step.action) {
            is StepAction.Tap -> ResolvedAction.Tap(px(a.point))
            is StepAction.LongPress -> ResolvedAction.LongPress(px(a.point), a.durationMs)
            is StepAction.DoubleTap -> ResolvedAction.DoubleTap(px(a.point), a.intervalMs)
            is StepAction.Swipe -> ResolvedAction.Swipe(px(a.start), px(a.end), a.durationMs)
            is StepAction.Wait -> ResolvedAction.Wait(randomBetween(a.minMs, a.maxMs))
            is StepAction.Global -> ResolvedAction.Global(a.action)
            is StepAction.PasteText -> ResolvedAction.PasteText(
                text = a.text,
                mode = a.mode,
                target = a.target?.let(::px),
                append = a.append,
                longPressMs = a.longPressMs,
                popupLabels = a.popupLabels,
                popupPoint = a.popupPoint?.let(::px),
                popupTimeoutMs = a.popupTimeoutMs,
            )
            is StepAction.LaunchApp -> ResolvedAction.LaunchApp(a.packageName)
            is StepAction.WaitForText -> ResolvedAction.WaitForText(a.text, a.timeoutMs, a.exactMatch, a.pollIntervalMs)
        }
    }

    /** Total pause after a step: fixed delay + random extra in [0, randomDelayMs]. */
    fun delayAfter(step: MacroStep): Long = step.delayAfterMs + randomBetween(0, step.randomDelayMs)

    /** Inclusive random value in [min, max]; returns min when max <= min. */
    fun randomBetween(min: Long, max: Long): Long =
        if (max <= min) min else random.nextLong(min, max + 1)

    private fun jitter(maxPx: Int): Float =
        if (maxPx <= 0) 0f else random.nextInt(-maxPx, maxPx + 1).toFloat()
}
