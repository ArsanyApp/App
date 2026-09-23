package com.choice.autotap.model

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingConverterTest {

    private val screen = ScreenSize(1000, 2000)

    @Test
    fun `clicks become steps at bounds center with time gaps as delays`() {
        val clicks = listOf(
            RecordedClick(1_000, 100, 200, 300, 400, isLongClick = false),
            RecordedClick(3_500, 0, 0, 1000, 2000, isLongClick = true),
            RecordedClick(5_000, 900, 1900, 1000, 2000, isLongClick = false),
        )
        val steps = RecordingConverter.convert(clicks, screen)
        assertEquals(3, steps.size)
        assertEquals(StepAction.Tap(NormalizedPoint(0.2f, 0.15f)), steps[0].action)
        assertEquals(2_500L, steps[0].delayAfterMs)
        assertEquals(StepAction.LongPress(NormalizedPoint(0.5f, 0.5f), 800), steps[1].action)
        assertEquals(1_500L - 800L, steps[1].delayAfterMs)
        assertEquals(RecordingConverter.LAST_STEP_DELAY_MS, steps[2].delayAfterMs)
    }

    @Test
    fun `out of order events are sorted and tiny gaps get a minimum delay`() {
        val clicks = listOf(
            RecordedClick(2_010, 0, 0, 10, 10, false),
            RecordedClick(2_000, 0, 0, 10, 10, false),
        )
        val steps = RecordingConverter.convert(clicks, screen)
        assertEquals(RecordingConverter.MIN_DELAY_MS, steps[0].delayAfterMs)
    }
}
