package com.sdwinnie.airgesture.input

import com.sdwinnie.airgesture.engine.AirGestureStateMachine
import com.sdwinnie.airgesture.model.AirGestureConfig
import com.sdwinnie.airgesture.model.GestureEvent
import com.sdwinnie.airgesture.model.HandFrame
import com.sdwinnie.airgesture.model.HudVisualState
import com.sdwinnie.airgesture.service.AirAccessibilityService

/**
 * Translates high-level [GestureEvent] emissions from [AirGestureStateMachine]
 * into native OS actions (Accessibility scroll, Back/Recents, AudioManager volume, 15-App Radial Launch)
 * and computes the synchronized [HudVisualState] for the Dynamic Island & Radial Pie overlays.
 */
class TouchlessActionDispatcher(
    private val volumeController: SystemVolumeController,
    private val radialAppSlotManager: RadialAppSlotManager
) {
    data class DispatchOutcome(
        val hudState: HudVisualState,
        val actionSummary: String
    )

    private var lastStatusLabel = "PALM READY"
    private var lastActionSummary = "Waiting for open palm..."
    private var lastAccent = HudVisualState.AccentKind.CYAN

    fun dispatchEvents(
        mode: AirGestureStateMachine.Mode,
        events: List<GestureEvent>,
        smoothedFrame: HandFrame?,
        activeRadialSlot: Int,
        activeRadialAngleDeg: Float,
        config: AirGestureConfig,
        fps: Float,
        thermalWarning: Boolean
    ): DispatchOutcome {
        for (event in events) {
            when (event) {
                GestureEvent.WakeDynamicIsland -> {
                    lastStatusLabel = "PALM ACTIVE"
                    lastAccent = HudVisualState.AccentKind.CYAN
                    lastActionSummary = "Palm Detected • Dynamic Island Awake"
                }

                GestureEvent.SleepDynamicIsland -> {
                    volumeController.endPinchSession()
                    lastStatusLabel = "Dormant"
                    lastActionSummary = "Idle • Dynamic Island Sleeping"
                }

                is GestureEvent.VerticalScroll -> {
                    val a11y = AirAccessibilityService.activeInstance
                    val injected = a11y?.injectSmoothVerticalScroll(
                        handMovedUp = event.scrollUp,
                        normalizedSpeed = event.normalizedSpeed,
                        sensitivity = config.scrollSensitivity
                    ) ?: false

                    lastStatusLabel = if (event.scrollUp) "SCROLL DOWN ↓" else "SCROLL UP ↑"
                    lastAccent = HudVisualState.AccentKind.EMERALD
                    lastActionSummary = if (injected) {
                        lastStatusLabel
                    } else {
                        "$lastStatusLabel (Enable Accessibility)"
                    }
                }

                is GestureEvent.HorizontalNavigate -> {
                    val a11y = AirAccessibilityService.activeInstance
                    val label = a11y?.performHorizontalAction(
                        swipeLeft = event.swipeLeft,
                        mode = config.horizontalNavMode
                    ) ?: "NAV (Enable Accessibility)"
                    lastStatusLabel = label
                    lastAccent = HudVisualState.AccentKind.VIOLET
                    lastActionSummary = label
                }

                is GestureEvent.PinchVolumeModulate -> {
                    val volPct = if (event.normalizedDeltaY == 0f) {
                        volumeController.beginPinchSession()
                    } else {
                        volumeController.applyPinchDelta(event.normalizedDeltaY, config.volumeSensitivity)
                    }
                    lastStatusLabel = "VOLUME $volPct%"
                    lastActionSummary = "Pinch Volume: $volPct%"
                }

                GestureEvent.PinchEnded -> {
                    volumeController.endPinchSession()
                    lastStatusLabel = "PALM ACTIVE"
                    lastAccent = HudVisualState.AccentKind.CYAN
                    lastActionSummary = "Volume Set (${volumeController.currentVolumePercent()}%)"
                }

                is GestureEvent.RadialMenuSummoned -> {
                    lastStatusLabel = "15-APP RADIAL"
                    lastActionSummary = "Radial Menu Slot #${event.selectedSlot + 1} (${event.angleDegrees.toInt()}°)"
                }

                is GestureEvent.RadialMenuUpdated -> {
                    lastStatusLabel = "15-APP RADIAL"
                    lastActionSummary = "Targeting Slot #${event.selectedSlot + 1} (${event.angleDegrees.toInt()}°)"
                }

                is GestureEvent.RadialMenuCommitted -> {
                    val launched = radialAppSlotManager.launchSlot(event.selectedSlot)
                    lastStatusLabel = if (launched != null) "OPEN: ${launched.label.take(12).uppercase()}" else "PALM ACTIVE"
                    lastAccent = HudVisualState.AccentKind.EMERALD
                    lastActionSummary = if (launched != null) {
                        "Launched ${launched.label}"
                    } else {
                        "Slot #${event.selectedSlot + 1} Empty"
                    }
                }

                GestureEvent.RadialMenuCancelled -> {
                    lastStatusLabel = "PALM ACTIVE"
                    lastAccent = HudVisualState.AccentKind.CYAN
                    lastActionSummary = "Radial Menu Dismissed"
                }
            }
        }

        val resolvedHud: HudVisualState = when (mode) {
            AirGestureStateMachine.Mode.DORMANT -> HudVisualState.Dormant

            AirGestureStateMachine.Mode.PINCH_VOLUME -> {
                val vol = volumeController.currentVolumePercent()
                HudVisualState.VolumeSlider(
                    volumePercent = vol,
                    isMuted = vol == 0
                )
            }

            AirGestureStateMachine.Mode.FIST_RADIAL_MENU -> {
                HudVisualState.RadialSelectorActive(
                    angleDegrees = activeRadialAngleDeg,
                    selectedSlot = activeRadialSlot.coerceAtLeast(0),
                    landmarks = smoothedFrame?.landmarks.orEmpty()
                )
            }

            AirGestureStateMachine.Mode.PALM_ACTIVE -> {
                HudVisualState.PalmTracking(
                    statusText = lastStatusLabel,
                    landmarks = smoothedFrame?.landmarks.orEmpty(),
                    accentHighlight = if (thermalWarning) HudVisualState.AccentKind.AMBER else lastAccent,
                    fps = fps,
                    thermalWarning = thermalWarning
                )
            }
        }

        return DispatchOutcome(
            hudState = resolvedHud,
            actionSummary = lastActionSummary
        )
    }
}
