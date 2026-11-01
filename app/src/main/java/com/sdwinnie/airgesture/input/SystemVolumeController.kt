package com.sdwinnie.airgesture.input

import android.content.Context
import android.media.AudioManager
import android.os.Build
import kotlin.math.roundToInt

/**
 * Smooth Master System/Media Volume Controller for the Pinch Gesture (ท่ามือจีบ).
 * Maintains a sub-step floating-point volume accumulator so continuous vertical hand movements
 * modulate Android's discrete AudioManager volume steps fluidly without popping up the stock OS toast
 * (since Dynamic Island renders the live Volume Pill Slider).
 */
class SystemVolumeController(context: Context) {

    private val audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val streamType = AudioManager.STREAM_MUSIC

    private var normalizedAccumulator: Float = readCurrentNormalizedVolume()
    private var sessionActive = false

    fun beginPinchSession(): Int {
        normalizedAccumulator = readCurrentNormalizedVolume()
        sessionActive = true
        return currentVolumePercent()
    }

    fun endPinchSession() {
        sessionActive = false
    }

    /**
     * Applies vertical normalized hand movement (+deltaUp increases volume, -deltaUp decreases volume).
     * Returns updated volume percentage [0..100].
     */
    fun applyPinchDelta(normalizedDeltaUp: Float, sensitivity: Float = 1.0f): Int {
        if (!sessionActive) {
            beginPinchSession()
        }

        // Full palm-scale vertical sweep (~1.35 * L_ref) spans 0% -> 100% smoothly
        val gain = 0.72f * sensitivity.coerceIn(0.5f, 2.0f)
        normalizedAccumulator = (normalizedAccumulator + normalizedDeltaUp * gain).coerceIn(0.0f, 1.0f)

        val maxVol = audioManager.getStreamMaxVolume(streamType).coerceAtLeast(1)
        val minVol = readMinVolume()
        val targetIndex = (minVol + normalizedAccumulator * (maxVol - minVol)).roundToInt().coerceIn(minVol, maxVol)

        val currentIndex = audioManager.getStreamVolume(streamType)
        if (targetIndex != currentIndex) {
            // flags = 0 suppresses stock Android volume popup because Dynamic Island renders the Volume Pill Slider
            runCatching {
                audioManager.setStreamVolume(streamType, targetIndex, 0)
            }
        }

        return (normalizedAccumulator * 100.0f).roundToInt().coerceIn(0, 100)
    }

    fun currentVolumePercent(): Int {
        val norm = if (sessionActive) normalizedAccumulator else readCurrentNormalizedVolume()
        return (norm * 100.0f).roundToInt().coerceIn(0, 100)
    }

    private fun readCurrentNormalizedVolume(): Float {
        val maxVol = audioManager.getStreamMaxVolume(streamType).coerceAtLeast(1)
        val minVol = readMinVolume()
        val cur = audioManager.getStreamVolume(streamType).coerceIn(minVol, maxVol)
        val span = (maxVol - minVol).coerceAtLeast(1)
        return ((cur - minVol).toFloat() / span.toFloat()).coerceIn(0.0f, 1.0f)
    }

    private fun readMinVolume(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { audioManager.getStreamMinVolume(streamType) }.getOrDefault(0)
        } else {
            0
        }
    }
}
