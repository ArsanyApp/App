package com.choice.autotap.gesture

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import com.choice.autotap.model.GlobalActionType
import com.choice.autotap.model.PasteMode
import com.choice.autotap.model.ResolvedAction
import com.choice.autotap.model.ScreenSize
import com.choice.autotap.player.ActionExecutor
import kotlinx.coroutines.delay

/**
 * Hook so overlay windows (the control bubble) can become non-touchable while a gesture runs,
 * otherwise a tap that lands on the bubble would be swallowed by it.
 */
interface GestureGuard {
    fun beforeGesture() {}
    fun afterGesture() {}
}

/** [ActionExecutor] backed by an AccessibilityService. Must be used from the main thread. */
class AccessibilityActionExecutor(
    private val service: AccessibilityService,
    private val guard: GestureGuard = object : GestureGuard {},
) : ActionExecutor {

    private val dispatcher = GestureDispatcher(service)
    private val nodes = NodeFinder(service)
    private val text = TextInjector(service, nodes)

    override fun screenSize(): ScreenSize = ScreenMetrics.realSize(service)

    override suspend fun isTextVisible(text: String, exactMatch: Boolean): Boolean = nodes.isTextVisible(text, exactMatch)

    override suspend fun perform(action: ResolvedAction): Boolean = when (action) {
        is ResolvedAction.Tap -> gesture(GestureFactory.tap(action.point))
        is ResolvedAction.LongPress -> gesture(GestureFactory.longPress(action.point, action.durationMs))
        is ResolvedAction.DoubleTap -> gesture(GestureFactory.doubleTap(action.point, action.intervalMs))
        is ResolvedAction.Swipe -> gesture(GestureFactory.swipe(action.start, action.end, action.durationMs))
        is ResolvedAction.Global -> service.performGlobalAction(
            when (action.action) {
                GlobalActionType.BACK -> AccessibilityService.GLOBAL_ACTION_BACK
                GlobalActionType.HOME -> AccessibilityService.GLOBAL_ACTION_HOME
                GlobalActionType.RECENTS -> AccessibilityService.GLOBAL_ACTION_RECENTS
            },
        )
        is ResolvedAction.LaunchApp -> AppLauncher.launch(service, action.packageName)
        is ResolvedAction.PasteText -> paste(action)
        // Handled by the player; never dispatched here.
        is ResolvedAction.Wait, is ResolvedAction.WaitForText -> true
    }

    private suspend fun gesture(g: GestureDescription): Boolean {
        guard.beforeGesture()
        try {
            // Let the window manager apply the non-touchable flag before injecting input.
            delay(GUARD_SETTLE_MS)
            return dispatcher.dispatch(g)
        } finally {
            guard.afterGesture()
        }
    }

    private suspend fun paste(a: ResolvedAction.PasteText): Boolean = when (a.mode) {
        PasteMode.SET_TEXT -> {
            a.target?.let { target ->
                gesture(GestureFactory.tap(target))
                delay(FOCUS_SETTLE_MS)
            }
            text.setText(a.text, a.target, a.append)
        }
        PasteMode.LONG_PRESS_POPUP -> pasteViaPopup(a)
    }

    private suspend fun pasteViaPopup(a: ResolvedAction.PasteText): Boolean {
        text.copyToClipboard(a.text)
        val target = a.target ?: return text.pasteIntoFocused()
        gesture(GestureFactory.longPress(target, a.longPressMs))

        val deadline = System.currentTimeMillis() + a.popupTimeoutMs
        while (System.currentTimeMillis() <= deadline) {
            for (label in a.popupLabels) {
                val node = nodes.findByText(label, exactMatch = true)
                if (node != null && nodes.clickNodeOrAncestor(node)) return true
            }
            delay(POPUP_POLL_MS)
        }
        a.popupPoint?.let { return gesture(GestureFactory.tap(it)) }
        return text.pasteIntoFocused()
    }

    private companion object {
        const val GUARD_SETTLE_MS = 16L
        const val FOCUS_SETTLE_MS = 350L
        const val POPUP_POLL_MS = 200L
    }
}
