package com.choice.autotap.player

import com.choice.autotap.model.ResolvedAction
import com.choice.autotap.model.ScreenSize

/**
 * The bridge between the platform-independent player and the device. The Android implementation
 * lives in the gesture-engine module and uses AccessibilityService.dispatchGesture().
 */
interface ActionExecutor {

    /** Current real screen size, used to convert percentages into pixels. */
    fun screenSize(): ScreenSize

    /**
     * Performs a device action. [ResolvedAction.Wait] and [ResolvedAction.WaitForText] are handled
     * by the player itself and never reach this method.
     * @return true if the system accepted and completed the action.
     */
    suspend fun perform(action: ResolvedAction): Boolean

    /** True if [text] is currently visible anywhere in the accessibility node tree. */
    suspend fun isTextVisible(text: String, exactMatch: Boolean): Boolean
}
