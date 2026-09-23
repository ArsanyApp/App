package com.choice.autotap.overlay

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import com.choice.autotap.overlay.OverlayWindows.safeUpdate
import kotlin.math.abs

/**
 * Makes [handle] drag the overlay window [root]. [onMoved] is called while moving,
 * [onDragEnd] when released after a real drag, [onTap] when released without moving.
 */
@SuppressLint("ClickableViewAccessibility")
internal fun attachDrag(
    handle: View,
    root: View,
    params: WindowManager.LayoutParams,
    wm: WindowManager,
    onMoved: () -> Unit = {},
    onDragEnd: () -> Unit = {},
    onTap: (() -> Unit)? = null,
) {
    val slop = ViewConfiguration.get(handle.context).scaledTouchSlop
    var downRawX = 0f
    var downRawY = 0f
    var startX = 0
    var startY = 0
    var dragging = false
    handle.setOnTouchListener { _, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = e.rawX
                downRawY = e.rawY
                startX = params.x
                startY = params.y
                dragging = false
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - downRawX
                val dy = e.rawY - downRawY
                if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                if (dragging) {
                    params.x = startX + dx.toInt()
                    params.y = startY + dy.toInt()
                    wm.safeUpdate(root, params)
                    onMoved()
                }
                true
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) onDragEnd() else onTap?.invoke()
                true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (dragging) onDragEnd()
                true
            }
            else -> false
        }
    }
}
