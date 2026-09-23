package com.choice.autotap.player

import com.choice.autotap.model.Macro
import com.choice.autotap.model.MacroEditing
import com.choice.autotap.model.ResolvedAction
import com.choice.autotap.model.StepResolver
import com.choice.autotap.model.TimeoutPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * Runs a [Macro] in a loop without any human interaction.
 *
 * Stopping is done by cancelling the coroutine that called [run]; the returned/reported
 * [RunLog] then has [RunEndReason.STOPPED_BY_USER]. [pause] / [resume] can be called from any thread.
 */
class MacroPlayer(
    private val executor: ActionExecutor,
    random: Random = Random.Default,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val resolver = StepResolver(random)

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val paused = MutableStateFlow(false)

    fun pause() {
        paused.value = true
        _state.update { if (it.isActive) it.copy(status = PlaybackStatus.PAUSED) else it }
    }

    fun resume() {
        paused.value = false
        _state.update { if (it.status == PlaybackStatus.PAUSED) it.copy(status = PlaybackStatus.RUNNING) else it }
    }

    val isPaused: Boolean get() = paused.value

    /**
     * Plays [input] until its loop settings are exhausted, the time limit is hit, a
     * "wait for text" timeout stops it, or the calling coroutine is cancelled.
     * [onLoopFinished] and [onRunFinished] are always invoked (also on cancellation).
     */
    suspend fun run(
        input: Macro,
        onLoopFinished: (LoopLog) -> Unit = {},
        onRunFinished: (RunLog) -> Unit = {},
    ): RunLog {
        val macro = MacroEditing.sanitize(input)
        val steps = macro.steps.filter { it.enabled }
        val loops = mutableListOf<LoopLog>()
        val runStart = clock()
        val deadline = if (macro.loop.stopAfterMs > 0) runStart + macro.loop.stopAfterMs else Long.MAX_VALUE
        val totalLoops = if (macro.loop.isInfinite) null else macro.loop.repeatCount
        var current: LoopTracker? = null
        var endReason = RunEndReason.COMPLETED
        var errorMessage: String? = null

        fun finishLoop(outcome: LoopOutcome) {
            val t = current ?: return
            val log = LoopLog(
                loopNumber = t.number,
                startedAt = t.startedAt,
                endedAt = clock(),
                stepsDone = t.stepsDone,
                stepsTotal = steps.size,
                outcome = outcome,
                timeouts = t.timeouts.toList(),
                retries = t.retries,
                failedActions = t.failed,
            )
            loops += log
            current = null
            onLoopFinished(log)
        }

        paused.value = false
        _state.value = PlaybackState(PlaybackStatus.STARTING, macro.name, 0, totalLoops, 0, steps.size)

        try {
            if (macro.loop.startDelayMs > 0) {
                countdown(macro.loop.startDelayMs, deadline)
            }
            var loopIndex = 0
            loopsLoop@ while (totalLoops == null || loopIndex < totalLoops) {
                if (clock() >= deadline) throw EndRun(RunEndReason.TIME_LIMIT)
                val tracker = LoopTracker(number = loopIndex + 1, startedAt = clock())
                current = tracker

                var stepIndex = 0
                while (stepIndex < steps.size) {
                    awaitResumed()
                    if (clock() >= deadline) throw EndRun(RunEndReason.TIME_LIMIT)
                    val step = steps[stepIndex]
                    _state.value = PlaybackState(
                        if (paused.value) PlaybackStatus.PAUSED else PlaybackStatus.RUNNING,
                        macro.name, tracker.number, totalLoops, stepIndex + 1, steps.size,
                    )

                    val action = resolver.resolve(step, loopIndex, executor.screenSize(), macro.loop.shift)
                    when (action) {
                        is ResolvedAction.Wait -> pausableDelay(action.durationMs, deadline)
                        is ResolvedAction.WaitForText -> {
                            val found = waitForText(action, deadline)
                            if (!found) {
                                tracker.timeouts += action.text
                                when (macro.onWaitTimeout) {
                                    TimeoutPolicy.STOP -> {
                                        finishLoop(LoopOutcome.STOPPED)
                                        throw EndRun(RunEndReason.WAIT_TIMEOUT)
                                    }
                                    TimeoutPolicy.SKIP_LOOP -> {
                                        finishLoop(LoopOutcome.SKIPPED_AFTER_TIMEOUT)
                                        loopIndex++
                                        if (totalLoops == null || loopIndex < totalLoops) betweenLoops(macro, deadline)
                                        continue@loopsLoop
                                    }
                                    TimeoutPolicy.RETRY_LOOP -> {
                                        if (tracker.retries < macro.timeoutRetries) {
                                            tracker.retries++
                                            tracker.stepsDone = 0
                                            stepIndex = 0
                                            continue
                                        }
                                        finishLoop(LoopOutcome.SKIPPED_AFTER_TIMEOUT)
                                        loopIndex++
                                        if (totalLoops == null || loopIndex < totalLoops) betweenLoops(macro, deadline)
                                        continue@loopsLoop
                                    }
                                }
                            }
                        }
                        else -> if (!executor.perform(action)) tracker.failed++
                    }
                    tracker.stepsDone++
                    pausableDelay(resolver.delayAfter(step), deadline)
                    stepIndex++
                }

                finishLoop(LoopOutcome.COMPLETED)
                loopIndex++
                if (totalLoops == null || loopIndex < totalLoops) betweenLoops(macro, deadline)
            }
        } catch (e: EndRun) {
            endReason = e.reason
        } catch (e: CancellationException) {
            endReason = RunEndReason.STOPPED_BY_USER
            finalize(macro, runStart, endReason, loops, null, ::finishLoop, onRunFinished)
            throw e
        } catch (e: Exception) {
            endReason = RunEndReason.ERROR
            errorMessage = e.message ?: e.javaClass.simpleName
        }
        return finalize(macro, runStart, endReason, loops, errorMessage, ::finishLoop, onRunFinished)
    }

    private suspend fun finalize(
        macro: Macro,
        runStart: Long,
        endReason: RunEndReason,
        loops: List<LoopLog>,
        errorMessage: String?,
        finishLoop: (LoopOutcome) -> Unit,
        onRunFinished: (RunLog) -> Unit,
    ): RunLog = withContext(NonCancellable) {
        finishLoop(LoopOutcome.STOPPED) // no-op if the current loop was already closed
        val log = RunLog(macro.id, macro.name, runStart, clock(), endReason, loops.toList(), errorMessage)
        paused.value = false
        _state.value = PlaybackState(PlaybackStatus.FINISHED, macro.name, message = endReason.name)
        onRunFinished(log)
        log
    }

    private suspend fun betweenLoops(macro: Macro, deadline: Long) {
        val wait = resolver.randomBetween(macro.loop.loopDelayMinMs, macro.loop.loopDelayMaxMs)
        if (wait <= 0) return
        val endAt = clock() + wait
        var remaining = wait
        while (remaining > 0) {
            awaitResumed()
            if (clock() >= deadline) throw EndRun(RunEndReason.TIME_LIMIT)
            _state.update {
                it.copy(
                    status = if (paused.value) PlaybackStatus.PAUSED else PlaybackStatus.WAITING_BETWEEN_LOOPS,
                    step = 0,
                    message = "next in ${(remaining + 999) / 1000}s",
                )
            }
            delay(minOf(remaining, 1000L, deadline - clock()))
            remaining = endAt - clock()
        }
    }

    private suspend fun countdown(ms: Long, deadline: Long) {
        var remaining = ms
        while (remaining > 0) {
            if (clock() >= deadline) throw EndRun(RunEndReason.TIME_LIMIT)
            _state.update { it.copy(status = PlaybackStatus.STARTING, message = "Starting in ${(remaining + 999) / 1000}s") }
            val chunk = minOf(remaining, 1000L, deadline - clock())
            delay(chunk)
            remaining -= chunk
        }
    }

    private suspend fun waitForText(action: ResolvedAction.WaitForText, deadline: Long): Boolean {
        val start = clock()
        while (true) {
            awaitResumed()
            if (clock() >= deadline) throw EndRun(RunEndReason.TIME_LIMIT)
            if (executor.isTextVisible(action.text, action.exactMatch)) return true
            val elapsed = clock() - start
            if (elapsed >= action.timeoutMs) return false
            delay(minOf(action.pollIntervalMs, action.timeoutMs - elapsed, deadline - clock()))
        }
    }

    /** Delay that holds while paused and aborts when the time limit is reached. */
    private suspend fun pausableDelay(ms: Long, deadline: Long) {
        var remaining = ms
        while (remaining > 0) {
            awaitResumed()
            if (clock() >= deadline) throw EndRun(RunEndReason.TIME_LIMIT)
            val chunk = minOf(remaining, 250L, deadline - clock())
            delay(chunk)
            remaining -= chunk
        }
    }

    private suspend fun awaitResumed() {
        if (paused.value) paused.first { !it }
    }

    private class LoopTracker(val number: Int, val startedAt: Long) {
        var stepsDone = 0
        var retries = 0
        var failed = 0
        val timeouts = mutableListOf<String>()
    }

    private class EndRun(val reason: RunEndReason) : Exception(null, null, false, false)
}
