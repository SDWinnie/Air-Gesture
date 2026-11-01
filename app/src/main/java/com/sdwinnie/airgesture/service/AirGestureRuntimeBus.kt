package com.sdwinnie.airgesture.service

import android.view.Surface
import com.sdwinnie.airgesture.model.TelemetrySnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * In-memory volatile runtime bus connecting the Foreground Service, Accessibility Service,
 * Quick Settings Tile, and MainActivity Control Center. Zero disk persistence.
 */
object AirGestureRuntimeBus {

    private val _telemetry = MutableStateFlow(TelemetrySnapshot())
    val telemetry: StateFlow<TelemetrySnapshot> = _telemetry.asStateFlow()

    private val _accessibilityConnected = MutableStateFlow(false)
    val accessibilityConnected: StateFlow<Boolean> = _accessibilityConnected.asStateFlow()

    @Volatile
    var liveCalibrationSurface: Surface? = null
        private set

    var onPreviewSurfaceChanged: ((Surface?) -> Unit)? = null

    fun updateTelemetry(transform: (TelemetrySnapshot) -> TelemetrySnapshot) {
        _telemetry.update(transform)
    }

    fun setAccessibilityConnected(connected: Boolean) {
        _accessibilityConnected.value = connected
    }

    fun bindCalibrationPreviewSurface(surface: Surface?) {
        liveCalibrationSurface = surface
        onPreviewSurfaceChanged?.invoke(surface)
    }
}
