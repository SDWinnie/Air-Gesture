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
 * 21-Landmark Dual-Zone 3D Filter Bank with specialized Fingertip Kinematics
 * and automatic tracking-gap reset.
 *
 * Kinematic Zoning:
 * - Zone 0 (Wrist / MCPs): Balanced cutoff for wrist & palm core inertia.
 * - Zone 1 (DIP / IP joints): Intermediate dampening.
 * - Zone 2 (Fingertips 4, 8, 12, 16, 20): Ultra-low stationary cutoff (0.55 Hz) to eliminate
 *   fine-motor tremor/jitter when aiming the Air Cursor or holding a posture, paired with a high
 *   acceleration coefficient (beta = 26.0) for zero-lag flicks, pinch clicks, and swipes.
 */
class LandmarkBankOneEuroFilter(
    val palmMinCutoffHz: Float = 1.4f,
    val palmBeta: Float = 15.0f,
    val fingertipMinCutoffHz: Float = 0.55f,
    val fingertipBeta: Float = 26.0f,
    val derivativeCutoffHz: Float = 2.8f,
    private val resetGapMs: Long = 350L
) {
    companion object {
        val FINGERTIP_INDICES = intArrayOf(4, 8, 12, 16, 20)
        val DIP_INDICES = intArrayOf(3, 7, 11, 15, 19)

        fun isFingertip(index: Int): Boolean = index in FINGERTIP_INDICES
        fun isDipJoint(index: Int): Boolean = index in DIP_INDICES
    }

    private val xFilters: Array<OneEuroFilter1D>
    private val yFilters: Array<OneEuroFilter1D>
    private val zFilters: Array<OneEuroFilter1D>
    private var lastFrameMs: Long = -1L

    init {
        xFilters = Array(21) { i ->
            val (minCut, betaVal) = when {
                isFingertip(i) -> fingertipMinCutoffHz to fingertipBeta
                isDipJoint(i) -> 0.90f to 22.0f
                else -> palmMinCutoffHz to palmBeta
            }
            OneEuroFilter1D(minCut, betaVal, derivativeCutoffHz)
        }
        yFilters = Array(21) { i ->
            val (minCut, betaVal) = when {
                isFingertip(i) -> fingertipMinCutoffHz to fingertipBeta
                isDipJoint(i) -> 0.90f to 22.0f
                else -> palmMinCutoffHz to palmBeta
            }
            OneEuroFilter1D(minCut, betaVal, derivativeCutoffHz)
        }
        zFilters = Array(21) { i ->
            val (minCut, betaVal) = when {
                isFingertip(i) -> fingertipMinCutoffHz to (fingertipBeta * 0.6f)
                isDipJoint(i) -> 0.90f to 12.0f
                else -> palmMinCutoffHz to (palmBeta * 0.5f)
            }
            OneEuroFilter1D(minCut, betaVal, derivativeCutoffHz)
        }
    }

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
