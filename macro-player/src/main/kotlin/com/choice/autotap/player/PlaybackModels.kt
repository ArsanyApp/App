package com.choice.autotap.player

import kotlinx.serialization.Serializable

enum class PlaybackStatus { IDLE, STARTING, RUNNING, PAUSED, WAITING_BETWEEN_LOOPS, FINISHED }

/** Live state shown by the floating bubble and the notification. */
data class PlaybackState(
    val status: PlaybackStatus = PlaybackStatus.IDLE,
    val macroName: String = "",
    /** 1-based loop number; 0 before the first loop starts. */
    val loop: Int = 0,
    /** null when the macro loops forever. */
    val totalLoops: Int? = null,
    /** 1-based step number inside the current loop; 0 when not executing a step. */
    val step: Int = 0,
    val totalSteps: Int = 0,
    val message: String = "",
) {
    val isActive: Boolean get() = status != PlaybackStatus.IDLE && status != PlaybackStatus.FINISHED

    fun shortLabel(): String = when (status) {
        PlaybackStatus.IDLE -> "Ready"
        PlaybackStatus.FINISHED -> "Done"
        PlaybackStatus.STARTING -> message.ifEmpty { "Starting…" }
        else -> buildString {
            append("Loop ").append(loop).append('/').append(totalLoops?.toString() ?: "∞")
            append(" · Step ").append(step).append('/').append(totalSteps)
            if (status == PlaybackStatus.PAUSED) append(" · Paused")
            if (status == PlaybackStatus.WAITING_BETWEEN_LOOPS) append(" · ").append(message)
        }
    }
}

@Serializable
enum class LoopOutcome { COMPLETED, SKIPPED_AFTER_TIMEOUT, STOPPED }

@Serializable
enum class RunEndReason { COMPLETED, STOPPED_BY_USER, TIME_LIMIT, WAIT_TIMEOUT, ERROR }

@Serializable
data class LoopLog(
    val loopNumber: Int,
    val startedAt: Long,
    val endedAt: Long,
    val stepsDone: Int,
    val stepsTotal: Int,
    val outcome: LoopOutcome,
    /** Text of every "wait until text" step that timed out in this loop. */
    val timeouts: List<String> = emptyList(),
    val retries: Int = 0,
    val failedActions: Int = 0,
)

@Serializable
data class RunLog(
    val macroId: Long,
    val macroName: String,
    val startedAt: Long,
    val endedAt: Long,
    val endReason: RunEndReason,
    val loops: List<LoopLog>,
    val errorMessage: String? = null,
)
