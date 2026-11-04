package com.sdwinnie.airgesture.vision

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Range
import android.util.Size
import android.view.Display
import android.view.Surface
import com.sdwinnie.airgesture.engine.EyeGazeBlinkAnalyzer
import com.sdwinnie.airgesture.model.AirGestureConfig
import com.sdwinnie.airgesture.model.HandFrame
import com.sdwinnie.airgesture.security.AirLogger
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Crash-Armored Decoupled Dual-Stream Camera2 Pipeline (v2.0).
 * Every background callback and JNI boundary is guarded against Throwable so hardware HAL
 * quirks or camera eviction events can NEVER terminate the application process.
 */
class DualStreamCameraController(
    private val context: Context,
    private val handTracker: MediaPipeHandTracker,
    private val thermalController: ThermalAndDutyController,
    private val onVisionStep: (
        handFrame: HandFrame?,
        eyeFrame: EyeGazeBlinkAnalyzer.EyeFrameData?,
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
    private val displayManager = context.applicationContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

    private val downscaler = VolatileRoiDownscaler(224)
    private val eyeTracker = CameraEyeGazeTracker()

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var visionThread: HandlerThread? = null
    private var visionHandler: Handler? = null

    private var activeConfig: AirGestureConfig = AirGestureConfig()
    private var previewSurface: Surface? = null

    private var activeCameraId: String? = null
    private var sensorOrientationDeg: Int = 270
    private var isFrontFacing: Boolean = true
    private var selectedCaptureSize: Size = Size(1280, 720)
    private var selectedFpsRange: Range<Int> = Range(30, 30)
    private var selectedFaceDetectMode: Int = CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF
    private var sensorActiveArray: Rect? = null

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var visionImageReader: ImageReader? = null

    private val isRunning = AtomicBoolean(false)
    private val isProcessingFrame = AtomicBoolean(false)
    private val trackerReady = AtomicBoolean(false)

    @Volatile
    private var cameraYieldedToOtherApp = false

    @Volatile
    private var lastInferenceStartMs = 0L

    @Volatile
    private var lastActivityDetectedMs = 0L

    private var smoothedFps = 0f

    private val threadCrashGuard = Thread.UncaughtExceptionHandler { thread, throwable ->
        AirLogger.e("CameraGuard", "Caught background thread exception on ${thread.name}", throwable)
    }

    private val availabilityCallback = object : CameraManager.AvailabilityCallback() {
        override fun onCameraAvailable(cameraId: String) {
            if (isRunning.get() && cameraYieldedToOtherApp && cameraId == activeCameraId) {
                cameraHandler?.postDelayed({
                    if (isRunning.get() && cameraDevice == null) {
                        safeOpenResolvedCamera()
                    }
                }, 400L)
            }
        }
    }

    @Synchronized
    fun start(config: AirGestureConfig, optionalPreviewSurface: Surface? = null) {
        stop()
        activeConfig = config
        previewSurface = optionalPreviewSurface?.takeIf { it.isValid }
        downscaler.ensureDimension(config.visionBufferSize.dimension)
        downscaler.virtualZoomFactor = if (config.enableLongDistanceBooster) 1.45f else 1.0f
        downscaler.enableContrastBoost = config.enableLongDistanceBooster
        cameraYieldedToOtherApp = false
        trackerReady.set(false)
        isRunning.set(true)

        cameraThread = HandlerThread("AirGesture-Camera2").apply {
            uncaughtExceptionHandler = threadCrashGuard
            start()
        }
        cameraHandler = Handler(cameraThread!!.looper)

        visionThread = HandlerThread("AirGesture-Vision").apply {
            uncaughtExceptionHandler = threadCrashGuard
            start()
        }
        visionHandler = Handler(visionThread!!.looper)

        visionHandler?.post {
            val ok = handTracker.initialize(config.hardwareDelegate)
            trackerReady.set(ok)
            if (!ok) {
                onStreamStateChanged("AI Engine Fallback", false)
            }
        }

        runCatching {
            cameraManager.registerAvailabilityCallback(availabilityCallback, cameraHandler)
        }
        cameraHandler?.post { safeOpenResolvedCamera() }
    }

    @Synchronized
    fun updatePreviewSurface(surface: Surface?) {
        val validSurface = surface?.takeIf { it.isValid }
        if (previewSurface === validSurface) return
        previewSurface = validSurface
        if (isRunning.get() && cameraDevice != null) {
            cameraHandler?.post { safeCreateDualStreamSession() }
        }
    }

    @Synchronized
    fun applyUpdatedConfig(newConfig: AirGestureConfig) {
        val requiresReopen = newConfig.useFrontCamera != activeConfig.useFrontCamera ||
            newConfig.resolution != activeConfig.resolution ||
            newConfig.targetFps != activeConfig.targetFps ||
            newConfig.hardwareDelegate != activeConfig.hardwareDelegate
        activeConfig = newConfig
        downscaler.ensureDimension(newConfig.visionBufferSize.dimension)
        downscaler.virtualZoomFactor = if (newConfig.enableLongDistanceBooster) 1.45f else 1.0f
        downscaler.enableContrastBoost = newConfig.enableLongDistanceBooster
        if (!isRunning.get()) return
        if (requiresReopen) {
            val savedPreview = previewSurface
            start(newConfig, savedPreview)
        }
    }

    @Synchronized
    fun stop() {
        isRunning.set(false)
        trackerReady.set(false)
        cameraYieldedToOtherApp = false
        runCatching { cameraManager.unregisterAvailabilityCallback(availabilityCallback) }

        closeCameraResources()
        downscaler.wipeVolatileMemory()
        eyeTracker.reset()

        visionThread?.quitSafely()
        runCatching { visionThread?.join(350L) }
        visionThread = null
        visionHandler = null

        cameraThread?.quitSafely()
        runCatching { cameraThread?.join(350L) }
        cameraThread = null
        cameraHandler = null

        onStreamStateChanged("Stopped", false)
    }

    private fun closeCameraResources() {
        runCatching { captureSession?.stopRepeating() }
        runCatching { captureSession?.close() }
        captureSession = null
        runCatching { cameraDevice?.close() }
        cameraDevice = null
        runCatching { visionImageReader?.setOnImageAvailableListener(null, null) }
        runCatching { visionImageReader?.close() }
        visionImageReader = null
    }

    @SuppressLint("MissingPermission")
    private fun safeOpenResolvedCamera() {
        try {
            if (!isRunning.get()) return
            closeCameraResources()

            val resolvedId = resolveCameraId(activeConfig.useFrontCamera) ?: run {
                onStreamStateChanged("No Camera Found", false)
                return
            }
            activeCameraId = resolvedId

            val chars = cameraManager.getCameraCharacteristics(resolvedId)
            val facing = chars.get(CameraCharacteristics.LENS_FACING)
            isFrontFacing = (facing == CameraCharacteristics.LENS_FACING_FRONT)
            sensorOrientationDeg = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: if (isFrontFacing) 270 else 90

            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val availableSizes = map?.getOutputSizes(ImageFormat.YUV_420_888)?.toList().orEmpty()
            selectedCaptureSize = chooseClosestResolution(
                availableSizes,
                activeConfig.resolution.targetWidth,
                activeConfig.resolution.targetHeight
            )

            val fpsRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList().orEmpty()
            selectedFpsRange = chooseBestFpsRange(fpsRanges, activeConfig.targetFps.fps)

            val faceModes = chars.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES) ?: intArrayOf()
            selectedFaceDetectMode = when {
                faceModes.contains(CameraCharacteristics.STATISTICS_FACE_DETECT_MODE_FULL) ->
                    CameraCharacteristics.STATISTICS_FACE_DETECT_MODE_FULL
                faceModes.contains(CameraCharacteristics.STATISTICS_FACE_DETECT_MODE_SIMPLE) ->
                    CameraCharacteristics.STATISTICS_FACE_DETECT_MODE_SIMPLE
                else -> CameraCharacteristics.STATISTICS_FACE_DETECT_MODE_OFF
            }
            sensorActiveArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)

            val reader = ImageReader.newInstance(
                selectedCaptureSize.width,
                selectedCaptureSize.height,
                ImageFormat.YUV_420_888,
                2
            )
            reader.setOnImageAvailableListener({ ir -> onVolatileFrameAvailable(ir) }, visionHandler)
            visionImageReader = reader

            cameraManager.openCamera(resolvedId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (!isRunning.get()) {
                        runCatching { camera.close() }
                        return
                    }
                    cameraDevice = camera
                    cameraYieldedToOtherApp = false
                    safeCreateDualStreamSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    runCatching { camera.close() }
                    cameraDevice = null
                    captureSession = null
                    cameraYieldedToOtherApp = true
                    downscaler.wipeVolatileMemory()
                    onStreamStateChanged("Yielded (Camera in use)", true)
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    runCatching { camera.close() }
                    cameraDevice = null
                    captureSession = null
                    cameraYieldedToOtherApp = true
                    downscaler.wipeVolatileMemory()
                    onStreamStateChanged("Camera Paused ($error)", true)
                }
            }, cameraHandler)
        } catch (t: Throwable) {
            AirLogger.w("Camera", "safeOpenResolvedCamera deferred: ${t.message}")
            cameraYieldedToOtherApp = true
            onStreamStateChanged("Waiting for Camera...", true)
        }
    }

    private fun safeCreateDualStreamSession() {
        try {
            val device = cameraDevice ?: return
            val readerSurface = visionImageReader?.surface?.takeIf { it.isValid } ?: return
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
                        runCatching { session.close() }
                        return
                    }
                    captureSession = session
                    try {
                        val reqBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            for (s in surfaces) addTarget(s)
                            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, selectedFpsRange)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                            if (selectedFaceDetectMode != CameraCharacteristics.STATISTICS_FACE_DETECT_MODE_OFF) {
                                set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, selectedFaceDetectMode)
                            }
                        }
                        val captureCallback = object : CameraCaptureSession.CaptureCallback() {
                            override fun onCaptureCompleted(
                                session: CameraCaptureSession,
                                request: CaptureRequest,
                                result: TotalCaptureResult
                            ) {
                                val faces = result.get(CaptureResult.STATISTICS_FACES)
                                eyeTracker.onHardwareCaptureResult(
                                    faces = faces,
                                    activeArray = sensorActiveArray,
                                    uprightRotDeg = computeUprightRotationDegrees(),
                                    isFront = isFrontFacing
                                )
                            }
                        }
                        session.setRepeatingRequest(reqBuilder.build(), captureCallback, handler)
                        val summary = "${selectedCaptureSize.width}×${selectedCaptureSize.height} @ ${selectedFpsRange.upper}fps"
                        onStreamStateChanged(summary, false)
                    } catch (t: Throwable) {
                        AirLogger.e("Camera", "Repeating request fallback", t)
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    onStreamStateChanged("Stream Config Fallback", false)
                }
            }

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
        } catch (t: Throwable) {
            AirLogger.e("Camera", "safeCreateDualStreamSession failed safely", t)
        }
    }

    private fun onVolatileFrameAvailable(reader: ImageReader) {
        val image = try {
            reader.acquireLatestImage()
        } catch (_: Throwable) {
            null
        } ?: return

        try {
            if (!isRunning.get() || !trackerReady.get()) return
            if (!isProcessingFrame.compareAndSet(false, true)) return

            try {
                val nowMs = SystemClock.elapsedRealtime()
                val minIntervalMs = thermalController.computeMinInferenceIntervalMs(
                    configuredTargetFps = activeConfig.targetFps.fps,
                    lastActivityDetectedMs = lastActivityDetectedMs,
                    nowMs = nowMs,
                    isEyeTrackingEnabled = activeConfig.enableEyeContactBlink
                )

                val elapsed = nowMs - lastInferenceStartMs
                if (lastInferenceStartMs > 0L && elapsed < minIntervalMs) {
                    return
                }

                if (lastInferenceStartMs > 0L && elapsed in 1..2000) {
                    val instFps = 1000.0f / elapsed.toFloat()
                    smoothedFps = if (smoothedFps <= 0.1f) instFps else (0.25f * instFps + 0.75f * smoothedFps)
                }
                lastInferenceStartMs = nowMs

                val t0Ns = SystemClock.elapsedRealtimeNanos()
                val uprightRot = computeUprightRotationDegrees()

                val volatileBitmap = downscaler.downscaleIsotropicToVolatileBitmap(
                    image = image,
                    uprightRotationDeg = uprightRot,
                    mirrorHorizontally = isFrontFacing
                ) ?: return

                val eyeFrame = if (activeConfig.enableEyeContactBlink) {
                    eyeTracker.analyzeEyeFrame(
                        pixelBuffer = downscaler.getVolatileArgbBuffer(),
                        bufferDim = downscaler.targetDimension,
                        nowMs = nowMs,
                        config = activeConfig
                    )
                } else {
                    null
                }

                val handFrame = if (activeConfig.enableHandGestures) {
                    handTracker.detectSingleHand(
                        isotropicBitmap = volatileBitmap,
                        isMirroredSelfie = isFrontFacing,
                        frameTimestampMs = nowMs
                    )
                } else {
                    null
                }

                if (handFrame != null || eyeFrame?.isFaceDetected == true) {
                    lastActivityDetectedMs = nowMs
                }

                val latencyMs = (SystemClock.elapsedRealtimeNanos() - t0Ns) / 1_000_000.0f
                onVisionStep(handFrame, eyeFrame, nowMs, latencyMs, smoothedFps)
            } catch (t: Throwable) {
                AirLogger.e("VisionLoop", "Frame processing recovered safely", t)
            } finally {
                isProcessingFrame.set(false)
            }
        } finally {
            runCatching { image.close() }
        }
    }

    private fun computeUprightRotationDegrees(): Int {
        val rotationEnum = runCatching {
            displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.rotation
        }.getOrNull() ?: Surface.ROTATION_0

        val deviceDegrees = when (rotationEnum) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }

        return if (isFrontFacing) {
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
            val facing = runCatching {
                cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
            }.getOrNull()
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
        return ranges.sortedWith(
            compareBy<Range<Int>> { abs(it.upper - desiredFps) }
                .thenByDescending { it.upper }
                .thenByDescending { it.lower }
        ).first()
    }
}
