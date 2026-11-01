package com.sdwinnie.airgesture.vision

import com.sdwinnie.airgesture.model.Landmark3D
import kotlin.math.max
import kotlin.math.min

/**
 * Tracks a normalized upright Region-of-Interest [left, top, right, bottom] in [0..1]
 * around the user's hand so high-resolution (1080p/2K/4K) camera streams can be cropped
 * in volatile RAM down to 192×192 or 256×256 while preserving crisp finger detail at distance.
 */
class RoiTracker {

    data class NormalizedRect(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float
    ) {
        val width: Float get() = (right - left).coerceAtLeast(0.1f)
        val height: Float get() = (bottom - top).coerceAtLeast(0.1f)
    }

    var currentRoi: NormalizedRect = FULL_FRAME
        private set

    private var consecutiveMisses = 0

    fun reset() {
        currentRoi = FULL_FRAME
        consecutiveMisses = 0
    }

    fun mapRoiToFullFrame(u: Float, v: Float, roiSnapshot: NormalizedRect): Pair<Float, Float> {
        val fx = (roiSnapshot.left + u * roiSnapshot.width).coerceIn(0f, 1f)
        val fy = (roiSnapshot.top + v * roiSnapshot.height).coerceIn(0f, 1f)
        return fx to fy
    }

    fun onLandmarksDetected(fullFrameLandmarks: List<Landmark3D>) {
        consecutiveMisses = 0
        var minX = 1f
        var minY = 1f
        var maxX = 0f
        var maxY = 0f

        for (pt in fullFrameLandmarks) {
            minX = min(minX, pt.x)
            minY = min(minY, pt.y)
            maxX = max(maxX, pt.x)
            maxY = max(maxY, pt.y)
        }

        val cx = (minX + maxX) * 0.5f
        val cy = (minY + maxY) * 0.5f
        val span = (max(maxX - minX, maxY - minY) * 2.25f).coerceIn(0.45f, 1.0f)
        val half = span * 0.5f

        var left = cx - half
        var right = cx + half
        if (left < 0f) {
            right = (right - left).coerceAtMost(1f)
            left = 0f
        } else if (right > 1f) {
            left = (left - (right - 1f)).coerceAtLeast(0f)
            right = 1f
        }

        var top = cy - half
        var bottom = cy + half
        if (top < 0f) {
            bottom = (bottom - top).coerceAtMost(1f)
            top = 0f
        } else if (bottom > 1f) {
            top = (top - (bottom - 1f)).coerceAtLeast(0f)
            bottom = 1f
        }

        val prev = currentRoi
        currentRoi = NormalizedRect(
            left = 0.65f * left + 0.35f * prev.left,
            top = 0.65f * top + 0.35f * prev.top,
            right = 0.65f * right + 0.35f * prev.right,
            bottom = 0.65f * bottom + 0.35f * prev.bottom
        )
    }

    fun onHandMissed() {
        consecutiveMisses++
        if (consecutiveMisses >= 2) {
            currentRoi = FULL_FRAME
        }
    }

    companion object {
        val FULL_FRAME = NormalizedRect(0f, 0f, 1f, 1f)
    }
}
