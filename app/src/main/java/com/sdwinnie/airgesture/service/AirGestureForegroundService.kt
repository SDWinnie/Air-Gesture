package com.sdwinnie.airgesture.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.sdwinnie.airgesture.R
import com.sdwinnie.airgesture.engine.AirGestureStateMachine
import com.sdwinnie.airgesture.engine.EyeGazeBlinkAnalyzer
import com.sdwinnie.airgesture.input.RadialAppSlotManager
import com.sdwinnie.airgesture.input.SystemVolumeController
import com.sdwinnie.airgesture.input.TouchlessActionDispatcher
import com.sdwinnie.airgesture.model.AirGestureConfig
import com.sdwinnie.airgesture.model.HandFrame
import com.sdwinnie.airgesture.model.HudVisualState
import com.sdwinnie.airgesture.security.AirLogger
import com.sdwinnie.airgesture.security.PreferenceStore
import com.sdwinnie.airgesture.ui.MainActivity
import com.sdwinnie.airgesture.vision.DualStreamCameraController
import com.sdwinnie.airgesture.vision.MediaPipeHandTracker
import com.sdwinnie.airgesture.vision.ThermalAndDutyController

/**
 * Crash-Hardened Camera Foreground Service (v2.0) for Android 14, 15, and 16+.
 * - Uses official foregroundServiceType="camera" ONLY (zero SPECIAL_USE heuristic flags).
 * - Uses START_NOT_STICKY so OS memory kills never enter an unauthorized background FGS crash loop.
 * - Broadcasts live HUD states to both In-App Dynamic Island & Official Android PiP Floating Island
 *   (zero SYSTEM_ALERT_WINDOW overlay permission required).
 */
class AirGestureForegroundService : LifecycleService() {

    private lateinit var preferenceStore: PreferenceStore
    private lateinit var radialSlotManager: RadialAppSlotManager
    private lateinit var volumeController: SystemVolumeController
    private lateinit var actionDispatcher: TouchlessActionDispatcher
    private lateinit var thermalController: ThermalAndDutyController
    private lateinit var handTracker: MediaPipeHandTracker
    private lateinit var cameraController: DualStreamCameraController

    private val gestureStateMachine = AirGestureStateMachine()

    @Volatile
    private var activeConfig: AirGestureConfig = AirGestureConfig()

    @Volatile
    private var manuallyPaused: Boolean = false

    private var engineRunning = false

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()

        preferenceStore = PreferenceStore(this)
        radialSlotManager = RadialAppSlotManager(this, preferenceStore)
        volumeController = SystemVolumeController(this)
        actionDispatcher = TouchlessActionDispatcher(volumeController, radialSlotManager)
        thermalController = ThermalAndDutyController(this)
        handTracker = MediaPipeHandTracker(this)

        cameraController = DualStreamCameraController(
            context = this,
            handTracker = handTracker,
            thermalController = thermalController,
            onVisionStep = ::handleVisionStep,
            onStreamStateChanged = ::handleStreamStateChanged
        )

        AirGestureRuntimeBus.onPreviewSurfaceChanged = { surface ->
            if (engineRunning && !manuallyPaused) {
                cameraController.updatePreviewSurface(surface)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (intent == null) {
            // Do not attempt unauthorized background camera FGS restart
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent.action) {
            ACTION_STOP -> {
                shutdownAndStop()
                return START_NOT_STICKY
            }

            ACTION_TOGGLE_PAUSE -> {
                if (engineRunning) {
                    toggleManualPause()
                }
                return START_NOT_STICKY
            }

            ACTION_RELOAD_CONFIG -> {
                activeConfig = preferenceStore.loadConfig()
                AirGestureRuntimeBus.updateVisualConfig(activeConfig)
                if (engineRunning) {
                    if (!manuallyPaused) {
                        cameraController.applyUpdatedConfig(activeConfig)
                    }
                    AirGestureRuntimeBus.updateTelemetry {
                        it.copy(visionBufferSummary = activeConfig.visionBufferSize.displayName)
                    }
                }
                return START_NOT_STICKY
            }

            ACTION_REFRESH_SLOTS -> {
                AirGestureRuntimeBus.notifyRadialSlotsChanged()
                return START_NOT_STICKY
            }
        }

        val hasCameraPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (!preferenceStore.isProminentDisclosureAccepted || !hasCameraPermission) {
            AirLogger.w("Service", "Missing disclosure consent or CAMERA permission")
            shutdownAndStop()
            return START_NOT_STICKY
        }

        if (!promoteToForeground(pausedByOtherCamera = false)) {
            shutdownAndStop()
            return START_NOT_STICKY
        }

        if (!engineRunning) {
            startTouchlessPipeline()
        }

        return START_NOT_STICKY
    }

    private fun startTouchlessPipeline() {
        engineRunning = true
        manuallyPaused = false
        activeConfig = preferenceStore.loadConfig()
        AirGestureRuntimeBus.updateVisualConfig(activeConfig)
        gestureStateMachine.reset()

        thermalController.startMonitoring { thermalLevel ->
            AirGestureRuntimeBus.updateTelemetry { it.copy(thermalStatus = thermalLevel) }
        }

        AirGestureRuntimeBus.updateHudState(HudVisualState.Dormant)

        cameraController.start(
            config = activeConfig,
            optionalPreviewSurface = AirGestureRuntimeBus.liveCalibrationSurface
        )

        AirGestureRuntimeBus.updateTelemetry {
            it.copy(
                serviceRunning = true,
                manualPaused = false,
                visionBufferSummary = activeConfig.visionBufferSize.displayName,
                thermalStatus = thermalController.currentThermalStatus,
                currentPosture = "พร้อมรับท่าทาง (ยกฝ่ามือ)",
                lastActionSummary = "แสดงฝ่ามือหน้ากล้องเพื่อปลุก Dynamic Island"
            )
        }
    }

    private fun toggleManualPause() {
        manuallyPaused = !manuallyPaused
        if (manuallyPaused) {
            cameraController.stop()
            gestureStateMachine.reset()
            AirGestureRuntimeBus.updateHudState(HudVisualState.PausedNotice("PAUSED BY USER"))
            AirGestureRuntimeBus.updateTelemetry {
                it.copy(
                    manualPaused = true,
                    activeStreamSummary = "Paused",
                    inferenceFps = 0f,
                    currentPosture = "พักการทำงานชั่วคราว"
                )
            }
        } else {
            activeConfig = preferenceStore.loadConfig()
            AirGestureRuntimeBus.updateHudState(HudVisualState.Dormant)
            cameraController.start(activeConfig, AirGestureRuntimeBus.liveCalibrationSurface)
            AirGestureRuntimeBus.updateTelemetry {
                it.copy(
                    manualPaused = false,
                    currentPosture = "พร้อมรับท่าทาง (ยกฝ่ามือ)"
                )
            }
        }
        promoteToForeground(pausedByOtherCamera = false)
    }

    private fun handleVisionStep(
        handFrame: HandFrame?,
        eyeFrame: EyeGazeBlinkAnalyzer.EyeFrameData?,
        nowMs: Long,
        inferenceLatencyMs: Float,
        instantFps: Float
    ) {
        if (manuallyPaused || !engineRunning) return

        val step = gestureStateMachine.process(handFrame, nowMs, activeConfig, eyeFrame)
        val outcome = actionDispatcher.dispatchEvents(
            mode = step.mode,
            events = step.events,
            smoothedFrame = step.smoothedFrame,
            posture = step.posture,
            lockedHandLabel = step.lockedHandLabel,
            activeRadialSlot = gestureStateMachine.activeRadialSlot,
            activeRadialAngleDeg = gestureStateMachine.activeRadialAngleDeg,
            activeRadialCursorX = gestureStateMachine.activeRadialCursorX,
            activeRadialCursorY = gestureStateMachine.activeRadialCursorY,
            activeRadialInCancelZone = gestureStateMachine.activeRadialInCancelZone,
            config = activeConfig,
            fps = instantFps,
            thermalWarning = thermalController.isThermalThrottling,
            cursorNormX = step.cursorNormX,
            cursorNormY = step.cursorNormY,
            skeletonData = step.skeletonData,
            eyeContactActive = step.eyeContactActive,
            lastBlinkFeedback = step.eyeFeedbackText,
            isEyeLocked = step.eyeLockedOn,
            eyeTrackCount = step.eyeTrackCount
        )

        AirGestureRuntimeBus.updateHudState(outcome.hudState)

        val postureText = when (step.mode) {
            AirGestureStateMachine.Mode.DORMANT ->
                if (step.eyeLockedOn) "🎯 ล็อกสายตา 60 FPS (${step.eyeTrackCount} Tracks)"
                else if (step.eyeContactActive) "👁️ สบตาหน้าจอ (พร้อมรับกระพริบตา)"
                else if (step.eyeFeedbackText != null) step.eyeFeedbackText
                else "โหมดประหยัดพลังงาน (รอ 1 มือ / สายตา)"
            AirGestureStateMachine.Mode.PALM_ACTIVE ->
                if (handFrame != null) "${step.posture.emoji} ${step.posture.thaiName} (${step.lockedHandLabel})"
                else if (step.eyeLockedOn) "🎯 ล็อกสายตา (${step.eyeTrackCount} Tracks)"
                else if (step.eyeContactActive) "👁️ สบตาหน้าจอ (${step.lockedHandLabel})"
                else "พร้อมรับท่ามือ (${step.lockedHandLabel})"
            AirGestureStateMachine.Mode.PINCH_VOLUME -> "${activeConfig.volumePreset.badgeEmoji} กำลังปรับเสียงระบบ"
            AirGestureStateMachine.Mode.FIST_RADIAL_MENU -> "🎡 วงกลมเลือกแอปบนหน้าจอ"
        }

        AirGestureRuntimeBus.updateTelemetry { prev ->
            prev.copy(
                serviceRunning = true,
                manualPaused = false,
                delegateName = handTracker.activeDelegateLabel,
                lockedHandLabel = step.lockedHandLabel,
                inferenceFps = instantFps,
                inferenceLatencyMs = inferenceLatencyMs,
                thermalStatus = thermalController.currentThermalStatus,
                currentPosture = postureText,
                lastActionSummary = outcome.actionSummary
            )
        }
    }

    private fun handleStreamStateChanged(streamSummary: String, cameraYielded: Boolean) {
        if (cameraYielded) {
            gestureStateMachine.reset()
            AirGestureRuntimeBus.updateHudState(HudVisualState.PausedNotice("CAMERA IN USE • YIELDED"))
            promoteToForeground(pausedByOtherCamera = true)
        } else if (engineRunning && !manuallyPaused) {
            AirGestureRuntimeBus.updateHudState(HudVisualState.Dormant)
            promoteToForeground(pausedByOtherCamera = false)
        }

        AirGestureRuntimeBus.updateTelemetry { prev ->
            prev.copy(
                activeStreamSummary = streamSummary,
                cameraYielded = cameraYielded
            )
        }
    }

    private fun promoteToForeground(pausedByOtherCamera: Boolean): Boolean {
        val notification = buildForegroundNotification(pausedByOtherCamera)
        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        } else {
            0
        }

        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, fgsType)
            true
        } catch (t: Throwable) {
            AirLogger.e("Service", "Unable to start camera foreground service", t)
            false
        }
    }

    private fun buildForegroundNotification(pausedByOtherCamera: Boolean): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            100,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pauseIntent = PendingIntent.getService(
            this,
            101,
            Intent(this, AirGestureForegroundService::class.java).setAction(ACTION_TOGGLE_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            102,
            Intent(this, AirGestureForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val bodyText = when {
            manuallyPaused -> "พักการทำงานชั่วคราว • แตะ Resume เพื่อทำงานต่อ"
            pausedByOtherCamera -> getString(R.string.notif_paused_camera_text)
            else -> getString(R.string.notif_running_text)
        }

        val toggleLabel = if (manuallyPaused) {
            getString(R.string.notif_action_resume)
        } else {
            getString(R.string.notif_action_pause)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_hand_gesture)
            .setContentTitle(getString(R.string.notif_running_title))
            .setContentText(bodyText)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, toggleLabel, pauseIntent)
            .addAction(0, getString(R.string.notif_action_stop), stopIntent)
            .build()
    }

    private fun ensureNotificationChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notif_channel_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun shutdownAndStop() {
        engineRunning = false
        manuallyPaused = false
        AirGestureRuntimeBus.onPreviewSurfaceChanged = null
        runCatching { thermalController.stopMonitoring() }
        runCatching { cameraController.stop() }
        runCatching { handTracker.close() }
        AirGestureRuntimeBus.updateHudState(HudVisualState.Dormant)

        AirGestureRuntimeBus.updateTelemetry {
            it.copy(
                serviceRunning = false,
                manualPaused = false,
                cameraYielded = false,
                activeStreamSummary = "Stopped",
                delegateName = "Idle",
                inferenceFps = 0f,
                inferenceLatencyMs = 0f,
                currentPosture = "พร้อมเริ่มงาน (Standby)",
                lastActionSummary = "กดปุ่มเปิดระบบเพื่อเริ่มสั่งงานไร้สัมผัส"
            )
        }

        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    override fun onDestroy() {
        if (engineRunning) {
            shutdownAndStop()
        } else {
            runCatching { cameraController.stop() }
            runCatching { handTracker.close() }
        }
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "air_gesture_edge_fgs"
        private const val NOTIFICATION_ID = 4102

        const val ACTION_START = "com.sdwinnie.airgesture.action.START"
        const val ACTION_STOP = "com.sdwinnie.airgesture.action.STOP"
        const val ACTION_TOGGLE_PAUSE = "com.sdwinnie.airgesture.action.TOGGLE_PAUSE"
        const val ACTION_RELOAD_CONFIG = "com.sdwinnie.airgesture.action.RELOAD_CONFIG"
        const val ACTION_REFRESH_SLOTS = "com.sdwinnie.airgesture.action.REFRESH_SLOTS"

        fun startService(context: Context) {
            runCatching {
                val intent = Intent(context, AirGestureForegroundService::class.java).setAction(ACTION_START)
                ContextCompat.startForegroundService(context, intent)
            }.onFailure { err ->
                AirLogger.e("ServiceStart", "Failed to start foreground service", err)
            }
        }

        fun stopService(context: Context) {
            if (!AirGestureRuntimeBus.telemetry.value.serviceRunning) return
            runCatching {
                val intent = Intent(context, AirGestureForegroundService::class.java).setAction(ACTION_STOP)
                context.startService(intent)
            }
        }

        fun notifyConfigUpdated(context: Context) {
            if (!AirGestureRuntimeBus.telemetry.value.serviceRunning) return
            runCatching {
                val intent = Intent(context, AirGestureForegroundService::class.java).setAction(ACTION_RELOAD_CONFIG)
                context.startService(intent)
            }
        }

        fun notifySlotsUpdated(context: Context) {
            AirGestureRuntimeBus.notifyRadialSlotsChanged()
        }
    }
}
