package com.sdwinnie.airgesture.engine

import com.sdwinnie.airgesture.model.AirTapTriggerPreset
import com.sdwinnie.airgesture.model.HandPosture
import com.sdwinnie.airgesture.model.Landmark3D
import com.sdwinnie.airgesture.model.SkeletonBoneDensity
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
        PALM_ARCH("ส่วนโค้งฝ่ามือ"),
        CARPAL_ARCH("กระดูกข้อมือ"),
        FOREARM_AXIS("ท่อนแขน"),
        CURVATURE_CHAIN("ความโค้งงอนิ้วมือ")
    }

    enum class BoneType {
        METACARPAL,
        PROXIMAL_PHALANX,
        INTERMEDIATE_PHALANX,
        DISTAL_PHALANX,
        TRANSVERSE_ARCH,
        CARPAL_BONE,
        FOREARM_SHAFT,
        CURVATURE_SEGMENT,
        TRANSVERSE_LIGAMENT
    }

    data class BoneSegment3D(
        val startIdx: Int,
        val endIdx: Int,
        val ray: FingerRay,
        val type: BoneType,
        val lengthNormalized: Float,
        val dirX: Float,
        val dirY: Float,
        val dirZ: Float,
        val startPoint: Landmark3D? = null,
        val endPoint: Landmark3D? = null,
        val label: String = ""
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
     * Extracts full 20, 36, or 48-bone kinematic skeleton, 3D palm normal, continuous joint angles, and Air Tap impulses.
     */
    fun analyze(
        landmarks: List<Landmark3D>,
        timestampMs: Long,
        palmScale: Float = HandGeometry.palmScale(landmarks),
        airTapTrigger: AirTapTriggerPreset = AirTapTriggerPreset.ANY_TAP,
        airTapSensitivity: Float = 1.0f,
        enableAirTap: Boolean = true,
        density: SkeletonBoneDensity = SkeletonBoneDensity.BONES_48
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

        // 2. Extract 20, 36, or 48 Anatomical Bone Segments
        val bones = ArrayList<BoneSegment3D>(48)
        fun addBone(
            s: Int,
            e: Int,
            ray: FingerRay,
            type: BoneType,
            p1: Landmark3D = landmarks[s],
            p2: Landmark3D = landmarks[e],
            label: String = ""
        ) {
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
                    dirZ = dz * inv,
                    startPoint = p1,
                    endPoint = p2,
                    label = label
                )
            )
        }

        // --- Core 20 Anatomical Bones ---
        // Thumb Bones (4)
        addBone(WRIST, THUMB_CMC, FingerRay.THUMB, BoneType.METACARPAL, label = "Thumb Metacarpal")
        addBone(THUMB_CMC, THUMB_MCP, FingerRay.THUMB, BoneType.METACARPAL, label = "Thumb CMC-MCP")
        addBone(THUMB_MCP, THUMB_IP, FingerRay.THUMB, BoneType.PROXIMAL_PHALANX, label = "Thumb Proximal")
        addBone(THUMB_IP, THUMB_TIP, FingerRay.THUMB, BoneType.DISTAL_PHALANX, label = "Thumb Distal")

        // Index Bones (4)
        addBone(WRIST, INDEX_MCP, FingerRay.INDEX, BoneType.METACARPAL, label = "Index Metacarpal")
        addBone(INDEX_MCP, INDEX_PIP, FingerRay.INDEX, BoneType.PROXIMAL_PHALANX, label = "Index Proximal")
        addBone(INDEX_PIP, INDEX_DIP, FingerRay.INDEX, BoneType.INTERMEDIATE_PHALANX, label = "Index Intermediate")
        addBone(INDEX_DIP, INDEX_TIP, FingerRay.INDEX, BoneType.DISTAL_PHALANX, label = "Index Distal")

        // Middle Bones (4)
        addBone(WRIST, MIDDLE_MCP, FingerRay.MIDDLE, BoneType.METACARPAL, label = "Middle Metacarpal")
        addBone(MIDDLE_MCP, MIDDLE_PIP, FingerRay.MIDDLE, BoneType.PROXIMAL_PHALANX, label = "Middle Proximal")
        addBone(MIDDLE_PIP, MIDDLE_DIP, FingerRay.MIDDLE, BoneType.INTERMEDIATE_PHALANX, label = "Middle Intermediate")
        addBone(MIDDLE_DIP, MIDDLE_TIP, FingerRay.MIDDLE, BoneType.DISTAL_PHALANX, label = "Middle Distal")

        // Ring Bones (4)
        addBone(WRIST, RING_MCP, FingerRay.RING, BoneType.METACARPAL, label = "Ring Metacarpal")
        addBone(RING_MCP, RING_PIP, FingerRay.RING, BoneType.PROXIMAL_PHALANX, label = "Ring Proximal")
        addBone(RING_PIP, RING_DIP, FingerRay.RING, BoneType.INTERMEDIATE_PHALANX, label = "Ring Intermediate")
        addBone(RING_DIP, RING_TIP, FingerRay.RING, BoneType.DISTAL_PHALANX, label = "Ring Distal")

        // Pinky Bones (4)
        addBone(WRIST, PINKY_MCP, FingerRay.PINKY, BoneType.METACARPAL, label = "Pinky Metacarpal")
        addBone(PINKY_MCP, PINKY_PIP, FingerRay.PINKY, BoneType.PROXIMAL_PHALANX, label = "Pinky Proximal")
        addBone(PINKY_PIP, PINKY_DIP, FingerRay.PINKY, BoneType.INTERMEDIATE_PHALANX, label = "Pinky Intermediate")
        addBone(PINKY_DIP, PINKY_TIP, FingerRay.PINKY, BoneType.DISTAL_PHALANX, label = "Pinky Distal")

        if (density == SkeletonBoneDensity.BONES_20) {
            // Keep exactly 20 or add 3 arches if desired
            addBone(INDEX_MCP, MIDDLE_MCP, FingerRay.PALM_ARCH, BoneType.TRANSVERSE_ARCH, label = "Arch I-M")
            addBone(MIDDLE_MCP, RING_MCP, FingerRay.PALM_ARCH, BoneType.TRANSVERSE_ARCH, label = "Arch M-R")
            addBone(RING_MCP, PINKY_MCP, FingerRay.PALM_ARCH, BoneType.TRANSVERSE_ARCH, label = "Arch R-P")
        } else {
            // --- 3 Knuckle Arch Segments ---
            addBone(INDEX_MCP, MIDDLE_MCP, FingerRay.PALM_ARCH, BoneType.TRANSVERSE_ARCH, label = "Arch Index-Middle")
            addBone(MIDDLE_MCP, RING_MCP, FingerRay.PALM_ARCH, BoneType.TRANSVERSE_ARCH, label = "Arch Middle-Ring")
            addBone(RING_MCP, PINKY_MCP, FingerRay.PALM_ARCH, BoneType.TRANSVERSE_ARCH, label = "Arch Ring-Pinky")

            // --- 13 Carpal & Forearm Biomechanical Segments (Total = 36 Bones) ---
            val forearmDirX = (wrist.x - middleMcp.x)
            val forearmDirY = (wrist.y - middleMcp.y)
            val forearmLen = hypot(forearmDirX, forearmDirY).coerceAtLeast(1e-5f)
            val fNormX = forearmDirX / forearmLen
            val fNormY = forearmDirY / forearmLen

            // Radial Styloid & Ulnar Styloid
            val radialStyloid = Landmark3D(wrist.x - vWidth.x * 0.32f, wrist.y - vWidth.y * 0.32f, wrist.z)
            val ulnarStyloid = Landmark3D(wrist.x + vWidth.x * 0.32f, wrist.y + vWidth.y * 0.32f, wrist.z)

            // Forearm Anchors
            val radiusShaftAnchor = Landmark3D(radialStyloid.x + fNormX * palmScale * 0.85f, radialStyloid.y + fNormY * palmScale * 0.85f, wrist.z)
            val ulnaShaftAnchor = Landmark3D(ulnarStyloid.x + fNormX * palmScale * 0.85f, ulnarStyloid.y + fNormY * palmScale * 0.85f, wrist.z)

            // 1-3 Forearm
            addBone(21, 22, FingerRay.FOREARM_AXIS, BoneType.FOREARM_SHAFT, radiusShaftAnchor, radialStyloid, "Radius Shaft")
            addBone(23, 24, FingerRay.FOREARM_AXIS, BoneType.FOREARM_SHAFT, ulnaShaftAnchor, ulnarStyloid, "Ulna Shaft")
            addBone(22, 24, FingerRay.FOREARM_AXIS, BoneType.TRANSVERSE_LIGAMENT, radialStyloid, ulnarStyloid, "DRUJ Ligament")

            // 4-11 Carpal Bones (8)
            val scaphoid = Landmark3D(wrist.x - vWidth.x * 0.18f + fNormX * -0.1f * palmScale, wrist.y - vWidth.y * 0.18f + fNormY * -0.1f * palmScale, wrist.z)
            val lunate = Landmark3D(wrist.x + fNormX * -0.1f * palmScale, wrist.y + fNormY * -0.1f * palmScale, wrist.z)
            val triquetrum = Landmark3D(wrist.x + vWidth.x * 0.18f + fNormX * -0.1f * palmScale, wrist.y + vWidth.y * 0.18f + fNormY * -0.1f * palmScale, wrist.z)
            val pisiform = Landmark3D(triquetrum.x + normal.x * 0.08f * palmScale, triquetrum.y + normal.y * 0.08f * palmScale, triquetrum.z + normal.z * 0.08f * palmScale)

            val trapezium = Landmark3D(scaphoid.x - vWidth.x * 0.15f + fNormX * -0.22f * palmScale, scaphoid.y - vWidth.y * 0.15f + fNormY * -0.22f * palmScale, wrist.z)
            val trapezoid = Landmark3D(scaphoid.x + fNormX * -0.22f * palmScale, scaphoid.y + fNormY * -0.22f * palmScale, wrist.z)
            val capitate = Landmark3D(lunate.x + fNormX * -0.24f * palmScale, lunate.y + fNormY * -0.24f * palmScale, wrist.z)
            val hamate = Landmark3D(triquetrum.x + fNormX * -0.22f * palmScale, triquetrum.y + fNormY * -0.22f * palmScale, wrist.z)

            addBone(22, 25, FingerRay.CARPAL_ARCH, BoneType.CARPAL_BONE, radialStyloid, scaphoid, "Scaphoid")
            addBone(0, 26, FingerRay.CARPAL_ARCH, BoneType.CARPAL_BONE, wrist, lunate, "Lunate")
            addBone(24, 27, FingerRay.CARPAL_ARCH, BoneType.CARPAL_BONE, ulnarStyloid, triquetrum, "Triquetrum")
            addBone(27, 28, FingerRay.CARPAL_ARCH, BoneType.CARPAL_BONE, triquetrum, pisiform, "Pisiform")
            addBone(25, 29, FingerRay.CARPAL_ARCH, BoneType.CARPAL_BONE, scaphoid, trapezium, "Trapezium")
            addBone(25, 30, FingerRay.CARPAL_ARCH, BoneType.CARPAL_BONE, scaphoid, trapezoid, "Trapezoid")
            addBone(26, 31, FingerRay.CARPAL_ARCH, BoneType.CARPAL_BONE, lunate, capitate, "Capitate")
            addBone(27, 32, FingerRay.CARPAL_ARCH, BoneType.CARPAL_BONE, triquetrum, hamate, "Hamate")

            // 12-13 Carpal Retinaculum & Palmar Ray (Total = 36 Bones)
            addBone(29, 32, FingerRay.CARPAL_ARCH, BoneType.TRANSVERSE_LIGAMENT, trapezium, hamate, "Transverse Carpal Ligament")
            addBone(26, 31, FingerRay.PALM_ARCH, BoneType.TRANSVERSE_ARCH, lunate, capitate, "Palmar Aponeurosis")

            if (density == SkeletonBoneDensity.BONES_48) {
                // --- 12 Clinical Kinematic Curvature & Intermetacarpal Segments (Total = 48 Bones) ---
                fun midArc(p1: Landmark3D, p2: Landmark3D, curveNorm: Float = 0.05f): Landmark3D {
                    return Landmark3D(
                        x = (p1.x + p2.x) * 0.5f + normal.x * curveNorm * palmScale,
                        y = (p1.y + p2.y) * 0.5f + normal.y * curveNorm * palmScale,
                        z = (p1.z + p2.z) * 0.5f + normal.z * curveNorm * palmScale
                    )
                }

                // 8 Curvature Sub-Bones (Mid-shaft arc divisions for Proximal & Intermediate phalanges)
                val idxProxMid = midArc(landmarks[INDEX_MCP], landmarks[INDEX_PIP])
                val idxInterMid = midArc(landmarks[INDEX_PIP], landmarks[INDEX_DIP])
                addBone(INDEX_MCP, 33, FingerRay.CURVATURE_CHAIN, BoneType.CURVATURE_SEGMENT, landmarks[INDEX_MCP], idxProxMid, "Index Proximal Arc")
                addBone(INDEX_PIP, 34, FingerRay.CURVATURE_CHAIN, BoneType.CURVATURE_SEGMENT, landmarks[INDEX_PIP], idxInterMid, "Index Intermediate Arc")

                val midProxMid = midArc(landmarks[MIDDLE_MCP], landmarks[MIDDLE_PIP])
                val midInterMid = midArc(landmarks[MIDDLE_PIP], landmarks[MIDDLE_DIP])
                addBone(MIDDLE_MCP, 35, FingerRay.CURVATURE_CHAIN, BoneType.CURVATURE_SEGMENT, landmarks[MIDDLE_MCP], midProxMid, "Middle Proximal Arc")
                addBone(MIDDLE_PIP, 36, FingerRay.CURVATURE_CHAIN, BoneType.CURVATURE_SEGMENT, landmarks[MIDDLE_PIP], midInterMid, "Middle Intermediate Arc")

                val rngProxMid = midArc(landmarks[RING_MCP], landmarks[RING_PIP])
                val rngInterMid = midArc(landmarks[RING_PIP], landmarks[RING_DIP])
                addBone(RING_MCP, 37, FingerRay.CURVATURE_CHAIN, BoneType.CURVATURE_SEGMENT, landmarks[RING_MCP], rngProxMid, "Ring Proximal Arc")
                addBone(RING_PIP, 38, FingerRay.CURVATURE_CHAIN, BoneType.CURVATURE_SEGMENT, landmarks[RING_PIP], rngInterMid, "Ring Intermediate Arc")

                val pnkProxMid = midArc(landmarks[PINKY_MCP], landmarks[PINKY_PIP])
                val pnkInterMid = midArc(landmarks[PINKY_PIP], landmarks[PINKY_DIP])
                addBone(PINKY_MCP, 39, FingerRay.CURVATURE_CHAIN, BoneType.CURVATURE_SEGMENT, landmarks[PINKY_MCP], pnkProxMid, "Pinky Proximal Arc")
                addBone(PINKY_PIP, 40, FingerRay.CURVATURE_CHAIN, BoneType.CURVATURE_SEGMENT, landmarks[PINKY_PIP], pnkInterMid, "Pinky Intermediate Arc")

                // 4 Deep Intermetacarpal Transverse Ligaments (Total = 48 Bones!)
                addBone(THUMB_CMC, INDEX_MCP, FingerRay.PALM_ARCH, BoneType.TRANSVERSE_LIGAMENT, landmarks[THUMB_CMC], landmarks[INDEX_MCP], "Intermetacarpal I-II")
                addBone(INDEX_MCP, MIDDLE_MCP, FingerRay.PALM_ARCH, BoneType.TRANSVERSE_LIGAMENT, landmarks[INDEX_MCP], landmarks[MIDDLE_MCP], "Intermetacarpal II-III")
                addBone(MIDDLE_MCP, RING_MCP, FingerRay.PALM_ARCH, BoneType.TRANSVERSE_LIGAMENT, landmarks[MIDDLE_MCP], landmarks[RING_MCP], "Intermetacarpal III-IV")
                addBone(RING_MCP, PINKY_MCP, FingerRay.PALM_ARCH, BoneType.TRANSVERSE_LIGAMENT, landmarks[RING_MCP], landmarks[PINKY_MCP], "Intermetacarpal IV-V")
            }
        }

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

        val middlePinchDistNorm = dist2D(landmarks[THUMB_TIP], landmarks[MIDDLE_TIP]) / scale

        // 1. Air Snap (ดีดนิ้ว: โป้งประกบกลาง)
        if (middlePinchDistNorm < 0.24f && ringCurl && pinkyCurl && !HandGeometry.isFullFist(landmarks, scale)) {
            return HandPosture.AIR_SNAP
        }

        // 2. Pinch Candidate
        if (pinchDistNorm < 0.26f && !HandGeometry.isFullFist(landmarks, scale)) {
            return HandPosture.PINCH_OK
        }

        // 3. Thumbs Up / Down
        if (indexCurl && middleCurl && ringCurl && pinkyCurl && thumbExt) {
            val tip = landmarks[THUMB_TIP]
            val mcp = landmarks[THUMB_MCP]
            if ((mcp.y - tip.y) > 0.26f * scale) return HandPosture.THUMB_UP
            if ((tip.y - mcp.y) > 0.26f * scale) return HandPosture.THUMB_DOWN
        }

        // 4. Closed Fist
        if (indexCurl && middleCurl && ringCurl && pinkyCurl && !thumbExt) {
            return HandPosture.CLOSED_FIST
        }

        // 5. Knife Hand (สันมือ)
        if (isKnifeHand && indexExt && middleExt && ringExt && pinkyExt) {
            return HandPosture.KNIFE_HAND
        }

        // 6. Two-Finger Tap (แตะ 2 นิ้ว: ชี้+กลางแนบชิดกัน)
        if (indexExt && middleExt && ringCurl && pinkyCurl && spreads[1] < 12.0f) {
            return HandPosture.TWO_FINGER_TAP
        }

        // 7. Victory / Peace (ชู 2 นิ้วแยกกว้าง)
        if (indexExt && middleExt && ringCurl && pinkyCurl && !thumbExt) {
            return HandPosture.VICTORY_PEACE
        }

        // 8. Index Beckon (กวักนิ้วชี้) / Pointing One (ชี้ 1 นิ้ว)
        if (indexExt && middleCurl && ringCurl && pinkyCurl) {
            val indexPip = landmarks[INDEX_PIP]
            val indexTip = landmarks[INDEX_TIP]
            if (indexTip.z - indexPip.z < -0.15f * scale) {
                return HandPosture.INDEX_BECKON
            }
            if (!thumbExt) {
                return HandPosture.POINTING_ONE
            }
        }

        // 9. Finger Gun (ปืนนิ้ว: โป้ง+ชี้)
        if (indexExt && thumbExt && middleCurl && ringCurl && pinkyCurl) {
            return HandPosture.FINGER_GUN
        }

        // 10. I Love You (โป้ง+ชี้+ก้อย)
        if (thumbExt && indexExt && pinkyExt && middleCurl && ringCurl) {
            return HandPosture.I_LOVE_YOU
        }

        // 11. Call Me / Shaka Wave (โป้ง+ก้อย)
        if (thumbExt && pinkyExt && indexCurl && middleCurl && ringCurl) {
            return HandPosture.CALL_ME_SHAKA
        }

        // 12. Spock Vulcan (ชู 4 นิ้วโดยแยกกลางกับนาง)
        if (indexExt && middleExt && ringExt && pinkyExt && spreads[1] > 20f) {
            return HandPosture.SPOCK_VULCAN
        }

        // 13. Three Fingers (ชู 3 นิ้ว)
        if (indexExt && middleExt && ringExt && pinkyCurl) {
            return HandPosture.THREE_FINGERS
        }

        // 14. Four Fingers (ชู 4 นิ้ว โป้งเก็บ)
        if (indexExt && middleExt && ringExt && pinkyExt && !thumbExt) {
            return HandPosture.FOUR_FINGERS
        }

        // 15. Palm Push (ผลักฝ่ามือเข้าหากล้อง)
        if (thumbExt && indexExt && middleExt && ringExt && pinkyExt && landmarks[INDEX_TIP].z < landmarks[WRIST].z - 0.20f * scale) {
            return HandPosture.PALM_PUSH
        }

        // 16. Open Palm (แบมือ 5 นิ้ว)
        if (thumbExt && indexExt && middleExt && ringExt && pinkyExt) {
            return HandPosture.OPEN_PALM
        }

        return HandPosture.NONE
    }
}
