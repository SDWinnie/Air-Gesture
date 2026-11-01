package com.sdwinnie.airgesture.vision

import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.sdwinnie.airgesture.security.AirLogger

/**
 * Dynamic Frame-Skipping & Hardware Thermal Throttling Guard.
 * Protects battery health and prevents device overheating during prolonged 60/120 FPS sessions.
 */
class ThermalAndDutyController(context: Context) {

    private val powerManager = context.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager

    @Volatile
    var currentThermalStatus: Int = 0
        private set

    private var thermalListener: Any? = null

    fun startMonitoring(onThermalChanged: (Int) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            currentThermalStatus = powerManager.currentThermalStatus
            val listener = PowerManager.OnThermalStatusChangedListener { status ->
                currentThermalStatus = status
                AirLogger.d("Thermal", "Thermal status updated: $status")
                onThermalChanged(status)
            }
            thermalListener = listener
            runCatching {
                powerManager.addThermalStatusListener(listener)
            }
        }
    }

    fun stopMonitoring() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val listener = thermalListener as? PowerManager.OnThermalStatusChangedListener ?: return
            runCatching {
                powerManager.removeThermalStatusListener(listener)
            }
            thermalListener = null
        }
    }

    /**
     * Computes adaptive minimum interval (ms) between neural inferences based on:
     * 1. OS thermal status (THERMAL_STATUS_MODERATE / SEVERE / CRITICAL)
     * 2. Hand presence activity (eco-sentry 15 FPS after 3.5s idle, instant 60/120 FPS burst on hand entry)
     */
    fun computeMinInferenceIntervalMs(
        configuredTargetFps: Int,
        lastHandDetectedMs: Long,
        nowMs: Long
    ): Long {
        val status = currentThermalStatus
        // Android PowerManager.THERMAL_STATUS_MODERATE = 2, SEVERE = 3, CRITICAL = 4+
        if (status >= 3) {
            return 83L // ~12 FPS thermal protection mode
        }
        if (status == 2) {
            return 33L // ~30 FPS moderate thermal cap
        }

        val handRecentlyActive = lastHandDetectedMs > 0L && (nowMs - lastHandDetectedMs) <= IDLE_ECO_TIMEOUT_MS
        if (!handRecentlyActive) {
            return 66L // ~15 FPS low-power palm sentry when no hand is in view
        }

        return (1000L / configuredTargetFps.coerceIn(30, 120)).coerceAtLeast(8L)
    }

    val isThermalThrottling: Boolean
        get() = currentThermalStatus >= 2

    companion object {
        private const val IDLE_ECO_TIMEOUT_MS = 3500L
    }
}
