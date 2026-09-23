package com.choice.autotap.gesture

import android.content.Context
import android.graphics.Point
import android.os.Build
import android.view.WindowManager
import com.choice.autotap.model.ScreenSize

/** Real (full) display size in pixels for the current rotation — the coordinate space of gestures. */
object ScreenMetrics {

    fun realSize(context: Context): ScreenSize {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            ScreenSize(bounds.width().coerceAtLeast(1), bounds.height().coerceAtLeast(1))
        } else {
            val p = Point()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealSize(p)
            ScreenSize(p.x.coerceAtLeast(1), p.y.coerceAtLeast(1))
        }
    }
}
