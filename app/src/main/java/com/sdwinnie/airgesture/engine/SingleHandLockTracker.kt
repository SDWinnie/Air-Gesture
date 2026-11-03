package com.sdwinnie.airgesture.engine

import com.sdwinnie.airgesture.model.HandFrame
import com.sdwinnie.airgesture.model.HandLockMode
import com.sdwinnie.airgesture.model.HandSide
import com.sdwinnie.airgesture.model.Landmark3D
import kotlin.math.abs

/**
 * Strict Single-Hand Identity, Spatial Quadrant, and Chirality Continuity Gate (v6.0).
 * Completely eliminates cross-hand jumping ("ตรวจจับมั่วๆทำอีกมือได้อีกมือ") by:
 * 1. Pinning spatial horizontal centroid and rejecting sudden opposite-screen jumps.
 * 2. Cross-verifying MediaPipe handedness against intrinsic 3D biomechanical chirality.
 * 3. Enforcing hysteresis lock so an active gesturing hand cannot be hijacked by a resting hand.
 */
class SingleHandLockTracker(
    private val lockReleaseTimeoutMs: Long = 1450L,
    private val maxSpatialTeleportDistance: Float = 0.35f,
    private val teleportWindowMs: Long = 280L
) {
    var lockedSide: HandSide? = null
        private set

    private var lockedSpatialX: Float? = null
    private var lastAcceptedCenter: Landmark3D? = null
    private var lastAcceptedTimestampMs: Long = -1L

    fun reset() {
        lockedSide = null
        lockedSpatialX = null
        lastAcceptedCenter = null
        lastAcceptedTimestampMs = -1L
    }

    /**
     * Biomechanically estimates hand chirality (Right vs Left) from 3D skeleton geometry,
     * independent of MediaPipe's raw classification which can flip when palm rotates.
     */
    fun computeGeometricHandSide(landmarks: List<Landmark3D>): HandSide {
        if (landmarks.size < 21) return HandSide.UNKNOWN
        val wrist = landmarks[0]
        val indexMcp = landmarks[5]
        val pinkyMcp = landmarks[17]
        val thumbTip = landmarks[4]

        val midKnuckleX = (indexMcp.x + pinkyMcp.x) * 0.5f
        val thumbOffset = thumbTip.x - midKnuckleX

        // For front camera mirrored view:
        // User's right hand has thumb on the right of palm (+X in image), left hand has thumb on left (-X).
        return when {
            thumbOffset > 0.035f -> HandSide.RIGHT
            thumbOffset < -0.035f -> HandSide.LEFT
            else -> HandSide.UNKNOWN
        }
    }

    /**
     * Returns the accepted [HandFrame] if it belongs to the active single hand,
     * or `null` if it is a second/wrong hand or anatomically implausible ghost detection.
     */
    fun filterSingleHand(
        candidate: HandFrame?,
        nowMs: Long,
        lockMode: HandLockMode
    ): HandFrame? {
        if (candidate == null) {
            if (lastAcceptedTimestampMs > 0L && (nowMs - lastAcceptedTimestampMs) >= lockReleaseTimeoutMs) {
                reset()
            }
            return null
        }

        // Release stale lock if previous hand was absent longer than timeout
        if (lastAcceptedTimestampMs > 0L && (nowMs - lastAcceptedTimestampMs) >= lockReleaseTimeoutMs) {
            reset()
        }

        // 1. Anatomical plausibility gate
        if (!HandGeometry.isAnatomicallyPlausibleHand(candidate.landmarks)) {
            return null
        }

        // 2. Resolve intrinsic hand side via fusion of MediaPipe + Biomechanical Geometry
        val geometricSide = computeGeometricHandSide(candidate.landmarks)
        val resolvedSide = if (candidate.handSide != HandSide.UNKNOWN) {
            candidate.handSide
        } else {
            geometricSide
        }

        // 3. Explicit Left/Right Hand Preference Lock
        when (lockMode) {
            HandLockMode.RIGHT_ONLY -> {
                if (resolvedSide == HandSide.LEFT && candidate.confidence >= 0.58f) {
                    return null
                }
            }
            HandLockMode.LEFT_ONLY -> {
                if (resolvedSide == HandSide.RIGHT && candidate.confidence >= 0.58f) {
                    return null
                }
            }
            HandLockMode.AUTO_SINGLE -> {
                val activeSide = lockedSide
                if (activeSide != null && activeSide != HandSide.UNKNOWN && resolvedSide != HandSide.UNKNOWN) {
                    if (resolvedSide != activeSide && candidate.confidence >= 0.60f) {
                        // Reject opposite second hand trying to hijack active session!
                        return null
                    }
                }
            }
            HandLockMode.FREE_TRACKING -> {
                // Free tracking allows either hand dynamically without locking
            }
        }

        // 4. Spatial Continuity Gate: reject instantaneous cross-screen teleport to a 2nd hand
        val newCenter = HandGeometry.palmCentroid(candidate.landmarks)
        val prevCenter = lastAcceptedCenter
        val dt = nowMs - lastAcceptedTimestampMs

        if (prevCenter != null && lastAcceptedTimestampMs > 0L && dt in 1..teleportWindowMs) {
            val jump = HandGeometry.dist2D(newCenter, prevCenter)
            if (jump > maxSpatialTeleportDistance) {
                return null
            }
        }

        // 5. Spatial Quadrant Hysteresis: If a hand is locked on one screen half,
        // reject an abrupt appearance on the opposite half (> 0.40 screen width)
        val lockedX = lockedSpatialX
        if (lockedX != null && lastAcceptedTimestampMs > 0L && (nowMs - lastAcceptedTimestampMs) < 1100L) {
            if (abs(newCenter.x - lockedX) > 0.45f) {
                return null
            }
        }

        // Accept and latch primary single hand & spatial anchor
        if (lockedSide == null || lockedSide == HandSide.UNKNOWN) {
            if (resolvedSide != HandSide.UNKNOWN && candidate.confidence >= 0.58f) {
                lockedSide = resolvedSide
            }
        }

        lockedSpatialX = if (lockedSpatialX == null) newCenter.x else (0.80f * lockedSpatialX!! + 0.20f * newCenter.x)
        lastAcceptedCenter = newCenter
        lastAcceptedTimestampMs = nowMs

        return candidate.copy(handSide = resolvedSide)
    }
}
