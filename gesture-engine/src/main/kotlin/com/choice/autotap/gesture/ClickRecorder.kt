package com.choice.autotap.gesture

import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import com.choice.autotap.model.RecordedClick

/**
 * Captures TYPE_VIEW_CLICKED / TYPE_VIEW_LONG_CLICKED events while recording.
 * Feed every accessibility event to [onEvent]; clicks on [ownPackage] (our overlay) are ignored.
 */
class ClickRecorder(private val ownPackage: String) {

    private val clicks = mutableListOf<RecordedClick>()

    var isRecording: Boolean = false
        private set

    val count: Int get() = clicks.size

    fun start() {
        clicks.clear()
        isRecording = true
    }

    fun stop(): List<RecordedClick> {
        isRecording = false
        return clicks.toList().also { clicks.clear() }
    }

    /** @return true if the event was recorded. */
    fun onEvent(event: AccessibilityEvent): Boolean {
        if (!isRecording) return false
        val long = when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> false
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> true
            else -> return false
        }
        val pkg = event.packageName?.toString()
        if (pkg == ownPackage) return false
        val source = event.source ?: return false
        val r = Rect()
        source.getBoundsInScreen(r)
        if (r.isEmpty) return false
        clicks += RecordedClick(SystemClock.uptimeMillis(), r.left, r.top, r.right, r.bottom, long, pkg)
        return true
    }
}
