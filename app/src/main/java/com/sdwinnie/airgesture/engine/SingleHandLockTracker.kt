package com.sdwinnie.airgesture.engine

import com.sdwinnie.airgesture.model.HandFrame
import com.sdwinnie.airgesture.model.HandLockMode
import com.sdwinnie.airgesture.model.HandSide
import com.sdwinnie.airgesture.model.Landmark3D

/**
 * Strict Single-Hand Identity & Spatial Continuity Gate.
 * Prevents the tracker from ever jumping to a second hand, resting hand, or background person.
 */
class SingleHandLockTracker(
    private val lockReleaseTimeoutMs: Long = 1100L,
    private val maxSpatialTeleportDistance: Float = 0.38f,
    private val teleportWindowMs: Long = 210L
) {
    var lockedSide: HandSide? = null
        private set

    private var lastAcceptedCenter: Landmark3D? = null
    private var lastAcceptedTimestampMs: Long = -1L

    fun reset() {
        lockedSide = null
        lastAcceptedCenter = null
        lastAcceptedTimestampMs = -1L
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

        // 2. Explicit Left/Right Hand Preference Lock
        when (lockMode) {
            HandLockMode.RIGHT_ONLY -> {
                if (candidate.handSide == HandSide.LEFT && candidate.confidence >= 0.60f) {
                    return null
                }
            }
            HandLockMode.LEFT_ONLY -> {
                if (candidate.handSide == HandSide.RIGHT && candidate.confidence >= 0.60f) {
                    return null
                }
            }
            HandLockMode.AUTO_SINGLE -> {
                val activeSide = lockedSide
                if (activeSide != null &&
                    activeSide != HandSide.UNKNOWN &&
                    candidate.handSide != HandSide.UNKNOWN &&
                    candidate.handSide != activeSide &&
                    candidate.confidence >= 0.66f
                ) {
                    // Reject opposite second hand trying to hijack active session!
                    return null
                }
            }
            HandLockMode.FREE_TRACKING -> {
                // Free tracking allows either hand dynamically without locking
            }
        }

        // 3. Spatial Continuity Gate: reject instantaneous cross-screen teleport to a 2nd hand
        val newCenter = HandGeometry.palmCentroid(candidate.landmarks)
        val prevCenter = lastAcceptedCenter
        val dt = nowMs - lastAcceptedTimestampMs
        if (prevCenter != null && lastAcceptedTimestampMs > 0L && dt in 1..teleportWindowMs) {
            val jump = HandGeometry.dist2D(newCenter, prevCenter)
            if (jump > maxSpatialTeleportDistance) {
                return null
            }
        }

        // Accept and latch primary single hand
        if (lockedSide == null || lockedSide == HandSide.UNKNOWN) {
            if (candidate.handSide != HandSide.UNKNOWN && candidate.confidence >= 0.62f) {
                lockedSide = candidate.handSide
            }
        }
        lastAcceptedCenter = newCenter
        lastAcceptedTimestampMs = nowMs
        return candidate
    }
}
