package com.choice.autotap.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MacroModelTest {

    private fun tap(x: Float) = MacroStep(action = StepAction.Tap(NormalizedPoint(x, 0.5f)))

    private val sample = Macro(
        id = 7,
        name = "رسالة ترحيب 👋",
        steps = listOf(
            MacroStep(action = StepAction.Tap(NormalizedPoint(0.1f, 0.2f)), randomOffsetPx = 5),
            MacroStep(action = StepAction.LongPress(NormalizedPoint(0.3f, 0.4f))),
            MacroStep(action = StepAction.DoubleTap(NormalizedPoint(0.3f, 0.4f))),
            MacroStep(action = StepAction.Swipe(NormalizedPoint(0.5f, 0.9f), NormalizedPoint(0.5f, 0.1f), 250)),
            MacroStep(action = StepAction.Wait(1000, 3000)),
            MacroStep(action = StepAction.Global(GlobalActionType.BACK)),
            MacroStep(action = StepAction.PasteText("مرحبا بك 😀", PasteMode.LONG_PRESS_POPUP, NormalizedPoint(0.5f, 0.5f))),
            MacroStep(action = StepAction.LaunchApp("com.example.chat", "Chat")),
            MacroStep(action = StepAction.WaitForText("Sent", timeoutMs = 5000)),
        ),
        loop = LoopSettings(repeatCount = 0, shift = LoopShift(true, 0, -40)),
        onWaitTimeout = TimeoutPolicy.RETRY_LOOP,
    )

    @Test
    fun `json round trip keeps every step type, RTL text and emoji`() {
        val text = MacroJson.encodeMacro(sample)
        assertTrue(text.contains("\"type\": \"paste_text\""))
        assertEquals(sample, MacroJson.decodeMacro(text))
    }

    @Test
    fun `export and import bundle resets ids`() {
        val exported = MacroJson.export(listOf(sample, sample.copy(id = 8, name = "Second")))
        val imported = MacroJson.import(exported)
        assertEquals(2, imported.size)
        assertTrue(imported.all { it.id == 0L })
        assertEquals(sample.steps, imported[0].steps)
        assertEquals("Second", imported[1].name)
    }

    @Test
    fun `import accepts a single bare macro and ignores unknown keys`() {
        val json = """{"name":"Bare","futureField":1,"steps":[{"action":{"type":"tap","point":{"x":0.5,"y":0.5}}}]}"""
        val imported = MacroJson.import(json)
        assertEquals(1, imported.size)
        assertEquals(StepAction.Tap(NormalizedPoint(0.5f, 0.5f)), imported[0].steps[0].action)
        assertEquals(TimeoutPolicy.SKIP_LOOP, imported[0].onWaitTimeout)
    }

    @Test
    fun `default loop settings`() {
        val loop = LoopSettings()
        assertEquals(20_000L, loop.loopDelayMinMs)
        assertEquals(45_000L, loop.loopDelayMaxMs)
        assertEquals(800L, StepAction.LongPress(NormalizedPoint(0f, 0f)).durationMs)
        assertTrue(LoopSettings(repeatCount = 0).isInfinite)
    }

    @Test
    fun `move reorders steps`() {
        val steps = listOf(tap(0.1f), tap(0.2f), tap(0.3f))
        val moved = MacroEditing.move(steps, 0, 2)
        assertEquals(listOf(steps[1], steps[2], steps[0]), moved)
        assertEquals(steps, MacroEditing.move(steps, 0, 5))
    }

    @Test
    fun `duplicate inserts copy with new id after original`() {
        val steps = listOf(tap(0.1f), tap(0.2f))
        val dup = MacroEditing.duplicate(steps, 0)
        assertEquals(3, dup.size)
        assertEquals(steps[0].action, dup[1].action)
        assertNotEquals(steps[0].id, dup[1].id)
        assertEquals(steps[1], dup[2])
    }

    @Test
    fun `delete and replace`() {
        val steps = listOf(tap(0.1f), tap(0.2f))
        assertEquals(listOf(steps[1]), MacroEditing.delete(steps, 0))
        val edited = steps[1].copy(delayAfterMs = 999)
        assertEquals(999L, MacroEditing.replace(steps, edited)[1].delayAfterMs)
    }

    @Test
    fun `sanitize fixes impossible values`() {
        val bad = Macro(
            name = " ",
            steps = listOf(
                MacroStep(action = StepAction.Wait(500, 100), delayAfterMs = -5, randomOffsetPx = -3),
                MacroStep(action = StepAction.Tap(NormalizedPoint(1.5f, -0.2f))),
                MacroStep(action = StepAction.LongPress(NormalizedPoint(0f, 0f), 120_000)),
            ),
            loop = LoopSettings(repeatCount = -2, loopDelayMinMs = 5000, loopDelayMaxMs = 1000),
        )
        val s = MacroEditing.sanitize(bad)
        assertEquals("Untitled macro", s.name)
        assertEquals(StepAction.Wait(500, 500), s.steps[0].action)
        assertEquals(0L, s.steps[0].delayAfterMs)
        assertEquals(0, s.steps[0].randomOffsetPx)
        assertEquals(StepAction.Tap(NormalizedPoint(1f, 0f)), s.steps[1].action)
        assertEquals(MacroEditing.MAX_GESTURE_MS, (s.steps[2].action as StepAction.LongPress).durationMs)
        assertEquals(0, s.loop.repeatCount)
        assertEquals(5000L, s.loop.loopDelayMaxMs)
    }

    @Test
    fun `withPoint replaces only the requested point`() {
        val swipe = StepAction.Swipe(NormalizedPoint(0.1f, 0.1f), NormalizedPoint(0.9f, 0.9f))
        val moved = swipe.withPoint(1, NormalizedPoint(0.5f, 0.5f)) as StepAction.Swipe
        assertEquals(NormalizedPoint(0.1f, 0.1f), moved.start)
        assertEquals(NormalizedPoint(0.5f, 0.5f), moved.end)
    }

    @Test
    fun `describe uses stable number formatting`() {
        assertEquals("Tap (10.0%, 20.0%)", StepAction.Tap(NormalizedPoint(0.1f, 0.2f)).describe())
    }
}
