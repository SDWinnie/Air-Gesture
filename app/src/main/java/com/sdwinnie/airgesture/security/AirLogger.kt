package com.sdwinnie.airgesture.security

import android.util.Log
import com.sdwinnie.airgesture.BuildConfig

/**
 * Edge-Privacy Logger:
 * Zero disk persistence. Completely silent in release builds so no frame,
 * timing, or gesture telemetry ever reaches system logcat buffers.
 */
object AirLogger {
    private const val TAG_PREFIX = "AirGesture/"

    fun d(section: String, message: String) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG_PREFIX + section, message)
        }
    }

    fun w(section: String, message: String) {
        if (BuildConfig.DEBUG) {
            Log.w(TAG_PREFIX + section, message)
        }
    }

    fun e(section: String, message: String, throwable: Throwable? = null) {
        if (BuildConfig.DEBUG) {
            Log.e(TAG_PREFIX + section, message, throwable)
        }
    }
}
