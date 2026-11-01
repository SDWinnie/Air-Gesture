package com.sdwinnie.airgesture.input

import com.sdwinnie.airgesture.engine.AirGestureStateMachine
import com.sdwinnie.airgesture.engine.HandSkeletonAnalyzer
import com.sdwinnie.airgesture.model.AirGestureConfig
import com.sdwinnie.airgesture.model.AirTapAction
import com.sdwinnie.airgesture.model.GestureEvent
import com.sdwinnie.airgesture.model.HandFrame
import com.sdwinnie.airgesture.model.HandPosture
import com.sdwinnie.airgesture.model.HudVisualState
import com.sdwinnie.airgesture.model.VolumeDirection
import com.sdwinnie.airgesture.service.AirAccessibilityService

/**
 * Translates [GestureEvent] emissions into native OS actions and produces synchronized
 * [HudVisualState] updates for the On-Screen Accessibility Circular Wheel, Volume Status Banner,
 * In-App Dynamic Island, and PiP Floating HUD.
 */
class TouchlessActionDispatcher(
    private val volumeController: SystemVolumeController,
    private val radialAppSlotManager: RadialAppSlotManager
) {
    data class DispatchOutcome(
        val hudState: HudVisualState,
        val actionSummary: String
    )

    private var lastStatusLabel = "พร้อมรับคำสั่งมือ"
    private var lastActionSummary = "แสดงท่ามือหน้ากล้องเพื่อสั่งงาน"
    private var lastAccent = HudVisualState.AccentKind.CYAN

    fun dispatchEvents(
        mode: AirGestureStateMachine.Mode,
        events: List<GestureEvent>,
        smoothedFrame: HandFrame?,
        posture: HandPosture,
        lockedHandLabel: String,
        activeRadialSlot: Int,
        activeRadialAngleDeg: Float,
        activeRadialCursorX: Float,
        activeRadialCursorY: Float,
        activeRadialInCancelZone: Boolean,
        config: AirGestureConfig,
        fps: Float,
        thermalWarning: Boolean,
        cursorNormX: Float = 0.5f,
        cursorNormY: Float = 0.5f,
        skeletonData: HandSkeletonAnalyzer.HandSkeletonData? = null
    ): DispatchOutcome {
        val totalSlots = config.radialSlotCount.count

        for (event in events) {
            when (event) {
                is GestureEvent.AirTapTriggered -> {
                    val a11y = AirAccessibilityService.activeInstance
                    val tapAction = config.airTapAction
                    val ok = when (tapAction) {
                        AirTapAction.CLICK_AT_CURSOR -> a11y?.injectAirTapClick(event.normX, event.normY) ?: false
                        AirTapAction.CLICK_SCREEN_CENTER -> a11y?.injectAirTapClick(0.5f, 0.5f) ?: false
                        AirTapAction.PLAY_PAUSE_MEDIA -> a11y?.injectMediaPlayPause() ?: false
                        AirTapAction.DOUBLE_CLICK -> a11y?.injectAirTapDoubleClick(event.normX, event.normY) ?: false
                    }
                    val actionName = when (tapAction) {
                        AirTapAction.CLICK_AT_CURSOR -> "คลิกชี้เป้า (${(event.normX * 100).toInt()}%, ${(event.normY * 100).toInt()}%)"
                        AirTapAction.CLICK_SCREEN_CENTER -> "คลิกกลางหน้าจอ"
                        AirTapAction.PLAY_PAUSE_MEDIA -> "เล่น/หยุดวิดีโอ (Play/Pause)"
                        AirTapAction.DOUBLE_CLICK -> "ดับเบิลคลิก 2 ครั้ง"
                    }
                    lastStatusLabel = "👆 เคาะจอ: $actionName"
                    lastAccent = HudVisualState.AccentKind.EMERALD
                    lastActionSummary = if (ok) {
                        "👆 ${event.tapKind} -> $actionName สำเร็จ!"
                    } else {
                        "👆 $actionName (โปรดเปิดการช่วยเหลือพิเศษ)"
                    }
                }
                GestureEvent.WakeDynamicIsland -> {
                    lastStatusLabel = "จับสัญญาณ $lockedHandLabel"
                    lastAccent = HudVisualState.AccentKind.CYAN
                    lastActionSummary = "ปลุก Dynamic Island • ล็อก $lockedHandLabel เท่านั้น"
                }

                GestureEvent.SleepDynamicIsland -> {
                    volumeController.endPinchSession()
                    lastStatusLabel = "Dormant"
                    lastActionSummary = "พักหน้าจอ HUD อัตโนมัติ (ไม่พบมือ)"
                }

                is GestureEvent.VerticalScroll -> {
                    val a11y = AirAccessibilityService.activeInstance
                    val injected = a11y?.injectSmoothVerticalScroll(
                        handMovedUp = event.scrollUp,
                        normalizedSpeed = event.normalizedSpeed,
                        sensitivity = config.scrollSensitivity
                    ) ?: false

                    lastStatusLabel = if (event.scrollUp) "เลื่อนลง ↓" else "เลื่อนขึ้น ↑"
                    lastAccent = HudVisualState.AccentKind.EMERALD
                    lastActionSummary = if (injected) {
                        "${posture.emoji} $lastStatusLabel"
                    } else {
                        "$lastStatusLabel (โปรดเปิดการช่วยเหลือพิเศษ)"
                    }
                }

                is GestureEvent.HorizontalNavigate -> {
                    val a11y = AirAccessibilityService.activeInstance
                    val label = a11y?.performHorizontalAction(
                        swipeLeft = event.swipeLeft,
                        mode = config.horizontalNavMode
                    ) ?: "โปรดเปิดการช่วยเหลือพิเศษ"
                    lastStatusLabel = label
                    lastAccent = HudVisualState.AccentKind.VIOLET
                    lastActionSummary = label
                }

                GestureEvent.QuickBackTriggered -> {
                    val a11y = AirAccessibilityService.activeInstance
                    val ok = a11y?.performQuickBack() ?: false
                    lastStatusLabel = "← ย้อนกลับด่วน"
                    lastAccent = HudVisualState.AccentKind.VIOLET
                    lastActionSummary = if (ok) "${posture.emoji} สั่งย้อนกลับ (Back)" else "ย้อนกลับ (โปรดเปิดการช่วยเหลือพิเศษ)"
                }

                is GestureEvent.PinchVolumeModulate -> {
                    val snap = if (event.normalizedDeltaY == 0f) {
                        volumeController.beginPinchSession()
                    } else {
                        volumeController.applyPinchDelta(
                            normalizedDeltaUp = event.normalizedDeltaY,
                            sensitivity = config.volumeSensitivity,
                            showSystemVolumeUi = config.showSystemVolumeUi
                        )
                    }
                    lastStatusLabel = formatVolumeStatusText(snap)
                    lastActionSummary = lastStatusLabel
                }

                GestureEvent.PinchEnded -> {
                    val finalPct = volumeController.currentVolumePercent()
                    volumeController.endPinchSession()
                    lastStatusLabel = "ล็อกเสียง $finalPct%"
                    lastAccent = HudVisualState.AccentKind.CYAN
                    lastActionSummary = "ตั้งค่าระดับเสียงสำเร็จที่ $finalPct%"
                }

                is GestureEvent.RadialMenuSummoned -> {
                    lastStatusLabel = "วงกลมเลือกแอป"
                    lastActionSummary = "เลื่อนมือไปยังแอปในวงกลม แล้วแบมือเพื่อเปิด"
                }

                is GestureEvent.RadialMenuUpdated -> {
                    if (event.inCancelZone) {
                        lastStatusLabel = "⭕ โซนยกเลิก"
                        lastActionSummary = "เลื่อนมือออกนอกวงในเพื่อเลือกแอป (แบมือตรงกลาง = ยกเลิก)"
                    } else {
                        val slot = radialAppSlotManager.loadResolvedSlots(totalSlots).getOrNull(event.selectedSlot)
                        val name = slot?.label ?: "ช่อง #${event.selectedSlot + 1}"
                        lastStatusLabel = "🎯 $name"
                        lastActionSummary = "เล็งแอป: $name • แบมือเพื่อเปิดทันที"
                    }
                }

                is GestureEvent.RadialMenuCommitted -> {
                    val launched = radialAppSlotManager.launchSlot(event.selectedSlot, totalSlots)
                    lastStatusLabel = if (launched != null) "เปิด ${launched.label.take(12)}" else "พร้อมทำงาน"
                    lastAccent = HudVisualState.AccentKind.EMERALD
                    lastActionSummary = if (launched != null) {
                        "🚀 เปิดแอป ${launched.label} เรียบร้อย"
                    } else {
                        "ช่อง #${event.selectedSlot + 1} ยังไม่ได้ตั้งค่าแอป"
                    }
                }

                GestureEvent.RadialMenuCancelled -> {
                    lastStatusLabel = "ยกเลิกวงล้อ"
                    lastAccent = HudVisualState.AccentKind.CYAN
                    lastActionSummary = "ยกเลิกการเลือกแอป (อยู่ในโซนปลอดภัย)"
                }
            }
        }

        val resolvedHud: HudVisualState = when (mode) {
            AirGestureStateMachine.Mode.DORMANT -> HudVisualState.Dormant

            AirGestureStateMachine.Mode.PINCH_VOLUME -> {
                val snap = volumeController.currentStatusSnapshot()
                HudVisualState.VolumeSlider(
                    volumePercent = snap.percent,
                    deltaStepPercent = snap.deltaPercent,
                    direction = snap.direction,
                    isMuted = snap.isMuted
                )
            }

            AirGestureStateMachine.Mode.FIST_RADIAL_MENU -> {
                HudVisualState.RadialSelectorActive(
                    angleDegrees = activeRadialAngleDeg,
                    selectedSlot = activeRadialSlot.coerceAtLeast(0),
                    totalSlots = totalSlots,
                    cursorNormX = activeRadialCursorX,
                    cursorNormY = activeRadialCursorY,
                    inCancelZone = activeRadialInCancelZone,
                    landmarks = smoothedFrame?.landmarks.orEmpty()
                )
            }

            AirGestureStateMachine.Mode.PALM_ACTIVE -> {
                val liveEmoji = if (posture != HandPosture.NONE) posture.emoji else config.scrollPreset.badgeEmoji
                HudVisualState.PalmTracking(
                    statusText = lastStatusLabel,
                    postureEmoji = liveEmoji,
                    handSideLabel = lockedHandLabel,
                    landmarks = smoothedFrame?.landmarks.orEmpty(),
                    accentHighlight = if (thermalWarning) HudVisualState.AccentKind.AMBER else lastAccent,
                    fps = fps,
                    thermalWarning = thermalWarning,
                    cursorNormX = cursorNormX,
                    cursorNormY = cursorNormY,
                    skeletonData = skeletonData
                )
            }
        }

        return DispatchOutcome(
            hudState = resolvedHud,
            actionSummary = lastActionSummary
        )
    }

    private fun formatVolumeStatusText(snap: SystemVolumeController.VolumeStatusSnapshot): String {
        val deltaTag = when {
            snap.deltaPercent > 0 -> " (+${snap.deltaPercent}%)"
            snap.deltaPercent < 0 -> " (${snap.deltaPercent}%)"
            else -> ""
        }
        return when (snap.direction) {
            VolumeDirection.MUTED -> "🔇 ปิดเสียง (MUTE) 0%"
            VolumeDirection.DOWN -> "🔉 กำลังลดเสียง ▼ ${snap.percent}%$deltaTag"
            VolumeDirection.UP -> "🔊 กำลังเพิ่มเสียง ▲ ${snap.percent}%$deltaTag"
            VolumeDirection.STEADY -> "🔊 ระดับเสียง • ${snap.percent}%"
        }
    }
}
