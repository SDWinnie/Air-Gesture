package com.sdwinnie.airgesture.vision

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.sdwinnie.airgesture.model.HandFrame
import com.sdwinnie.airgesture.model.Landmark3D
import com.sdwinnie.airgesture.security.AirLogger
import com.sdwinnie.airgesture.security.ModelIntegrityVerifier
import kotlin.math.max

/**
 * 100% On-Device MediaPipe HandLandmarker Wrapper.
 * - Verifies SHA-256 of bundled model in volatile DirectByteBuffer RAM before initialization.
 * - Automatically attempts GPU delegate first and gracefully falls back to CPU XNNPACK SIMD
 *   if background service GL context is restricted by OEM firmware.
 */
class MediaPipeHandTracker(private val context: Context) {

    private var handLandmarker: HandLandmarker? = null
    private var lastMonotonicTimestampMs: Long = 0L

    var activeDelegateLabel: String = "Uninitialized"
        private set

    @Synchronized
    fun initialize() {
        if (handLandmarker != null) return

        val verifiedModelBuffer = ModelIntegrityVerifier.loadVerifiedDirectBuffer(context)

        // Attempt GPU delegate first for ultra-low latency (< 5 ms), fallback to CPU SIMD cleanly
        val gpuAttempt = runCatching {
            verifiedModelBuffer.rewind()
            buildLandmarker(verifiedModelBuffer, Delegate.GPU)
        }

        if (gpuAttempt.isSuccess) {
            handLandmarker = gpuAttempt.getOrThrow()
            activeDelegateLabel = "GPU Edge"
            AirLogger.d("Vision", "MediaPipe HandLandmarker initialized on GPU")
            return
        }

        AirLogger.w("Vision", "GPU delegate unavailable in background context; falling back to CPU XNNPACK")
        val freshBuffer = ModelIntegrityVerifier.loadVerifiedDirectBuffer(context)
        handLandmarker = buildLandmarker(freshBuffer, Delegate.CPU)
        activeDelegateLabel = "CPU SIMD"
    }

    private fun buildLandmarker(modelBuffer: java.nio.ByteBuffer, delegate: Delegate): HandLandmarker {
        val baseOptions = BaseOptions.builder()
            .setModelAssetBuffer(modelBuffer)
            .setDelegate(delegate)
            .build()

        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.VIDEO)
            .setNumHands(1)
            .setMinHandDetectionConfidence(0.50f)
            .setMinHandPresenceConfidence(0.50f)
            .setMinTrackingConfidence(0.48f)
            .build()

        return HandLandmarker.createFromOptions(context, options)
    }

    /**
     * Runs hand landmark inference on the small (192×192 or 256×256) volatile ROI Bitmap
     * and maps landmarks back into full-frame upright screen coordinates [0..1].
     */
    @Synchronized
    fun detectInVolatileRoi(
        roiBitmap: Bitmap,
        roiSnapshot: RoiTracker.NormalizedRect,
        roiTracker: RoiTracker,
        frameTimestampMs: Long
    ): HandFrame? {
        val landmarker = handLandmarker ?: return null

        val safeTimestampMs = max(lastMonotonicTimestampMs + 1L, frameTimestampMs)
        lastMonotonicTimestampMs = safeTimestampMs

        val mpImage = BitmapImageBuilder(roiBitmap).build()
        val result = try {
            landmarker.detectForVideo(mpImage, safeTimestampMs)
        } finally {
            mpImage.close()
        }

        val allHands = result.landmarks()
        if (allHands.isNullOrEmpty() || allHands[0].size < 21) {
            roiTracker.onHandMissed()
            return null
        }

        val rawHand = allHands[0]
        val fullFramePoints = ArrayList<Landmark3D>(21)
        for (i in 0 until 21) {
            val lm = rawHand[i]
            val (fx, fy) = roiTracker.mapRoiToFullFrame(lm.x(), lm.y(), roiSnapshot)
            fullFramePoints.add(Landmark3D(x = fx, y = fy, z = lm.z()))
        }

        roiTracker.onLandmarksDetected(fullFramePoints)
        val handednessScore = result.handedness()
            ?.firstOrNull()
            ?.firstOrNull()
            ?.score() ?: 0.9f

        return HandFrame(
            landmarks = fullFramePoints,
            timestampMs = safeTimestampMs,
            confidence = handednessScore
        )
    }

    @Synchronized
    fun close() {
        runCatching { handLandmarker?.close() }
        handLandmarker = null
        activeDelegateLabel = "Stopped"
    }
}
