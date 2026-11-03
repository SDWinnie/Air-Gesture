package com.sdwinnie.airgesture.engine

import com.sdwinnie.airgesture.model.AirGestureConfig
import com.sdwinnie.airgesture.model.BlinkAction
import com.sdwinnie.airgesture.model.EyeBlinkTriggerMode
import com.sdwinnie.airgesture.model.GestureEvent
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Intelligent Edge-AI Eye Contact & Multi-Blink Controller (v5.0).
 *
 * Capabilities:
 * 1. Eye Contact Verification: Ensures the user is looking directly at the device display
 *    (preventing accidental triggers when looking away, talking, or turning head).
 * 2. High-precision Eye Aspect Ratio (EAR) & Multi-Blink Cadence Engine:
 *    - 1 Blink: Scroll Down (เลื่อนลง)
 *    - 2 Blinks: Scroll Up (เลื่อนขึ้น)
 *    - 3 Blinks: Send Heart / Like (กดใจ TikTok / Reels / YouTube Shorts)
 * 3. 100% Freely Customizable: Every action, sensitivity, trigger mode, and speed window
 *    is configurable by the user.
 */
class EyeGazeBlinkAnalyzer(
    private var baselineEar: Float = 0.32f
) {
    data class EyeTrackPoints(
        val pupilX: Float,
        val pupilY: Float,
        val upperLidY: Float,
        val lowerLidY: Float,
        val innerCanthusX: Float,
        val outerCanthusX: Float,
        val apertureHeight: Float,
        val apertureWidth: Float,
        val upperLidLateralY: Float = upperLidY,
        val upperLidMedialY: Float = upperLidY,
        val lowerLidLateralY: Float = lowerLidY,
        val lowerLidMedialY: Float = lowerLidY,
        val eyebrowCenterY: Float = upperLidY - 0.05f,
        val cheekApexY: Float = lowerLidY + 0.06f
    )

    data class EyeFrameData(
        val leftEar: Float,
        val rightEar: Float,
        val gazeYawDeg: Float = 0f,
        val gazePitchDeg: Float = 0f,
        val eyeDistanceNorm: Float = 0.15f,
        val isFaceDetected: Boolean = true,
        val confidence: Float = 0.95f,
        val isLockedOn: Boolean = false,
        val leftEyeTrack: EyeTrackPoints? = null,
        val rightEyeTrack: EyeTrackPoints? = null,
        val trackingTrackCount: Int = 32,
        val isSquintOrSmile: Boolean = false,
        val irisGazeRatioX: Float = 0.5f,
        val irisGazeRatioY: Float = 0.5f,
        val isIrisCentered: Boolean = true
    ) {
        val averageEar: Float get() = (leftEar + rightEar) * 0.5f

        val isLookingAtScreen: Boolean
            get() = isFaceDetected && abs(gazeYawDeg) < 24.0f && abs(gazePitchDeg) < 24.0f && isIrisCentered
    }

    data class AnalysisResult(
        val isLookingAtScreen: Boolean,
        val gazeConfidence: Float,
        val leftEar: Float,
        val rightEar: Float,
        val pendingBlinkCount: Int,
        val events: List<GestureEvent>,
        val feedbackText: String?,
        val isLockedOn: Boolean = false,
        val trackCount: Int = 32
    )

    private var wasEyeClosed = false
    private var eyeClosedStartMs = -1L
    private var pendingBlinkCount = 0
    private var blinkWindowExpireMs = -1L
    private var lastActionFiredMs = -10_000L

    private var prevEyeContactState = false

    fun reset() {
        wasEyeClosed = false
        eyeClosedStartMs = -1L
        pendingBlinkCount = 0
        blinkWindowExpireMs = -1L
        lastActionFiredMs = -10_000L
        prevEyeContactState = false
        baselineEar = 0.32f
    }

    /**
     * Processes eye tracking data and evaluates eye contact and 1/2/3 blink triggers.
     */
    fun process(
        eyeFrame: EyeFrameData?,
        nowMs: Long,
        config: AirGestureConfig
    ): AnalysisResult {
        val events = mutableListOf<GestureEvent>()
        var feedback: String? = null

        if (!config.enableEyeContactBlink || eyeFrame == null || !eyeFrame.isFaceDetected) {
            // Check if we need to resolve any pending blink window that reached timeout
            if (pendingBlinkCount > 0 && nowMs >= blinkWindowExpireMs) {
                val action = resolveBlinkAction(pendingBlinkCount, config)
                if (action != BlinkAction.NONE) {
                    val desc = getActionDescription(pendingBlinkCount, action)
                    events.add(GestureEvent.EyeBlinkTriggered(pendingBlinkCount, action, desc))
                    feedback = desc
                    lastActionFiredMs = nowMs
                }
                pendingBlinkCount = 0
                blinkWindowExpireMs = -1L
            }
            return AnalysisResult(
                isLookingAtScreen = false,
                gazeConfidence = 0f,
                leftEar = 0f,
                rightEar = 0f,
                pendingBlinkCount = pendingBlinkCount,
                events = events,
                feedbackText = feedback,
                isLockedOn = false,
                trackCount = 0
            )
        }

        val isLooking = eyeFrame.isLookingAtScreen
        if (isLooking != prevEyeContactState) {
            events.add(GestureEvent.EyeContactChanged(isLooking, eyeFrame.confidence))
            prevEyeContactState = isLooking
        }

        // Adaptive baseline learning when eyes are open and looking at screen
        val curEar = when (config.eyeBlinkTriggerMode) {
            EyeBlinkTriggerMode.LEFT_EYE_ONLY -> eyeFrame.leftEar
            EyeBlinkTriggerMode.RIGHT_EYE_ONLY -> eyeFrame.rightEar
            EyeBlinkTriggerMode.ANY_EYE -> min(eyeFrame.leftEar, eyeFrame.rightEar)
            EyeBlinkTriggerMode.BOTH_EYES -> eyeFrame.averageEar
        }

        val sensFactor = config.blinkSensitivity.coerceIn(0.5f, 2.5f)
        val blinkThreshold = (baselineEar * 0.60f) * (1.0f / sensFactor)

        if (!wasEyeClosed && curEar > 0.24f) {
            baselineEar = 0.95f * baselineEar + 0.05f * curEar
        }

        val isEyeClosedNow = curEar < blinkThreshold && !eyeFrame.isSquintOrSmile

        if (eyeFrame.isSquintOrSmile) {
            feedback = "😊 ตรวจพบรอยยิ้ม/หยีตา (ระบบแยกแยะอัจฉริยะ)"
        }

        // Evaluate Blink State Transition
        if (isEyeClosedNow && !wasEyeClosed) {
            // Eye just closed
            wasEyeClosed = true
            eyeClosedStartMs = nowMs
            feedback = "👁️ กำลังกระพริบตา..."
        } else if (!isEyeClosedNow && wasEyeClosed) {
            // Eye just reopened -> calculate blink duration
            val blinkDuration = nowMs - eyeClosedStartMs
            wasEyeClosed = false
            eyeClosedStartMs = -1L

            // Standard human blink is 40..480ms at high frame rates
            if (blinkDuration in 40L..480L) {
                // If eye contact is strictly required, ensure user was looking at screen
                val validEyeContact = !config.requireEyeContact || isLooking
                if (validEyeContact && (nowMs - lastActionFiredMs) > 320L) {
                    pendingBlinkCount++
                    blinkWindowExpireMs = nowMs + config.blinkSpeedWindowMs

                    if (pendingBlinkCount >= 3) {
                        // Immediately fire 3-blink action without waiting for window timeout!
                        val action = resolveBlinkAction(3, config)
                        if (action != BlinkAction.NONE) {
                            val desc = getActionDescription(3, action)
                            events.add(GestureEvent.EyeBlinkTriggered(3, action, desc))
                            feedback = desc
                            lastActionFiredMs = nowMs
                        }
                        pendingBlinkCount = 0
                        blinkWindowExpireMs = -1L
                    } else {
                        feedback = "🎯 ล็อกตา: กระพริบ $pendingBlinkCount ครั้ง..."
                    }
                }
            }
        }

        // Check if pending window for 1 or 2 blinks expired -> dispatch action
        if (pendingBlinkCount > 0 && nowMs >= blinkWindowExpireMs) {
            val count = pendingBlinkCount
            pendingBlinkCount = 0
            blinkWindowExpireMs = -1L

            val action = resolveBlinkAction(count, config)
            if (action != BlinkAction.NONE) {
                val desc = getActionDescription(count, action)
                events.add(GestureEvent.EyeBlinkTriggered(count, action, desc))
                feedback = desc
                lastActionFiredMs = nowMs
            }
        }

        if (feedback == null && eyeFrame.isLockedOn) {
            feedback = if (isLooking) "🎯 ล็อกสายตาแล้ว (${eyeFrame.trackingTrackCount} Tracks)" else "👁️ มองตรงหน้าจอเพื่อสั่งงาน"
        }

        return AnalysisResult(
            isLookingAtScreen = isLooking,
            gazeConfidence = eyeFrame.confidence,
            leftEar = eyeFrame.leftEar,
            rightEar = eyeFrame.rightEar,
            pendingBlinkCount = pendingBlinkCount,
            events = events,
            feedbackText = feedback,
            isLockedOn = eyeFrame.isLockedOn,
            trackCount = eyeFrame.trackingTrackCount
        )
    }

    private fun resolveBlinkAction(count: Int, config: AirGestureConfig): BlinkAction {
        return when (count) {
            1 -> config.singleBlinkAction
            2 -> config.doubleBlinkAction
            3 -> config.tripleBlinkAction
            else -> BlinkAction.NONE
        }
    }

    private fun getActionDescription(count: Int, action: BlinkAction): String {
        val countText = when (count) {
            1 -> "กระพริบตา 1 ครั้ง"
            2 -> "กระพริบตา 2 ครั้ง"
            3 -> "กระพริบตา 3 ครั้ง"
            else -> "กระพริบตา $count ครั้ง"
        }
        return "${action.badgeEmoji} $countText: ${action.displayName}"
    }
}
