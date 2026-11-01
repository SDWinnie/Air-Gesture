package com.sdwinnie.airgesture.security

import android.content.Context
import com.sdwinnie.airgesture.model.AirGestureConfig
import com.sdwinnie.airgesture.model.CameraResolution
import com.sdwinnie.airgesture.model.HorizontalNavMode
import com.sdwinnie.airgesture.model.TargetFps
import com.sdwinnie.airgesture.model.VisionBufferSize

/**
 * Stores strictly non-sensitive user configuration flags and the 15 radial shortcut package IDs.
 * Never stores camera pixels, landmarks, or behavioral history.
 */
class PreferenceStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var isProminentDisclosureAccepted: Boolean
        get() = prefs.getBoolean(KEY_DISCLOSURE_ACCEPTED, false)
        set(value) = prefs.edit().putBoolean(KEY_DISCLOSURE_ACCEPTED, value).apply()

    fun loadConfig(): AirGestureConfig {
        val resName = prefs.getString(KEY_RESOLUTION, CameraResolution.RES_1080P.name)
        val fpsName = prefs.getString(KEY_TARGET_FPS, TargetFps.FPS_60.name)
        val roiName = prefs.getString(KEY_VISION_BUFFER, VisionBufferSize.ROI_192.name)
        val navName = prefs.getString(KEY_H_NAV_MODE, HorizontalNavMode.BACK_AND_RECENTS.name)

        return AirGestureConfig(
            resolution = runCatching { CameraResolution.valueOf(resName!!) }.getOrDefault(CameraResolution.RES_1080P),
            targetFps = runCatching { TargetFps.valueOf(fpsName!!) }.getOrDefault(TargetFps.FPS_60),
            visionBufferSize = runCatching { VisionBufferSize.valueOf(roiName!!) }.getOrDefault(VisionBufferSize.ROI_192),
            useFrontCamera = prefs.getBoolean(KEY_FRONT_CAMERA, true),
            horizontalNavMode = runCatching { HorizontalNavMode.valueOf(navName!!) }.getOrDefault(HorizontalNavMode.BACK_AND_RECENTS),
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
            .putString(KEY_H_NAV_MODE, config.horizontalNavMode.name)
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
        private const val KEY_H_NAV_MODE = "horizontal_nav_mode"
        private const val KEY_SCROLL_SENS = "scroll_sensitivity"
        private const val KEY_VOL_SENS = "volume_sensitivity"
        private const val KEY_SHOW_PREVIEW = "show_live_preview"
        private const val KEY_RADIAL_SLOT_PREFIX = "radial_slot_pkg_"
    }
}
