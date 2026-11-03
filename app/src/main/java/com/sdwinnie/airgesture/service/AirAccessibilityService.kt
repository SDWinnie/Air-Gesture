package com.sdwinnie.airgesture.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.sdwinnie.airgesture.model.HorizontalNavMode
import com.sdwinnie.airgesture.overlay.AccessibilityScreenOverlay
import com.sdwinnie.airgesture.security.AirLogger
import com.sdwinnie.airgesture.security.PreferenceStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Touchless Gesture Injector & Official TYPE_ACCESSIBILITY_OVERLAY Host (v3.0).
 *
 * Privacy Hardened (res/xml/accessibility_service_config.xml):
 * - canPerformGestures="true"
 * - canRetrieveWindowContent="false" (Strictly incapable of reading screen text or UI trees)
 * - Zero SYSTEM_ALERT_WINDOW permission required in AndroidManifest.xml
 */
class AirAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var serviceScope: CoroutineScope? = null
    private var screenOverlay: AccessibilityScreenOverlay? = null

    @Volatile
    private var lastStrokeEndMs: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeInstance = this
        AirGestureRuntimeBus.setAccessibilityConnected(true)

        val prefs = PreferenceStore(this)
        AirGestureRuntimeBus.updateVisualConfig(prefs.loadConfig())

        val overlay = AccessibilityScreenOverlay(this).also {
            it.attachIfNeeded()
        }
        screenOverlay = overlay

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        serviceScope = scope

        scope.launch {
            combine(
                AirGestureRuntimeBus.hudState,
                AirGestureRuntimeBus.telemetry,
                AirGestureRuntimeBus.dashboardForeground
            ) { hud, telemetry, dashFg ->
                Triple(hud, telemetry.serviceRunning, dashFg)
            }.collect { (hud, running, dashFg) ->
                overlay.render(
                    hudState = hud,
                    engineRunning = running,
                    dashboardInForeground = dashFg
                )
            }
        }

        scope.launch {
            AirGestureRuntimeBus.visualConfig.collect { cfg ->
                overlay.applyConfig(cfg)
            }
        }

        scope.launch {
            AirGestureRuntimeBus.radialSlotsVersion.collect {
                overlay.refreshSlots(AirGestureRuntimeBus.visualConfig.value.radialSlotCount.count)
            }
        }

        AirLogger.d("A11y", "AirAccessibilityService v3.0 connected (touchless scroll & circular HUD ready)")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Intentionally no-op: zero screen event reading.
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        teardownOverlayAndScope()
        if (activeInstance === this) {
            activeInstance = null
        }
        AirGestureRuntimeBus.setAccessibilityConnected(false)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardownOverlayAndScope()
        if (activeInstance === this) {
            activeInstance = null
        }
        AirGestureRuntimeBus.setAccessibilityConnected(false)
        super.onDestroy()
    }

    private fun teardownOverlayAndScope() {
        serviceScope?.cancel()
        serviceScope = null
        screenOverlay?.detach()
        screenOverlay = null
    }

    fun performQuickBack(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_BACK)
    }

    fun performGoHome(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_HOME)
    }

    fun performOpenRecents(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_RECENTS)
    }

    fun performTakeScreenshot(): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
        } else {
            false
        }
    }

    fun injectAirTapClick(normX: Float? = null, normY: Float? = null): Boolean {
        val (screenW, screenH) = currentScreenDimensions()
        val targetX = if (normX != null) (normX * screenW).coerceIn(12f, screenW - 12f) else (screenW * 0.5f)
        val targetY = if (normY != null) (normY * screenH).coerceIn(12f, screenH - 12f) else (screenH * 0.5f)

        val path = Path().apply {
            moveTo(targetX, targetY)
            lineTo(targetX + 1f, targetY + 1f)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 40L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    fun injectAirTapDoubleClick(normX: Float? = null, normY: Float? = null): Boolean {
        val (screenW, screenH) = currentScreenDimensions()
        val targetX = if (normX != null) (normX * screenW).coerceIn(12f, screenW - 12f) else (screenW * 0.5f)
        val targetY = if (normY != null) (normY * screenH).coerceIn(12f, screenH - 12f) else (screenH * 0.5f)

        val path1 = Path().apply {
            moveTo(targetX, targetY)
            lineTo(targetX + 1f, targetY + 1f)
        }
        val path2 = Path().apply {
            moveTo(targetX, targetY)
            lineTo(targetX + 1f, targetY + 1f)
        }
        val stroke1 = GestureDescription.StrokeDescription(path1, 0L, 35L)
        val stroke2 = GestureDescription.StrokeDescription(path2, 85L, 35L)
        val gesture = GestureDescription.Builder().addStroke(stroke1).addStroke(stroke2).build()
        return dispatchGesture(gesture, null, null)
    }

    fun injectMediaPlayPause(): Boolean {
        val am = getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
        val down = android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        val up = android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        am?.dispatchMediaKeyEvent(down)
        am?.dispatchMediaKeyEvent(up)
        return injectAirTapClick(0.5f, 0.5f)
    }

    /**
     * Injects rapid double tap at the specified normalized position to like / heart (เช่น บน TikTok, Reels, IG, Shorts).
     */
    fun injectHeartDoubleTap(normX: Float = 0.5f, normY: Float = 0.5f): Boolean {
        return injectAirTapDoubleClick(normX, normY)
    }

    /**
     * Injects vertical scroll triggered by Eye Blinks (1 Blink = Down, 2 Blinks = Up).
     */
    fun injectBlinkScroll(scrollUp: Boolean, sensitivity: Float = 1.0f): Boolean {
        return injectSmoothVerticalScroll(handMovedUp = scrollUp, normalizedSpeed = 3.2f, sensitivity = sensitivity)
    }

    fun injectSmoothVerticalScroll(
        handMovedUp: Boolean,
        normalizedSpeed: Float,
        sensitivity: Float
    ): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now < lastStrokeEndMs) {
            return false
        }

        val (screenW, screenH) = currentScreenDimensions()
        val centerX = screenW * 0.52f
        val speedFactor = ((normalizedSpeed / 4.0f) * sensitivity).coerceIn(0.65f, 1.60f)
        val travelFraction = (0.30f * speedFactor).coerceIn(0.20f, 0.50f)
        val halfTravel = (screenH * travelFraction) * 0.5f
        val midY = screenH * 0.52f

        val startY: Float
        val endY: Float
        if (handMovedUp) {
            startY = (midY + halfTravel).coerceIn(screenH * 0.15f, screenH * 0.88f)
            endY = (midY - halfTravel).coerceIn(screenH * 0.12f, screenH * 0.85f)
        } else {
            startY = (midY - halfTravel).coerceIn(screenH * 0.12f, screenH * 0.85f)
            endY = (midY + halfTravel).coerceIn(screenH * 0.15f, screenH * 0.88f)
        }

        val durationMs = (185L / speedFactor.coerceIn(0.8f, 1.35f)).toLong().coerceIn(130L, 220L)
        val path = Path().apply {
            moveTo(centerX, startY)
            quadTo(centerX + 6f, (startY + endY) * 0.5f, centerX, endY)
        }

        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        lastStrokeEndMs = now + durationMs - 20L
        return dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    lastStrokeEndMs = 0L
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    lastStrokeEndMs = 0L
                }
            },
            null
        )
    }

    fun performHorizontalAction(swipeLeft: Boolean, mode: HorizontalNavMode): String {
        return when (mode) {
            HorizontalNavMode.BACK_ONLY -> {
                performGlobalAction(GLOBAL_ACTION_BACK)
                "← ย้อนกลับ (Back)"
            }

            HorizontalNavMode.BACK_AND_RECENTS -> {
                if (swipeLeft) {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    "← ย้อนกลับ (Back)"
                } else {
                    performGlobalAction(GLOBAL_ACTION_RECENTS)
                    "สลับแอป (Recents) →"
                }
            }

            HorizontalNavMode.BACK_AND_QUICK_SWITCH -> {
                if (swipeLeft) {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    "← ย้อนกลับ (Back)"
                } else {
                    performGlobalAction(GLOBAL_ACTION_RECENTS)
                    mainHandler.postDelayed({
                        performGlobalAction(GLOBAL_ACTION_RECENTS)
                    }, 180L)
                    "สลับแอปก่อนหน้า ⇄"
                }
            }
        }
    }

    private fun currentScreenDimensions(): Pair<Float, Float> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            bounds.width().toFloat().coerceAtLeast(360f) to bounds.height().toFloat().coerceAtLeast(640f)
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            dm.widthPixels.toFloat().coerceAtLeast(360f) to dm.heightPixels.toFloat().coerceAtLeast(640f)
        }
    }

    companion object {
        @Volatile
        var activeInstance: AirAccessibilityService? = null
            private set

        fun isEnabledInSettings(context: Context): Boolean {
            if (activeInstance != null) return true
            val expected = "${context.packageName}/${AirAccessibilityService::class.java.canonicalName}"
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
            return enabledServices.split(':').any { it.equals(expected, ignoreCase = true) }
        }
    }
}
