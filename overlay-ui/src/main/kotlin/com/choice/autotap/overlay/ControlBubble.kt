package com.choice.autotap.overlay

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.choice.autotap.overlay.OverlayWindows.safeRemove
import com.choice.autotap.overlay.OverlayWindows.safeUpdate

/** Actions the floating bubble can request. Implemented by the accessibility service. */
interface BubbleListener {
    fun onPlay()
    fun onPauseToggle()
    fun onStop()
    fun onEditMarkers()
    fun onRecordToggle()
    fun onOpenApp()
    fun onHideBubble()
}

/** The draggable floating control panel: shows loop/step progress with Play, Pause and Stop. */
class ControlBubble(
    private val context: Context,
    private val wm: WindowManager,
    private val listener: BubbleListener,
) {
    sealed interface Mode {
        data class Idle(val macroName: String?) : Mode
        data class Running(val label: String, val paused: Boolean) : Mode
        data class Recording(val clicks: Int) : Mode
        data object Editing : Mode
    }

    private val params = OverlayWindows.params().apply {
        x = context.dp(8f)
        y = context.dp(160f)
    }
    private val status = TextView(context).apply {
        setTextColor(0xFFFFFFFF.toInt())
        textSize = 13f
        maxLines = 2
        maxWidth = context.dp(180f)
        val pad = context.dp(6f)
        setPadding(pad, 0, pad, 0)
    }
    private val handle = TextView(context).apply {
        text = "⠿"
        setTextColor(0xFFFFFFFF.toInt())
        textSize = 20f
        gravity = Gravity.CENTER
        minWidth = context.dp(32f)
        minHeight = context.dp(40f)
    }
    private val buttons = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private val root = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        val pad = context.dp(4f)
        setPadding(pad, pad, pad, pad)
        background = roundedBackground(0xDD1B5E20.toInt(), context.dp(24f).toFloat())
        addView(handle)
        addView(status)
        addView(buttons)
    }

    private var mode: Mode = Mode.Idle(null)

    val isShowing: Boolean get() = root.isAttachedToWindow

    init {
        attachDrag(handle, root, params, wm, onTap = { listener.onOpenApp() })
    }

    fun show() {
        if (!isShowing) runCatching { wm.addView(root, params) }
        render()
    }

    fun hide() = wm.safeRemove(root)

    fun setMode(newMode: Mode) {
        if (newMode == mode && buttons.childCount > 0) return
        val rebuild = newMode::class != mode::class || buttons.childCount == 0 ||
            (newMode is Mode.Running && (mode as? Mode.Running)?.paused != newMode.paused)
        mode = newMode
        if (rebuild) render() else updateStatus()
    }

    /** While true the bubble ignores touches so injected gestures pass through it. */
    fun setPassThrough(passThrough: Boolean) {
        OverlayWindows.setFlag(params, WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, passThrough)
        wm.safeUpdate(root, params)
    }

    /** Keeps the display on while a macro is running. */
    fun setKeepScreenOn(on: Boolean) {
        OverlayWindows.setFlag(params, WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON, on)
        root.keepScreenOn = on
        wm.safeUpdate(root, params)
    }

    private fun render() {
        buttons.removeAllViews()
        fun add(label: String, action: () -> Unit) {
            buttons.addView(
                context.overlayButton(label, action),
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { marginStart = context.dp(4f) },
            )
        }
        when (val m = mode) {
            is Mode.Idle -> {
                add("▶") { listener.onPlay() }
                add("✎") { listener.onEditMarkers() }
                add("●") { listener.onRecordToggle() }
                add("✕") { listener.onHideBubble() }
            }
            is Mode.Running -> {
                add(if (m.paused) "▶" else "❚❚") { listener.onPauseToggle() }
                add("■") { listener.onStop() }
            }
            is Mode.Recording -> add("■") { listener.onRecordToggle() }
            Mode.Editing -> Unit
        }
        updateStatus()
    }

    private fun updateStatus() {
        status.text = when (val m = mode) {
            is Mode.Idle -> m.macroName ?: "No macro"
            is Mode.Running -> m.label
            is Mode.Recording -> "● REC  ${m.clicks} taps"
            Mode.Editing -> "Editing"
        }
    }

    fun destroy() = hide()
}
