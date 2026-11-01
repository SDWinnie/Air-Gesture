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
import com.sdwinnie.airgesture.model.HardwareDelegateMode
import com.sdwinnie.airgesture.model.Landmark3D
import com.sdwinnie.airgesture.security.AirLogger
import com.sdwinnie.airgesture.security.ModelIntegrityVerifier
import kotlin.math.max

/**
 * Ultra-Responsive MediaPipe HandLandmarker Wrapper with GPU Acceleration & CPU Fallback (v4.0).
 * - Tries GPU GLES delegate first for sub-8ms neural inference, enabling solid 30-120 FPS.
 * - Automatically falls back to XNNPACK CPU if GPU is not available on legacy/emulator targets.
 * - Strict 1-Hand configuration with hardened confidence thresholds (0.64 / 0.64 / 0.60).
 */
class MediaPipeHandTracker(private val context: Context) {

    private var handLandmarker: HandLandmarker? = null
    private var lastMonotonicTimestampMs: Long = 0L

    @Volatile
    var activeDelegateLabel: String = "Idle"
        private set

    @Synchronized
    fun initialize(delegateMode: HardwareDelegateMode = HardwareDelegateMode.AUTO_GPU_CPU): Boolean {
        if (handLandmarker != null) return true

        return try {
            val verifiedBuffer = ModelIntegrityVerifier.loadVerifiedDirectBuffer(context)

            if (delegateMode == HardwareDelegateMode.AUTO_GPU_CPU) {
                try {
                    val gpuBase = BaseOptions.builder()
                        .setModelAssetBuffer(verifiedBuffer)
                        .setDelegate(Delegate.GPU)
                        .build()

                    val gpuOptions = HandLandmarker.HandLandmarkerOptions.builder()
                        .setBaseOptions(gpuBase)
                        .setRunningMode(RunningMode.VIDEO)
                        .setNumHands(1)
                        .setMinHandDetectionConfidence(0.64f)
                        .setMinHandPresenceConfidence(0.64f)
                        .setMinTrackingConfidence(0.60f)
                        .build()

                    handLandmarker = HandLandmarker.createFromOptions(context, gpuOptions)
                    activeDelegateLabel = "GPU GLES (ความเร็วสูง)"
                    AirLogger.d("Vision", "MediaPipe GPU Delegate ready (30-120 FPS capable)")
                    return true
                } catch (t: Throwable) {
                    AirLogger.w("Vision", "GPU delegate unavail (${t.message}), falling back to CPU XNNPACK")
                    verifiedBuffer.rewind()
                }
            }

            // Fallback or explicit CPU mode
            val cpuBase = BaseOptions.builder()
                .setModelAssetBuffer(verifiedBuffer)
                .setDelegate(Delegate.CPU)
                .build()

            val cpuOptions = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(cpuBase)
                .setRunningMode(RunningMode.VIDEO)
                .setNumHands(1)
                .setMinHandDetectionConfidence(0.64f)
                .setMinHandPresenceConfidence(0.64f)
                .setMinTrackingConfidence(0.60f)
                .build()

            handLandmarker = HandLandmarker.createFromOptions(context, cpuOptions)
            activeDelegateLabel = "XNNPACK CPU"
            AirLogger.d("Vision", "MediaPipe CPU Delegate ready")
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
            return null
        }

        val safeTimestampMs = max(lastMonotonicTimestampMs + 1L, frameTimestampMs)
        lastMonotonicTimestampMs = safeTimestampMs

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
            runCatching { mpImage.close() }
            // Do NOT recycle isotropicBitmap: reused in VolatileRoiDownscaler!
        }
    }

    @Synchronized
    fun close() {
        runCatching { handLandmarker?.close() }
        handLandmarker = null
        activeDelegateLabel = "Idle"
    }
}
