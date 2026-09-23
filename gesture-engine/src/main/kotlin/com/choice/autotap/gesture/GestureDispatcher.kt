package com.choice.autotap.gesture

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Suspending wrapper around [AccessibilityService.dispatchGesture]. */
class GestureDispatcher(private val service: AccessibilityService) {

    private val mainHandler = Handler(Looper.getMainLooper())

    /** @return true when the gesture completed, false when it was cancelled or rejected. */
    suspend fun dispatch(gesture: GestureDescription): Boolean {
        val timeout = GestureFactory.durationOf(gesture) + 5_000
        return withTimeoutOrNull(timeout) {
            suspendCancellableCoroutine { cont ->
                val callback = object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                }
                val accepted = service.dispatchGesture(gesture, callback, mainHandler)
                if (!accepted && cont.isActive) cont.resume(false)
            }
        } ?: false
    }
}
