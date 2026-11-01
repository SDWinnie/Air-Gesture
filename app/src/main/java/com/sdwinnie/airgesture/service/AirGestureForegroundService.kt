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
import com.sdwinnie.airgesture.input.RadialAppSlotManager
import com.sdwinnie.airgesture.input.SystemVolumeController
import com.sdwinnie.airgesture.input.TouchlessActionDispatcher
import com.sdwinnie.airgesture.model.AirGestureConfig
import com.sdwinnie.airgesture.model.HandFrame
import com.sdwinnie.airgesture.model.HudVisualState
import com.sdwinnie.airgesture.overlay.OverlayHudManager
import com.sdwinnie.airgesture.security.AirLogger
import com.sdwinnie.airgesture.security.PreferenceStore
import com.sdwinnie.airgesture.ui.MainActivity
import com.sdwinnie.airgesture.vision.DualStreamCameraController
import com.sdwinnie.airgesture.vision.MediaPipeHandTracker
import com.sdwinnie.airgesture.vision.ThermalAndDutyController

/**
 * Persistent Background Touchless Vision & HUD Service for Android 14, 15, and 16+.
 * Runs with explicit foregroundServiceType="camera|specialUse" and zero disk/network usage.
 */
class AirGestureForegroundService : LifecycleService() {

    private lateinit var preferenceStore: PreferenceStore
    private lateinit var radialSlotManager: RadialAppSlotManager
    private lateinit var volumeController: SystemVolumeController
    private lateinit var actionDispatcher: TouchlessActionDispatcher
    private lateinit var overlayHudManager: OverlayHudManager
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
        overlayHudManager = OverlayHudManager(this, radialSlotManager)
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

        when (intent?.action) {
            ACTION_STOP -> {
                shutdownAndStop()
                return START_NOT_STICKY
            }

            ACTION_TOGGLE_PAUSE -> {
                if (engineRunning) {
                    toggleManualPause()
                }
                return START_STICKY
            }

            ACTION_RELOAD_CONFIG -> {
                if (engineRunning) {
                    activeConfig = preferenceStore.loadConfig()
                    if (!manuallyPaused) {
                        cameraController.applyUpdatedConfig(activeConfig)
                    }
                    AirGestureRuntimeBus.updateTelemetry {
                        it.copy(visionBufferSummary = activeConfig.visionBufferSize.displayName)
                    }
                }
                return START_STICKY
            }

            ACTION_REFRESH_SLOTS -> {
                overlayHudManager.notifyRadialSlotsChanged()
                return START_STICKY
            }
        }

        // Verify mandatory Prominent Disclosure + Camera grant before entering camera FGS
        val hasCameraPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (!preferenceStore.isProminentDisclosureAccepted || !hasCameraPermission) {
            AirLogger.w("Service", "Missing disclosure consent or CAMERA permission; aborting FGS start")
            stopSelf()
            return START_NOT_STICKY
        }

        if (!promoteToForeground(pausedByOtherCamera = false)) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (!engineRunning) {
            startTouchlessPipeline()
        }

        return START_STICKY
    }

    private fun startTouchlessPipeline() {
        engineRunning = true
        manuallyPaused = false
        activeConfig = preferenceStore.loadConfig()
        gestureStateMachine.reset()

        thermalController.startMonitoring { thermalLevel ->
            AirGestureRuntimeBus.updateTelemetry { it.copy(thermalStatus = thermalLevel) }
        }

        overlayHudManager.attachOverlaysIfPermitted()
        overlayHudManager.updateHudState(HudVisualState.Dormant)

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
                currentPosture = "Armed (Show Palm)",
                lastActionSummary = "Waiting for open palm..."
            )
        }
    }

    private fun toggleManualPause() {
        manuallyPaused = !manuallyPaused
        if (manuallyPaused) {
            cameraController.stop()
            gestureStateMachine.reset()
            overlayHudManager.updateHudState(HudVisualState.PausedNotice("AIR GESTURE PAUSED"))
            AirGestureRuntimeBus.updateTelemetry {
                it.copy(
                    manualPaused = true,
                    activeStreamSummary = "Paused by user",
                    inferenceFps = 0f,
                    currentPosture = "Paused"
                )
            }
        } else {
            activeConfig = preferenceStore.loadConfig()
            overlayHudManager.updateHudState(HudVisualState.Dormant)
            cameraController.start(activeConfig, AirGestureRuntimeBus.liveCalibrationSurface)
            AirGestureRuntimeBus.updateTelemetry {
                it.copy(
                    manualPaused = false,
                    currentPosture = "Armed (Show Palm)"
                )
            }
        }
        promoteToForeground(pausedByOtherCamera = false)
    }

    private fun handleVisionStep(
        handFrame: HandFrame?,
        nowMs: Long,
        inferenceLatencyMs: Float,
        instantFps: Float
    ) {
        if (manuallyPaused || !engineRunning) return

        val step = gestureStateMachine.process(handFrame, nowMs)
        val outcome = actionDispatcher.dispatchEvents(
            mode = step.mode,
            events = step.events,
            smoothedFrame = step.smoothedFrame,
            activeRadialSlot = gestureStateMachine.activeRadialSlot,
            activeRadialAngleDeg = gestureStateMachine.activeRadialAngleDeg,
            config = activeConfig,
            fps = instantFps,
            thermalWarning = thermalController.isThermalThrottling
        )

        overlayHudManager.updateHudState(outcome.hudState)

        val postureText = when (step.mode) {
            AirGestureStateMachine.Mode.DORMANT -> "Dormant (Sentry)"
            AirGestureStateMachine.Mode.PALM_ACTIVE -> if (handFrame != null) "Open Palm Tracked" else "Island Awake"
            AirGestureStateMachine.Mode.PINCH_VOLUME -> "Pinch Volume (ท่ามือจีบ)"
            AirGestureStateMachine.Mode.FIST_RADIAL_MENU -> "Fist Radial 15 (ท่ากำมือ)"
        }

        AirGestureRuntimeBus.updateTelemetry { prev ->
            prev.copy(
                serviceRunning = true,
                manualPaused = false,
                delegateName = handTracker.activeDelegateLabel,
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
            overlayHudManager.updateHudState(HudVisualState.PausedNotice("CAMERA IN USE • YIELDED"))
            promoteToForeground(pausedByOtherCamera = true)
        } else if (engineRunning && !manuallyPaused) {
            overlayHudManager.updateHudState(HudVisualState.Dormant)
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
        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        } else {
            0
        }

        return runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, fgsType)
            true
        }.onFailure { err ->
            AirLogger.e("Service", "Unable to enter foreground service state", err)
        }.getOrDefault(false)
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
            manuallyPaused -> "Paused by user • Tap Resume to re-arm touchless gestures"
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
        thermalController.stopMonitoring()
        cameraController.stop()
        handTracker.close()
        overlayHudManager.detachOverlays()

        AirGestureRuntimeBus.updateTelemetry {
            it.copy(
                serviceRunning = false,
                manualPaused = false,
                cameraYielded = false,
                activeStreamSummary = "Stopped",
                delegateName = "Idle",
                inferenceFps = 0f,
                inferenceLatencyMs = 0f,
                currentPosture = "Dormant",
                lastActionSummary = "Service stopped"
            )
        }

        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (engineRunning) {
            shutdownAndStop()
        } else {
            overlayHudManager.detachOverlays()
            cameraController.stop()
            handTracker.close()
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
            val intent = Intent(context, AirGestureForegroundService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stopService(context: Context) {
            val intent = Intent(context, AirGestureForegroundService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }

        fun notifyConfigUpdated(context: Context) {
            if (!AirGestureRuntimeBus.telemetry.value.serviceRunning) return
            val intent = Intent(context, AirGestureForegroundService::class.java).setAction(ACTION_RELOAD_CONFIG)
            context.startService(intent)
        }

        fun notifySlotsUpdated(context: Context) {
            if (!AirGestureRuntimeBus.telemetry.value.serviceRunning) return
            val intent = Intent(context, AirGestureForegroundService::class.java).setAction(ACTION_REFRESH_SLOTS)
            context.startService(intent)
        }
    }
}
