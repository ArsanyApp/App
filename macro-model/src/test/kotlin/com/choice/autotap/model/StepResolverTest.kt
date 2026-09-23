package com.choice.autotap.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class StepResolverTest {

    private val screen = ScreenSize(1000, 2000)
    private val resolver = StepResolver(Random(42))

    @Test
    fun `tap without jitter resolves to exact pixels`() {
        val step = MacroStep(action = StepAction.Tap(NormalizedPoint(0.1f, 0.2f)))
        val r = resolver.resolve(step, 0, screen, LoopShift()) as ResolvedAction.Tap
        assertEquals(PixelPoint(100f, 400f), r.point)
    }

    @Test
    fun `loop shift moves target by N px per loop`() {
        val step = MacroStep(action = StepAction.Tap(NormalizedPoint(0.5f, 0.5f)))
        val shift = LoopShift(enabled = true, dxPx = 0, dyPx = -120)
        val loop0 = resolver.resolve(step, 0, screen, shift) as ResolvedAction.Tap
        val loop3 = resolver.resolve(step, 3, screen, shift) as ResolvedAction.Tap
        assertEquals(1000f, loop0.point.y, 0f)
        assertEquals(1000f - 360f, loop3.point.y, 0f)
    }

    @Test
    fun `loop shift is ignored when disabled or opted out`() {
        val shift = LoopShift(enabled = true, dyPx = 50)
        val optedOut = MacroStep(action = StepAction.Tap(NormalizedPoint(0.5f, 0.5f)), applyLoopShift = false)
        val r = resolver.resolve(optedOut, 5, screen, shift) as ResolvedAction.Tap
        assertEquals(1000f, r.point.y, 0f)
        val disabled = resolver.resolve(optedOut.copy(applyLoopShift = true), 5, screen, shift.copy(enabled = false))
        assertEquals(1000f, (disabled as ResolvedAction.Tap).point.y, 0f)
    }

    @Test
    fun `shifted points stay on screen`() {
        val step = MacroStep(action = StepAction.Tap(NormalizedPoint(0.5f, 0.05f)))
        val r = resolver.resolve(step, 10, screen, LoopShift(true, 0, -100)) as ResolvedAction.Tap
        assertEquals(0f, r.point.y, 0f)
    }

    @Test
    fun `random offset stays within range`() {
        val step = MacroStep(action = StepAction.Tap(NormalizedPoint(0.5f, 0.5f)), randomOffsetPx = 8)
        repeat(500) {
            val p = (resolver.resolve(step, 0, screen, LoopShift()) as ResolvedAction.Tap).point
            assertTrue(p.x in 492f..508f)
            assertTrue(p.y in 992f..1008f)
        }
    }

    @Test
    fun `random delay stays within range`() {
        val step = MacroStep(action = StepAction.Wait(10), delayAfterMs = 300, randomDelayMs = 200)
        repeat(500) {
            assertTrue(resolver.delayAfter(step) in 300L..500L)
        }
    }

    @Test
    fun `wait with range resolves inside range and fixed wait is exact`() {
        repeat(200) {
            val r = resolver.resolve(MacroStep(action = StepAction.Wait(1000, 2000)), 0, screen, LoopShift())
            assertTrue((r as ResolvedAction.Wait).durationMs in 1000L..2000L)
        }
        val fixed = resolver.resolve(MacroStep(action = StepAction.Wait(750)), 0, screen, LoopShift())
        assertEquals(750L, (fixed as ResolvedAction.Wait).durationMs)
    }

    @Test
    fun `swipe resolves both ends`() {
        val step = MacroStep(action = StepAction.Swipe(NormalizedPoint(0.5f, 0.8f), NormalizedPoint(0.5f, 0.2f), 300))
        val r = resolver.resolve(step, 0, screen, LoopShift()) as ResolvedAction.Swipe
        assertEquals(PixelPoint(500f, 1600f), r.start)
        assertEquals(PixelPoint(500f, 400f), r.end)
        assertEquals(300L, r.durationMs)
    }
}
