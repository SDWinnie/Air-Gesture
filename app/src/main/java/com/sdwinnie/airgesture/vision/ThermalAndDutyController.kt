package com.sdwinnie.airgesture.vision

import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.sdwinnie.airgesture.security.AirLogger

/**
 * Dynamic Frame-Skipping & Hardware Thermal Throttling Guard.
 * Protects battery health and prevents device overheating during prolonged 60/120 FPS sessions.
 */
class ThermalAndDutyController(context: Context? = null) {

    private val powerManager = context?.applicationContext?.getSystemService(Context.POWER_SERVICE) as? PowerManager

    @Volatile
    var currentThermalStatus: Int = 0
        private set

    private var thermalListener: Any? = null

    fun startMonitoring(onThermalChanged: (Int) -> Unit) {
        val pm = powerManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            currentThermalStatus = pm.currentThermalStatus
            val listener = PowerManager.OnThermalStatusChangedListener { status ->
                currentThermalStatus = status
                AirLogger.d("Thermal", "Thermal status updated: $status")
                onThermalChanged(status)
            }
            thermalListener = listener
            runCatching {
                pm.addThermalStatusListener(listener)
            }
        }
    }

    fun stopMonitoring() {
        val pm = powerManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val listener = thermalListener as? PowerManager.OnThermalStatusChangedListener ?: return
            runCatching {
                pm.removeThermalStatusListener(listener)
            }
            thermalListener = null
        }
    }

    /**
     * Computes adaptive minimum interval (ms) between neural inferences based on:
     * 1. OS thermal status (THERMAL_STATUS_MODERATE / SEVERE / CRITICAL)
     * 2. Active gesture/eye tracking presence (unthrottled 60/120 FPS when eye or hand is active)
     */
    fun computeMinInferenceIntervalMs(
        configuredTargetFps: Int,
        lastActivityDetectedMs: Long,
        nowMs: Long,
        isEyeTrackingEnabled: Boolean = false
    ): Long {
        val status = currentThermalStatus
        // Android PowerManager.THERMAL_STATUS_MODERATE = 2, SEVERE = 3, CRITICAL = 4+
        if (status >= 3) {
            return 83L // ~12 FPS thermal emergency protection
        }
        if (status == 2) {
            return 33L // ~30 FPS moderate thermal cap
        }

        val targetFps = configuredTargetFps.coerceIn(30, 120)
        val fullSpeedIntervalMs = (1000L / targetFps).coerceAtLeast(8L)

        // When Eye Tracking is enabled, ALWAYS maintain full high-rate sampling (60/120 FPS)
        // so rapid human blinks (80-250ms) are tracked continuously with zero dropped frames!
        if (isEyeTrackingEnabled) {
            return fullSpeedIntervalMs
        }

        val recentlyActive = lastActivityDetectedMs > 0L && (nowMs - lastActivityDetectedMs) <= IDLE_ECO_TIMEOUT_MS
        if (!recentlyActive) {
            return 33L // Smooth 30 FPS sentry (never drop to sluggish 15 FPS)
        }

        return fullSpeedIntervalMs
    }

    val isThermalThrottling: Boolean
        get() = currentThermalStatus >= 2

    companion object {
        private const val IDLE_ECO_TIMEOUT_MS = 3500L
    }
}
