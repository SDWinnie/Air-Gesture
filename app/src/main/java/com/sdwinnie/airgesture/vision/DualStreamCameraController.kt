package com.sdwinnie.airgesture.vision

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.WindowManager
import com.sdwinnie.airgesture.model.AirGestureConfig
import com.sdwinnie.airgesture.model.HandFrame
import com.sdwinnie.airgesture.security.AirLogger
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Decoupled Dual-Stream Camera2 Pipeline for Android 14, 15, and 16+.
 *
 * - Stream A (Optional GPU Surface): High-res 480p..4K live calibration preview (zero CPU readback).
 * - Stream B (Volatile Vision Stream): Lock-free maxImages=2 YUV_420_888 ImageReader whose frames
 *   are immediately ROI-cropped & downscaled in volatile RAM to 192×192 or 256×256 RGB before AI.
 * - Graceful Camera Eviction Yield & Auto-Resume when user opens another camera app.
 */
class DualStreamCameraController(
    private val context: Context,
    private val handTracker: MediaPipeHandTracker,
    private val thermalController: ThermalAndDutyController,
    private val onVisionStep: (
        handFrame: HandFrame?,
        nowMs: Long,
        inferenceLatencyMs: Float,
        instantFps: Float
    ) -> Unit,
    private val onStreamStateChanged: (
        actualStreamSummary: String,
        cameraYielded: Boolean
    ) -> Unit
) {
    private val cameraManager = context.applicationContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val windowManager = context.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val roiTracker = RoiTracker()
    private val downscaler = VolatileRoiDownscaler(192)

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var visionThread: HandlerThread? = null
    private var visionHandler: Handler? = null

    private var activeConfig: AirGestureConfig = AirGestureConfig()
    private var previewSurface: Surface? = null

    private var activeCameraId: String? = null
    private var sensorOrientationDeg: Int = 270
    private var isFrontFacing: Boolean = true
    private var selectedCaptureSize: Size = Size(1920, 1080)
    private var selectedFpsRange: Range<Int> = Range(30, 60)

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var visionImageReader: ImageReader? = null

    private val isRunning = AtomicBoolean(false)
    private val isProcessingFrame = AtomicBoolean(false)

    @Volatile
    private var cameraYieldedToOtherApp = false

    @Volatile
    private var lastInferenceStartMs = 0L

    @Volatile
    private var lastHandDetectedMs = 0L

    private var smoothedFps = 0f

    private val availabilityCallback = object : CameraManager.AvailabilityCallback() {
        override fun onCameraAvailable(cameraId: String) {
            if (isRunning.get() && cameraYieldedToOtherApp && cameraId == activeCameraId) {
                AirLogger.d("Camera", "Camera $cameraId available again; auto-resuming Air Gesture")
                cameraHandler?.postDelayed({
                    if (isRunning.get() && cameraDevice == null) {
                        openResolvedCamera()
                    }
                }, 350L)
            }
        }
    }

    @Synchronized
    fun start(config: AirGestureConfig, optionalPreviewSurface: Surface? = null) {
        stop()
        activeConfig = config
        previewSurface = optionalPreviewSurface?.takeIf { it.isValid }
        downscaler.ensureDimension(config.visionBufferSize.dimension)
        roiTracker.reset()
        cameraYieldedToOtherApp = false
        isRunning.set(true)

        cameraThread = HandlerThread("AirGesture-Camera2Control").apply { start() }
        cameraHandler = Handler(cameraThread!!.looper)

        visionThread = HandlerThread("AirGesture-VolatileVision").apply {
            start()
            priority = Thread.MAX_PRIORITY - 1
        }
        visionHandler = Handler(visionThread!!.looper)

        visionHandler?.post {
            runCatching { handTracker.initialize() }
                .onFailure { AirLogger.e("Camera", "HandTracker init failed", it) }
        }

        cameraManager.registerAvailabilityCallback(availabilityCallback, cameraHandler)
        cameraHandler?.post { openResolvedCamera() }
    }

    @Synchronized
    fun updatePreviewSurface(surface: Surface?) {
        val validSurface = surface?.takeIf { it.isValid }
        if (previewSurface === validSurface) return
        previewSurface = validSurface
        if (isRunning.get() && cameraDevice != null) {
            cameraHandler?.post { createDualStreamSession() }
        }
    }

    @Synchronized
    fun applyUpdatedConfig(newConfig: AirGestureConfig) {
        val requiresReopen = newConfig.useFrontCamera != activeConfig.useFrontCamera ||
            newConfig.resolution != activeConfig.resolution ||
            newConfig.targetFps != activeConfig.targetFps
        activeConfig = newConfig
        downscaler.ensureDimension(newConfig.visionBufferSize.dimension)
        if (!isRunning.get()) return
        if (requiresReopen) {
            val savedPreview = previewSurface
            start(newConfig, savedPreview)
        }
    }

    @Synchronized
    fun stop() {
        isRunning.set(false)
        cameraYieldedToOtherApp = false
        runCatching { cameraManager.unregisterAvailabilityCallback(availabilityCallback) }

        closeCameraResources()
        downscaler.wipeVolatileMemory()
        roiTracker.reset()

        visionThread?.quitSafely()
        runCatching { visionThread?.join(400L) }
        visionThread = null
        visionHandler = null

        cameraThread?.quitSafely()
        runCatching { cameraThread?.join(400L) }
        cameraThread = null
        cameraHandler = null

        onStreamStateChanged("Stopped", false)
    }

    private fun closeCameraResources() {
        runCatching { captureSession?.close() }
        captureSession = null
        runCatching { cameraDevice?.close() }
        cameraDevice = null
        runCatching { visionImageReader?.close() }
        visionImageReader = null
    }

    @SuppressLint("MissingPermission")
    private fun openResolvedCamera() {
        if (!isRunning.get()) return
        closeCameraResources()

        val resolvedId = resolveCameraId(activeConfig.useFrontCamera) ?: run {
            onStreamStateChanged("No Camera Hardware", false)
            return
        }
        activeCameraId = resolvedId

        val chars = cameraManager.getCameraCharacteristics(resolvedId)
        val facing = chars.get(CameraCharacteristics.LENS_FACING)
        isFrontFacing = (facing == CameraCharacteristics.LENS_FACING_FRONT)
        sensorOrientationDeg = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: if (isFrontFacing) 270 else 90

        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val availableSizes = map?.getOutputSizes(ImageFormat.YUV_420_888)?.toList().orEmpty()
        selectedCaptureSize = chooseClosestResolution(availableSizes, activeConfig.resolution.targetWidth, activeConfig.resolution.targetHeight)

        val fpsRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList().orEmpty()
        selectedFpsRange = chooseBestFpsRange(fpsRanges, activeConfig.targetFps.fps)

        // maxImages = 2 ensures strictly bounded volatile ring buffer and immediate drop of stale frames
        val reader = ImageReader.newInstance(
            selectedCaptureSize.width,
            selectedCaptureSize.height,
            ImageFormat.YUV_420_888,
            2
        )
        reader.setOnImageAvailableListener({ ir -> onVolatileFrameAvailable(ir) }, visionHandler)
        visionImageReader = reader

        runCatching {
            cameraManager.openCamera(resolvedId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (!isRunning.get()) {
                        camera.close()
                        return
                    }
                    cameraDevice = camera
                    cameraYieldedToOtherApp = false
                    createDualStreamSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    // Android 14-16+ foreground camera eviction: yield gracefully and await AvailabilityCallback
                    AirLogger.w("Camera", "Camera evicted by foreground app; yielding cleanly")
                    camera.close()
                    cameraDevice = null
                    captureSession = null
                    cameraYieldedToOtherApp = true
                    downscaler.wipeVolatileMemory()
                    onStreamStateChanged("Paused (Camera in use)", true)
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    AirLogger.w("Camera", "Camera StateCallback onError=$error")
                    camera.close()
                    cameraDevice = null
                    captureSession = null
                    cameraYieldedToOtherApp = true
                    downscaler.wipeVolatileMemory()
                    onStreamStateChanged("Yielded (Code $error)", true)
                }
            }, cameraHandler)
        }.onFailure { err ->
            AirLogger.w("Camera", "openCamera deferred/yielded: ${err.message}")
            cameraYieldedToOtherApp = true
            onStreamStateChanged("Waiting for Camera...", true)
        }
    }

    private fun createDualStreamSession() {
        val device = cameraDevice ?: return
        val readerSurface = visionImageReader?.surface ?: return
        val handler = cameraHandler ?: return

        runCatching { captureSession?.close() }
        captureSession = null

        val surfaces = mutableListOf(readerSurface)
        previewSurface?.takeIf { it.isValid && activeConfig.showLiveCalibrationPreview }?.let {
            surfaces.add(it)
        }

        val stateCallback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                if (!isRunning.get() || cameraDevice == null) {
                    session.close()
                    return
                }
                captureSession = session
                runCatching {
                    val reqBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        for (s in surfaces) addTarget(s)
                        set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, selectedFpsRange)
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                    }
                    session.setRepeatingRequest(reqBuilder.build(), null, handler)
                    val summary = "${selectedCaptureSize.width}×${selectedCaptureSize.height} @ ${selectedFpsRange.upper}fps"
                    onStreamStateChanged(summary, false)
                }.onFailure { e ->
                    AirLogger.e("Camera", "Repeating request failed", e)
                }
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                onStreamStateChanged("Stream Config Fallback", false)
            }
        }

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val outputs = surfaces.map { OutputConfiguration(it) }
                val executor = Executor { cmd -> handler.post(cmd) }
                val sessionConfig = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    outputs,
                    executor,
                    stateCallback
                )
                device.createCaptureSession(sessionConfig)
            } else {
                @Suppress("DEPRECATION")
                device.createCaptureSession(surfaces, stateCallback, handler)
            }
        }.onFailure { e ->
            AirLogger.e("Camera", "createCaptureSession failed", e)
        }
    }

    private fun onVolatileFrameAvailable(reader: ImageReader) {
        val image = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return
        try {
            if (!isRunning.get()) return

            // Lock-free backpressure: never queue frames behind active inference
            if (!isProcessingFrame.compareAndSet(false, true)) {
                return
            }

            try {
                val nowMs = SystemClock.elapsedRealtime()
                val minIntervalMs = thermalController.computeMinInferenceIntervalMs(
                    configuredTargetFps = activeConfig.targetFps.fps,
                    lastHandDetectedMs = lastHandDetectedMs,
                    nowMs = nowMs
                )

                val elapsedSinceLast = nowMs - lastInferenceStartMs
                if (lastInferenceStartMs > 0L && elapsedSinceLast < minIntervalMs) {
                    return
                }

                if (lastInferenceStartMs > 0L && elapsedSinceLast in 1..2000) {
                    val instFps = 1000.0f / elapsedSinceLast.toFloat()
                    smoothedFps = if (smoothedFps <= 0.1f) instFps else (0.25f * instFps + 0.75f * smoothedFps)
                }
                lastInferenceStartMs = nowMs

                val t0Ns = SystemClock.elapsedRealtimeNanos()
                val roiSnapshot = roiTracker.currentRoi
                val uprightRot = computeUprightRotationDegrees()

                // Crop & downscale ROI strictly in volatile RAM (192×192 or 256×256)
                val volatileBitmap = downscaler.cropAndDownscaleToVolatileBitmap(
                    image = image,
                    roi = roiSnapshot,
                    uprightRotationDeg = uprightRot,
                    mirrorHorizontally = isFrontFacing
                )

                val handFrame = handTracker.detectInVolatileRoi(
                    roiBitmap = volatileBitmap,
                    roiSnapshot = roiSnapshot,
                    roiTracker = roiTracker,
                    frameTimestampMs = nowMs
                )

                if (handFrame != null) {
                    lastHandDetectedMs = nowMs
                }

                val latencyMs = (SystemClock.elapsedRealtimeNanos() - t0Ns) / 1_000_000.0f
                onVisionStep(handFrame, nowMs, latencyMs, smoothedFps)
            } finally {
                isProcessingFrame.set(false)
            }
        } finally {
            // Always release hardware Image buffer immediately back to Camera2 HAL
            image.close()
        }
    }

    private fun computeUprightRotationDegrees(): Int {
        @Suppress("DEPRECATION")
        val displayRotationEnum = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { context.display?.rotation }.getOrNull() ?: windowManager.defaultDisplay.rotation
        } else {
            windowManager.defaultDisplay.rotation
        }

        val deviceDegrees = when (displayRotationEnum) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }

        return if (isFrontFacing) {
            // Front camera sensor orientation relative to natural portrait upright
            (sensorOrientationDeg + deviceDegrees) % 360
        } else {
            (sensorOrientationDeg - deviceDegrees + 360) % 360
        }
    }

    private fun resolveCameraId(preferFront: Boolean): String? {
        val targetFacing = if (preferFront) {
            CameraCharacteristics.LENS_FACING_FRONT
        } else {
            CameraCharacteristics.LENS_FACING_BACK
        }
        val ids = runCatching { cameraManager.cameraIdList }.getOrNull() ?: return null
        for (id in ids) {
            val facing = cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
            if (facing == targetFacing) return id
        }
        return ids.firstOrNull()
    }

    private fun chooseClosestResolution(sizes: List<Size>, targetW: Int, targetH: Int): Size {
        if (sizes.isEmpty()) return Size(1280, 720)
        val targetLong = maxOf(targetW, targetH)
        val targetShort = minOf(targetW, targetH)
        return sizes.minByOrNull { s ->
            val sLong = maxOf(s.width, s.height)
            val sShort = minOf(s.width, s.height)
            abs(sLong - targetLong) * 2 + abs(sShort - targetShort)
        } ?: sizes.first()
    }

    private fun chooseBestFpsRange(ranges: List<Range<Int>>, desiredFps: Int): Range<Int> {
        if (ranges.isEmpty()) return Range(30, 30)
        // Prefer range whose upper bound is closest to desiredFps (60 or 120), then highest lower bound
        return ranges.sortedWith(
            compareBy<Range<Int>> { abs(it.upper - desiredFps) }
                .thenByDescending { it.upper }
                .thenByDescending { it.lower }
        ).first()
    }
}
