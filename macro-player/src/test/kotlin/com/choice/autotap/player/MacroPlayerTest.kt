package com.choice.autotap.player

import com.choice.autotap.model.GlobalActionType
import com.choice.autotap.model.LoopSettings
import com.choice.autotap.model.LoopShift
import com.choice.autotap.model.Macro
import com.choice.autotap.model.MacroStep
import com.choice.autotap.model.NormalizedPoint
import com.choice.autotap.model.ResolvedAction
import com.choice.autotap.model.ScreenSize
import com.choice.autotap.model.StepAction
import com.choice.autotap.model.TimeoutPolicy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

@OptIn(ExperimentalCoroutinesApi::class)
class MacroPlayerTest {

    private class FakeExecutor(var textVisibleAfterPolls: Int = 0) : ActionExecutor {
        val performed = mutableListOf<ResolvedAction>()
        var polls = 0
        override fun screenSize() = ScreenSize(1000, 2000)
        override suspend fun perform(action: ResolvedAction): Boolean {
            performed += action
            return true
        }
        override suspend fun isTextVisible(text: String, exactMatch: Boolean): Boolean =
            ++polls > textVisibleAfterPolls
    }

    private fun tap(y: Float) = MacroStep(action = StepAction.Tap(NormalizedPoint(0.5f, y)), delayAfterMs = 100)

    private fun macro(steps: List<MacroStep>, loop: LoopSettings, policy: TimeoutPolicy = TimeoutPolicy.SKIP_LOOP) =
        Macro(id = 1, name = "Test", steps = steps, loop = loop, onWaitTimeout = policy)

    private fun TestScope.player(exec: ActionExecutor) = MacroPlayer(exec, Random(1)) { testScheduler.currentTime }

    private val quickLoops = LoopSettings(repeatCount = 3, loopDelayMinMs = 1000, loopDelayMaxMs = 1000, startDelayMs = 0)

    @Test
    fun `runs N loops and logs each`() = runTest {
        val exec = FakeExecutor()
        val log = player(exec).run(macro(listOf(tap(0.1f), tap(0.2f)), quickLoops))
        assertEquals(RunEndReason.COMPLETED, log.endReason)
        assertEquals(6, exec.performed.size)
        assertEquals(3, log.loops.size)
        assertTrue(log.loops.all { it.outcome == LoopOutcome.COMPLETED && it.stepsDone == 2 })
        // 3 loops x 2 steps x 100ms + 2 gaps x 1000ms
        assertEquals(2600L, testScheduler.currentTime)
    }

    @Test
    fun `disabled steps are skipped`() = runTest {
        val exec = FakeExecutor()
        player(exec).run(macro(listOf(tap(0.1f), tap(0.2f).copy(enabled = false)), quickLoops.copy(repeatCount = 1)))
        assertEquals(1, exec.performed.size)
    }

    @Test
    fun `loop shift applies per loop`() = runTest {
        val exec = FakeExecutor()
        val loop = quickLoops.copy(shift = LoopShift(enabled = true, dyPx = -100))
        player(exec).run(macro(listOf(tap(0.5f)), loop))
        val ys = exec.performed.map { (it as ResolvedAction.Tap).point.y }
        assertEquals(listOf(1000f, 900f, 800f), ys)
    }

    @Test
    fun `stop after time limit`() = runTest {
        val exec = FakeExecutor()
        val loop = LoopSettings(repeatCount = 0, loopDelayMinMs = 1000, loopDelayMaxMs = 1000, stopAfterMs = 5_000, startDelayMs = 0)
        val log = player(exec).run(macro(listOf(tap(0.1f)), loop))
        assertEquals(RunEndReason.TIME_LIMIT, log.endReason)
        assertEquals(5_000L, testScheduler.currentTime)
        assertEquals(5, exec.performed.size)
    }

    @Test
    fun `cancellation reports stopped by user`() = runTest {
        val exec = FakeExecutor()
        var reported: RunLog? = null
        val p = player(exec)
        val job = launch {
            p.run(macro(listOf(tap(0.1f)), quickLoops.copy(repeatCount = 0)), onRunFinished = { reported = it })
        }
        advanceTimeBy(2_500)
        job.cancel()
        job.join()
        assertEquals(RunEndReason.STOPPED_BY_USER, reported?.endReason)
        assertEquals(PlaybackStatus.FINISHED, p.state.value.status)
    }

    @Test
    fun `pause holds execution until resumed`() = runTest {
        val exec = FakeExecutor()
        val p = player(exec)
        val job = launch { p.run(macro(listOf(tap(0.1f), tap(0.2f)), quickLoops.copy(repeatCount = 1))) }
        runCurrent()
        assertEquals(1, exec.performed.size)
        p.pause()
        advanceTimeBy(10_000)
        assertEquals(1, exec.performed.size)
        assertEquals(PlaybackStatus.PAUSED, p.state.value.status)
        p.resume()
        job.join()
        assertEquals(2, exec.performed.size)
    }

    @Test
    fun `wait for text found continues`() = runTest {
        val exec = FakeExecutor(textVisibleAfterPolls = 2)
        val steps = listOf(MacroStep(action = StepAction.WaitForText("OK", 5000, pollIntervalMs = 500)), tap(0.1f))
        val log = player(exec).run(macro(steps, quickLoops.copy(repeatCount = 1)))
        assertEquals(1, exec.performed.size)
        assertTrue(log.loops.single().timeouts.isEmpty())
    }

    @Test
    fun `wait timeout skip policy moves to next loop`() = runTest {
        val exec = FakeExecutor(textVisibleAfterPolls = Int.MAX_VALUE)
        val steps = listOf(tap(0.1f), MacroStep(action = StepAction.WaitForText("Never", 1000)), tap(0.2f))
        val log = player(exec).run(macro(steps, quickLoops.copy(repeatCount = 2)))
        assertEquals(RunEndReason.COMPLETED, log.endReason)
        assertEquals(2, log.loops.size)
        assertTrue(log.loops.all { it.outcome == LoopOutcome.SKIPPED_AFTER_TIMEOUT && it.timeouts == listOf("Never") })
        assertEquals(2, exec.performed.size) // only the first tap of each loop
    }

    @Test
    fun `wait timeout stop policy ends run`() = runTest {
        val exec = FakeExecutor(textVisibleAfterPolls = Int.MAX_VALUE)
        val steps = listOf(MacroStep(action = StepAction.WaitForText("Never", 1000)), tap(0.2f))
        val log = player(exec).run(macro(steps, quickLoops, TimeoutPolicy.STOP))
        assertEquals(RunEndReason.WAIT_TIMEOUT, log.endReason)
        assertEquals(1, log.loops.size)
        assertEquals(LoopOutcome.STOPPED, log.loops[0].outcome)
        assertTrue(exec.performed.isEmpty())
    }

    @Test
    fun `wait timeout retry policy restarts loop then skips`() = runTest {
        val exec = FakeExecutor(textVisibleAfterPolls = Int.MAX_VALUE)
        val steps = listOf(tap(0.1f), MacroStep(action = StepAction.WaitForText("Never", 1000)))
        val m = macro(steps, quickLoops.copy(repeatCount = 1), TimeoutPolicy.RETRY_LOOP).copy(timeoutRetries = 2)
        val log = player(exec).run(m)
        val loop = log.loops.single()
        assertEquals(2, loop.retries)
        assertEquals(LoopOutcome.SKIPPED_AFTER_TIMEOUT, loop.outcome)
        assertEquals(3, exec.performed.size) // first attempt + 2 retries
    }

    @Test
    fun `global actions and start delay`() = runTest {
        val exec = FakeExecutor()
        val steps = listOf(MacroStep(action = StepAction.Global(GlobalActionType.HOME)))
        player(exec).run(macro(steps, quickLoops.copy(repeatCount = 1, startDelayMs = 3000)))
        assertEquals(listOf(ResolvedAction.Global(GlobalActionType.HOME)), exec.performed)
        assertEquals(3500L, testScheduler.currentTime)
    }
}
