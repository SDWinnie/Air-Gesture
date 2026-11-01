package com.sdwinnie.airgesture.engine

import com.sdwinnie.airgesture.model.AirTapTriggerPreset
import com.sdwinnie.airgesture.model.HandPosture
import com.sdwinnie.airgesture.model.Landmark3D
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Biomechanical 3D Hand Skeleton Analyzer & Touchless Air Tap Detector (v4.0).
 *
 * Reads all 21 MediaPipe landmarks and extracts:
 * 1. 20 anatomical 3D bone segments + 3 transverse knuckle arch segments.
 * 2. 3D palm plane normal vector (\vec{n}) to detect camera-facing palm, back-of-hand, and knife-hand.
 * 3. Continuous 5-finger flexion percentage [0.0..1.0] and inter-finger abduction angles.
 * 4. Multi-modal Touchless Air Tap (เคาะจอ / จดจอ) via Z-depth plunge, 2D dip-recoil, and pinch clicks.
 */
object HandSkeletonAnalyzer {

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

    enum class FingerRay(val thaiLabel: String) {
        THUMB("นิ้วโป้ง"),
        INDEX("นิ้วชี้"),
        MIDDLE("นิ้วกลาง"),
        RING("นิ้วนาง"),
        PINKY("นิ้วก้อย"),
        PALM_ARCH("ส่วนโค้งฝ่ามือ")
    }

    enum class BoneType {
        METACARPAL,
        PROXIMAL_PHALANX,
        INTERMEDIATE_PHALANX,
        DISTAL_PHALANX,
        TRANSVERSE_ARCH
    }

    data class BoneSegment3D(
        val startIdx: Int,
        val endIdx: Int,
        val ray: FingerRay,
        val type: BoneType,
        val lengthNormalized: Float,
        val dirX: Float,
        val dirY: Float,
        val dirZ: Float
    )

    data class Vector3D(val x: Float, val y: Float, val z: Float) {
        val magnitude: Float get() = sqrt(x * x + y * y + z * z)

        fun normalized(): Vector3D {
            val m = magnitude
            return if (m < 1e-6f) Vector3D(0f, 0f, 1f) else Vector3D(x / m, y / m, z / m)
        }
    }

    data class HandSkeletonData(
        val bones: List<BoneSegment3D>,
        val palmNormal: Vector3D,
        val isPalmFacingCamera: Boolean,
        val isBackFacingCamera: Boolean,
        val isKnifeHand: Boolean,
        val fingerFlexion: FloatArray, // [Thumb, Index, Middle, Ring, Pinky] in [0..1] (1 = extended)
        val interFingerSpreadDeg: FloatArray, // [Thumb-Index, Index-Middle, Middle-Ring, Ring-Pinky]
        val palmScale: Float,
        val palmCentroid: Landmark3D,
        val indexTipPos: Landmark3D,
        val isAirTapDetected: Boolean,
        val airTapKind: String,
        val classifiedPosture: HandPosture
    )

    // Circular motion history for Air Tap impulse detection (last ~380ms)
    private const val HISTORY_CAPACITY = 24
    private val timeHistory = LongArray(HISTORY_CAPACITY)
    private val indexZHistory = FloatArray(HISTORY_CAPACITY)
    private val indexYHistory = FloatArray(HISTORY_CAPACITY)
    private val palmZHistory = FloatArray(HISTORY_CAPACITY)
    private val pinchGapHistory = FloatArray(HISTORY_CAPACITY)
    private var historyCount = 0
    private var historyHead = 0

    private var lastAirTapFiredMs = -10_000L

    fun reset() {
        historyCount = 0
        historyHead = 0
        lastAirTapFiredMs = -10_000L
    }

    fun dist3D(a: Landmark3D, b: Landmark3D): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        val dz = a.z - b.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    fun dist2D(a: Landmark3D, b: Landmark3D): Float {
        return hypot(a.x - b.x, a.y - b.y)
    }

    private fun crossProduct(a: Vector3D, b: Vector3D): Vector3D {
        return Vector3D(
            x = a.y * b.z - a.z * b.y,
            y = a.z * b.x - a.x * b.z,
            z = a.x * b.y - a.y * b.x
        )
    }

    private fun dotProduct(a: Vector3D, b: Vector3D): Float {
        return a.x * b.x + a.y * b.y + a.z * b.z
    }

    private fun angleBetweenDeg(a: Vector3D, b: Vector3D): Float {
        val ma = a.magnitude
        val mb = b.magnitude
        if (ma < 1e-6f || mb < 1e-6f) return 0f
        val cosVal = (dotProduct(a, b) / (ma * mb)).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(cosVal.toDouble())).toFloat()
    }

    /**
     * Extracts full 20-bone skeleton, 3D palm normal, continuous joint angles, and Air Tap impulses.
     */
    fun analyze(
        landmarks: List<Landmark3D>,
        timestampMs: Long,
        palmScale: Float = HandGeometry.palmScale(landmarks),
        airTapTrigger: AirTapTriggerPreset = AirTapTriggerPreset.ANY_TAP,
        airTapSensitivity: Float = 1.0f,
        enableAirTap: Boolean = true
    ): HandSkeletonData {
        require(landmarks.size >= 21) { "Skeleton requires 21 landmarks" }

        val wrist = landmarks[WRIST]
        val indexMcp = landmarks[INDEX_MCP]
        val pinkyMcp = landmarks[PINKY_MCP]
        val middleMcp = landmarks[MIDDLE_MCP]

        // 1. Compute 3D Palm Plane Normal Vector
        // Width vector = Index MCP -> Pinky MCP
        val vWidth = Vector3D(pinkyMcp.x - indexMcp.x, pinkyMcp.y - indexMcp.y, pinkyMcp.z - indexMcp.z)
        // Length vector = Wrist -> Middle MCP
        val vLength = Vector3D(middleMcp.x - wrist.x, middleMcp.y - wrist.y, middleMcp.z - wrist.z)
        val normal = crossProduct(vLength, vWidth).normalized()

        val isPalmFacingCamera = normal.z > 0.18f
        val isBackFacingCamera = normal.z < -0.18f
        val isKnifeHand = abs(normal.z) <= 0.22f

        // 2. Extract All 20 Anatomical Bones + 3 Knuckle Arch Segments
        val bones = ArrayList<BoneSegment3D>(23)
        fun addBone(s: Int, e: Int, ray: FingerRay, type: BoneType) {
            val p1 = landmarks[s]
            val p2 = landmarks[e]
            val dx = p2.x - p1.x
            val dy = p2.y - p1.y
            val dz = p2.z - p1.z
            val len = sqrt(dx * dx + dy * dy + dz * dz)
            val inv = if (len > 1e-6f) 1.0f / len else 0f
            bones.add(
                BoneSegment3D(
                    startIdx = s,
                    endIdx = e,
                    ray = ray,
                    type = type,
                    lengthNormalized = len / palmScale.coerceAtLeast(0.01f),
                    dirX = dx * inv,
                    dirY = dy * inv,
                    dirZ = dz * inv
                )
            )
        }

        // Thumb Bones
        addBone(WRIST, THUMB_CMC, FingerRay.THUMB, BoneType.METACARPAL)
        addBone(THUMB_CMC, THUMB_MCP, FingerRay.THUMB, BoneType.METACARPAL)
        addBone(THUMB_MCP, THUMB_IP, FingerRay.THUMB, BoneType.PROXIMAL_PHALANX)
        addBone(THUMB_IP, THUMB_TIP, FingerRay.THUMB, BoneType.DISTAL_PHALANX)

        // Index Bones
        addBone(WRIST, INDEX_MCP, FingerRay.INDEX, BoneType.METACARPAL)
        addBone(INDEX_MCP, INDEX_PIP, FingerRay.INDEX, BoneType.PROXIMAL_PHALANX)
        addBone(INDEX_PIP, INDEX_DIP, FingerRay.INDEX, BoneType.INTERMEDIATE_PHALANX)
        addBone(INDEX_DIP, INDEX_TIP, FingerRay.INDEX, BoneType.DISTAL_PHALANX)

        // Middle Bones
        addBone(WRIST, MIDDLE_MCP, FingerRay.MIDDLE, BoneType.METACARPAL)
        addBone(MIDDLE_MCP, MIDDLE_PIP, FingerRay.MIDDLE, BoneType.PROXIMAL_PHALANX)
        addBone(MIDDLE_PIP, MIDDLE_DIP, FingerRay.MIDDLE, BoneType.INTERMEDIATE_PHALANX)
        addBone(MIDDLE_DIP, MIDDLE_TIP, FingerRay.MIDDLE, BoneType.DISTAL_PHALANX)

        // Ring Bones
        addBone(WRIST, RING_MCP, FingerRay.RING, BoneType.METACARPAL)
        addBone(RING_MCP, RING_PIP, FingerRay.RING, BoneType.PROXIMAL_PHALANX)
        addBone(RING_PIP, RING_DIP, FingerRay.RING, BoneType.INTERMEDIATE_PHALANX)
        addBone(RING_DIP, RING_TIP, FingerRay.RING, BoneType.DISTAL_PHALANX)

        // Pinky Bones
        addBone(WRIST, PINKY_MCP, FingerRay.PINKY, BoneType.METACARPAL)
        addBone(PINKY_MCP, PINKY_PIP, FingerRay.PINKY, BoneType.PROXIMAL_PHALANX)
        addBone(PINKY_PIP, PINKY_DIP, FingerRay.PINKY, BoneType.INTERMEDIATE_PHALANX)
        addBone(PINKY_DIP, PINKY_TIP, FingerRay.PINKY, BoneType.DISTAL_PHALANX)

        // Knuckle Arch Segments
        addBone(INDEX_MCP, MIDDLE_MCP, FingerRay.PALM_ARCH, BoneType.TRANSVERSE_ARCH)
        addBone(MIDDLE_MCP, RING_MCP, FingerRay.PALM_ARCH, BoneType.TRANSVERSE_ARCH)
        addBone(RING_MCP, PINKY_MCP, FingerRay.PALM_ARCH, BoneType.TRANSVERSE_ARCH)

        // 3. Compute Finger Flexion [0..1]
        val fingerFlexion = FloatArray(5)
        fun calcFingerFlexion(mcp: Int, pip: Int, tip: Int): Float {
            val v1x = landmarks[mcp].x - landmarks[pip].x
            val v1y = landmarks[mcp].y - landmarks[pip].y
            val v2x = landmarks[tip].x - landmarks[pip].x
            val v2y = landmarks[tip].y - landmarks[pip].y
            val m = sqrt((v1x * v1x + v1y * v1y) * (v2x * v2x + v2y * v2y))
            if (m < 1e-6f) return 0.5f
            val cosAngle = ((v1x * v2x + v1y * v2y) / m).coerceIn(-1f, 1f)
            // cos = -1 (180° straight) -> flexion = 1.0 (extended)
            // cos = 0.5 (60° folded) -> flexion = 0.0 (curled)
            return ((-cosAngle + 0.35f) / 1.35f).coerceIn(0f, 1f)
        }

        // Thumb flexion
        val thumbDist = dist2D(landmarks[THUMB_TIP], landmarks[INDEX_MCP]) / palmScale
        fingerFlexion[0] = (thumbDist / 0.85f).coerceIn(0f, 1f)
        fingerFlexion[1] = calcFingerFlexion(INDEX_MCP, INDEX_PIP, INDEX_TIP)
        fingerFlexion[2] = calcFingerFlexion(MIDDLE_MCP, MIDDLE_PIP, MIDDLE_TIP)
        fingerFlexion[3] = calcFingerFlexion(RING_MCP, RING_PIP, RING_TIP)
        fingerFlexion[4] = calcFingerFlexion(PINKY_MCP, PINKY_PIP, PINKY_TIP)

        // 4. Inter-finger spread angles
        fun rayVector(mcp: Int, tip: Int): Vector3D {
            return Vector3D(
                landmarks[tip].x - landmarks[mcp].x,
                landmarks[tip].y - landmarks[mcp].y,
                landmarks[tip].z - landmarks[mcp].z
            )
        }
        val tRay = rayVector(THUMB_MCP, THUMB_TIP)
        val iRay = rayVector(INDEX_MCP, INDEX_TIP)
        val mRay = rayVector(MIDDLE_MCP, MIDDLE_TIP)
        val rRay = rayVector(RING_MCP, RING_TIP)
        val pRay = rayVector(PINKY_MCP, PINKY_TIP)

        val spreads = floatArrayOf(
            angleBetweenDeg(tRay, iRay),
            angleBetweenDeg(iRay, mRay),
            angleBetweenDeg(mRay, rRay),
            angleBetweenDeg(rRay, pRay)
        )

        val palmCentroid = HandGeometry.palmCentroid(landmarks)
        val indexTip = landmarks[INDEX_TIP]
        val pinchDistNorm = dist2D(landmarks[THUMB_TIP], landmarks[INDEX_TIP]) / palmScale

        // 5. Record History for Air Tap Detection
        pushHistory(
            ts = timestampMs,
            indexZ = indexTip.z / palmScale,
            indexY = indexTip.y,
            palmZ = palmCentroid.z / palmScale,
            pinchGap = pinchDistNorm
        )

        // 6. Detect Touchless Air Tap (เคาะจอ / จดจอ)
        var tapDetected = false
        var detectedTapKind = ""

        if (enableAirTap && (timestampMs - lastAirTapFiredMs) >= 280L) {
            val tapResult = evaluateAirTap(airTapTrigger, airTapSensitivity)
            if (tapResult != null) {
                tapDetected = true
                detectedTapKind = tapResult
                lastAirTapFiredMs = timestampMs
            }
        }

        // 7. Posture Classification (with rich canonical postures)
        val posture = classifyDetailedPosture(landmarks, fingerFlexion, spreads, pinchDistNorm, isKnifeHand, palmScale)

        return HandSkeletonData(
            bones = bones,
            palmNormal = normal,
            isPalmFacingCamera = isPalmFacingCamera,
            isBackFacingCamera = isBackFacingCamera,
            isKnifeHand = isKnifeHand,
            fingerFlexion = fingerFlexion,
            interFingerSpreadDeg = spreads,
            palmScale = palmScale,
            palmCentroid = palmCentroid,
            indexTipPos = indexTip,
            isAirTapDetected = tapDetected,
            airTapKind = detectedTapKind,
            classifiedPosture = posture
        )
    }

    private fun pushHistory(ts: Long, indexZ: Float, indexY: Float, palmZ: Float, pinchGap: Float) {
        val idx = historyHead
        timeHistory[idx] = ts
        indexZHistory[idx] = indexZ
        indexYHistory[idx] = indexY
        palmZHistory[idx] = palmZ
        pinchGapHistory[idx] = pinchGap

        historyHead = (historyHead + 1) % HISTORY_CAPACITY
        if (historyCount < HISTORY_CAPACITY) {
            historyCount++
        }
    }

    /**
     * Evaluates whether an Air Tap (เคาะจอ) occurred in the recent 50..280ms window.
     */
    private fun evaluateAirTap(
        triggerPreset: AirTapTriggerPreset,
        sensitivity: Float
    ): String? {
        if (historyCount < 2) return null

        val currentIdx = (historyHead - 1 + HISTORY_CAPACITY) % HISTORY_CAPACITY
        val nowTs = timeHistory[currentIdx]
        val curIndexZ = indexZHistory[currentIdx]
        val curIndexY = indexYHistory[currentIdx]
        val curPalmZ = palmZHistory[currentIdx]
        val curPinch = pinchGapHistory[currentIdx]

        // Find baseline sample from 50-280ms ago
        var baselineIdx = -1
        for (i in 1 until historyCount) {
            val pastIdx = (historyHead - 1 - i + HISTORY_CAPACITY * 2) % HISTORY_CAPACITY
            val dt = nowTs - timeHistory[pastIdx]
            if (dt in 50L..280L) {
                baselineIdx = pastIdx
                break
            }
        }
        if (baselineIdx == -1) return null

        val baseIndexZ = indexZHistory[baselineIdx]
        val baseIndexY = indexYHistory[baselineIdx]
        val basePalmZ = palmZHistory[baselineIdx]
        val basePinch = pinchGapHistory[baselineIdx]

        val sensFactor = sensitivity.coerceIn(0.5f, 2.5f)

        // Mode A: Index Air Knock (เคาะปลายนิ้วชี้ไปข้างหน้า / แตะกระจก)
        if (triggerPreset == AirTapTriggerPreset.INDEX_KNOCK || triggerPreset == AirTapTriggerPreset.ANY_TAP) {
            // Negative deltaZ means moving closer to camera/screen
            val zPlunge = (baseIndexZ - curIndexZ) * sensFactor
            val yDipAndReturn = abs(curIndexY - baseIndexY) * sensFactor
            if (zPlunge > 0.048f || (zPlunge > 0.028f && yDipAndReturn > 0.035f)) {
                return "เคาะนิ้วชี้ (Index Knock)"
            }
        }

        // Mode B: Pinch Quick Tap (จีบเคาะเร็วๆ แบบ Vision Pro)
        if (triggerPreset == AirTapTriggerPreset.PINCH_QUICK_TAP || triggerPreset == AirTapTriggerPreset.ANY_TAP) {
            // Started open (> 0.28), snapped closed (< 0.18), or rebounded
            if (basePinch > 0.26f && curPinch < (0.19f / sensFactor)) {
                return "จีบนิ้วคลิก (Pinch Tap)"
            }
        }

        // Mode C: Palm Forward Knock (เคาะฝ่ามือ / เคาะสันมือเข้าหากล้อง)
        if (triggerPreset == AirTapTriggerPreset.PALM_FORWARD_KNOCK || triggerPreset == AirTapTriggerPreset.ANY_TAP) {
            val palmZPlunge = (basePalmZ - curPalmZ) * sensFactor
            if (palmZPlunge > 0.052f) {
                return "เคาะฝ่ามือ (Palm Knock)"
            }
        }

        return null
    }

    private fun classifyDetailedPosture(
        landmarks: List<Landmark3D>,
        flexion: FloatArray,
        spreads: FloatArray,
        pinchDistNorm: Float,
        isKnifeHand: Boolean,
        scale: Float
    ): HandPosture {
        val thumbExt = flexion[0] > 0.52f
        val indexExt = flexion[1] > 0.62f
        val middleExt = flexion[2] > 0.62f
        val ringExt = flexion[3] > 0.62f
        val pinkyExt = flexion[4] > 0.62f

        val indexCurl = flexion[1] < 0.40f
        val middleCurl = flexion[2] < 0.40f
        val ringCurl = flexion[3] < 0.40f
        val pinkyCurl = flexion[4] < 0.40f

        // 1. Pinch Candidate
        if (pinchDistNorm < 0.26f && !HandGeometry.isFullFist(landmarks, scale)) {
            return HandPosture.PINCH_OK
        }

        // 2. Thumbs Up / Down
        if (indexCurl && middleCurl && ringCurl && pinkyCurl && thumbExt) {
            val tip = landmarks[THUMB_TIP]
            val mcp = landmarks[THUMB_MCP]
            if ((mcp.y - tip.y) > 0.26f * scale) return HandPosture.THUMB_UP
            if ((tip.y - mcp.y) > 0.26f * scale) return HandPosture.THUMB_DOWN
        }

        // 3. Closed Fist
        if (indexCurl && middleCurl && ringCurl && pinkyCurl && !thumbExt) {
            return HandPosture.CLOSED_FIST
        }

        // 4. Knife Hand (สันมือ)
        if (isKnifeHand && indexExt && middleExt && ringExt && pinkyExt) {
            return HandPosture.KNIFE_HAND
        }

        // 5. Pointing One (ชี้ 1 นิ้ว)
        if (indexExt && middleCurl && ringCurl && pinkyCurl && !thumbExt) {
            return HandPosture.POINTING_ONE
        }

        // 6. Finger Gun (ปืนนิ้ว: โป้ง+ชี้)
        if (indexExt && thumbExt && middleCurl && ringCurl && pinkyCurl) {
            return HandPosture.FINGER_GUN
        }

        // 7. Victory / Peace (ชู 2 นิ้ว)
        if (indexExt && middleExt && ringCurl && pinkyCurl && !thumbExt) {
            return HandPosture.VICTORY_PEACE
        }

        // 8. I Love You (โป้ง+ชี้+ก้อย)
        if (thumbExt && indexExt && pinkyExt && middleCurl && ringCurl) {
            return HandPosture.I_LOVE_YOU
        }

        // 9. Call Me / Shaka (โป้ง+ก้อย)
        if (thumbExt && pinkyExt && indexCurl && middleCurl && ringCurl) {
            return HandPosture.CALL_ME_SHAKA
        }

        // 10. Spock Vulcan (ชู 4 นิ้วโดยแยกกลางกับนาง)
        if (indexExt && middleExt && ringExt && pinkyExt && spreads[1] > 20f) {
            return HandPosture.SPOCK_VULCAN
        }

        // 11. Three Fingers (ชู 3 นิ้ว)
        if (indexExt && middleExt && ringExt && pinkyCurl) {
            return HandPosture.THREE_FINGERS
        }

        // 12. Four Fingers (ชู 4 นิ้ว โป้งเก็บ)
        if (indexExt && middleExt && ringExt && pinkyExt && !thumbExt) {
            return HandPosture.FOUR_FINGERS
        }

        // 13. Open Palm (แบมือ 5 นิ้ว)
        if (thumbExt && indexExt && middleExt && ringExt && pinkyExt) {
            return HandPosture.OPEN_PALM
        }

        return HandPosture.NONE
    }
}
