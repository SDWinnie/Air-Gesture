package com.sdwinnie.airgesture.security

import android.content.Context
import com.sdwinnie.airgesture.model.AirGestureConfig
import com.sdwinnie.airgesture.model.AirTapAction
import com.sdwinnie.airgesture.model.AirTapTriggerPreset
import com.sdwinnie.airgesture.model.BackTriggerPreset
import com.sdwinnie.airgesture.model.BlinkAction
import com.sdwinnie.airgesture.model.CameraResolution
import com.sdwinnie.airgesture.model.EyeBlinkTriggerMode
import com.sdwinnie.airgesture.model.HandLockMode
import com.sdwinnie.airgesture.model.HardwareDelegateMode
import com.sdwinnie.airgesture.model.HorizontalNavMode
import com.sdwinnie.airgesture.model.HudPalmStyle
import com.sdwinnie.airgesture.model.HudThemeColor
import com.sdwinnie.airgesture.model.RadialSlotCount
import com.sdwinnie.airgesture.model.RadialTriggerPreset
import com.sdwinnie.airgesture.model.ScrollGesturePreset
import com.sdwinnie.airgesture.model.SkeletonBoneDensity
import com.sdwinnie.airgesture.model.TargetFps
import com.sdwinnie.airgesture.model.VisionBalanceMode
import com.sdwinnie.airgesture.model.VisionBufferSize
import com.sdwinnie.airgesture.model.VolumeGesturePreset

/**
 * Stores non-sensitive user configuration flags, Air Tap settings, custom human posture presets,
 * HUD palm/theme visual styles, and the 15 radial shortcut package IDs (v4.0).
 * Never stores camera pixels, landmarks, or behavioral history.
 */
class PreferenceStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var isProminentDisclosureAccepted: Boolean
        get() = prefs.getBoolean(KEY_DISCLOSURE_ACCEPTED, false)
        set(value) = prefs.edit().putBoolean(KEY_DISCLOSURE_ACCEPTED, value).apply()

    fun loadConfig(): AirGestureConfig {
        fun <T : Enum<T>> readEnum(key: String, default: T, resolver: (String) -> T): T {
            val raw = prefs.getString(key, default.name) ?: return default
            return runCatching { resolver(raw) }.getOrDefault(default)
        }

        return AirGestureConfig(
            resolution = readEnum(KEY_RESOLUTION, CameraResolution.RES_1080P, CameraResolution::valueOf),
            targetFps = readEnum(KEY_TARGET_FPS, TargetFps.FPS_60, TargetFps::valueOf),
            visionBufferSize = readEnum(KEY_VISION_BUFFER, VisionBufferSize.ROI_192, VisionBufferSize::valueOf),
            hardwareDelegate = readEnum(KEY_HARDWARE_DELEGATE, HardwareDelegateMode.AUTO_GPU_CPU, HardwareDelegateMode::valueOf),
            useFrontCamera = prefs.getBoolean(KEY_FRONT_CAMERA, true),
            handLockMode = readEnum(KEY_HAND_LOCK, HandLockMode.AUTO_SINGLE, HandLockMode::valueOf),
            skeletonDensity = readEnum(KEY_SKELETON_DENSITY, SkeletonBoneDensity.BONES_48, SkeletonBoneDensity::valueOf),
            visionBalanceMode = readEnum(KEY_VISION_BALANCE_MODE, VisionBalanceMode.ISOTROPIC_CENTER_WEIGHTED, VisionBalanceMode::valueOf),
            balanceSmoothingFactor = prefs.getFloat(KEY_BALANCE_SMOOTHING, 1.0f).coerceIn(0.5f, 2.5f),
            enableEyeContactBlink = prefs.getBoolean(KEY_ENABLE_EYE_BLINK, true),
            requireEyeContact = prefs.getBoolean(KEY_REQUIRE_EYE_CONTACT, true),
            eyeBlinkTriggerMode = readEnum(KEY_EYE_TRIGGER_MODE, EyeBlinkTriggerMode.BOTH_EYES, EyeBlinkTriggerMode::valueOf),
            singleBlinkAction = readEnum(KEY_SINGLE_BLINK_ACTION, BlinkAction.SCROLL_DOWN, BlinkAction::valueOf),
            doubleBlinkAction = readEnum(KEY_DOUBLE_BLINK_ACTION, BlinkAction.SCROLL_UP, BlinkAction::valueOf),
            tripleBlinkAction = readEnum(KEY_TRIPLE_BLINK_ACTION, BlinkAction.SEND_HEART_LIKE, BlinkAction::valueOf),
            blinkSensitivity = prefs.getFloat(KEY_BLINK_SENS, 1.0f).coerceIn(0.5f, 2.5f),
            blinkSpeedWindowMs = prefs.getLong(KEY_BLINK_WINDOW_MS, 650L).coerceIn(350L, 1200L),
            enableAirTap = prefs.getBoolean(KEY_ENABLE_AIR_TAP, true),
            airTapTrigger = readEnum(KEY_AIR_TAP_TRIGGER, AirTapTriggerPreset.ANY_TAP, AirTapTriggerPreset::valueOf),
            airTapAction = readEnum(KEY_AIR_TAP_ACTION, AirTapAction.CLICK_AT_CURSOR, AirTapAction::valueOf),
            showAirCursor = prefs.getBoolean(KEY_SHOW_AIR_CURSOR, true),
            airTapSensitivity = prefs.getFloat(KEY_AIR_TAP_SENS, 1.0f).coerceIn(0.5f, 2.5f),
            airCursorSensitivity = prefs.getFloat(KEY_AIR_CURSOR_SENS, 1.0f).coerceIn(0.5f, 2.5f),
            scrollPreset = readEnum(KEY_SCROLL_PRESET, ScrollGesturePreset.OPEN_PALM_SWEEP, ScrollGesturePreset::valueOf),
            volumePreset = readEnum(KEY_VOLUME_PRESET, VolumeGesturePreset.PINCH_OK_SLIDE, VolumeGesturePreset::valueOf),
            radialPreset = readEnum(KEY_RADIAL_PRESET, RadialTriggerPreset.CLOSED_FIST, RadialTriggerPreset::valueOf),
            backPreset = readEnum(KEY_BACK_PRESET, BackTriggerPreset.HORIZONTAL_SWIPE, BackTriggerPreset::valueOf),
            horizontalNavMode = readEnum(KEY_H_NAV_MODE, HorizontalNavMode.BACK_AND_RECENTS, HorizontalNavMode::valueOf),
            hudPalmStyle = readEnum(KEY_HUD_PALM_STYLE, HudPalmStyle.CYBER_SKELETON_3D, HudPalmStyle::valueOf),
            hudThemeColor = readEnum(KEY_HUD_THEME, HudThemeColor.CYBER_CYAN, HudThemeColor::valueOf),
            radialSlotCount = readEnum(KEY_RADIAL_COUNT, RadialSlotCount.SLOTS_8, RadialSlotCount::valueOf),
            showSystemVolumeUi = prefs.getBoolean(KEY_SHOW_SYS_VOL_UI, true),
            showFpsCounter = prefs.getBoolean(KEY_SHOW_FPS, true),
            scrollSensitivity = prefs.getFloat(KEY_SCROLL_SENS, 1.0f).coerceIn(0.5f, 2.5f),
            volumeSensitivity = prefs.getFloat(KEY_VOL_SENS, 1.0f).coerceIn(0.5f, 2.5f),
            showLiveCalibrationPreview = prefs.getBoolean(KEY_SHOW_PREVIEW, false),
            enableGeminiAi = prefs.getBoolean(KEY_ENABLE_GEMINI, false),
            geminiApiKey = prefs.getString(KEY_GEMINI_API_KEY, "") ?: "",
            geminiModelName = prefs.getString(KEY_GEMINI_MODEL, "gemini-2.5-flash") ?: "gemini-2.5-flash",
            enableVoiceCommands = prefs.getBoolean(KEY_ENABLE_VOICE, false),
            enableVoiceTts = prefs.getBoolean(KEY_ENABLE_VOICE_TTS, true),
            enableLongDistanceBooster = prefs.getBoolean(KEY_ENABLE_LONG_DISTANCE, true)
        )
    }

    fun saveConfig(config: AirGestureConfig) {
        prefs.edit()
            .putString(KEY_RESOLUTION, config.resolution.name)
            .putString(KEY_TARGET_FPS, config.targetFps.name)
            .putString(KEY_VISION_BUFFER, config.visionBufferSize.name)
            .putString(KEY_HARDWARE_DELEGATE, config.hardwareDelegate.name)
            .putBoolean(KEY_FRONT_CAMERA, config.useFrontCamera)
            .putString(KEY_HAND_LOCK, config.handLockMode.name)
            .putString(KEY_SKELETON_DENSITY, config.skeletonDensity.name)
            .putString(KEY_VISION_BALANCE_MODE, config.visionBalanceMode.name)
            .putFloat(KEY_BALANCE_SMOOTHING, config.balanceSmoothingFactor)
            .putBoolean(KEY_ENABLE_EYE_BLINK, config.enableEyeContactBlink)
            .putBoolean(KEY_REQUIRE_EYE_CONTACT, config.requireEyeContact)
            .putString(KEY_EYE_TRIGGER_MODE, config.eyeBlinkTriggerMode.name)
            .putString(KEY_SINGLE_BLINK_ACTION, config.singleBlinkAction.name)
            .putString(KEY_DOUBLE_BLINK_ACTION, config.doubleBlinkAction.name)
            .putString(KEY_TRIPLE_BLINK_ACTION, config.tripleBlinkAction.name)
            .putFloat(KEY_BLINK_SENS, config.blinkSensitivity)
            .putLong(KEY_BLINK_WINDOW_MS, config.blinkSpeedWindowMs)
            .putBoolean(KEY_ENABLE_AIR_TAP, config.enableAirTap)
            .putString(KEY_AIR_TAP_TRIGGER, config.airTapTrigger.name)
            .putString(KEY_AIR_TAP_ACTION, config.airTapAction.name)
            .putBoolean(KEY_SHOW_AIR_CURSOR, config.showAirCursor)
            .putFloat(KEY_AIR_TAP_SENS, config.airTapSensitivity)
            .putFloat(KEY_AIR_CURSOR_SENS, config.airCursorSensitivity)
            .putString(KEY_SCROLL_PRESET, config.scrollPreset.name)
            .putString(KEY_VOLUME_PRESET, config.volumePreset.name)
            .putString(KEY_RADIAL_PRESET, config.radialPreset.name)
            .putString(KEY_BACK_PRESET, config.backPreset.name)
            .putString(KEY_H_NAV_MODE, config.horizontalNavMode.name)
            .putString(KEY_HUD_PALM_STYLE, config.hudPalmStyle.name)
            .putString(KEY_HUD_THEME, config.hudThemeColor.name)
            .putString(KEY_RADIAL_COUNT, config.radialSlotCount.name)
            .putBoolean(KEY_SHOW_SYS_VOL_UI, config.showSystemVolumeUi)
            .putBoolean(KEY_SHOW_FPS, config.showFpsCounter)
            .putFloat(KEY_SCROLL_SENS, config.scrollSensitivity)
            .putFloat(KEY_VOL_SENS, config.volumeSensitivity)
            .putBoolean(KEY_SHOW_PREVIEW, config.showLiveCalibrationPreview)
            .putBoolean(KEY_ENABLE_GEMINI, config.enableGeminiAi)
            .putString(KEY_GEMINI_API_KEY, config.geminiApiKey)
            .putString(KEY_GEMINI_MODEL, config.geminiModelName)
            .putBoolean(KEY_ENABLE_VOICE, config.enableVoiceCommands)
            .putBoolean(KEY_ENABLE_VOICE_TTS, config.enableVoiceTts)
            .putBoolean(KEY_ENABLE_LONG_DISTANCE, config.enableLongDistanceBooster)
            .apply()
    }

    fun getRadialSlotPackage(slotIndex: Int): String? {
        require(slotIndex in 0 until RADIAL_SLOT_COUNT)
        return prefs.getString("$KEY_RADIAL_SLOT_PREFIX$slotIndex", null)
    }

    fun setRadialSlotPackage(slotIndex: Int, packageName: String) {
        require(slotIndex in 0 until RADIAL_SLOT_COUNT)
        prefs.edit().putString("$KEY_RADIAL_SLOT_PREFIX$slotIndex", packageName).apply()
    }

    companion object {
        const val RADIAL_SLOT_COUNT = 15
        private const val PREFS_NAME = "air_gesture_edge_prefs"
        private const val KEY_DISCLOSURE_ACCEPTED = "prominent_disclosure_accepted"
        private const val KEY_RESOLUTION = "camera_resolution"
        private const val KEY_TARGET_FPS = "target_fps"
        private const val KEY_VISION_BUFFER = "vision_buffer_size"
        private const val KEY_HARDWARE_DELEGATE = "hardware_delegate"
        private const val KEY_FRONT_CAMERA = "use_front_camera"
        private const val KEY_HAND_LOCK = "hand_lock_mode"
        private const val KEY_SKELETON_DENSITY = "skeleton_density"
        private const val KEY_VISION_BALANCE_MODE = "vision_balance_mode"
        private const val KEY_BALANCE_SMOOTHING = "balance_smoothing_factor"
        private const val KEY_ENABLE_EYE_BLINK = "enable_eye_blink"
        private const val KEY_REQUIRE_EYE_CONTACT = "require_eye_contact"
        private const val KEY_EYE_TRIGGER_MODE = "eye_trigger_mode"
        private const val KEY_SINGLE_BLINK_ACTION = "single_blink_action"
        private const val KEY_DOUBLE_BLINK_ACTION = "double_blink_action"
        private const val KEY_TRIPLE_BLINK_ACTION = "triple_blink_action"
        private const val KEY_BLINK_SENS = "blink_sensitivity"
        private const val KEY_BLINK_WINDOW_MS = "blink_window_ms"
        private const val KEY_ENABLE_AIR_TAP = "enable_air_tap"
        private const val KEY_AIR_TAP_TRIGGER = "air_tap_trigger"
        private const val KEY_AIR_TAP_ACTION = "air_tap_action"
        private const val KEY_SHOW_AIR_CURSOR = "show_air_cursor"
        private const val KEY_AIR_TAP_SENS = "air_tap_sensitivity"
        private const val KEY_AIR_CURSOR_SENS = "air_cursor_sensitivity"
        private const val KEY_SCROLL_PRESET = "scroll_gesture_preset"
        private const val KEY_VOLUME_PRESET = "volume_gesture_preset"
        private const val KEY_RADIAL_PRESET = "radial_trigger_preset"
        private const val KEY_BACK_PRESET = "back_trigger_preset"
        private const val KEY_H_NAV_MODE = "horizontal_nav_mode"
        private const val KEY_HUD_PALM_STYLE = "hud_palm_style"
        private const val KEY_HUD_THEME = "hud_theme_color"
        private const val KEY_RADIAL_COUNT = "radial_slot_count"
        private const val KEY_SHOW_SYS_VOL_UI = "show_system_volume_ui"
        private const val KEY_SHOW_FPS = "show_fps_counter"
        private const val KEY_SCROLL_SENS = "scroll_sensitivity"
        private const val KEY_VOL_SENS = "volume_sensitivity"
        private const val KEY_SHOW_PREVIEW = "show_live_preview"
        private const val KEY_ENABLE_GEMINI = "enable_gemini_ai"
        private const val KEY_GEMINI_API_KEY = "gemini_api_key"
        private const val KEY_GEMINI_MODEL = "gemini_model_name"
        private const val KEY_ENABLE_VOICE = "enable_voice_commands"
        private const val KEY_ENABLE_VOICE_TTS = "enable_voice_tts"
        private const val KEY_ENABLE_LONG_DISTANCE = "enable_long_distance_booster"
        private const val KEY_RADIAL_SLOT_PREFIX = "radial_slot_pkg_"
    }
}
