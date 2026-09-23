package com.choice.autotap.service

import com.choice.autotap.player.PlaybackState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** In-process link between the UI, the accessibility service and the foreground service. */
object AutoTapBridge {
    private val _service = MutableStateFlow<AutoTapAccessibilityService?>(null)

    /** The connected accessibility service, or null when it is disabled. */
    val service: StateFlow<AutoTapAccessibilityService?> = _service.asStateFlow()

    val playback = MutableStateFlow(PlaybackState())

    /** Macro the floating bubble's ▶ / ✎ / ● buttons act on. */
    val activeMacroId = MutableStateFlow<Long?>(null)

    internal fun attach(s: AutoTapAccessibilityService) {
        _service.value = s
    }

    internal fun detach(s: AutoTapAccessibilityService) {
        if (_service.value === s) _service.value = null
    }
}
