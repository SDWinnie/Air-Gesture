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
    FPS_30("30 FPS (โหมดมาตรฐาน เสถียร ไม่กระตุก ประหยัดแบตเตอรี่)", 30),
    FPS_60("60 FPS (โหมดลื่นไหล ประสิทธิภาพสูง)", 60),
    FPS_120("120 FPS (โหมดความเร็วสูงสุด Ultra-Low Latency)", 120)
}

enum class VisionBufferSize(
    val displayName: String,
    val dimension: Int
) {
    ROI_192("224×224 RAM (เร็วสมดุล)", 224),
    ROI_256("256×256 RAM (คมชัดระยะไกล)", 256)
}

enum class HardwareDelegateMode(
    val displayName: String
) {
    AUTO_GPU_CPU("⚡ อัตโนมัติ GPU GLES + CPU สำรอง (เร็วที่สุด)"),
    CPU_ONLY("🐢 CPU XNNPACK อย่างเดียว (ประหยัดพลังงาน)")
}

/**
 * Strict Single-Hand Lock modes preventing 2-hand jumping and background hand interference.
 */
enum class HandLockMode(
    val displayName: String
) {
    AUTO_SINGLE("🔒 มือเดียวอัตโนมัติ (ล็อกมือข้างแรกที่ยก)"),
    RIGHT_ONLY("🤚 จับเฉพาะมือขวาเท่านั้น (Right Hand)"),
    LEFT_ONLY("✋ จับเฉพาะมือซ้ายเท่านั้น (Left Hand)"),
    FREE_TRACKING("🌐 ตรวจจับอิสระ (ติดตามมือที่เด่นที่สุด)")
}

enum class HandSide(val thaiLabel: String) {
    RIGHT("มือขวา"),
    LEFT("มือซ้าย"),
    UNKNOWN("1 มือ")
}

/**
 * Air Tap Trigger Presets (เคาะจอ / จดจอ).
 */
enum class AirTapTriggerPreset(
    val displayName: String,
    val badgeEmoji: String
) {
    INDEX_KNOCK("☝️ เคาะปลายนิ้วชี้กลางอากาศ (แตะกระจกจอ)", "☝️"),
    PINCH_QUICK_TAP("🤏 จีบนิ้วชี้กับโป้งเคาะเร็วๆ (Vision Pro Style)", "🤏"),
    PALM_FORWARD_KNOCK("🖐️ เคาะฝ่ามือเข้าหากล้อง", "🖐️"),
    ANY_TAP("✨ เคาะได้ทุกแบบตามถนัด", "✨")
}

/**
 * Air Tap Actions injected via AccessibilityService.
 */
enum class AirTapAction(
    val displayName: String
) {
    CLICK_AT_CURSOR("🎯 เคาะคลิกตรงตำแหน่งชี้เป้า (Air Cursor เล็งตรงไหนแตะตรงนั้น)"),
    CLICK_SCREEN_CENTER("🔘 เคาะคลิกตรงกลางหน้าจอ (Pause/Play, Shorts/Reels)"),
    PLAY_PAUSE_MEDIA("⏯️ เล่น / หยุดสื่อมีเดียทันที (Media Play/Pause)"),
    DOUBLE_CLICK("⚡ เคาะดับเบิลคลิก 2 ครั้ง (Double Click)")
}

/**
 * Ergonomic Human-Doable Postures based on MediaPipe Canonical Gesture Taxonomy & HCI Biomechanics (v4.0).
 */
enum class ScrollGesturePreset(
    val displayName: String,
    val badgeEmoji: String
) {
    OPEN_PALM_SWEEP("✋ แบมือ 5 นิ้วปัดขึ้น / ปัดลง (ธรรมชาติ)", "✋"),
    ONE_FINGER_POINT("☝️ ชี้นิ้วชี้ 1 นิ้วปัดขึ้น / ปัดลง", "☝️"),
    VICTORY_TWO_FINGER("✌️ ชู 2 นิ้ว (ตัว V) ปัดขึ้น / ปัดลง", "✌️"),
    KNIFE_HAND_SWIPE("✋ ใช้สันมือปัดขึ้น / ปัดลง", "✋"),
    THUMB_SWEEP("👍 ใช้หัวแม่มือปัดขึ้น / ปัดลง", "👍")
}

enum class VolumeGesturePreset(
    val displayName: String,
    val badgeEmoji: String
) {
    PINCH_OK_SLIDE("🤏 จีบนิ้ว (OK) เลื่อนขึ้น=เพิ่ม • ลง=ลดเสียง", "🤏"),
    THUMBS_UP_DOWN("👍/👎 ชูโป้งขึ้น=เพิ่มเสียง • โป้งลง=ลดเสียง (ง่ายสุด)", "👍"),
    VICTORY_SLIDE("✌️ ชู 2 นิ้ว เลื่อนขึ้น=เพิ่ม • ลง=ลดเสียง", "✌️"),
    FINGER_GUN_SLIDE("👉 ทำท่าปืนเลื่อนขึ้น=เพิ่ม • ลง=ลดเสียง", "👉")
}

enum class RadialTriggerPreset(
    val displayName: String,
    val badgeEmoji: String
) {
    CLOSED_FIST("✊ กำมือค้างสั้นๆ (Closed Fist)", "✊"),
    I_LOVE_YOU_SIGN("🤟 ท่า I Love You (โป้ง+ชี้+ก้อย)", "🤟"),
    THREE_FINGERS("🖖 ชู 3 นิ้วค้างสั้นๆ (ชี้+กลาง+นาง)", "🖖"),
    CALL_ME_SHAKA("🤙 ท่าโทรมา (ชากา 🤙) ค้างสั้นๆ", "🤙"),
    OPEN_PALM_HOLD("🖐️ แบมือนิ่งๆ ค้างกลางจอ", "🖐️")
}

enum class BackTriggerPreset(
    val displayName: String,
    val badgeEmoji: String
) {
    HORIZONTAL_SWIPE("↔️ ปัดมือซ้าย = ย้อนกลับ • ปัดขวา = สลับแอป", "↔️"),
    PEACE_HOLD_BACK("✌️ ชู 2 นิ้วค้างสั้นๆ = กดย้อนกลับทันที", "✌️"),
    ILY_HOLD_BACK("🤟 ท่า I Love You ค้างสั้นๆ = กดย้อนกลับทันที", "🤟"),
    FINGER_GUN_BACK("👉 ท่าปืนนิ้วค้างสั้นๆ = กดย้อนกลับทันที", "👉")
}

enum class HorizontalNavMode(
    val displayName: String
) {
    BACK_AND_RECENTS("ปัดซ้าย = ย้อนกลับ • ปัดขวา = สลับแอป (Recents)"),
    BACK_AND_QUICK_SWITCH("ปัดซ้าย = ย้อนกลับ • ปัดขวา = แอปก่อนหน้าทันที"),
    BACK_ONLY("ปัดซ้ายหรือขวา = ย้อนกลับ (Back)")
}

/**
 * Dynamic Hand Skeleton Bone Tracking Density (20, 36, and 48 bones).
 */
enum class SkeletonBoneDensity(
    val boneCount: Int,
    val displayName: String
) {
    BONES_20(20, "20 ชิ้น (กระดูกนิ้วมือมาตรฐาน 5 แฉก MediaPipe)"),
    BONES_36(36, "36 ชิ้น (กระดูกข้อมือ 8 ชิ้น + ปลายแขนเรเดียส/อัลนา Biomechanical)"),
    BONES_48(48, "48 ชิ้น (กระดูกตามหลักคลินิก + รัศมีความโค้งงอนิ้วมือลึก Ultra-Dense Kinematic)")
}

/**
 * Balanced AI Vision Calibration and Coordinate Spatial Framing.
 */
enum class VisionBalanceMode(
    val displayName: String
) {
    ISOTROPIC_CENTER_WEIGHTED("⚖️ ระนาบสมดุล 1:1 Center-Weighted (ขจัดภาพบวม/บิดเบี้ยว)"),
    EUCLIDEAN_ONE_EURO_FILTER("🎯 1-Euro Spatial Filter (ขจัดอาการสั่น Jitter มือคงที่)"),
    ADAPTIVE_EDGE_SOFT_MARGIN("🛡️ Edge Soft-Margin (ป้องกันการหลุดขอบกล้อง)")
}

/**
 * Eye Contact & Blink Detection Trigger Scope.
 */
enum class EyeBlinkTriggerMode(
    val displayName: String
) {
    BOTH_EYES("👀 ตรวจจับตาทั้งสองข้างพร้อมกัน (ธรรมชาติ ป้องกันอุบัติเหตุ)"),
    ANY_EYE("👁️ กระพริบตาข้างใดข้างหนึ่งก็ได้"),
    LEFT_EYE_ONLY("👁️ เฉพาะตาซ้ายเท่านั้น"),
    RIGHT_EYE_ONLY("👁️ เฉพาะตาขวาเท่านั้น")
}

/**
 * Actions executed when Eye Blink sequences are detected.
 */
enum class BlinkAction(
    val displayName: String,
    val badgeEmoji: String
) {
    SCROLL_DOWN("⬇️ เลื่อนหน้าจอลง (Scroll Down)", "⬇️"),
    SCROLL_UP("⬆️ เลื่อนหน้าจอขึ้น (Scroll Up)", "⬆️"),
    SEND_HEART_LIKE("❤️ กดใจ / ดับเบิ้ลคลิกถูกใจ (Like TikTok/Reels)", "❤️"),
    CLICK_AT_CURSOR("🎯 เคาะคลิกตรงตำแหน่งชี้เป้า (Air Cursor)", "🎯"),
    CLICK_SCREEN_CENTER("🔘 เคาะคลิกตรงกลางหน้าจอ", "🔘"),
    PLAY_PAUSE_MEDIA("⏯️ เล่น / หยุดสื่อมีเดีย", "⏯️"),
    QUICK_BACK("🔙 กดย้อนกลับ (Back)", "🔙"),
    NONE("❌ ปิดการใช้งาน", "❌")
}

/**
 * User-customizable palm / hand graphic rendering styles on the Dynamic Island & Floating HUD.
 */
enum class HudPalmStyle(
    val displayName: String
) {
    CYBER_SKELETON_3D("🦴 โครงกระดูกมือ 3D ไบโอเมทริกซ์ 20 ข้อต่อแยกสี"),
    CYBER_SKELETON_36("🦴 โครงกระดูก 36 ชิ้น (Carpal & Forearm Chain)"),
    CYBER_SKELETON_48("🦴 โครงกระดูก 48 ชิ้นระดับคลินิก (Ultra-Dense 3D Kinematic)"),
    GLOWING_PALM("✋ รูปฝ่ามือเรืองแสง (Glowing Palm Silhouette)"),
    POSTURE_BADGE("🎭 ไอคอนท่าทางอัจฉริยะ (✋ 🤏 👍 👎 ✌️ ✊ 🤟 🤙 👉)"),
    AIR_CURSOR_RETICLE("🎯 เป้าเล็งแอร์เมาส์เคาะจอ (Air Cursor Reticle)"),
    MINIMAL_ORB("🔮 วงแหวนพลังงานมินิมอล (Spatial Pulse Orb)")
}

/**
 * User-customizable neon accent themes for Dynamic Island & On-Screen Circular App Wheel.
 */
enum class HudThemeColor(
    val displayName: String,
    val primaryHex: String,
    val secondaryHex: String
) {
    CYBER_CYAN("💎 Cyber Cyan", "#00E5FF", "#00E676"),
    MATRIX_EMERALD("💚 Matrix Emerald", "#00E676", "#69F0AE"),
    SPATIAL_VIOLET("💜 Spatial Violet", "#A374FF", "#00E5FF"),
    SOLAR_GOLD("🧡 Solar Gold", "#FFC400", "#FF6D00"),
    NEON_ROSE("🩷 Neon Rose", "#FF4D8D", "#FF80AB"),
    HOLO_WHITE("⚪ Holo Crystal", "#FFFFFF", "#B0BEC5")
}

/**
 * Configurable number of apps in the On-Screen 360° Circular App Selector.
 */
enum class RadialSlotCount(
    val count: Int,
    val displayName: String
) {
    SLOTS_6(6, "6 แอป (ช่องใหญ่ 60° เลือกง่ายสุด)"),
    SLOTS_8(8, "8 แอป (สมดุล 45° แนะนำ)"),
    SLOTS_12(12, "12 แอป (30° ครบครัน)"),
    SLOTS_15(15, "15 แอป (24° เต็มวงล้อ)")
}

data class AirGestureConfig(
    val resolution: CameraResolution = CameraResolution.RES_1080P,
    val targetFps: TargetFps = TargetFps.FPS_60,
    val visionBufferSize: VisionBufferSize = VisionBufferSize.ROI_192,
    val hardwareDelegate: HardwareDelegateMode = HardwareDelegateMode.AUTO_GPU_CPU,
    val useFrontCamera: Boolean = true,
    val handLockMode: HandLockMode = HandLockMode.AUTO_SINGLE,
    val skeletonDensity: SkeletonBoneDensity = SkeletonBoneDensity.BONES_48,
    val visionBalanceMode: VisionBalanceMode = VisionBalanceMode.ISOTROPIC_CENTER_WEIGHTED,
    val balanceSmoothingFactor: Float = 1.0f,
    val enableEyeContactBlink: Boolean = true,
    val requireEyeContact: Boolean = true,
    val eyeBlinkTriggerMode: EyeBlinkTriggerMode = EyeBlinkTriggerMode.BOTH_EYES,
    val singleBlinkAction: BlinkAction = BlinkAction.SCROLL_DOWN,
    val doubleBlinkAction: BlinkAction = BlinkAction.SCROLL_UP,
    val tripleBlinkAction: BlinkAction = BlinkAction.SEND_HEART_LIKE,
    val blinkSensitivity: Float = 1.0f,
    val blinkSpeedWindowMs: Long = 650L,
    val enableAirTap: Boolean = true,
    val airTapTrigger: AirTapTriggerPreset = AirTapTriggerPreset.ANY_TAP,
    val airTapAction: AirTapAction = AirTapAction.CLICK_AT_CURSOR,
    val showAirCursor: Boolean = true,
    val airTapSensitivity: Float = 1.0f,
    val airCursorSensitivity: Float = 1.0f,
    val scrollPreset: ScrollGesturePreset = ScrollGesturePreset.OPEN_PALM_SWEEP,
    val volumePreset: VolumeGesturePreset = VolumeGesturePreset.PINCH_OK_SLIDE,
    val radialPreset: RadialTriggerPreset = RadialTriggerPreset.CLOSED_FIST,
    val backPreset: BackTriggerPreset = BackTriggerPreset.HORIZONTAL_SWIPE,
    val horizontalNavMode: HorizontalNavMode = HorizontalNavMode.BACK_AND_RECENTS,
    val hudPalmStyle: HudPalmStyle = HudPalmStyle.CYBER_SKELETON_48,
    val hudThemeColor: HudThemeColor = HudThemeColor.CYBER_CYAN,
    val radialSlotCount: RadialSlotCount = RadialSlotCount.SLOTS_8,
    val showSystemVolumeUi: Boolean = true,
    val showFpsCounter: Boolean = true,
    val scrollSensitivity: Float = 1.0f,
    val volumeSensitivity: Float = 1.0f,
    val showLiveCalibrationPreview: Boolean = false
)

data class RadialAppSlot(
    val slotIndex: Int,
    val packageName: String,
    val label: String,
    val totalActiveSlots: Int = 15
) {
    val sliceDegrees: Float get() = 360.0f / totalActiveSlots.coerceAtLeast(1)
    val centerAngleDegrees: Float get() = slotIndex * sliceDegrees
}

data class TelemetrySnapshot(
    val serviceRunning: Boolean = false,
    val manualPaused: Boolean = false,
    val cameraYielded: Boolean = false,
    val activeStreamSummary: String = "Stopped",
    val visionBufferSummary: String = "224×224 Volatile RAM",
    val delegateName: String = "Idle",
    val lockedHandLabel: String = "รอตรวจจับ 1 มือ",
    val inferenceFps: Float = 0f,
    val inferenceLatencyMs: Float = 0f,
    val thermalStatus: Int = 0,
    val currentPosture: String = "Dormant",
    val lastActionSummary: String = "รอแสดงท่ามือหน้ากล้อง..."
)
