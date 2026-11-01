package com.sdwinnie.airgesture.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Rational
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.sdwinnie.airgesture.R
import com.sdwinnie.airgesture.databinding.ActivityMainBinding
import com.sdwinnie.airgesture.databinding.ItemSecurityAuditBinding
import com.sdwinnie.airgesture.engine.HandGeometry
import com.sdwinnie.airgesture.input.RadialAppSlotManager
import com.sdwinnie.airgesture.model.AirGestureConfig
import com.sdwinnie.airgesture.model.CameraResolution
import com.sdwinnie.airgesture.model.HorizontalNavMode
import com.sdwinnie.airgesture.model.HudVisualState
import com.sdwinnie.airgesture.model.RadialAppSlot
import com.sdwinnie.airgesture.model.TargetFps
import com.sdwinnie.airgesture.model.TelemetrySnapshot
import com.sdwinnie.airgesture.model.VisionBufferSize
import com.sdwinnie.airgesture.security.PreferenceStore
import com.sdwinnie.airgesture.security.SecuritySelfAuditor
import com.sdwinnie.airgesture.service.AirAccessibilityService
import com.sdwinnie.airgesture.service.AirGestureForegroundService
import com.sdwinnie.airgesture.service.AirGestureRuntimeBus
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private enum class DashboardTab {
        CONTROL,
        CAMERA_AI,
        RADIAL_APPS
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var preferenceStore: PreferenceStore
    private lateinit var radialSlotManager: RadialAppSlotManager
    private lateinit var radialSlotAdapter: RadialSlotAdapter

    private var currentConfig: AirGestureConfig = AirGestureConfig()
    private var suppressConfigListeners = false
    private var activePreviewSurface: Surface? = null
    private var previewTestSlotIndex = 0
    private var lastPipSquareMode: Boolean? = null
    private var pendingPipOnStart = false

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        refreshPermissionChecklist()
        if (granted) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestNotificationPermissionIfNeeded()
            }
            if (pendingPipOnStart) {
                attemptStartEngine(enterPipAfterStart = true)
            }
        } else {
            pendingPipOnStart = false
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        refreshPermissionChecklist()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        preferenceStore = PreferenceStore(this)
        radialSlotManager = RadialAppSlotManager(this, preferenceStore)
        currentConfig = preferenceStore.loadConfig()

        binding.pipHudView.attachSlotManager(radialSlotManager)
        binding.liveRadialPreviewView.attachSlotManager(radialSlotManager)

        setupSegmentedTabs()
        setupPermissionAndControlActions()
        setupPipelineConfigControls()
        setupCalibrationPreviewSurface()
        setupRadialSlotsGrid()
        refreshSecurityAudit()
        applyPipLayoutState(isInPictureInPictureMode)
        syncPipParams(squareWheel = false, serviceActive = AirGestureRuntimeBus.telemetry.value.serviceRunning)
        observeRuntimeStreams()
    }

    override fun onResume() {
        super.onResume()
        applyPipLayoutState(isInPictureInPictureMode)
        refreshPermissionChecklist()
        refreshSecurityAudit()
        radialSlotAdapter.submitList(radialSlotManager.loadResolvedSlots())
        binding.liveRadialPreviewView.refreshSlots()
        binding.pipHudView.refreshRadialSlots()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S &&
            AirGestureRuntimeBus.telemetry.value.serviceRunning &&
            !isInPictureInPictureMode
        ) {
            enterFloatingPipHud(showToastOnFail = false)
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        applyPipLayoutState(isInPictureInPictureMode)
    }

    override fun onDestroy() {
        AirGestureRuntimeBus.bindCalibrationPreviewSurface(null)
        activePreviewSurface?.release()
        activePreviewSurface = null
        super.onDestroy()
    }

    private fun applyPipLayoutState(inPip: Boolean) {
        binding.pipHudView.visibility = if (inPip) View.VISIBLE else View.GONE
        binding.dashboardRoot.visibility = if (inPip) View.GONE else View.VISIBLE
    }

    private fun setupSegmentedTabs() {
        binding.tabControl.setOnClickListener { switchTab(DashboardTab.CONTROL) }
        binding.tabCameraAi.setOnClickListener { switchTab(DashboardTab.CAMERA_AI) }
        binding.tabRadialApps.setOnClickListener { switchTab(DashboardTab.RADIAL_APPS) }
        switchTab(DashboardTab.CONTROL)
    }

    private fun switchTab(tab: DashboardTab) {
        binding.panelControl.visibility = if (tab == DashboardTab.CONTROL) View.VISIBLE else View.GONE
        binding.panelCameraAi.visibility = if (tab == DashboardTab.CAMERA_AI) View.VISIBLE else View.GONE
        binding.panelRadialApps.visibility = if (tab == DashboardTab.RADIAL_APPS) View.VISIBLE else View.GONE

        val primaryText = ContextCompat.getColor(this, R.color.text_primary)
        val secondaryText = ContextCompat.getColor(this, R.color.text_secondary)

        binding.tabControl.setBackgroundResource(if (tab == DashboardTab.CONTROL) R.drawable.bg_tab_selected else 0)
        binding.tabControl.setTextColor(if (tab == DashboardTab.CONTROL) primaryText else secondaryText)

        binding.tabCameraAi.setBackgroundResource(if (tab == DashboardTab.CAMERA_AI) R.drawable.bg_tab_selected else 0)
        binding.tabCameraAi.setTextColor(if (tab == DashboardTab.CAMERA_AI) primaryText else secondaryText)

        binding.tabRadialApps.setBackgroundResource(if (tab == DashboardTab.RADIAL_APPS) R.drawable.bg_tab_selected else 0)
        binding.tabRadialApps.setTextColor(if (tab == DashboardTab.RADIAL_APPS) primaryText else secondaryText)

        binding.mainScrollView.smoothScrollTo(0, 0)
    }

    private fun setupPermissionAndControlActions() {
        binding.btnDisclosure.setOnClickListener {
            ProminentDisclosureDialog.show(
                context = this,
                preferenceStore = preferenceStore,
                onAccepted = { refreshPermissionChecklist() }
            )
        }

        binding.btnGrantCamera.setOnClickListener {
            ensureDisclosureThen {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }

        binding.btnGrantA11y.setOnClickListener {
            ensureDisclosureThen {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        binding.btnRestrictedSettingsGuide.setOnClickListener {
            ProminentDisclosureDialog.showRestrictedSettingsGuide(this)
        }

        binding.btnRefreshAudit.setOnClickListener {
            refreshSecurityAudit()
            Toast.makeText(this, "ตรวจสอบความปลอดภัยไบนารีเรียบร้อย", Toast.LENGTH_SHORT).show()
        }

        binding.btnToggleEngine.setOnClickListener {
            val running = AirGestureRuntimeBus.telemetry.value.serviceRunning
            if (running) {
                AirGestureForegroundService.stopService(this)
            } else {
                attemptStartEngine(enterPipAfterStart = false)
            }
        }

        binding.btnEnterPipHud.setOnClickListener {
            val running = AirGestureRuntimeBus.telemetry.value.serviceRunning
            if (running) {
                enterFloatingPipHud(showToastOnFail = true)
            } else {
                attemptStartEngine(enterPipAfterStart = true)
            }
        }
    }

    private fun attemptStartEngine(enterPipAfterStart: Boolean) {
        if (!preferenceStore.isProminentDisclosureAccepted) {
            ProminentDisclosureDialog.show(
                context = this,
                preferenceStore = preferenceStore,
                onAccepted = {
                    refreshPermissionChecklist()
                    attemptStartEngine(enterPipAfterStart)
                }
            )
            return
        }

        if (!hasCameraPermission()) {
            pendingPipOnStart = enterPipAfterStart
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            return
        }

        pendingPipOnStart = false
        requestNotificationPermissionIfNeeded()
        AirGestureForegroundService.startService(this)

        if (enterPipAfterStart) {
            binding.root.postDelayed({
                if (!isFinishing && !isDestroyed) {
                    enterFloatingPipHud(showToastOnFail = true)
                }
            }, 220L)
        }
    }

    private fun enterFloatingPipHud(showToastOnFail: Boolean) {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            if (showToastOnFail) {
                Toast.makeText(this, "อุปกรณ์นี้ไม่รองรับโหมดหน้าต่างลอย PiP", Toast.LENGTH_SHORT).show()
            }
            return
        }

        val isRadial = AirGestureRuntimeBus.hudState.value is HudVisualState.RadialSelectorActive
        val params = buildPipParams(
            squareWheel = isRadial,
            serviceActive = AirGestureRuntimeBus.telemetry.value.serviceRunning
        )

        val entered = runCatching {
            enterPictureInPictureMode(params)
        }.getOrDefault(false)

        if (!entered && showToastOnFail) {
            Toast.makeText(
                this,
                "ไม่สามารถเปิด PiP ได้ กรุณาตรวจสอบสิทธิ์ Picture-in-Picture ในข้อมูลแอป",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun buildPipParams(squareWheel: Boolean, serviceActive: Boolean): PictureInPictureParams {
        val ratio = if (squareWheel) Rational(100, 100) else Rational(220, 100)
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(ratio)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(serviceActive)
            builder.setSeamlessResizeEnabled(true)
        }
        return builder.build()
    }

    private fun syncPipParams(squareWheel: Boolean, serviceActive: Boolean) {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
        runCatching {
            setPictureInPictureParams(buildPipParams(squareWheel, serviceActive))
            lastPipSquareMode = squareWheel
        }
    }

    private fun ensureDisclosureThen(block: () -> Unit) {
        if (preferenceStore.isProminentDisclosureAccepted) {
            block()
        } else {
            ProminentDisclosureDialog.show(
                context = this,
                preferenceStore = preferenceStore,
                onAccepted = {
                    refreshPermissionChecklist()
                    block()
                }
            )
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasNotif = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasNotif) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun setupPipelineConfigControls() {
        suppressConfigListeners = true

        when (currentConfig.resolution) {
            CameraResolution.RES_480P -> binding.chipRes480.isChecked = true
            CameraResolution.RES_720P -> binding.chipRes720.isChecked = true
            CameraResolution.RES_1080P -> binding.chipRes1080.isChecked = true
            CameraResolution.RES_2K -> binding.chipRes2K.isChecked = true
            CameraResolution.RES_4K -> binding.chipRes4K.isChecked = true
        }

        when (currentConfig.targetFps) {
            TargetFps.FPS_60 -> binding.chipFps60.isChecked = true
            TargetFps.FPS_120 -> binding.chipFps120.isChecked = true
        }

        when (currentConfig.visionBufferSize) {
            VisionBufferSize.ROI_192 -> binding.chipRoi192.isChecked = true
            VisionBufferSize.ROI_256 -> binding.chipRoi256.isChecked = true
        }

        val snappedScroll = ((currentConfig.scrollSensitivity * 10f).roundToInt() / 10f).coerceIn(0.5f, 2.0f)
        val snappedVol = ((currentConfig.volumeSensitivity * 10f).roundToInt() / 10f).coerceIn(0.5f, 2.0f)
        binding.sliderScrollSens.value = snappedScroll
        binding.sliderVolumeSens.value = snappedVol
        binding.tvScrollSensValue.text = String.format(Locale.US, "%.1f×", snappedScroll)
        binding.tvVolumeSensValue.text = String.format(Locale.US, "%.1f×", snappedVol)

        binding.switchFrontCamera.isChecked = currentConfig.useFrontCamera
        binding.switchCalibrationPreview.isChecked = currentConfig.showLiveCalibrationPreview
        binding.cardCalibrationPreview.visibility =
            if (currentConfig.showLiveCalibrationPreview) View.VISIBLE else View.GONE
        binding.btnCycleHorizontalMode.text = "โหมดปัดแนวนอน: ${currentConfig.horizontalNavMode.displayName}"

        suppressConfigListeners = false

        binding.chipGroupResolution.setOnCheckedStateChangeListener { _, checkedIds ->
            if (suppressConfigListeners) return@setOnCheckedStateChangeListener
            val res = when (checkedIds.firstOrNull()) {
                R.id.chipRes480 -> CameraResolution.RES_480P
                R.id.chipRes720 -> CameraResolution.RES_720P
                R.id.chipRes2K -> CameraResolution.RES_2K
                R.id.chipRes4K -> CameraResolution.RES_4K
                else -> CameraResolution.RES_1080P
            }
            persistAndReloadConfig(currentConfig.copy(resolution = res))
        }

        binding.chipGroupFps.setOnCheckedStateChangeListener { _, checkedIds ->
            if (suppressConfigListeners) return@setOnCheckedStateChangeListener
            val fps = if (checkedIds.firstOrNull() == R.id.chipFps120) {
                TargetFps.FPS_120
            } else {
                TargetFps.FPS_60
            }
            persistAndReloadConfig(currentConfig.copy(targetFps = fps))
        }

        binding.chipGroupRoi.setOnCheckedStateChangeListener { _, checkedIds ->
            if (suppressConfigListeners) return@setOnCheckedStateChangeListener
            val roi = if (checkedIds.firstOrNull() == R.id.chipRoi256) {
                VisionBufferSize.ROI_256
            } else {
                VisionBufferSize.ROI_192
            }
            persistAndReloadConfig(currentConfig.copy(visionBufferSize = roi))
        }

        binding.switchFrontCamera.setOnCheckedChangeListener { _, isChecked ->
            if (suppressConfigListeners) return@setOnCheckedChangeListener
            persistAndReloadConfig(currentConfig.copy(useFrontCamera = isChecked))
        }

        binding.sliderScrollSens.addOnChangeListener { _, value, fromUser ->
            if (!fromUser || suppressConfigListeners) return@addOnChangeListener
            val clean = (value * 10f).roundToInt() / 10f
            binding.tvScrollSensValue.text = String.format(Locale.US, "%.1f×", clean)
            persistAndReloadConfig(currentConfig.copy(scrollSensitivity = clean))
        }

        binding.sliderVolumeSens.addOnChangeListener { _, value, fromUser ->
            if (!fromUser || suppressConfigListeners) return@addOnChangeListener
            val clean = (value * 10f).roundToInt() / 10f
            binding.tvVolumeSensValue.text = String.format(Locale.US, "%.1f×", clean)
            persistAndReloadConfig(currentConfig.copy(volumeSensitivity = clean))
        }

        binding.btnCycleHorizontalMode.setOnClickListener {
            val allModes = HorizontalNavMode.entries
            val nextMode = allModes[(currentConfig.horizontalNavMode.ordinal + 1) % allModes.size]
            binding.btnCycleHorizontalMode.text = "โหมดปัดแนวนอน: ${nextMode.displayName}"
            persistAndReloadConfig(currentConfig.copy(horizontalNavMode = nextMode))
        }

        binding.switchCalibrationPreview.setOnCheckedChangeListener { _, isChecked ->
            if (suppressConfigListeners) return@setOnCheckedChangeListener
            binding.cardCalibrationPreview.visibility = if (isChecked) View.VISIBLE else View.GONE
            persistAndReloadConfig(currentConfig.copy(showLiveCalibrationPreview = isChecked))
            if (isChecked && binding.textureCalibrationPreview.isAvailable) {
                bindSurfaceFromTexture(binding.textureCalibrationPreview.surfaceTexture)
            } else if (!isChecked) {
                AirGestureRuntimeBus.bindCalibrationPreviewSurface(null)
            }
        }
    }

    private fun setupCalibrationPreviewSurface() {
        binding.textureCalibrationPreview.surfaceTextureListener =
            object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(
                    surfaceTexture: SurfaceTexture,
                    width: Int,
                    height: Int
                ) {
                    if (currentConfig.showLiveCalibrationPreview) {
                        bindSurfaceFromTexture(surfaceTexture)
                    }
                }

                override fun onSurfaceTextureSizeChanged(
                    surfaceTexture: SurfaceTexture,
                    width: Int,
                    height: Int
                ) = Unit

                override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                    AirGestureRuntimeBus.bindCalibrationPreviewSurface(null)
                    activePreviewSurface?.release()
                    activePreviewSurface = null
                    return true
                }

                override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) = Unit
            }
    }

    private fun bindSurfaceFromTexture(surfaceTexture: SurfaceTexture?) {
        val st = surfaceTexture ?: return
        activePreviewSurface?.release()
        val surface = Surface(st)
        activePreviewSurface = surface
        AirGestureRuntimeBus.bindCalibrationPreviewSurface(surface)
    }

    private fun persistAndReloadConfig(updated: AirGestureConfig) {
        currentConfig = updated
        preferenceStore.saveConfig(updated)
        AirGestureForegroundService.notifyConfigUpdated(this)
    }

    private fun setupRadialSlotsGrid() {
        radialSlotAdapter = RadialSlotAdapter(radialSlotManager) { clickedSlot ->
            previewTestSlotIndex = clickedSlot.slotIndex
            binding.liveRadialPreviewView.updateSelection(
                clickedSlot.centerAngleDegrees,
                clickedSlot.slotIndex
            )
            showAppPickerForRadialSlot(clickedSlot)
        }
        binding.rvRadialSlots.layoutManager = GridLayoutManager(this, 3)
        binding.rvRadialSlots.adapter = radialSlotAdapter
        radialSlotAdapter.submitList(radialSlotManager.loadResolvedSlots())

        binding.btnCyclePreviewSlot.setOnClickListener {
            previewTestSlotIndex = (previewTestSlotIndex + 1) % HandGeometry.RADIAL_SLOTS
            val angle = previewTestSlotIndex * HandGeometry.SLICE_DEGREES
            binding.liveRadialPreviewView.updateSelection(angle, previewTestSlotIndex)
        }
    }

    private fun showAppPickerForRadialSlot(slot: RadialAppSlot) {
        val installedApps = radialSlotManager.queryInstalledLauncherApps()
        if (installedApps.isEmpty()) {
            Toast.makeText(this, "ไม่พบรายการแอปในเครื่อง", Toast.LENGTH_SHORT).show()
            return
        }

        val labels = installedApps.map { it.label }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("เลือกแอปช่อง #${slot.slotIndex + 1} (${slot.centerAngleDegrees.toInt()}°)")
            .setItems(labels) { _, which ->
                val chosen = installedApps[which]
                preferenceStore.setRadialSlotPackage(slot.slotIndex, chosen.packageName)
                radialSlotAdapter.submitList(radialSlotManager.loadResolvedSlots())
                binding.liveRadialPreviewView.refreshSlots()
                binding.liveRadialPreviewView.updateSelection(slot.centerAngleDegrees, slot.slotIndex)
                AirGestureForegroundService.notifySlotsUpdated(this)
            }
            .setNegativeButton("ยกเลิก", null)
            .show()
    }

    @SuppressLint("SetTextI18n")
    private fun refreshSecurityAudit() {
        val auditResults = SecuritySelfAuditor.runLiveAudit(this)
        binding.containerSecurityAudit.removeAllViews()

        val passedCount = auditResults.count { it.passed }
        binding.tvSafeEdgeBadge.text = "🛡️ $passedCount/${auditResults.size} SAFE"

        val emerald = ContextCompat.getColor(this, R.color.accent_emerald)
        val amber = ContextCompat.getColor(this, R.color.accent_amber)

        for (item in auditResults) {
            val rowBinding = ItemSecurityAuditBinding.inflate(
                layoutInflater,
                binding.containerSecurityAudit,
                false
            )
            rowBinding.tvAuditBadge.text = if (item.passed) "PASS" else "WARN"
            rowBinding.tvAuditBadge.setTextColor(if (item.passed) emerald else amber)
            rowBinding.tvAuditTitle.text = item.title
            rowBinding.tvAuditDetail.text = item.detail
            binding.containerSecurityAudit.addView(rowBinding.root)
        }
    }

    private fun observeRuntimeStreams() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    AirGestureRuntimeBus.telemetry.collect { snapshot ->
                        renderTelemetry(snapshot)
                        val isRadial = AirGestureRuntimeBus.hudState.value is HudVisualState.RadialSelectorActive
                        syncPipParams(squareWheel = isRadial, serviceActive = snapshot.serviceRunning)
                    }
                }
                launch {
                    AirGestureRuntimeBus.hudState.collect { hud ->
                        binding.liveDynamicIslandView.updateState(hud)
                        binding.pipHudView.renderState(hud)

                        if (hud is HudVisualState.RadialSelectorActive) {
                            binding.liveRadialPreviewView.updateSelection(hud.angleDegrees, hud.selectedSlot)
                        }

                        val wantSquare = hud is HudVisualState.RadialSelectorActive
                        if (lastPipSquareMode != wantSquare) {
                            syncPipParams(
                                squareWheel = wantSquare,
                                serviceActive = AirGestureRuntimeBus.telemetry.value.serviceRunning
                            )
                        }
                    }
                }
                launch {
                    AirGestureRuntimeBus.radialSlotsVersion.collect {
                        binding.pipHudView.refreshRadialSlots()
                        binding.liveRadialPreviewView.refreshSlots()
                        radialSlotAdapter.submitList(radialSlotManager.loadResolvedSlots())
                    }
                }
                launch {
                    AirGestureRuntimeBus.accessibilityConnected.collect {
                        refreshPermissionChecklist()
                    }
                }
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun renderTelemetry(t: TelemetrySnapshot) {
        if (t.serviceRunning) {
            binding.tvEngineHeadline.text = when {
                t.manualPaused -> "⏸ พักการทำงานชั่วคราว"
                t.cameraYielded -> "⏸ คืนกล้องให้แอปอื่นอัตโนมัติ"
                else -> "● ${t.currentPosture}"
            }
            binding.tvEngineHeadline.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (t.manualPaused || t.cameraYielded) R.color.accent_amber else R.color.accent_emerald
                )
            )
            binding.btnToggleEngine.text = "หยุดการทำงาน Air Gesture (Stop Engine)"
            binding.btnToggleEngine.backgroundTintList =
                ColorStateList.valueOf(ContextCompat.getColor(this, R.color.accent_coral))
            binding.btnToggleEngine.setTextColor(Color.WHITE)
        } else {
            binding.tvEngineHeadline.text = "● พร้อมเริ่มงาน (Engine Standby)"
            binding.tvEngineHeadline.setTextColor(ContextCompat.getColor(this, R.color.accent_cyan))
            binding.btnToggleEngine.text = "เปิดใช้งาน Air Gesture (Start Engine)"
            binding.btnToggleEngine.backgroundTintList =
                ColorStateList.valueOf(ContextCompat.getColor(this, R.color.accent_cyan))
            binding.btnToggleEngine.setTextColor(Color.parseColor("#041019"))
        }

        binding.tvLastAction.text = t.lastActionSummary
        binding.tvTelemetryStream.text = t.activeStreamSummary
        binding.tvTelemetryAiBuffer.text = "${t.visionBufferSummary} (${t.delegateName})"
        binding.tvTelemetryPerf.text = String.format(
            Locale.US,
            "%.1f ms • %.0f fps",
            t.inferenceLatencyMs,
            t.inferenceFps
        )

        binding.tvTelemetryThermal.text = when {
            t.thermalStatus >= 3 -> "Throttled (${t.thermalStatus}) • Eco 12fps"
            t.thermalStatus == 2 -> "Warm (2) • Cap 30fps"
            else -> "Cool (${t.thermalStatus}) • Full Speed"
        }
    }

    @SuppressLint("SetTextI18n")
    private fun refreshPermissionChecklist() {
        val disclosureOk = preferenceStore.isProminentDisclosureAccepted
        val cameraOk = hasCameraPermission()
        val a11yOk = AirAccessibilityService.isEnabledInSettings(this)

        binding.tvStatusDisclosure.text =
            if (disclosureOk) "✅ 1. Prominent Disclosure (ยอมรับแล้ว)" else "⚪ 1. Prominent Disclosure"
        binding.btnDisclosure.text = if (disclosureOk) "ดูข้อมูล" else "อ่านและยอมรับ"

        binding.tvStatusCamera.text =
            if (cameraOk) "✅ 2. กล้องถ่ายรูป (Volatile RAM Ready)" else "⚪ 2. กล้องถ่ายรูป (จำเป็น)"
        binding.btnGrantCamera.isEnabled = !cameraOk
        binding.btnGrantCamera.text = if (cameraOk) "พร้อมแล้ว" else "อนุญาตกล้อง"

        binding.tvStatusA11y.text =
            if (a11yOk) "✅ 3. Touchless Scroll & Back/Recents" else "⚪ 3. Touchless Scroll (ตัวเลือกเสริม)"
        binding.btnGrantA11y.text = if (a11yOk) "ตั้งค่า" else "เปิดใช้งาน"
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }
}
