package com.choice.autotap.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import com.choice.autotap.appGraph
import com.choice.autotap.R
import com.choice.autotap.gesture.AccessibilityActionExecutor
import com.choice.autotap.gesture.ClickRecorder
import com.choice.autotap.gesture.GestureGuard
import com.choice.autotap.gesture.ScreenMetrics
import com.choice.autotap.model.Macro
import com.choice.autotap.model.MacroStep
import com.choice.autotap.model.RecordingConverter
import com.choice.autotap.overlay.BubbleListener
import com.choice.autotap.overlay.ControlBubble
import com.choice.autotap.overlay.MarkerEditor
import com.choice.autotap.overlay.MarkerEditorListener
import com.choice.autotap.player.MacroPlayer
import com.choice.autotap.player.PlaybackStatus
import com.choice.autotap.player.TriplePressDetector
import com.choice.autotap.ui.MainActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * The heart of the app: performs gestures in any app (dispatchGesture), hosts the floating
 * bubble and marker overlays (TYPE_ACCESSIBILITY_OVERLAY), records clicks, and listens for the
 * volume-down x3 emergency stop.
 */
class AutoTapAccessibilityService : AccessibilityService(), BubbleListener, MarkerEditorListener, GestureGuard {

    private val scope = MainScope()
    private var bubble: ControlBubble? = null
    private var markerEditor: MarkerEditor? = null
    private val recorder by lazy { ClickRecorder(packageName) }
    private val volumeDown = TriplePressDetector(count = 3, windowMs = 1500)

    private var player: MacroPlayer? = null
    private var playJob: Job? = null
    private var editingMacroId: Long? = null
    private var foregroundApp: Pair<String, String>? = null

    val isPlaying: Boolean get() = playJob?.isActive == true

    override fun onServiceConnected() {
        super.onServiceConnected()
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        bubble = ControlBubble(this, wm, this).also { it.show() }
        markerEditor = MarkerEditor(this, wm, { ScreenMetrics.realSize(this) }, this)
        AutoTapBridge.attach(this)
        scope.launch {
            AutoTapBridge.activeMacroId.collectLatest { refreshIdle() }
        }
        // Licensing gate: stop a run if the license stops being valid, and re-verify periodically
        // (the controller itself only contacts the server when a check is due, at most every 12 h).
        scope.launch {
            appGraph.license.status.collect { s ->
                if (!s.state.isUsable && isPlaying) {
                    stopPlayback()
                    toast(getString(R.string.license_stopped_toast))
                }
            }
        }
        scope.launch {
            while (true) {
                runCatching { appGraph.license.refreshIfDue() }
                delay(LICENSE_CHECK_INTERVAL_MS)
            }
        }
    }

    /** True if the app is licensed; otherwise explains why and opens the activation screen. */
    private fun requireLicense(): Boolean {
        val license = appGraph.license
        license.reevaluate()
        if (license.isUsable) return true
        toast(getString(R.string.license_required_toast))
        onOpenApp()
        return false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (recorder.onEvent(event)) {
            bubble?.setMode(ControlBubble.Mode.Recording(recorder.count))
        }
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: return
            if (pkg != packageName && pkg != "com.android.systemui") {
                if (foregroundApp?.first != pkg) foregroundApp = pkg to appLabel(pkg)
            }
        }
    }

    override fun onInterrupt() = Unit

    /** Emergency stop: volume-down pressed 3 times within 1.5 s while a macro runs. */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && event.action == KeyEvent.ACTION_DOWN && isPlaying) {
            if (volumeDown.onPress(event.eventTime)) {
                stopPlayback()
                toast("Emergency stop")
                return true
            }
        }
        return false
    }

    override fun onUnbind(intent: Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        playJob?.cancel()
        markerEditor?.close(notify = false)
        bubble?.destroy()
        bubble = null
        markerEditor = null
        AutoTapBridge.detach(this)
        scope.cancel()
    }

    // ---- Playback ------------------------------------------------------------------------------

    fun startPlayback(macroId: Long) {
        scope.launch {
            val macro = appGraph.macros.get(macroId) ?: return@launch toast("Macro not found")
            startPlayback(macro)
        }
    }

    fun startPlayback(macro: Macro) {
        if (!requireLicense()) return
        if (isPlaying) return toast("A macro is already running")
        if (macro.steps.none { it.enabled }) return toast("This macro has no enabled steps")
        if (recorder.isRecording) recorder.stop()
        markerEditor?.close() // hide markers so they don't block the taps
        AutoTapBridge.activeMacroId.value = macro.id

        val p = MacroPlayer(AccessibilityActionExecutor(this, this))
        player = p
        volumeDown.reset()
        bubble?.show()
        bubble?.setKeepScreenOn(true)
        PlaybackService.start(this)

        playJob = scope.launch {
            val stateJob = launch {
                p.state.collect { s ->
                    AutoTapBridge.playback.value = s
                    if (s.isActive) {
                        bubble?.setMode(ControlBubble.Mode.Running(s.shortLabel(), s.status == PlaybackStatus.PAUSED))
                    }
                }
            }
            try {
                p.run(macro, onRunFinished = { log -> appGraph.appScope.launch { appGraph.logs.save(log) } })
            } finally {
                stateJob.cancel()
                AutoTapBridge.playback.value = p.state.value
                player = null
                bubble?.setPassThrough(false)
                bubble?.setKeepScreenOn(false)
                PlaybackService.stop(this@AutoTapAccessibilityService)
                refreshIdle()
            }
        }
    }

    fun stopPlayback() {
        playJob?.cancel()
    }

    fun togglePause() {
        val p = player ?: return
        if (p.isPaused) p.resume() else p.pause()
    }

    // ---- Marker editor -------------------------------------------------------------------------

    /** Opens the on-screen marker editor for [macroId], or a brand new macro when null. */
    fun openMarkerEditor(macroId: Long?) {
        if (!requireLicense()) return
        if (isPlaying) return toast("Stop the running macro first")
        scope.launch {
            val id = macroId ?: appGraph.macros.create(defaultName())
            val macro = appGraph.macros.get(id) ?: return@launch
            AutoTapBridge.activeMacroId.value = id
            editingMacroId = id
            bubble?.hide()
            markerEditor?.open(macro.steps)
            toast("Add steps with the toolbar, drag the crosshairs, then ✔ Done")
        }
    }

    override fun onStepsChanged(steps: List<MacroStep>) {
        val id = editingMacroId ?: return
        scope.launch { appGraph.macros.updateSteps(id, steps) }
    }

    override fun onEditorClosed(steps: List<MacroStep>) {
        onStepsChanged(steps)
        editingMacroId = null
        bubble?.show()
        refreshIdle()
    }

    override fun currentForegroundApp(): Pair<String, String>? = foregroundApp

    // ---- Bubble --------------------------------------------------------------------------------

    fun showBubble() {
        bubble?.show()
        refreshIdle()
    }

    override fun onPlay() {
        val id = AutoTapBridge.activeMacroId.value ?: return toast("Choose a macro in the app first")
        startPlayback(id)
    }

    override fun onPauseToggle() = togglePause()

    override fun onStop() = stopPlayback()

    override fun onEditMarkers() = openMarkerEditor(AutoTapBridge.activeMacroId.value)

    override fun onRecordToggle() {
        if (recorder.isRecording) finishRecording() else {
            if (!requireLicense()) return
            recorder.start()
            bubble?.setMode(ControlBubble.Mode.Recording(0))
            toast("Recording: use the app normally, then press ■")
        }
    }

    override fun onOpenApp() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun onHideBubble() {
        bubble?.hide()
        toast("Bubble hidden. Open Choice Auto Tap to show it again.")
    }

    private fun finishRecording() {
        val clicks = recorder.stop()
        val steps = RecordingConverter.convert(clicks, ScreenMetrics.realSize(this))
        scope.launch {
            if (steps.isNotEmpty()) {
                val activeId = AutoTapBridge.activeMacroId.value
                val existing = activeId?.let { appGraph.macros.get(it) }
                val id = if (existing != null) {
                    appGraph.macros.save(existing.copy(steps = existing.steps + steps))
                } else {
                    appGraph.macros.save(Macro(name = "Recording " + defaultName(), steps = steps))
                }
                AutoTapBridge.activeMacroId.value = id
                toast("Recorded ${steps.size} steps. Edit them in the app.")
            } else {
                toast("Nothing recorded")
            }
            refreshIdle()
        }
    }

    private fun refreshIdle() {
        if (isPlaying || recorder.isRecording) return
        scope.launch {
            val name = AutoTapBridge.activeMacroId.value?.let { appGraph.macros.get(it)?.name }
            if (!isPlaying && !recorder.isRecording) bubble?.setMode(ControlBubble.Mode.Idle(name))
        }
    }

    // ---- GestureGuard: let injected taps pass through the bubble --------------------------------

    override fun beforeGesture() {
        bubble?.setPassThrough(true)
    }

    override fun afterGesture() {
        bubble?.setPassThrough(false)
    }

    // ---- Helpers -------------------------------------------------------------------------------

    private fun appLabel(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private fun defaultName(): String = "Macro " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date())

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val LICENSE_CHECK_INTERVAL_MS = 60L * 60 * 1000
    }
}
