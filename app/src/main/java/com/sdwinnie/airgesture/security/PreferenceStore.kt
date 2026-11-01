package com.sdwinnie.airgesture.security

import android.content.Context
import com.sdwinnie.airgesture.model.AirGestureConfig
import com.sdwinnie.airgesture.model.BackTriggerPreset
import com.sdwinnie.airgesture.model.CameraResolution
import com.sdwinnie.airgesture.model.HandLockMode
import com.sdwinnie.airgesture.model.HorizontalNavMode
import com.sdwinnie.airgesture.model.HudPalmStyle
import com.sdwinnie.airgesture.model.HudThemeColor
import com.sdwinnie.airgesture.model.RadialSlotCount
import com.sdwinnie.airgesture.model.RadialTriggerPreset
import com.sdwinnie.airgesture.model.ScrollGesturePreset
import com.sdwinnie.airgesture.model.TargetFps
import com.sdwinnie.airgesture.model.VisionBufferSize
import com.sdwinnie.airgesture.model.VolumeGesturePreset

/**
 * Stores strictly non-sensitive user configuration flags, custom human posture presets,
 * HUD palm/theme visual styles, and the 15 radial shortcut package IDs.
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
            useFrontCamera = prefs.getBoolean(KEY_FRONT_CAMERA, true),
            handLockMode = readEnum(KEY_HAND_LOCK, HandLockMode.AUTO_SINGLE, HandLockMode::valueOf),
            scrollPreset = readEnum(KEY_SCROLL_PRESET, ScrollGesturePreset.OPEN_PALM_SWEEP, ScrollGesturePreset::valueOf),
            volumePreset = readEnum(KEY_VOLUME_PRESET, VolumeGesturePreset.PINCH_OK_SLIDE, VolumeGesturePreset::valueOf),
            radialPreset = readEnum(KEY_RADIAL_PRESET, RadialTriggerPreset.CLOSED_FIST, RadialTriggerPreset::valueOf),
            backPreset = readEnum(KEY_BACK_PRESET, BackTriggerPreset.HORIZONTAL_SWIPE, BackTriggerPreset::valueOf),
            horizontalNavMode = readEnum(KEY_H_NAV_MODE, HorizontalNavMode.BACK_AND_RECENTS, HorizontalNavMode::valueOf),
            hudPalmStyle = readEnum(KEY_HUD_PALM_STYLE, HudPalmStyle.SKELETON_21, HudPalmStyle::valueOf),
            hudThemeColor = readEnum(KEY_HUD_THEME, HudThemeColor.CYBER_CYAN, HudThemeColor::valueOf),
            radialSlotCount = readEnum(KEY_RADIAL_COUNT, RadialSlotCount.SLOTS_8, RadialSlotCount::valueOf),
            showSystemVolumeUi = prefs.getBoolean(KEY_SHOW_SYS_VOL_UI, true),
            scrollSensitivity = prefs.getFloat(KEY_SCROLL_SENS, 1.0f).coerceIn(0.5f, 2.0f),
            volumeSensitivity = prefs.getFloat(KEY_VOL_SENS, 1.0f).coerceIn(0.5f, 2.0f),
            showLiveCalibrationPreview = prefs.getBoolean(KEY_SHOW_PREVIEW, false)
        )
    }

    fun saveConfig(config: AirGestureConfig) {
        prefs.edit()
            .putString(KEY_RESOLUTION, config.resolution.name)
            .putString(KEY_TARGET_FPS, config.targetFps.name)
            .putString(KEY_VISION_BUFFER, config.visionBufferSize.name)
            .putBoolean(KEY_FRONT_CAMERA, config.useFrontCamera)
            .putString(KEY_HAND_LOCK, config.handLockMode.name)
            .putString(KEY_SCROLL_PRESET, config.scrollPreset.name)
            .putString(KEY_VOLUME_PRESET, config.volumePreset.name)
            .putString(KEY_RADIAL_PRESET, config.radialPreset.name)
            .putString(KEY_BACK_PRESET, config.backPreset.name)
            .putString(KEY_H_NAV_MODE, config.horizontalNavMode.name)
            .putString(KEY_HUD_PALM_STYLE, config.hudPalmStyle.name)
            .putString(KEY_HUD_THEME, config.hudThemeColor.name)
            .putString(KEY_RADIAL_COUNT, config.radialSlotCount.name)
            .putBoolean(KEY_SHOW_SYS_VOL_UI, config.showSystemVolumeUi)
            .putFloat(KEY_SCROLL_SENS, config.scrollSensitivity)
            .putFloat(KEY_VOL_SENS, config.volumeSensitivity)
            .putBoolean(KEY_SHOW_PREVIEW, config.showLiveCalibrationPreview)
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
        private const val KEY_FRONT_CAMERA = "use_front_camera"
        private const val KEY_HAND_LOCK = "hand_lock_mode"
        private const val KEY_SCROLL_PRESET = "scroll_gesture_preset"
        private const val KEY_VOLUME_PRESET = "volume_gesture_preset"
        private const val KEY_RADIAL_PRESET = "radial_trigger_preset"
        private const val KEY_BACK_PRESET = "back_trigger_preset"
        private const val KEY_H_NAV_MODE = "horizontal_nav_mode"
        private const val KEY_HUD_PALM_STYLE = "hud_palm_style"
        private const val KEY_HUD_THEME = "hud_theme_color"
        private const val KEY_RADIAL_COUNT = "radial_slot_count"
        private const val KEY_SHOW_SYS_VOL_UI = "show_system_volume_ui"
        private const val KEY_SCROLL_SENS = "scroll_sensitivity"
        private const val KEY_VOL_SENS = "volume_sensitivity"
        private const val KEY_SHOW_PREVIEW = "show_live_preview"
        private const val KEY_RADIAL_SLOT_PREFIX = "radial_slot_pkg_"
    }
}
