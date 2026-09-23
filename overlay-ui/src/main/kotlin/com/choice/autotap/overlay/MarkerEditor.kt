package com.choice.autotap.overlay

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.choice.autotap.model.CoordinateScaler
import com.choice.autotap.model.GlobalActionType
import com.choice.autotap.model.MacroStep
import com.choice.autotap.model.NormalizedPoint
import com.choice.autotap.model.PasteMode
import com.choice.autotap.model.ScreenSize
import com.choice.autotap.model.StepAction
import com.choice.autotap.model.typeLabel
import com.choice.autotap.model.withPoint
import com.choice.autotap.overlay.OverlayWindows.safeRemove
import com.choice.autotap.overlay.OverlayWindows.safeUpdate

/** Callbacks from the on-screen marker editor. */
interface MarkerEditorListener {
    /** Called after every change (add, drag, undo). */
    fun onStepsChanged(steps: List<MacroStep>)

    /** "Done" pressed. */
    fun onEditorClosed(steps: List<MacroStep>)

    /** Package of the app currently in the foreground, for "Launch app" steps. */
    fun currentForegroundApp(): Pair<String, String>?
}

/**
 * Marker mode: a floating toolbar adds steps; every step with coordinates gets a draggable
 * numbered crosshair placed over whatever app is open.
 */
class MarkerEditor(
    private val context: Context,
    private val wm: WindowManager,
    private val screenProvider: () -> ScreenSize,
    private val listener: MarkerEditorListener,
) {
    private class Marker(val stepId: String, val pointIndex: Int, val view: MarkerView, val params: WindowManager.LayoutParams) {
        /** Difference between requested window position and actual on-screen position. */
        var calibX = 0
        var calibY = 0
    }

    private var steps: MutableList<MacroStep> = mutableListOf()
    private val markers = mutableListOf<Marker>()
    private val prompt = TextPrompt(context, wm)
    private var toolbar: View? = null
    private val toolbarParams = OverlayWindows.params().apply {
        x = 0
        y = context.dp(48f)
    }
    private val markerSize = context.dp(MarkerView.SIZE_DP)

    var isOpen: Boolean = false
        private set

    fun open(initialSteps: List<MacroStep>) {
        close(notify = false)
        steps = initialSteps.toMutableList()
        isOpen = true
        showToolbar()
        rebuildMarkers()
    }

    fun close(notify: Boolean = true) {
        prompt.dismiss()
        removeMarkers()
        toolbar?.let { wm.safeRemove(it) }
        toolbar = null
        if (isOpen && notify) listener.onEditorClosed(steps.toList())
        isOpen = false
    }

    /** Hides / shows the crosshairs without closing the editor. */
    fun setMarkersVisible(visible: Boolean) {
        markers.forEach { it.view.visibility = if (visible) View.VISIBLE else View.GONE }
    }

    // ---- Toolbar -------------------------------------------------------------------------------

    private fun showToolbar() {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        fun add(label: String, action: () -> Unit) {
            row.addView(
                context.overlayButton(label, action),
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { marginEnd = context.dp(4f) },
            )
        }
        add("+Tap") { addPointStep { StepAction.Tap(it) } }
        add("+Long") { addPointStep { StepAction.LongPress(it) } }
        add("+2×Tap") { addPointStep { StepAction.DoubleTap(it) } }
        add("+Swipe") { addSwipe() }
        add("+Wait") { addWait() }
        add("Back") { addStep(StepAction.Global(GlobalActionType.BACK)) }
        add("Home") { addStep(StepAction.Global(GlobalActionType.HOME)) }
        add("Recents") { addStep(StepAction.Global(GlobalActionType.RECENTS)) }
        add("+Paste") { addPaste(PasteMode.SET_TEXT) }
        add("+Paste(popup)") { addPaste(PasteMode.LONG_PRESS_POPUP) }
        add("+App") { addLaunchApp() }
        add("+WaitText") { addWaitForText() }
        add("👁") { toggleMarkers() }
        add("↶") { undo() }
        add("✔ Done") { close() }

        val scroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
        val handle = TextView(context).apply {
            text = "⠿"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 20f
            gravity = Gravity.CENTER
            minWidth = context.dp(32f)
            minHeight = context.dp(40f)
        }
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pad = context.dp(4f)
            setPadding(pad, pad, pad, pad)
            background = roundedBackground(0xEE0D47A1.toInt(), context.dp(12f).toFloat())
            addView(handle)
            addView(scroll, LinearLayout.LayoutParams(context.resources.displayMetrics.widthPixels - context.dp(56f), LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        attachDrag(handle, bar, toolbarParams, wm)
        wm.addView(bar, toolbarParams)
        toolbar = bar
    }

    private var markersVisible = true

    private fun toggleMarkers() {
        markersVisible = !markersVisible
        setMarkersVisible(markersVisible)
    }

    // ---- Adding steps --------------------------------------------------------------------------

    /** New markers appear near the screen center, slightly staggered so they don't stack. */
    private fun spawnPoint(): NormalizedPoint {
        val n = steps.size % 6
        return NormalizedPoint(0.5f, (0.35f + n * 0.06f).coerceAtMost(0.9f))
    }

    private fun addPointStep(factory: (NormalizedPoint) -> StepAction) = addStep(factory(spawnPoint()))

    private fun addSwipe() {
        val start = spawnPoint()
        addStep(StepAction.Swipe(start, NormalizedPoint(start.x, (start.y - 0.25f).coerceAtLeast(0.05f))))
    }

    private fun addWait() {
        prompt.show("Wait (ms) — e.g. 1500 or 1000-3000", "1000", numeric = true) { input ->
            val range = parseRange(input ?: return@show) ?: return@show toast("Invalid number")
            addStep(StepAction.Wait(range.first, range.second))
        }
    }

    private fun addPaste(mode: PasteMode) {
        prompt.show("Text to paste (Arabic & emoji OK)", "", numeric = false) { input ->
            if (input.isNullOrEmpty()) return@show
            addStep(StepAction.PasteText(text = input, mode = mode, target = spawnPoint()))
        }
    }

    private fun addLaunchApp() {
        val app = listener.currentForegroundApp()
        if (app != null) {
            addStep(StepAction.LaunchApp(app.first, app.second))
            toast("Added: launch ${app.second}. Change it in the app editor if needed.")
        } else {
            prompt.show("Package name to launch", "", numeric = false) { input ->
                if (!input.isNullOrBlank()) addStep(StepAction.LaunchApp(input.trim()))
            }
        }
    }

    private fun addWaitForText() {
        prompt.show("Wait until this text appears", "", numeric = false) { input ->
            if (!input.isNullOrBlank()) addStep(StepAction.WaitForText(input.trim()))
        }
    }

    private fun addStep(action: StepAction) {
        steps.add(MacroStep(action = action))
        rebuildMarkers()
        listener.onStepsChanged(steps.toList())
        toast("Step ${steps.size}: ${action.typeLabel()}")
    }

    private fun undo() {
        if (steps.isEmpty()) return
        steps.removeAt(steps.lastIndex)
        rebuildMarkers()
        listener.onStepsChanged(steps.toList())
    }

    // ---- Markers -------------------------------------------------------------------------------

    private fun rebuildMarkers() {
        removeMarkers()
        val screen = screenProvider()
        steps.forEachIndexed { index, step ->
            val pts = step.action.points
            pts.forEachIndexed { pi, p ->
                val label = when {
                    step.action is StepAction.Swipe -> "${index + 1}${if (pi == 0) "a" else "b"}"
                    step.action is StepAction.PasteText && pi == 1 -> "${index + 1}p"
                    else -> "${index + 1}"
                }
                addMarker(step, pi, p, label, screen)
            }
        }
        setMarkersVisible(markersVisible)
    }

    private fun addMarker(step: MacroStep, pointIndex: Int, p: NormalizedPoint, label: String, screen: ScreenSize) {
        val color = colorFor(step.action)
        val view = MarkerView(context, label, color)
        val px = CoordinateScaler.toPixels(p, screen)
        val params = OverlayWindows.params().apply {
            x = px.x.toInt() - markerSize / 2
            y = px.y.toInt() - markerSize / 2
        }
        val marker = Marker(step.id, pointIndex, view, params)
        attachDrag(view, view, params, wm, onDragEnd = { commitMarker(marker) })
        wm.addView(view, params)
        markers += marker
        // Some devices offset overlay windows (status bar / cutout). Measure once and correct.
        view.post {
            val loc = IntArray(2)
            view.getLocationOnScreen(loc)
            val wantedX = params.x
            val wantedY = params.y
            marker.calibX = loc[0] - wantedX
            marker.calibY = loc[1] - wantedY
            if (marker.calibX != 0 || marker.calibY != 0) {
                params.x = wantedX - marker.calibX
                params.y = wantedY - marker.calibY
                wm.safeUpdate(view, params)
            }
        }
    }

    /** Writes the marker's on-screen center back into its step as a percentage. */
    private fun commitMarker(marker: Marker) {
        val screen = screenProvider()
        val cx = marker.params.x + marker.calibX + markerSize / 2f
        val cy = marker.params.y + marker.calibY + markerSize / 2f
        val point = CoordinateScaler.toNormalized(cx, cy, screen)
        val index = steps.indexOfFirst { it.id == marker.stepId }
        if (index < 0) return
        val step = steps[index]
        steps[index] = step.copy(action = step.action.withPoint(marker.pointIndex, point))
        listener.onStepsChanged(steps.toList())
    }

    private fun removeMarkers() {
        markers.forEach { wm.safeRemove(it.view) }
        markers.clear()
    }

    private fun colorFor(action: StepAction): Int = when (action) {
        is StepAction.Tap -> 0xFFE53935.toInt()
        is StepAction.LongPress -> 0xFFFB8C00.toInt()
        is StepAction.DoubleTap -> 0xFF8E24AA.toInt()
        is StepAction.Swipe -> 0xFF1E88E5.toInt()
        is StepAction.PasteText -> 0xFF43A047.toInt()
        else -> 0xFF757575.toInt()
    }

    private fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    companion object {
        /** Parses "1500" or "1000-3000" into a (min, max) pair. */
        fun parseRange(text: String): Pair<Long, Long>? {
            val parts = text.split('-', '–').map { it.trim() }.filter { it.isNotEmpty() }
            val nums = parts.map { it.toLongOrNull() ?: return null }
            return when (nums.size) {
                1 -> nums[0] to nums[0]
                2 -> minOf(nums[0], nums[1]) to maxOf(nums[0], nums[1])
                else -> null
            }
        }
    }
}
