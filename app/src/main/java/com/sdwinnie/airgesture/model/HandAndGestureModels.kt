package com.sdwinnie.airgesture.model

data class Landmark3D(
    val x: Float,
    val y: Float,
    val z: Float = 0f
)

data class HandFrame(
    val landmarks: List<Landmark3D>,
    val timestampMs: Long,
    val confidence: Float = 1.0f
) {
    init {
        require(landmarks.size == 21) { "MediaPipe HandFrame requires 21 3D landmarks, got ${landmarks.size}" }
    }
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

    data class PinchVolumeModulate(
        val normalizedDeltaY: Float
    ) : GestureEvent

    data object PinchEnded : GestureEvent

    data class RadialMenuSummoned(
        val angleDegrees: Float,
        val selectedSlot: Int
    ) : GestureEvent

    data class RadialMenuUpdated(
        val angleDegrees: Float,
        val selectedSlot: Int
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
        val landmarks: List<Landmark3D>,
        val accentHighlight: AccentKind = AccentKind.CYAN,
        val fps: Float = 0f,
        val thermalWarning: Boolean = false
    ) : HudVisualState

    data class VolumeSlider(
        val volumePercent: Int,
        val isMuted: Boolean = false
    ) : HudVisualState

    data class RadialSelectorActive(
        val angleDegrees: Float,
        val selectedSlot: Int,
        val landmarks: List<Landmark3D>
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
