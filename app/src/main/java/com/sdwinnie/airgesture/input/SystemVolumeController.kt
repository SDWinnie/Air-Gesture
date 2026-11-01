package com.sdwinnie.airgesture.input

import android.content.Context
import android.media.AudioManager
import android.os.Build
import com.sdwinnie.airgesture.model.VolumeDirection
import kotlin.math.roundToInt

/**
 * Smooth Master System/Media Volume Controller (v3.0) with Explicit Directional Status Tracking
 * (`🔉 กำลังลดเสียง ▼`, `🔊 กำลังเพิ่มเสียง ▲`, `🔇 ปิดเสียง 0%`, `🔊 คงระดับเสียง`).
 */
class SystemVolumeController(context: Context) {

    data class VolumeStatusSnapshot(
        val percent: Int,
        val deltaPercent: Int,
        val direction: VolumeDirection,
        val isMuted: Boolean
    )

    private val audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val streamType = AudioManager.STREAM_MUSIC

    private var normalizedAccumulator: Float = readCurrentNormalizedVolume()
    private var sessionStartPercent: Int = 50
    private var lastDirection: VolumeDirection = VolumeDirection.STEADY
    private var lastDeltaPercent: Int = 0
    private var sessionActive = false

    fun beginPinchSession(): VolumeStatusSnapshot {
        normalizedAccumulator = readCurrentNormalizedVolume()
        val pct = (normalizedAccumulator * 100.0f).roundToInt().coerceIn(0, 100)
        sessionStartPercent = pct
        lastDeltaPercent = 0
        lastDirection = if (pct == 0) VolumeDirection.MUTED else VolumeDirection.STEADY
        sessionActive = true
        return currentStatusSnapshot()
    }

    fun endPinchSession() {
        sessionActive = false
        lastDirection = VolumeDirection.STEADY
        lastDeltaPercent = 0
    }

    /**
     * Applies vertical normalized hand movement (+deltaUp increases volume, -deltaUp decreases volume).
     */
    fun applyPinchDelta(
        normalizedDeltaUp: Float,
        sensitivity: Float = 1.0f,
        showSystemVolumeUi: Boolean = true
    ): VolumeStatusSnapshot {
        if (!sessionActive) {
            beginPinchSession()
        }

        val prevPct = (normalizedAccumulator * 100.0f).roundToInt().coerceIn(0, 100)
        val gain = 0.75f * sensitivity.coerceIn(0.5f, 2.0f)
        normalizedAccumulator = (normalizedAccumulator + normalizedDeltaUp * gain).coerceIn(0.0f, 1.0f)
        val newPct = (normalizedAccumulator * 100.0f).roundToInt().coerceIn(0, 100)

        val maxVol = audioManager.getStreamMaxVolume(streamType).coerceAtLeast(1)
        val minVol = readMinVolume()
        val targetIndex = (minVol + normalizedAccumulator * (maxVol - minVol)).roundToInt().coerceIn(minVol, maxVol)

        val currentIndex = audioManager.getStreamVolume(streamType)
        if (targetIndex != currentIndex) {
            val flags = if (showSystemVolumeUi) AudioManager.FLAG_SHOW_UI else 0
            runCatching {
                audioManager.setStreamVolume(streamType, targetIndex, flags)
            }
        }

        lastDeltaPercent = newPct - sessionStartPercent
        lastDirection = when {
            newPct == 0 && normalizedDeltaUp <= 0f -> VolumeDirection.MUTED
            normalizedDeltaUp > 0.004f || newPct > prevPct -> VolumeDirection.UP
            normalizedDeltaUp < -0.004f || newPct < prevPct -> VolumeDirection.DOWN
            else -> lastDirection
        }

        return VolumeStatusSnapshot(
            percent = newPct,
            deltaPercent = lastDeltaPercent,
            direction = lastDirection,
            isMuted = newPct == 0
        )
    }

    fun currentStatusSnapshot(): VolumeStatusSnapshot {
        val pct = currentVolumePercent()
        val dir = if (pct == 0) VolumeDirection.MUTED else lastDirection
        return VolumeStatusSnapshot(
            percent = pct,
            deltaPercent = lastDeltaPercent,
            direction = dir,
            isMuted = pct == 0
        )
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
