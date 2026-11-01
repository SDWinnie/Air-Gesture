package com.sdwinnie.airgesture.model

data class Landmark3D(
    val x: Float,
    val y: Float,
    val z: Float = 0f
)

/**
 * Canonical Ergonomic Human Hand Postures (MediaPipe / HCI Standard Taxonomy).
 */
enum class HandPosture(val emoji: String, val thaiName: String) {
    NONE("⚪", "ไม่พบท่ามือ"),
    OPEN_PALM("✋", "แบมือ 5 นิ้ว"),
    PINCH_OK("🤏", "ท่ามือจีบ (OK)"),
    CLOSED_FIST("✊", "ท่ากำมือ"),
    THUMB_UP("👍", "ชูนิ้วโป้งขึ้น"),
    THUMB_DOWN("👎", "นิ้วโป้งชี้ลง"),
    POINTING_ONE("☝️", "ชี้นิ้วเดียว"),
    VICTORY_PEACE("✌️", "ชู 2 นิ้ว (V)"),
    THREE_FINGERS("🖖", "ชู 3 นิ้ว"),
    I_LOVE_YOU("🤟", "ท่า I Love You")
}

data class HandFrame(
    val landmarks: List<Landmark3D>,
    val timestampMs: Long,
    val confidence: Float = 1.0f,
    val handSide: HandSide = HandSide.UNKNOWN
) {
    init {
        require(landmarks.size == 21) { "MediaPipe HandFrame requires 21 3D landmarks, got ${landmarks.size}" }
    }
}

enum class VolumeDirection {
    UP,      // 🔊 กำลังเพิ่มเสียง ▲
    DOWN,    // 🔉 กำลังลดเสียง ▼
    STEADY,  // 🔊 คงระดับเสียง •
    MUTED    // 🔇 ปิดเสียง 0%
}

sealed interface GestureEvent {
    data object WakeDynamicIsland : GestureEvent
    data object SleepDynamicIsland : GestureEvent

    data class VerticalScroll(
        val scrollUp: Boolean,
        val normalizedSpeed: Float
    ) : GestureEvent

    data class HorizontalNavigate(
        val swipeLeft: Boolean,
        val normalizedSpeed: Float
    ) : GestureEvent

    data object QuickBackTriggered : GestureEvent

    data class PinchVolumeModulate(
        val normalizedDeltaY: Float
    ) : GestureEvent

    data object PinchEnded : GestureEvent

    data class RadialMenuSummoned(
        val angleDegrees: Float,
        val selectedSlot: Int,
        val cursorOffsetX: Float = 0f,
        val cursorOffsetY: Float = 0f,
        val inCancelZone: Boolean = true
    ) : GestureEvent

    data class RadialMenuUpdated(
        val angleDegrees: Float,
        val selectedSlot: Int,
        val cursorOffsetX: Float = 0f,
        val cursorOffsetY: Float = 0f,
        val inCancelZone: Boolean = false
    ) : GestureEvent

    data class RadialMenuCommitted(
        val selectedSlot: Int
    ) : GestureEvent

    data object RadialMenuCancelled : GestureEvent
}

sealed interface HudVisualState {
    data object Dormant : HudVisualState

    data class PalmTracking(
        val statusText: String,
        val postureEmoji: String = "✋",
        val handSideLabel: String = "1 มือ",
        val landmarks: List<Landmark3D>,
        val accentHighlight: AccentKind = AccentKind.CYAN,
        val fps: Float = 0f,
        val thermalWarning: Boolean = false
    ) : HudVisualState

    data class VolumeSlider(
        val volumePercent: Int,
        val deltaStepPercent: Int = 0,
        val direction: VolumeDirection = VolumeDirection.STEADY,
        val isMuted: Boolean = volumePercent == 0
    ) : HudVisualState

    data class RadialSelectorActive(
        val angleDegrees: Float,
        val selectedSlot: Int,
        val totalSlots: Int = 8,
        val cursorNormX: Float = 0f,
        val cursorNormY: Float = 0f,
        val inCancelZone: Boolean = false,
        val landmarks: List<Landmark3D> = emptyList()
    ) : HudVisualState

    data class PausedNotice(
        val reason: String
    ) : HudVisualState

    enum class AccentKind {
        CYAN,
        EMERALD,
        VIOLET,
        AMBER
    }
}
