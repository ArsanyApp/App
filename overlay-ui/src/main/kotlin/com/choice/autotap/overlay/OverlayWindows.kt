package com.choice.autotap.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/** Helpers shared by all overlay windows. */
internal object OverlayWindows {

    /**
     * Layout params for an accessibility overlay. TYPE_ACCESSIBILITY_OVERLAY windows can be shown
     * by an accessibility service over any app and are laid out in full-screen coordinates.
     */
    fun params(
        focusable: Boolean = false,
        width: Int = WindowManager.LayoutParams.WRAP_CONTENT,
        height: Int = WindowManager.LayoutParams.WRAP_CONTENT,
    ): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        flags = if (focusable) {
            flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        } else {
            flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        return WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            if (focusable) {
                softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
            }
        }
    }

    fun setFlag(params: WindowManager.LayoutParams, flag: Int, on: Boolean) {
        params.flags = if (on) params.flags or flag else params.flags and flag.inv()
    }

    fun WindowManager.safeUpdate(view: View, params: WindowManager.LayoutParams) {
        if (view.isAttachedToWindow) runCatching { updateViewLayout(view, params) }
    }

    fun WindowManager.safeRemove(view: View) {
        if (view.isAttachedToWindow) runCatching { removeViewImmediate(view) }
    }
}

internal fun Context.dp(value: Float): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics).toInt()

internal fun roundedBackground(color: Int, radiusPx: Float) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = radiusPx
}

/** A compact, clickable text "button" for overlay toolbars. */
internal fun Context.overlayButton(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
    text = label
    setTextColor(0xFFFFFFFF.toInt())
    textSize = 14f
    gravity = Gravity.CENTER
    minWidth = dp(40f)
    minHeight = dp(40f)
    val pad = dp(8f)
    setPadding(pad, pad / 2, pad, pad / 2)
    background = roundedBackground(0x33FFFFFF, dp(8f).toFloat())
    isClickable = true
    setOnClickListener { onClick() }
}
