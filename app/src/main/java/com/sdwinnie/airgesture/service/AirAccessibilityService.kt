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
import com.sdwinnie.airgesture.security.AirLogger

/**
 * Touchless Gesture & Global Action Injector for Android 14, 15, and 16+ (No Root / No ADB).
 *
 * Privacy Hardened (see res/xml/accessibility_service_config.xml):
 * - canPerformGestures="true"
 * - canRetrieveWindowContent="false" (Strictly incapable of reading screen text or UI trees)
 * - accessibilityEventTypes=""
 */
class AirAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var lastStrokeEndMs: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeInstance = this
        AirGestureRuntimeBus.setAccessibilityConnected(true)
        AirLogger.d("A11y", "AirAccessibilityService connected (touchless gesture injection ready)")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Intentionally no-op: Air Gesture subscribes to zero screen events and never reads window content.
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (activeInstance === this) {
            activeInstance = null
        }
        AirGestureRuntimeBus.setAccessibilityConnected(false)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (activeInstance === this) {
            activeInstance = null
        }
        AirGestureRuntimeBus.setAccessibilityConnected(false)
        super.onDestroy()
    }

    /**
     * Injects a smooth inertial vertical scroll stroke adapted to the current screen bounds
     * (works seamlessly across Phones, Tablets, Foldables, Portrait & Landscape).
     */
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
        val speedFactor = ((normalizedSpeed / 4.2f) * sensitivity).coerceIn(0.65f, 1.55f)
        val travelFraction = (0.28f * speedFactor).coerceIn(0.20f, 0.48f)
        val halfTravel = (screenH * travelFraction) * 0.5f
        val midY = screenH * 0.52f

        val startY: Float
        val endY: Float
        if (handMovedUp) {
            // Upward swipe stroke scrolls feed downward (natural scrolling)
            startY = (midY + halfTravel).coerceIn(screenH * 0.15f, screenH * 0.88f)
            endY = (midY - halfTravel).coerceIn(screenH * 0.12f, screenH * 0.85f)
        } else {
            // Downward swipe stroke scrolls feed upward
            startY = (midY - halfTravel).coerceIn(screenH * 0.12f, screenH * 0.85f)
            endY = (midY + halfTravel).coerceIn(screenH * 0.15f, screenH * 0.88f)
        }

        val durationMs = (185L / speedFactor.coerceIn(0.8f, 1.35f)).toLong().coerceIn(130L, 220L)
        val path = Path().apply {
            moveTo(centerX, startY)
            // Slight organic curve prevents synthetic straight-line rejection in custom webviews
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

    /**
     * Executes horizontal open-palm navigation action: Back, Recents, or Quick-Switch previous app.
     */
    fun performHorizontalAction(swipeLeft: Boolean, mode: HorizontalNavMode): String {
        return when (mode) {
            HorizontalNavMode.BACK_ONLY -> {
                performGlobalAction(GLOBAL_ACTION_BACK)
                "← BACK"
            }

            HorizontalNavMode.BACK_AND_RECENTS -> {
                if (swipeLeft) {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    "← BACK"
                } else {
                    performGlobalAction(GLOBAL_ACTION_RECENTS)
                    "RECENTS →"
                }
            }

            HorizontalNavMode.BACK_AND_QUICK_SWITCH -> {
                if (swipeLeft) {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    "← BACK"
                } else {
                    performGlobalAction(GLOBAL_ACTION_RECENTS)
                    mainHandler.postDelayed({
                        performGlobalAction(GLOBAL_ACTION_RECENTS)
                    }, 180L)
                    "SWITCH APP ⇄"
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
