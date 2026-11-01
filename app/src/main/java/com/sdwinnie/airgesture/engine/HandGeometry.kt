package com.sdwinnie.airgesture.engine

import com.sdwinnie.airgesture.model.HandPosture
import com.sdwinnie.airgesture.model.Landmark3D
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Scale-normalized & viewpoint-invariant 21-landmark hand geometry, anatomical plausibility gate,
 * MediaPipe canonical human posture classifier, and Spatial Virtual Joystick circular wheel math.
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

    const val MAX_RADIAL_SLOTS = 15
    const val RADIAL_SLOTS = 15
    const val SLICE_DEGREES = 360.0f / RADIAL_SLOTS

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

    /**
     * Rejects non-hand phantom hallucinations (faces, curtains, distant background people, clipped blobs).
     */
    fun isAnatomicallyPlausibleHand(landmarks: List<Landmark3D>): Boolean {
        if (landmarks.size < 21) return false
        val wrist = landmarks[WRIST]
        val rawScale = dist2D(wrist, landmarks[MIDDLE_MCP])
        if (rawScale < 0.048f || rawScale > 0.62f) return false

        val palmWidth = dist2D(landmarks[INDEX_MCP], landmarks[PINKY_MCP])
        val widthRatio = palmWidth / rawScale
        if (widthRatio < 0.32f || widthRatio > 1.75f) return false

        // Ensure no fingertip teleports beyond anatomically possible 3.2x palm length
        val tips = intArrayOf(THUMB_TIP, INDEX_TIP, MIDDLE_TIP, RING_TIP, PINKY_TIP)
        for (tipIdx in tips) {
            if (dist2D(landmarks[tipIdx], wrist) > rawScale * 3.25f) return false
        }
        return true
    }

    /**
     * Computes cos(∠MCP-PIP-TIP). Straight finger approaches -1.0 (180°); folded finger > -0.35 (< 110°).
     */
    private fun pipJointCosine(mcp: Landmark3D, pip: Landmark3D, tip: Landmark3D): Float {
        val v1x = mcp.x - pip.x
        val v1y = mcp.y - pip.y
        val v2x = tip.x - pip.x
        val v2y = tip.y - pip.y
        val mag = sqrt((v1x * v1x + v1y * v1y) * (v2x * v2x + v2y * v2y))
        if (mag < 1e-6f) return 0f
        return ((v1x * v2x + v1y * v2y) / mag).coerceIn(-1f, 1f)
    }

    fun isFingerExtended(landmarks: List<Landmark3D>, mcpIdx: Int, pipIdx: Int, tipIdx: Int, scale: Float): Boolean {
        val wrist = landmarks[WRIST]
        val mcp = landmarks[mcpIdx]
        val pip = landmarks[pipIdx]
        val tip = landmarks[tipIdx]

        val tipToWrist = dist2D(tip, wrist)
        val pipToWrist = dist2D(pip, wrist)
        val tipToMcpNormalized = dist2D(tip, mcp) / scale
        val jointCos = pipJointCosine(mcp, pip, tip)

        // Straight joint angle (cos < -0.58 => > 125°) + outward extension
        return jointCos < -0.55f && tipToWrist > (pipToWrist * 1.13f) && tipToMcpNormalized > 0.58f
    }

    fun isFingerCurled(landmarks: List<Landmark3D>, mcpIdx: Int, pipIdx: Int, tipIdx: Int, scale: Float): Boolean {
        val wrist = landmarks[WRIST]
        val mcp = landmarks[mcpIdx]
        val pip = landmarks[pipIdx]
        val tip = landmarks[tipIdx]

        val tipToWrist = dist2D(tip, wrist)
        val pipToWrist = dist2D(pip, wrist)
        val tipToMcpNormalized = dist2D(tip, mcp) / scale
        val jointCos = pipJointCosine(mcp, pip, tip)

        return tipToWrist < (pipToWrist * 1.10f) || tipToMcpNormalized < 0.56f || jointCos > -0.20f
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

    fun pinchNormalizedDistance(landmarks: List<Landmark3D>, scale: Float = palmScale(landmarks)): Float {
        return dist2D(landmarks[THUMB_TIP], landmarks[INDEX_TIP]) / scale
    }

    fun isThumbExtendedOutward(landmarks: List<Landmark3D>, scale: Float = palmScale(landmarks)): Boolean {
        val mcp = landmarks[THUMB_MCP]
        val ip = landmarks[THUMB_IP]
        val tip = landmarks[THUMB_TIP]
        val indexMcp = landmarks[INDEX_MCP]
        val middleMcp = landmarks[MIDDLE_MCP]
        val middlePip = landmarks[MIDDLE_PIP]

        val tipToIndexMcp = dist2D(tip, indexMcp) / scale
        val tipToMiddleMcp = dist2D(tip, middleMcp) / scale
        val tipToMiddlePip = dist2D(tip, middlePip) / scale
        val thumbLen = dist2D(tip, mcp) / scale
        val ipCos = pipJointCosine(mcp, ip, tip)

        return thumbLen > 0.42f &&
            ipCos < -0.50f &&
            tipToIndexMcp > 0.38f &&
            tipToMiddleMcp > 0.45f &&
            tipToMiddlePip > 0.38f
    }

    fun isThumbUp(landmarks: List<Landmark3D>, scale: Float = palmScale(landmarks)): Boolean {
        if (countCurledNonThumbFingers(landmarks, scale) < 4) return false
        if (!isThumbExtendedOutward(landmarks, scale)) return false
        val tip = landmarks[THUMB_TIP]
        val mcp = landmarks[THUMB_MCP]
        val indexMcp = landmarks[INDEX_MCP]
        // Screen Y grows downward: thumb tip clearly above thumb MCP & index MCP
        return (mcp.y - tip.y) > 0.30f * scale && (indexMcp.y - tip.y) > 0.20f * scale
    }

    fun isThumbDown(landmarks: List<Landmark3D>, scale: Float = palmScale(landmarks)): Boolean {
        if (countCurledNonThumbFingers(landmarks, scale) < 4) return false
        if (!isThumbExtendedOutward(landmarks, scale)) return false
        val tip = landmarks[THUMB_TIP]
        val mcp = landmarks[THUMB_MCP]
        val indexMcp = landmarks[INDEX_MCP]
        // Screen Y grows downward: thumb tip clearly below thumb MCP & index MCP
        return (tip.y - mcp.y) > 0.30f * scale && (tip.y - indexMcp.y) > 0.20f * scale
    }

    fun isOpenPalm(landmarks: List<Landmark3D>, scale: Float = palmScale(landmarks)): Boolean {
        val extended = countExtendedNonThumbFingers(landmarks, scale)
        val pinchGap = pinchNormalizedDistance(landmarks, scale)
        return extended == 4 && pinchGap > 0.34f
    }

    fun isFullFist(landmarks: List<Landmark3D>, scale: Float = palmScale(landmarks)): Boolean {
        val curled = countCurledNonThumbFingers(landmarks, scale)
        if (curled < 4) return false
        if (isThumbUp(landmarks, scale) || isThumbDown(landmarks, scale)) return false
        val indexTipToMcp = dist2D(landmarks[INDEX_TIP], landmarks[INDEX_MCP]) / scale
        return indexTipToMcp < 0.64f
    }

    fun isPinchCandidate(
        landmarks: List<Landmark3D>,
        thresholdNormalized: Float,
        scale: Float = palmScale(landmarks)
    ): Boolean {
        val pinchDist = pinchNormalizedDistance(landmarks, scale)
        if (pinchDist > thresholdNormalized) return false
        if (isFullFist(landmarks, scale)) return false

        val supportExtendedOrRelaxed =
            !isFingerCurled(landmarks, MIDDLE_MCP, MIDDLE_PIP, MIDDLE_TIP, scale) ||
                !isFingerCurled(landmarks, RING_MCP, RING_PIP, RING_TIP, scale) ||
                !isFingerCurled(landmarks, PINKY_MCP, PINKY_PIP, PINKY_TIP, scale)
        val indexMeetingThumbOutward =
            dist2D(landmarks[INDEX_TIP], landmarks[WRIST]) > 1.08f * dist2D(landmarks[INDEX_MCP], landmarks[WRIST])
        return supportExtendedOrRelaxed || indexMeetingThumbOutward
    }

    fun isOneFingerPoint(landmarks: List<Landmark3D>, scale: Float = palmScale(landmarks)): Boolean {
        val indexExt = isFingerExtended(landmarks, INDEX_MCP, INDEX_PIP, INDEX_TIP, scale)
        val midCurl = isFingerCurled(landmarks, MIDDLE_MCP, MIDDLE_PIP, MIDDLE_TIP, scale)
        val ringCurl = isFingerCurled(landmarks, RING_MCP, RING_PIP, RING_TIP, scale)
        val pinkyCurl = isFingerCurled(landmarks, PINKY_MCP, PINKY_PIP, PINKY_TIP, scale)
        return indexExt && midCurl && ringCurl && pinkyCurl && pinchNormalizedDistance(landmarks, scale) > 0.35f
    }

    fun isVictoryPeace(landmarks: List<Landmark3D>, scale: Float = palmScale(landmarks)): Boolean {
        val indexExt = isFingerExtended(landmarks, INDEX_MCP, INDEX_PIP, INDEX_TIP, scale)
        val midExt = isFingerExtended(landmarks, MIDDLE_MCP, MIDDLE_PIP, MIDDLE_TIP, scale)
        val ringCurl = isFingerCurled(landmarks, RING_MCP, RING_PIP, RING_TIP, scale)
        val pinkyCurl = isFingerCurled(landmarks, PINKY_MCP, PINKY_PIP, PINKY_TIP, scale)
        if (!(indexExt && midExt && ringCurl && pinkyCurl)) return false
        val vSpread = dist2D(landmarks[INDEX_TIP], landmarks[MIDDLE_TIP]) / scale
        return vSpread > 0.16f && pinchNormalizedDistance(landmarks, scale) > 0.32f
    }

    fun isThreeFingers(landmarks: List<Landmark3D>, scale: Float = palmScale(landmarks)): Boolean {
        val indexExt = isFingerExtended(landmarks, INDEX_MCP, INDEX_PIP, INDEX_TIP, scale)
        val midExt = isFingerExtended(landmarks, MIDDLE_MCP, MIDDLE_PIP, MIDDLE_TIP, scale)
        val ringExt = isFingerExtended(landmarks, RING_MCP, RING_PIP, RING_TIP, scale)
        val pinkyCurl = isFingerCurled(landmarks, PINKY_MCP, PINKY_PIP, PINKY_TIP, scale)
        return indexExt && midExt && ringExt && pinkyCurl && pinchNormalizedDistance(landmarks, scale) > 0.32f
    }

    fun isILoveYouSign(landmarks: List<Landmark3D>, scale: Float = palmScale(landmarks)): Boolean {
        val indexExt = isFingerExtended(landmarks, INDEX_MCP, INDEX_PIP, INDEX_TIP, scale)
        val midCurl = isFingerCurled(landmarks, MIDDLE_MCP, MIDDLE_PIP, MIDDLE_TIP, scale)
        val ringCurl = isFingerCurled(landmarks, RING_MCP, RING_PIP, RING_TIP, scale)
        val pinkyExt = isFingerExtended(landmarks, PINKY_MCP, PINKY_PIP, PINKY_TIP, scale)
        return indexExt && pinkyExt && midCurl && ringCurl && pinchNormalizedDistance(landmarks, scale) > 0.34f
    }

    /**
     * Classifies the hand into one of the canonical human-ergonomic MediaPipe/HCI postures.
     */
    fun classifyPosture(
        landmarks: List<Landmark3D>,
        pinchThreshold: Float = 0.28f,
        scale: Float = palmScale(landmarks)
    ): HandPosture {
        return when {
            isPinchCandidate(landmarks, pinchThreshold, scale) -> HandPosture.PINCH_OK
            isOpenPalm(landmarks, scale) -> HandPosture.OPEN_PALM
            isThumbUp(landmarks, scale) -> HandPosture.THUMB_UP
            isThumbDown(landmarks, scale) -> HandPosture.THUMB_DOWN
            isFullFist(landmarks, scale) -> HandPosture.CLOSED_FIST
            isILoveYouSign(landmarks, scale) -> HandPosture.I_LOVE_YOU
            isVictoryPeace(landmarks, scale) -> HandPosture.VICTORY_PEACE
            isThreeFingers(landmarks, scale) -> HandPosture.THREE_FINGERS
            isOneFingerPoint(landmarks, scale) -> HandPosture.POINTING_ONE
            else -> HandPosture.NONE
        }
    }

    data class JoystickRadialTarget(
        val cursorNormX: Float, // [-1..1], +X = right
        val cursorNormY: Float, // [-1..1], +Y = down (screen space)
        val radiusNormalized: Float, // [0..1]
        val inCancelZone: Boolean,
        val angleDegrees: Float, // 0° = 12 o'clock, 90° = 3 o'clock
        val selectedSlot: Int
    )

    /**
     * Human-ergonomic Spatial Virtual Joystick for the On-Screen Circular App Wheel:
     * Translating the hand slightly (~0.85 * palmScale = ~5-7 cm) around the wheel's summon anchor
     * effortlessly reaches all 360° of the circle without uncomfortable wrist twisting!
     * Also blends subtle wrist tilt when near the anchor boundary.
     */
    fun computeJoystickRadialTarget(
        anchor: Landmark3D,
        currentCenter: Landmark3D,
        scale: Float,
        totalSlots: Int,
        previousSlot: Int,
        cancelDeadzone: Float = 0.26f
    ): JoystickRadialTarget {
        val fullReach = (scale * 0.92f).coerceAtLeast(0.055f)
        val rawDx = (currentCenter.x - anchor.x) / fullReach
        val rawDyScreen = (currentCenter.y - anchor.y) / fullReach // +down in screen coords
        val mag = hypot(rawDx, rawDyScreen)

        val clampedScale = if (mag > 1.0f) 1.0f / mag else 1.0f
        val normX = (rawDx * clampedScale).coerceIn(-1f, 1f)
        val normY = (rawDyScreen * clampedScale).coerceIn(-1f, 1f)
        val radius = mag.coerceIn(0f, 1f)

        if (radius < cancelDeadzone) {
            return JoystickRadialTarget(
                cursorNormX = normX,
                cursorNormY = normY,
                radiusNormalized = radius,
                inCancelZone = true,
                angleDegrees = 0f,
                selectedSlot = -1
            )
        }

        // Clockwise from 12 o'clock: +X = right, -normY = up
        val rawDeg = Math.toDegrees(atan2(rawDx.toDouble(), (-rawDyScreen).toDouble())).toFloat()
        val angle = ((rawDeg % 360.0f) + 360.0f) % 360.0f
        val slot = angleToRadialSlot(
            angleDegrees = angle,
            previousSlot = previousSlot,
            hysteresisDeg = 3.0f,
            totalSlots = totalSlots
        )
        return JoystickRadialTarget(
            cursorNormX = normX,
            cursorNormY = normY,
            radiusNormalized = radius,
            inCancelZone = false,
            angleDegrees = angle,
            selectedSlot = slot
        )
    }

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
        val dy = wrist.y - palmKnuckleY

        val rawDeg = Math.toDegrees(atan2(dx.toDouble(), dy.toDouble())).toFloat()
        return ((rawDeg % 360.0f) + 360.0f) % 360.0f
    }

    fun angleToRadialSlot(
        angleDegrees: Float,
        previousSlot: Int = -1,
        hysteresisDeg: Float = 2.5f,
        totalSlots: Int = RADIAL_SLOTS
    ): Int {
        val slots = totalSlots.coerceIn(2, MAX_RADIAL_SLOTS)
        val sliceDeg = 360.0f / slots
        val normalized = ((angleDegrees % 360.0f) + 360.0f) % 360.0f

        if (previousSlot in 0 until slots) {
            val prevCenter = previousSlot * sliceDeg
            val angularDiff = shortestAngularDistanceDeg(normalized, prevCenter)
            if (abs(angularDiff) <= (sliceDeg * 0.5f + hysteresisDeg)) {
                return previousSlot
            }
        }

        val shifted = (normalized + sliceDeg * 0.5f) % 360.0f
        return ((shifted / sliceDeg).toInt()).coerceIn(0, slots - 1)
    }

    fun shortestAngularDistanceDeg(a: Float, b: Float): Float {
        var diff = (a - b) % 360.0f
        if (diff > 180.0f) diff -= 360.0f
        if (diff < -180.0f) diff += 360.0f
        return diff
    }
}
