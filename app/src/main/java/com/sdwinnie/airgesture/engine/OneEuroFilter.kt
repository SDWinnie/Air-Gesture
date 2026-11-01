package com.sdwinnie.airgesture.engine

import com.sdwinnie.airgesture.model.HandFrame
import com.sdwinnie.airgesture.model.Landmark3D
import kotlin.math.PI
import kotlin.math.abs

/**
 * Speed-adaptive 1€ (One-Euro) Low-Pass Filter (Casiez et al., CHI 2012),
 * calibrated specifically for normalized [0.0 .. 1.0] screen-space landmark coordinates.
 * Suppresses stationary landmark micro-jitter while keeping fast gesture latency near zero.
 */
class OneEuroFilter1D(
    private val minCutoffHz: Float = 1.7f,
    private val beta: Float = 18.0f,
    private val derivativeCutoffHz: Float = 2.5f
) {
    private var initialized = false
    private var lastValue = 0f
    private var lastDerivative = 0f
    private var lastTimestampMs = 0L

    fun reset() {
        initialized = false
        lastValue = 0f
        lastDerivative = 0f
        lastTimestampMs = 0L
    }

    fun filter(rawValue: Float, timestampMs: Long): Float {
        if (!initialized) {
            initialized = true
            lastValue = rawValue
            lastDerivative = 0f
            lastTimestampMs = timestampMs
            return rawValue
        }

        if (timestampMs <= lastTimestampMs) {
            return lastValue
        }
        val dtSeconds = ((timestampMs - lastTimestampMs).coerceAtLeast(1L)) / 1000.0f
        lastTimestampMs = timestampMs

        val rawDerivative = (rawValue - lastValue) / dtSeconds
        val alphaD = smoothingAlpha(derivativeCutoffHz, dtSeconds)
        val filteredDerivative = lowPass(rawDerivative, lastDerivative, alphaD)
        lastDerivative = filteredDerivative

        val adaptiveCutoff = minCutoffHz + beta * abs(filteredDerivative)
        val alpha = smoothingAlpha(adaptiveCutoff, dtSeconds)
        val filteredValue = lowPass(rawValue, lastValue, alpha)
        lastValue = filteredValue
        return filteredValue
    }

    private fun smoothingAlpha(cutoffHz: Float, dtSeconds: Float): Float {
        val tau = (1.0 / (2.0 * PI * cutoffHz.coerceAtLeast(0.01f))).toFloat()
        return 1.0f / (1.0f + tau / dtSeconds)
    }

    private fun lowPass(current: Float, previous: Float, alpha: Float): Float {
        return alpha * current + (1.0f - alpha) * previous
    }
}

/**
 * 21-Landmark 3D Filter Bank with automatic tracking-gap reset.
 */
class LandmarkBankOneEuroFilter(
    minCutoffHz: Float = 1.7f,
    beta: Float = 18.0f,
    derivativeCutoffHz: Float = 2.5f,
    private val resetGapMs: Long = 350L
) {
    private val xFilters = Array(21) { OneEuroFilter1D(minCutoffHz, beta, derivativeCutoffHz) }
    private val yFilters = Array(21) { OneEuroFilter1D(minCutoffHz, beta, derivativeCutoffHz) }
    private val zFilters = Array(21) { OneEuroFilter1D(minCutoffHz, beta * 0.5f, derivativeCutoffHz) }
    private var lastFrameMs: Long = -1L

    fun reset() {
        for (i in 0 until 21) {
            xFilters[i].reset()
            yFilters[i].reset()
            zFilters[i].reset()
        }
        lastFrameMs = -1L
    }

    fun filter(frame: HandFrame): HandFrame {
        if (lastFrameMs > 0L && (frame.timestampMs - lastFrameMs) > resetGapMs) {
            reset()
        }
        lastFrameMs = frame.timestampMs

        val smoothed = ArrayList<Landmark3D>(21)
        for (i in 0 until 21) {
            val pt = frame.landmarks[i]
            smoothed.add(
                Landmark3D(
                    x = xFilters[i].filter(pt.x, frame.timestampMs),
                    y = yFilters[i].filter(pt.y, frame.timestampMs),
                    z = zFilters[i].filter(pt.z, frame.timestampMs)
                )
            )
        }
        return HandFrame(
            landmarks = smoothed,
            timestampMs = frame.timestampMs,
            confidence = frame.confidence
        )
    }
}
