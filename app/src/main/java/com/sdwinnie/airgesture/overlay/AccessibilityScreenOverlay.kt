package com.sdwinnie.airgesture.overlay

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.sdwinnie.airgesture.input.RadialAppSlotManager
import com.sdwinnie.airgesture.model.AirGestureConfig
import com.sdwinnie.airgesture.model.HudVisualState
import com.sdwinnie.airgesture.security.AirLogger
import com.sdwinnie.airgesture.security.PreferenceStore

/**
 * Zero-Permission-Flag On-Screen Circular Wheel, Dynamic Island & Air Cursor Overlay (v4.0).
 *
 * Uses official Android [WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY] bound strictly
 * to [AccessibilityService] token — requiring ZERO `SYSTEM_ALERT_WINDOW` permission in AndroidManifest.xml!
 * Renders:
 * 1. Top-edge Dynamic Island & Live Volume Status Banner (`🔉 กำลังลดเสียง ▼` / `🔊 กำลังเพิ่มเสียง ▲`)
 * 2. Centered 360° Circular App Selector Wheel right on top of any foreground app!
 * 3. High-tech Touchless Air Cursor target reticle and Air Tap ripple shockwaves.
 */
class AccessibilityScreenOverlay(
    private val service: AccessibilityService
) {
    private val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val preferenceStore = PreferenceStore(service)
    private val slotManager = RadialAppSlotManager(service, preferenceStore)
    private val density = service.resources.displayMetrics.density
    private fun dp(v: Float): Int = (v * density).toInt()

    private var islandView: DynamicIslandView? = null
    private var wheelView: RadialAppSelectorView? = null
    private var cursorView: AirCursorOverlayView? = null
    private var activeConfig: AirGestureConfig = AirGestureConfig()
    private var attached = false

    fun attachIfNeeded() {
        if (attached) return
        try {
            val cfg = preferenceStore.loadConfig()
            activeConfig = cfg

            val topIsland = DynamicIslandView(service).apply {
                applyVisualStyle(cfg.hudPalmStyle, cfg.hudThemeColor)
                visibility = View.GONE
            }
            val topParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = dp(10f)
                title = "AirGestureDynamicIsland"
            }

            val centerWheel = RadialAppSelectorView(service).apply {
                attachSlotManager(slotManager, cfg.radialSlotCount.count)
                applyThemeColor(cfg.hudThemeColor)
                visibility = View.GONE
            }
            val wheelSize = dp(336f)
            val centerParams = WindowManager.LayoutParams(
                wheelSize,
                wheelSize,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.CENTER
                title = "AirGestureCircularWheel"
            }

            val fullCursor = AirCursorOverlayView(service).apply {
                applyTheme(cfg.hudThemeColor)
            }
            val cursorParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                title = "AirGestureAirCursor"
            }

            windowManager.addView(fullCursor, cursorParams)
            windowManager.addView(topIsland, topParams)
            windowManager.addView(centerWheel, centerParams)
            cursorView = fullCursor
            islandView = topIsland
            wheelView = centerWheel
            attached = true
        } catch (t: Throwable) {
            AirLogger.e("A11yOverlay", "Failed to attach TYPE_ACCESSIBILITY_OVERLAY", t)
            detach()
        }
    }

    fun applyConfig(config: AirGestureConfig) {
        activeConfig = config
        islandView?.applyVisualStyle(config.hudPalmStyle, config.hudThemeColor)
        wheelView?.applyThemeColor(config.hudThemeColor)
        wheelView?.refreshSlots(config.radialSlotCount.count)
        cursorView?.applyTheme(config.hudThemeColor)
    }

    fun refreshSlots(slotCount: Int) {
        wheelView?.refreshSlots(slotCount)
    }

    fun render(
        hudState: HudVisualState,
        engineRunning: Boolean,
        dashboardInForeground: Boolean
    ) {
        if (!attached) {
            if (engineRunning) attachIfNeeded() else return
        }

        val island = islandView ?: return
        val wheel = wheelView ?: return
        val cursor = cursorView

        if (!engineRunning) {
            island.visibility = View.GONE
            wheel.visibility = View.GONE
            cursor?.updatePointer(0.5f, 0.5f, visible = false)
            return
        }

        // 1. Air Cursor Reticle & Ripple Animation
        if (hudState is HudVisualState.PalmTracking && activeConfig.showAirCursor) {
            cursor?.updatePointer(hudState.cursorNormX, hudState.cursorNormY, visible = true)
        } else if (hudState is HudVisualState.AirTapFeedback) {
            cursor?.triggerAirTapRipple()
            cursor?.updatePointer(hudState.normX, hudState.normY, visible = activeConfig.showAirCursor)
        } else {
            cursor?.updatePointer(0.5f, 0.5f, visible = false)
        }

        // 2. Centered Circular App Wheel
        if (hudState is HudVisualState.RadialSelectorActive) {
            wheel.updateSelection(
                angleDegrees = hudState.angleDegrees,
                selectedSlot = hudState.selectedSlot,
                totalSlots = hudState.totalSlots,
                normCursorX = hudState.cursorNormX,
                normCursorY = hudState.cursorNormY,
                cancelZone = hudState.inCancelZone
            )
            if (wheel.visibility != View.VISIBLE) {
                wheel.visibility = View.VISIBLE
            }
        } else if (wheel.visibility != View.GONE) {
            wheel.visibility = View.GONE
        }

        // 3. Top-edge Dynamic Island & Volume Status Pill
        val shouldShowTopIsland = !dashboardInForeground && hudState !is HudVisualState.Dormant
        if (shouldShowTopIsland) {
            if (island.visibility != View.VISIBLE) {
                island.visibility = View.VISIBLE
            }
            island.updateState(hudState)
        } else if (island.visibility != View.GONE) {
            island.updateState(HudVisualState.Dormant)
            island.visibility = View.GONE
        }
    }

    fun detach() {
        attached = false
        cursorView?.let { v -> runCatching { windowManager.removeViewImmediate(v) } }
        islandView?.let { v -> runCatching { windowManager.removeViewImmediate(v) } }
        wheelView?.let { v -> runCatching { windowManager.removeViewImmediate(v) } }
        cursorView = null
        islandView = null
        wheelView = null
    }
}
