package com.choice.autotap.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.Locale
import java.util.UUID

/** Global system actions available through AccessibilityService.performGlobalAction(). */
@Serializable
enum class GlobalActionType { BACK, HOME, RECENTS }

/** How a [StepAction.PasteText] step gets its text into a field. */
@Serializable
enum class PasteMode {
    /** Find the focused (or targeted) editable node and use ACTION_SET_TEXT. Most reliable; supports RTL and emoji. */
    SET_TEXT,

    /** Put text on the clipboard, long-press the target, then tap the "Paste" popup. */
    LONG_PRESS_POPUP,
}

/** What a single step does. Every variant is serialized with a `"type"` discriminator. */
@Serializable
sealed interface StepAction {

    /** Points of this action that markers can be placed on / loop-shift applies to. */
    val points: List<NormalizedPoint>

    @Serializable
    @SerialName("tap")
    data class Tap(val point: NormalizedPoint) : StepAction {
        override val points get() = listOf(point)
    }

    @Serializable
    @SerialName("long_press")
    data class LongPress(val point: NormalizedPoint, val durationMs: Long = DEFAULT_LONG_PRESS_MS) : StepAction {
        override val points get() = listOf(point)
    }

    @Serializable
    @SerialName("double_tap")
    data class DoubleTap(val point: NormalizedPoint, val intervalMs: Long = 100) : StepAction {
        override val points get() = listOf(point)
    }

    @Serializable
    @SerialName("swipe")
    data class Swipe(
        val start: NormalizedPoint,
        val end: NormalizedPoint,
        val durationMs: Long = 400,
    ) : StepAction {
        override val points get() = listOf(start, end)
    }

    /** Waits a fixed time (min == max) or a random time between [minMs] and [maxMs]. */
    @Serializable
    @SerialName("wait")
    data class Wait(val minMs: Long = 1000, val maxMs: Long = minMs) : StepAction {
        override val points get() = emptyList<NormalizedPoint>()
    }

    @Serializable
    @SerialName("global")
    data class Global(val action: GlobalActionType) : StepAction {
        override val points get() = emptyList<NormalizedPoint>()
    }

    /**
     * Pastes [text] into a field.
     *
     * - [PasteMode.SET_TEXT]: if [target] is set it is tapped first to focus the field, then the
     *   focused editable node receives ACTION_SET_TEXT (or the editable node under [target]).
     * - [PasteMode.LONG_PRESS_POPUP]: [text] is copied to the clipboard, [target] is long-pressed
     *   for [longPressMs], then a node labeled with one of [popupLabels] is clicked. If none is
     *   found within [popupTimeoutMs] and [popupPoint] is set, that point is tapped instead.
     */
    @Serializable
    @SerialName("paste_text")
    data class PasteText(
        val text: String,
        val mode: PasteMode = PasteMode.SET_TEXT,
        val target: NormalizedPoint? = null,
        val append: Boolean = false,
        val longPressMs: Long = DEFAULT_LONG_PRESS_MS,
        val popupLabels: List<String> = DEFAULT_PASTE_LABELS,
        val popupPoint: NormalizedPoint? = null,
        val popupTimeoutMs: Long = 3000,
    ) : StepAction {
        override val points get() = listOfNotNull(target, popupPoint)
    }

    @Serializable
    @SerialName("launch_app")
    data class LaunchApp(val packageName: String, val label: String = packageName) : StepAction {
        override val points get() = emptyList<NormalizedPoint>()
    }

    /** Polls the accessibility node tree until [text] is visible, or [timeoutMs] elapses. */
    @Serializable
    @SerialName("wait_text")
    data class WaitForText(
        val text: String,
        val timeoutMs: Long = 10_000,
        val exactMatch: Boolean = false,
        val pollIntervalMs: Long = 500,
    ) : StepAction {
        override val points get() = emptyList<NormalizedPoint>()
    }

    companion object {
        const val DEFAULT_LONG_PRESS_MS = 800L
        val DEFAULT_PASTE_LABELS = listOf("Paste", "لصق", "Coller", "Pegar", "Einfügen", "Incolla")
    }
}

/**
 * One step of a macro: an [action] plus timing / humanization settings.
 *
 * @param delayAfterMs fixed pause after the action.
 * @param randomDelayMs extra random pause in [0, randomDelayMs] added after the action.
 * @param randomOffsetPx each coordinate is moved by a random amount in [-randomOffsetPx, +randomOffsetPx].
 * @param applyLoopShift whether the macro's per-loop coordinate shift applies to this step.
 */
@Serializable
data class MacroStep(
    val id: String = newId(),
    val action: StepAction,
    val delayAfterMs: Long = 500,
    val randomDelayMs: Long = 0,
    val randomOffsetPx: Int = 0,
    val applyLoopShift: Boolean = true,
    val enabled: Boolean = true,
    val note: String = "",
) {
    companion object {
        fun newId(): String = UUID.randomUUID().toString()
    }
}

/** Human readable name for a step type, used by the editor and overlay. */
fun StepAction.typeLabel(): String = when (this) {
    is StepAction.Tap -> "Tap"
    is StepAction.LongPress -> "Long-press"
    is StepAction.DoubleTap -> "Double tap"
    is StepAction.Swipe -> "Swipe"
    is StepAction.Wait -> "Wait"
    is StepAction.Global -> when (action) {
        GlobalActionType.BACK -> "Back"
        GlobalActionType.HOME -> "Home"
        GlobalActionType.RECENTS -> "Recents"
    }
    is StepAction.PasteText -> "Paste text"
    is StepAction.LaunchApp -> "Launch app"
    is StepAction.WaitForText -> "Wait for text"
}

/** Short one-line description, e.g. "Tap (50.0%, 72.3%)". */
fun StepAction.describe(): String {
    fun p(pt: NormalizedPoint) = String.format(Locale.US, "(%.1f%%, %.1f%%)", pt.x * 100, pt.y * 100)
    return when (this) {
        is StepAction.Tap -> "Tap ${p(point)}"
        is StepAction.LongPress -> "Long-press ${p(point)} ${durationMs}ms"
        is StepAction.DoubleTap -> "Double tap ${p(point)}"
        is StepAction.Swipe -> "Swipe ${p(start)} → ${p(end)} ${durationMs}ms"
        is StepAction.Wait -> if (minMs == maxMs) "Wait ${minMs}ms" else "Wait ${minMs}–${maxMs}ms"
        is StepAction.Global -> typeLabel()
        is StepAction.PasteText -> "Paste \"${text.take(24)}\"" + if (mode == PasteMode.LONG_PRESS_POPUP) " (popup)" else ""
        is StepAction.LaunchApp -> "Launch $label"
        is StepAction.WaitForText -> "Wait for \"$text\" (≤${timeoutMs}ms)"
    }
}

/** Returns a copy of this action with every point transformed by [f]. */
fun StepAction.mapPoints(f: (NormalizedPoint) -> NormalizedPoint): StepAction = when (this) {
    is StepAction.Tap -> copy(point = f(point))
    is StepAction.LongPress -> copy(point = f(point))
    is StepAction.DoubleTap -> copy(point = f(point))
    is StepAction.Swipe -> copy(start = f(start), end = f(end))
    is StepAction.PasteText -> copy(target = target?.let(f), popupPoint = popupPoint?.let(f))
    is StepAction.Wait, is StepAction.Global, is StepAction.LaunchApp, is StepAction.WaitForText -> this
}

/** Returns a copy with the point at [index] (as listed in [StepAction.points]) replaced. */
fun StepAction.withPoint(index: Int, newPoint: NormalizedPoint): StepAction {
    var i = 0
    return mapPoints { old -> if (i++ == index) newPoint else old }
}
