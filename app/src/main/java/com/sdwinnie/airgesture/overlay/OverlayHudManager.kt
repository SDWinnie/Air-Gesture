package com.sdwinnie.airgesture.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.input.InputManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.sdwinnie.airgesture.input.RadialAppSlotManager
import com.sdwinnie.airgesture.model.HudVisualState
import com.sdwinnie.airgesture.security.AirLogger
import kotlin.math.min

/**
 * Manages the top-edge Dynamic Island HUD capsule and centered 15-App Radial Pie Menu
 * using WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY.
 *
 * Android 12–16+ Untrusted-Touch Compliance:
 * Uses compact WRAP_CONTENT bounds + touch-passthrough opacity <= InputManager.maximumObscuringOpacityForTouch
 * so underlying apps NEVER have user touch events blocked while the HUD is visible.
 */
class OverlayHudManager(
    private val context: Context,
    private val radialAppSlotManager: RadialAppSlotManager
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager = context.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val inputManager = context.applicationContext.getSystemService(Context.INPUT_SERVICE) as InputManager

    private var dynamicIslandView: DynamicIslandView? = null
    private var radialSelectorView: RadialAppSelectorView? = null
    private var attached = false

    fun attachOverlaysIfPermitted() {
        mainHandler.post {
            if (attached) return@post
            if (!Settings.canDrawOverlays(context)) {
                AirLogger.w("Overlay", "SYSTEM_ALERT_WINDOW not yet granted; skipping HUD attach")
                return@post
            }

            val safePassthroughAlpha = computeSafePassthroughAlpha()

            // 1. Top-Edge Dynamic Island Capsule
            val island = DynamicIslandView(context)
            val islandParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = 0
                alpha = safePassthroughAlpha
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }

            // 2. Centered 360-Degree 15-App Radial Pie Menu (Hidden until Fist Gesture triggers)
            val radial = RadialAppSelectorView(context, radialAppSlotManager).apply {
                visibility = View.GONE
                refreshSlots()
            }
            val radialParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.CENTER
                alpha = safePassthroughAlpha
            }

            runCatching {
                windowManager.addView(island, islandParams)
                windowManager.addView(radial, radialParams)
                dynamicIslandView = island
                radialSelectorView = radial
                attached = true
            }.onFailure { err ->
                AirLogger.e("Overlay", "Failed to attach HUD overlay windows", err)
            }
        }
    }

    fun updateHudState(state: HudVisualState) {
        mainHandler.post {
            if (!attached && Settings.canDrawOverlays(context)) {
                attachOverlaysIfPermitted()
            }
            dynamicIslandView?.updateState(state)

            val radial = radialSelectorView ?: return@post
            if (state is HudVisualState.RadialSelectorActive) {
                if (radial.visibility != View.VISIBLE) {
                    radial.refreshSlots()
                    radial.visibility = View.VISIBLE
                }
                radial.updateSelection(state.angleDegrees, state.selectedSlot)
            } else if (radial.visibility != View.GONE) {
                radial.visibility = View.GONE
            }
        }
    }

    fun notifyRadialSlotsChanged() {
        mainHandler.post {
            radialSelectorView?.refreshSlots()
        }
    }

    fun detachOverlays() {
        mainHandler.post {
            dynamicIslandView?.let { v ->
                runCatching { windowManager.removeViewImmediate(v) }
            }
            radialSelectorView?.let { v ->
                runCatching { windowManager.removeViewImmediate(v) }
            }
            dynamicIslandView = null
            radialSelectorView = null
            attached = false
        }
    }

    private fun computeSafePassthroughAlpha(): Float {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            min(0.79f, inputManager.maximumObscuringOpacityForTouch)
        } else {
            0.96f
        }
    }
}
