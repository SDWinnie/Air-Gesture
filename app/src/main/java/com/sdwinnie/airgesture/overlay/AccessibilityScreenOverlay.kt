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
 * Zero-Permission-Flag On-Screen Circular Wheel & Dynamic Island Overlay (v3.0).
 *
 * Uses official Android [WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY] bound strictly
 * to [AccessibilityService] token — requiring ZERO `SYSTEM_ALERT_WINDOW` permission in AndroidManifest.xml!
 * Renders:
 * 1. Top-edge Dynamic Island & Live Volume Status Banner (`🔉 กำลังลดเสียง ▼` / `🔊 กำลังเพิ่มเสียง ▲`)
 * 2. Centered 360° Circular App Selector Wheel right on top of any foreground app!
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
    private var attached = false

    fun attachIfNeeded() {
        if (attached) return
        try {
            val cfg = preferenceStore.loadConfig()

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

            windowManager.addView(topIsland, topParams)
            windowManager.addView(centerWheel, centerParams)
            islandView = topIsland
            wheelView = centerWheel
            attached = true
        } catch (t: Throwable) {
            AirLogger.e("A11yOverlay", "Failed to attach TYPE_ACCESSIBILITY_OVERLAY", t)
            detach()
        }
    }

    fun applyConfig(config: AirGestureConfig) {
        islandView?.applyVisualStyle(config.hudPalmStyle, config.hudThemeColor)
        wheelView?.applyThemeColor(config.hudThemeColor)
        wheelView?.refreshSlots(config.radialSlotCount.count)
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

        if (!engineRunning) {
            island.visibility = View.GONE
            wheel.visibility = View.GONE
            return
        }

        // 1. Centered Circular App Wheel pops up directly in the center of the screen!
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

        // 2. Top-edge Dynamic Island & Volume Status Pill (hidden when dormant or when MainActivity's dock is visible)
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
        islandView?.let { v -> runCatching { windowManager.removeViewImmediate(v) } }
        wheelView?.let { v -> runCatching { windowManager.removeViewImmediate(v) } }
        islandView = null
        wheelView = null
    }
}
