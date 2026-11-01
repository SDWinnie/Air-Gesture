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
 * Crash-Hardened 100% On-Device MediaPipe HandLandmarker Wrapper (v2.0).
 * - Uses CPU XNNPACK ARM NEON SIMD delegate exclusively in background service threads to eliminate
 *   native C++ EGL/OpenGL SIGABRT driver crashes across Xiaomi HyperOS, Samsung, and Pixel devices.
 * - Deterministically disposes per-frame MPImage & Bitmap wrappers without recycling shared state.
 */
class MediaPipeHandTracker(private val context: Context) {

    private var handLandmarker: HandLandmarker? = null
    private var lastMonotonicTimestampMs: Long = 0L

    @Volatile
    var activeDelegateLabel: String = "Idle"
        private set

    @Synchronized
    fun initialize(): Boolean {
        if (handLandmarker != null) return true

        return try {
            val verifiedBuffer = ModelIntegrityVerifier.loadVerifiedDirectBuffer(context)
            val baseOptions = BaseOptions.builder()
                .setModelAssetBuffer(verifiedBuffer)
                .setDelegate(Delegate.CPU)
                .build()

            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.VIDEO)
                .setNumHands(1)
                .setMinHandDetectionConfidence(0.48f)
                .setMinHandPresenceConfidence(0.48f)
                .setMinTrackingConfidence(0.46f)
                .build()

            handLandmarker = HandLandmarker.createFromOptions(context, options)
            activeDelegateLabel = "XNNPACK SIMD"
            AirLogger.d("Vision", "MediaPipe HandLandmarker ready on XNNPACK SIMD")
            true
        } catch (t: Throwable) {
            activeDelegateLabel = "Init Error"
            AirLogger.e("Vision", "MediaPipe initialization failed safely", t)
            false
        }
    }

    @Synchronized
    fun detectInVolatileRoi(
        roiBitmap: Bitmap,
        roiSnapshot: RoiTracker.NormalizedRect,
        roiTracker: RoiTracker,
        frameTimestampMs: Long
    ): HandFrame? {
        val landmarker = handLandmarker
        if (landmarker == null || roiBitmap.isRecycled) {
            if (!roiBitmap.isRecycled) roiBitmap.recycle()
            return null
        }

        val safeTimestampMs = max(lastMonotonicTimestampMs + 1L, frameTimestampMs)
        lastMonotonicTimestampMs = safeTimestampMs

        var mpImageClosed = false
        val mpImage = BitmapImageBuilder(roiBitmap).build()
        return try {
            val result = landmarker.detectForVideo(mpImage, safeTimestampMs)
            val allHands = result?.landmarks()
            if (allHands.isNullOrEmpty() || allHands[0].size < 21) {
                roiTracker.onHandMissed()
                null
            } else {
                val rawHand = allHands[0]
                val fullFramePoints = ArrayList<Landmark3D>(21)
                for (i in 0 until 21) {
                    val lm = rawHand[i]
                    val (fx, fy) = roiTracker.mapRoiToFullFrame(lm.x(), lm.y(), roiSnapshot)
                    fullFramePoints.add(Landmark3D(x = fx, y = fy, z = lm.z()))
                }
                roiTracker.onLandmarksDetected(fullFramePoints)
                val score = result.handedness()?.firstOrNull()?.firstOrNull()?.score() ?: 0.9f
                HandFrame(
                    landmarks = fullFramePoints,
                    timestampMs = safeTimestampMs,
                    confidence = score
                )
            }
        } catch (t: Throwable) {
            AirLogger.e("Vision", "Inference frame skipped safely", t)
            null
        } finally {
            runCatching {
                mpImage.close()
                mpImageClosed = true
            }
            if (!mpImageClosed && !roiBitmap.isRecycled) {
                runCatching { roiBitmap.recycle() }
            }
        }
    }

    @Synchronized
    fun close() {
        runCatching { handLandmarker?.close() }
        handLandmarker = null
        activeDelegateLabel = "Idle"
    }
}
