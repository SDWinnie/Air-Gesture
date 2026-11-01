package com.sdwinnie.airgesture.engine

import com.sdwinnie.airgesture.model.Landmark3D
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Scale-normalized 21-landmark hand geometry & vector mathematics.
 * Invariant to hand distance from camera lens via palm reference length L_ref.
 */
object HandGeometry {

    const val WRIST = 0
    const val THUMB_CMC = 1
    const val THUMB_MCP = 2
    const val THUMB_IP = 3
    const val THUMB_TIP = 4

    const val INDEX_MCP = 5
    const val INDEX_PIP = 6
    const val INDEX_DIP = 7
    const val INDEX_TIP = 8

    const val MIDDLE_MCP = 9
    const val MIDDLE_PIP = 10
    const val MIDDLE_DIP = 11
    const val MIDDLE_TIP = 12

    const val RING_MCP = 13
    const val RING_PIP = 14
    const val RING_DIP = 15
    const val RING_TIP = 16

    const val PINKY_MCP = 17
    const val PINKY_PIP = 18
    const val PINKY_DIP = 19
    const val PINKY_TIP = 20

    const val RADIAL_SLOTS = 15
    const val SLICE_DEGREES = 360.0f / RADIAL_SLOTS // 24.0 degrees per app segment

    private val FINGER_CHAINS = arrayOf(
        intArrayOf(INDEX_MCP, INDEX_PIP, INDEX_TIP),
        intArrayOf(MIDDLE_MCP, MIDDLE_PIP, MIDDLE_TIP),
        intArrayOf(RING_MCP, RING_PIP, RING_TIP),
        intArrayOf(PINKY_MCP, PINKY_PIP, PINKY_TIP)
    )

    fun dist2D(a: Landmark3D, b: Landmark3D): Float {
        return hypot(a.x - b.x, a.y - b.y)
    }

    /**
     * Reference palm length L_ref = ||wrist(0) - middle_mcp(9)||_2.
     * Used to normalize all thresholds for distance invariance (25 cm to 1.5 m).
     */
    fun palmScale(landmarks: List<Landmark3D>): Float {
        val wrist = landmarks[WRIST]
        val middleMcp = landmarks[MIDDLE_MCP]
        return dist2D(wrist, middleMcp).coerceAtLeast(0.035f)
    }

    fun palmCentroid(landmarks: List<Landmark3D>): Landmark3D {
        val w = landmarks[WRIST]
        val i = landmarks[INDEX_MCP]
        val m = landmarks[MIDDLE_MCP]
        val r = landmarks[RING_MCP]
        val p = landmarks[PINKY_MCP]
        return Landmark3D(
            x = (w.x + i.x + m.x + r.x + p.x) * 0.2f,
            y = (w.y + i.y + m.y + r.y + p.y) * 0.2f,
            z = (w.z + i.z + m.z + r.z + p.z) * 0.2f
        )
    }

    fun isFingerExtended(landmarks: List<Landmark3D>, mcpIdx: Int, pipIdx: Int, tipIdx: Int, scale: Float): Boolean {
        val wrist = landmarks[WRIST]
        val mcp = landmarks[mcpIdx]
        val pip = landmarks[pipIdx]
        val tip = landmarks[tipIdx]

        val tipToWrist = dist2D(tip, wrist)
        val pipToWrist = dist2D(pip, wrist)
        val tipToMcpNormalized = dist2D(tip, mcp) / scale
        return tipToWrist > (pipToWrist * 1.18f) && tipToMcpNormalized > 0.62f
    }

    fun isFingerCurled(landmarks: List<Landmark3D>, mcpIdx: Int, pipIdx: Int, tipIdx: Int, scale: Float): Boolean {
        val wrist = landmarks[WRIST]
        val mcp = landmarks[mcpIdx]
        val pip = landmarks[pipIdx]
        val tip = landmarks[tipIdx]

        val tipToWrist = dist2D(tip, wrist)
        val pipToWrist = dist2D(pip, wrist)
        val tipToMcpNormalized = dist2D(tip, mcp) / scale
        return tipToWrist < (pipToWrist * 1.12f) || tipToMcpNormalized < 0.56f
    }

    fun countExtendedNonThumbFingers(landmarks: List<Landmark3D>, scale: Float = palmScale(landmarks)): Int {
        var count = 0
        for (chain in FINGER_CHAINS) {
            if (isFingerExtended(landmarks, chain[0], chain[1], chain[2], scale)) {
                count++
            }
        }
        return count
    }

    fun countCurledNonThumbFingers(landmarks: List<Landmark3D>, scale: Float = palmScale(landmarks)): Int {
        var count = 0
        for (chain in FINGER_CHAINS) {
            if (isFingerCurled(landmarks, chain[0], chain[1], chain[2], scale)) {
                count++
            }
        }
        return count
    }

    /**
     * Normalized proximity between Thumb Tip (4) and Index Tip (8).
     */
    fun pinchNormalizedDistance(landmarks: List<Landmark3D>, scale: Float = palmScale(landmarks)): Float {
        return dist2D(landmarks[THUMB_TIP], landmarks[INDEX_TIP]) / scale
    }

    /**
     * Open Palm: All 4 fingers extended and thumb-index clearly separated (not pinching).
     */
    fun isOpenPalm(landmarks: List<Landmark3D>, scale: Float = palmScale(landmarks)): Boolean {
        val extended = countExtendedNonThumbFingers(landmarks, scale)
        val pinchGap = pinchNormalizedDistance(landmarks, scale)
        return extended == 4 && pinchGap > 0.38f
    }

    /**
     * Full Fist (ท่ากำมือ): All 4 non-thumb fingertips curled tightly toward their MCP bases.
     */
    fun isFullFist(landmarks: List<Landmark3D>, scale: Float = palmScale(landmarks)): Boolean {
        val curled = countCurledNonThumbFingers(landmarks, scale)
        if (curled < 4) return false
        // Ensure index tip is genuinely curled toward index MCP/palm rather than extended forward in an OK-pinch
        val indexTipToMcp = dist2D(landmarks[INDEX_TIP], landmarks[INDEX_MCP]) / scale
        return indexTipToMcp < 0.62f
    }

    /**
     * Pinch Gesture (ท่ามือจีบ): Thumb Tip & Index Tip within normalized threshold,
     * disambiguated from a closed 4-finger fist.
     */
    fun isPinchCandidate(
        landmarks: List<Landmark3D>,
        thresholdNormalized: Float,
        scale: Float = palmScale(landmarks)
    ): Boolean {
        val pinchDist = pinchNormalizedDistance(landmarks, scale)
        if (pinchDist > thresholdNormalized) return false
        // Disambiguate from full closed fist: at least one of middle/ring/pinky not curled OR index extended out to meet thumb
        val supportExtendedOrRelaxed =
            !isFingerCurled(landmarks, MIDDLE_MCP, MIDDLE_PIP, MIDDLE_TIP, scale) ||
                !isFingerCurled(landmarks, RING_MCP, RING_PIP, RING_TIP, scale) ||
                !isFingerCurled(landmarks, PINKY_MCP, PINKY_PIP, PINKY_TIP, scale)
        val indexMeetingThumbOutward =
            dist2D(landmarks[INDEX_TIP], landmarks[WRIST]) > 1.08f * dist2D(landmarks[INDEX_MCP], landmarks[WRIST])
        return supportExtendedOrRelaxed || indexMeetingThumbOutward
    }

    /**
     * Computes 360-degree clockwise directional angle from Wrist (0) to Palm Knuckle Center.
     * 0° = 12 o'clock (Up), 90° = 3 o'clock (Right), 180° = 6 o'clock (Down), 270° = 9 o'clock (Left).
     */
    fun wristToPalmAngleDegrees(landmarks: List<Landmark3D>): Float {
        val wrist = landmarks[WRIST]
        val palmKnuckleX = (
            landmarks[INDEX_MCP].x +
                landmarks[MIDDLE_MCP].x * 2.0f +
                landmarks[RING_MCP].x +
                landmarks[PINKY_MCP].x
            ) / 5.0f
        val palmKnuckleY = (
            landmarks[INDEX_MCP].y +
                landmarks[MIDDLE_MCP].y * 2.0f +
                landmarks[RING_MCP].y +
                landmarks[PINKY_MCP].y
            ) / 5.0f

        val dx = palmKnuckleX - wrist.x
        val dy = wrist.y - palmKnuckleY // Screen Y increases downward; invert so UP = +Y

        val rawDeg = Math.toDegrees(atan2(dx.toDouble(), dy.toDouble())).toFloat()
        return ((rawDeg % 360.0f) + 360.0f) % 360.0f
    }

    /**
     * Maps [0°, 360°) into one of 15 circular segments (0..14, 24° each)
     * with ±hysteresisDeg angular deadband to prevent slice flickering at boundaries.
     */
    fun angleToRadialSlot(
        angleDegrees: Float,
        previousSlot: Int = -1,
        hysteresisDeg: Float = 2.5f
    ): Int {
        val normalized = ((angleDegrees % 360.0f) + 360.0f) % 360.0f

        if (previousSlot in 0 until RADIAL_SLOTS) {
            val prevCenter = previousSlot * SLICE_DEGREES
            val angularDiff = shortestAngularDistanceDeg(normalized, prevCenter)
            if (abs(angularDiff) <= (SLICE_DEGREES * 0.5f + hysteresisDeg)) {
                return previousSlot
            }
        }

        val shifted = (normalized + SLICE_DEGREES * 0.5f) % 360.0f
        return ((shifted / SLICE_DEGREES).toInt()).coerceIn(0, RADIAL_SLOTS - 1)
    }

    fun shortestAngularDistanceDeg(a: Float, b: Float): Float {
        var diff = (a - b) % 360.0f
        if (diff > 180.0f) diff -= 360.0f
        if (diff < -180.0f) diff += 360.0f
        return diff
    }
}
