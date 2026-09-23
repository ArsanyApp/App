package com.choice.autotap.model

/** Pure list operations used by the macro editor. All functions return a new list. */
object MacroEditing {

    fun move(steps: List<MacroStep>, from: Int, to: Int): List<MacroStep> {
        if (from !in steps.indices || to !in steps.indices || from == to) return steps
        val list = steps.toMutableList()
        list.add(to, list.removeAt(from))
        return list
    }

    /** Inserts a copy (with a fresh id) right after [index]. */
    fun duplicate(steps: List<MacroStep>, index: Int): List<MacroStep> {
        if (index !in steps.indices) return steps
        val list = steps.toMutableList()
        list.add(index + 1, steps[index].copy(id = MacroStep.newId()))
        return list
    }

    fun delete(steps: List<MacroStep>, index: Int): List<MacroStep> =
        if (index !in steps.indices) steps else steps.filterIndexed { i, _ -> i != index }

    fun replace(steps: List<MacroStep>, step: MacroStep): List<MacroStep> =
        steps.map { if (it.id == step.id) step else it }

    fun insert(steps: List<MacroStep>, step: MacroStep, index: Int = steps.size): List<MacroStep> {
        val list = steps.toMutableList()
        list.add(index.coerceIn(0, steps.size), step)
        return list
    }

    /** Sanitizes user-entered values so playback never receives impossible numbers. */
    fun sanitize(macro: Macro): Macro {
        val loop = macro.loop.let { l ->
            val min = l.loopDelayMinMs.coerceAtLeast(0)
            l.copy(
                repeatCount = l.repeatCount.coerceAtLeast(0),
                loopDelayMinMs = min,
                loopDelayMaxMs = l.loopDelayMaxMs.coerceAtLeast(min),
                stopAfterMs = l.stopAfterMs.coerceAtLeast(0),
                startDelayMs = l.startDelayMs.coerceAtLeast(0),
            )
        }
        return macro.copy(
            name = macro.name.ifBlank { "Untitled macro" },
            loop = loop,
            timeoutRetries = macro.timeoutRetries.coerceAtLeast(0),
            steps = macro.steps.map(::sanitize),
        )
    }

    fun sanitize(step: MacroStep): MacroStep {
        val action = when (val a = step.action.mapPoints { it.clamped() }) {
            is StepAction.LongPress -> a.copy(durationMs = a.durationMs.coerceIn(MIN_GESTURE_MS, MAX_GESTURE_MS))
            is StepAction.DoubleTap -> a.copy(intervalMs = a.intervalMs.coerceIn(MIN_GESTURE_MS, 1000))
            is StepAction.Swipe -> a.copy(durationMs = a.durationMs.coerceIn(MIN_GESTURE_MS, MAX_GESTURE_MS))
            is StepAction.Wait -> {
                val min = a.minMs.coerceAtLeast(0)
                a.copy(minMs = min, maxMs = a.maxMs.coerceAtLeast(min))
            }
            is StepAction.WaitForText -> a.copy(
                timeoutMs = a.timeoutMs.coerceAtLeast(0),
                pollIntervalMs = a.pollIntervalMs.coerceIn(50, 10_000),
            )
            is StepAction.PasteText -> a.copy(
                longPressMs = a.longPressMs.coerceIn(MIN_GESTURE_MS, MAX_GESTURE_MS),
                popupTimeoutMs = a.popupTimeoutMs.coerceAtLeast(0),
            )
            else -> a
        }
        return step.copy(
            action = action,
            delayAfterMs = step.delayAfterMs.coerceAtLeast(0),
            randomDelayMs = step.randomDelayMs.coerceAtLeast(0),
            randomOffsetPx = step.randomOffsetPx.coerceAtLeast(0),
        )
    }

    const val MIN_GESTURE_MS = 1L

    /** Android's GestureDescription.getMaxGestureDuration() is 60 seconds. */
    const val MAX_GESTURE_MS = 59_000L
}
