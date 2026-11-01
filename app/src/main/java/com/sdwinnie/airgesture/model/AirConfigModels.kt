package com.sdwinnie.airgesture.model

enum class CameraResolution(
    val displayName: String,
    val targetWidth: Int,
    val targetHeight: Int
) {
    RES_480P("480p", 640, 480),
    RES_720P("720p", 1280, 720),
    RES_1080P("1080p", 1920, 1080),
    RES_2K("2K QHD", 2560, 1440),
    RES_4K("4K UHD", 3840, 2160);

    val totalPixels: Long get() = targetWidth.toLong() * targetHeight.toLong()
}

enum class TargetFps(
    val displayName: String,
    val fps: Int
) {
    FPS_60("60 FPS", 60),
    FPS_120("120 FPS", 120)
}

enum class VisionBufferSize(
    val displayName: String,
    val dimension: Int
) {
    ROI_192("192×192 RAM", 192),
    ROI_256("256×256 RAM", 256)
}

enum class HorizontalNavMode(
    val displayName: String
) {
    BACK_AND_RECENTS("ปัดซ้าย = ย้อนกลับ • ปัดขวา = สลับแอป (Recents)"),
    BACK_AND_QUICK_SWITCH("ปัดซ้าย = ย้อนกลับ • ปัดขวา = แอปก่อนหน้าทันที"),
    BACK_ONLY("ปัดซ้ายหรือขวา = ย้อนกลับ (Back)")
}

data class AirGestureConfig(
    val resolution: CameraResolution = CameraResolution.RES_1080P,
    val targetFps: TargetFps = TargetFps.FPS_60,
    val visionBufferSize: VisionBufferSize = VisionBufferSize.ROI_192,
    val useFrontCamera: Boolean = true,
    val horizontalNavMode: HorizontalNavMode = HorizontalNavMode.BACK_AND_RECENTS,
    val scrollSensitivity: Float = 1.0f,
    val volumeSensitivity: Float = 1.0f,
    val showLiveCalibrationPreview: Boolean = false
)

data class RadialAppSlot(
    val slotIndex: Int,
    val packageName: String,
    val label: String
) {
    val centerAngleDegrees: Float get() = slotIndex * 24.0f
}

data class TelemetrySnapshot(
    val serviceRunning: Boolean = false,
    val manualPaused: Boolean = false,
    val cameraYielded: Boolean = false,
    val activeStreamSummary: String = "Stopped",
    val visionBufferSummary: String = "192×192 Volatile RAM",
    val delegateName: String = "Idle",
    val inferenceFps: Float = 0f,
    val inferenceLatencyMs: Float = 0f,
    val thermalStatus: Int = 0,
    val currentPosture: String = "Dormant",
    val lastActionSummary: String = "Waiting for open palm..."
)
