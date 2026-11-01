package com.sdwinnie.airgesture.vision

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.sdwinnie.airgesture.model.HandFrame
import com.sdwinnie.airgesture.model.HandSide
import com.sdwinnie.airgesture.model.Landmark3D
import com.sdwinnie.airgesture.security.AirLogger
import com.sdwinnie.airgesture.security.ModelIntegrityVerifier
import kotlin.math.max

/**
 * High-Precision Single-Hand MediaPipe HandLandmarker Wrapper (v3.0).
 * - Strictly configured for 1 Hand (`setNumHands(1)`) with hardened confidence thresholds
 *   (0.64 detection / 0.64 presence / 0.60 tracking) to eliminate ghost detections on faces or objects.
 * - Normalizes physical Left/Right handedness across mirrored selfie and rear cameras.
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
                .setMinHandDetectionConfidence(0.64f)
                .setMinHandPresenceConfidence(0.64f)
                .setMinTrackingConfidence(0.60f)
                .build()

            handLandmarker = HandLandmarker.createFromOptions(context, options)
            activeDelegateLabel = "XNNPACK 1-Hand"
            AirLogger.d("Vision", "MediaPipe HandLandmarker v3.0 ready (Strict 1-Hand Lock)")
            true
        } catch (t: Throwable) {
            activeDelegateLabel = "Init Error"
            AirLogger.e("Vision", "MediaPipe initialization failed safely", t)
            false
        }
    }

    @Synchronized
    fun detectSingleHand(
        isotropicBitmap: Bitmap,
        isMirroredSelfie: Boolean,
        frameTimestampMs: Long
    ): HandFrame? {
        val landmarker = handLandmarker
        if (landmarker == null || isotropicBitmap.isRecycled) {
            if (!isotropicBitmap.isRecycled) isotropicBitmap.recycle()
            return null
        }

        val safeTimestampMs = max(lastMonotonicTimestampMs + 1L, frameTimestampMs)
        lastMonotonicTimestampMs = safeTimestampMs

        var mpImageClosed = false
        val mpImage = BitmapImageBuilder(isotropicBitmap).build()
        return try {
            val result = landmarker.detectForVideo(mpImage, safeTimestampMs)
            val allHands = result?.landmarks()
            if (allHands.isNullOrEmpty() || allHands[0].size < 21) {
                null
            } else {
                val rawHand = allHands[0]
                val isotropicPoints = ArrayList<Landmark3D>(21)
                for (i in 0 until 21) {
                    val lm = rawHand[i]
                    isotropicPoints.add(
                        Landmark3D(
                            x = lm.x().coerceIn(0f, 1f),
                            y = lm.y().coerceIn(0f, 1f),
                            z = lm.z()
                        )
                    )
                }

                val handednessCategory = result.handedness()?.firstOrNull()?.firstOrNull()
                val score = handednessCategory?.score() ?: 0.85f
                val rawName = handednessCategory?.categoryName()?.trim().orEmpty()

                // MediaPipe classifies un-mirrored camera perspective; selfie-mirrored image flips Left<->Right
                val physicalSide = when {
                    rawName.equals("Left", ignoreCase = true) ->
                        if (isMirroredSelfie) HandSide.RIGHT else HandSide.LEFT
                    rawName.equals("Right", ignoreCase = true) ->
                        if (isMirroredSelfie) HandSide.LEFT else HandSide.RIGHT
                    else -> HandSide.UNKNOWN
                }

                HandFrame(
                    landmarks = isotropicPoints,
                    timestampMs = safeTimestampMs,
                    confidence = score,
                    handSide = physicalSide
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
            if (!mpImageClosed && !isotropicBitmap.isRecycled) {
                runCatching { isotropicBitmap.recycle() }
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
