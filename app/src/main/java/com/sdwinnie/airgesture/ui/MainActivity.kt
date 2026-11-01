package com.sdwinnie.airgesture.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.sdwinnie.airgesture.R
import com.sdwinnie.airgesture.databinding.ActivityMainBinding
import com.sdwinnie.airgesture.input.RadialAppSlotManager
import com.sdwinnie.airgesture.model.AirGestureConfig
import com.sdwinnie.airgesture.model.CameraResolution
import com.sdwinnie.airgesture.model.HorizontalNavMode
import com.sdwinnie.airgesture.model.RadialAppSlot
import com.sdwinnie.airgesture.model.TargetFps
import com.sdwinnie.airgesture.model.TelemetrySnapshot
import com.sdwinnie.airgesture.model.VisionBufferSize
import com.sdwinnie.airgesture.security.PreferenceStore
import com.sdwinnie.airgesture.service.AirAccessibilityService
import com.sdwinnie.airgesture.service.AirGestureForegroundService
import com.sdwinnie.airgesture.service.AirGestureRuntimeBus
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var preferenceStore: PreferenceStore
    private lateinit var radialSlotManager: RadialAppSlotManager
    private lateinit var radialSlotAdapter: RadialSlotAdapter

    private var currentConfig: AirGestureConfig = AirGestureConfig()
    private var suppressConfigListeners = false
    private var activePreviewSurface: Surface? = null

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        refreshPermissionChecklist()
        if (granted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotificationPermissionIfNeeded()
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

        setupPermissionAndControlActions()
        setupPipelineConfigControls()
        setupCalibrationPreviewSurface()
        setupRadialSlotsList()
        observeRuntimeTelemetry()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionChecklist()
        radialSlotAdapter.submitList(radialSlotManager.loadResolvedSlots())
    }

    override fun onDestroy() {
        AirGestureRuntimeBus.bindCalibrationPreviewSurface(null)
        activePreviewSurface?.release()
        activePreviewSurface = null
        super.onDestroy()
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

        binding.btnGrantOverlay.setOnClickListener {
            ensureDisclosureThen {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
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

        binding.btnToggleEngine.setOnClickListener {
            val running = AirGestureRuntimeBus.telemetry.value.serviceRunning
            if (running) {
                AirGestureForegroundService.stopService(this)
            } else {
                attemptStartEngine()
            }
        }
    }

    private fun attemptStartEngine() {
        if (!preferenceStore.isProminentDisclosureAccepted) {
            ProminentDisclosureDialog.show(
                context = this,
                preferenceStore = preferenceStore,
                onAccepted = {
                    refreshPermissionChecklist()
                    attemptStartEngine()
                }
            )
            return
        }

        if (!hasCameraPermission()) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(
                this,
                "โปรดเปิดสิทธิ์ Dynamic Island Overlay ก่อนเริ่มใช้งาน",
                Toast.LENGTH_LONG
            ).show()
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
            return
        }

        requestNotificationPermissionIfNeeded()
        AirGestureForegroundService.startService(this)
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

        binding.switchFrontCamera.isChecked = currentConfig.useFrontCamera
        binding.switchCalibrationPreview.isChecked = currentConfig.showLiveCalibrationPreview
        binding.textureCalibrationPreview.visibility =
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

        binding.btnCycleHorizontalMode.setOnClickListener {
            val allModes = HorizontalNavMode.entries
            val nextMode = allModes[(currentConfig.horizontalNavMode.ordinal + 1) % allModes.size]
            binding.btnCycleHorizontalMode.text = "โหมดปัดแนวนอน: ${nextMode.displayName}"
            persistAndReloadConfig(currentConfig.copy(horizontalNavMode = nextMode))
        }

        binding.switchCalibrationPreview.setOnCheckedChangeListener { _, isChecked ->
            if (suppressConfigListeners) return@setOnCheckedChangeListener
            binding.textureCalibrationPreview.visibility = if (isChecked) View.VISIBLE else View.GONE
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

    private fun setupRadialSlotsList() {
        radialSlotAdapter = RadialSlotAdapter(radialSlotManager) { clickedSlot ->
            showAppPickerForRadialSlot(clickedSlot)
        }
        binding.rvRadialSlots.layoutManager = LinearLayoutManager(this)
        binding.rvRadialSlots.adapter = radialSlotAdapter
        radialSlotAdapter.submitList(radialSlotManager.loadResolvedSlots())
    }

    private fun showAppPickerForRadialSlot(slot: RadialAppSlot) {
        val installedApps = radialSlotManager.queryInstalledLauncherApps()
        if (installedApps.isEmpty()) {
            Toast.makeText(this, "ไม่พบรายการแอปในเครื่อง", Toast.LENGTH_SHORT).show()
            return
        }

        val labels = installedApps.map { "${it.label} (${it.packageName})" }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("เลือกแอปสำหรับช่อง #${slot.slotIndex + 1} (${slot.centerAngleDegrees.toInt()}°)")
            .setItems(labels) { _, which ->
                val chosen = installedApps[which]
                preferenceStore.setRadialSlotPackage(slot.slotIndex, chosen.packageName)
                radialSlotAdapter.submitList(radialSlotManager.loadResolvedSlots())
                AirGestureForegroundService.notifySlotsUpdated(this)
            }
            .setNegativeButton("ยกเลิก", null)
            .show()
    }

    private fun observeRuntimeTelemetry() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    AirGestureRuntimeBus.telemetry.collect { snapshot ->
                        renderTelemetry(snapshot)
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
                t.manualPaused -> "⏸ Paused by User"
                t.cameraYielded -> "⏸ Yielded (Camera in use)"
                else -> "● ${t.currentPosture}"
            }
            binding.tvEngineHeadline.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (t.manualPaused || t.cameraYielded) R.color.accent_amber else R.color.accent_emerald
                )
            )
            binding.btnToggleEngine.text = "หยุดการทำงาน Air Gesture (Stop Engine)"
            binding.btnToggleEngine.setBackgroundColor(ContextCompat.getColor(this, R.color.accent_coral))
        } else {
            binding.tvEngineHeadline.text = "● Engine Standby"
            binding.tvEngineHeadline.setTextColor(ContextCompat.getColor(this, R.color.accent_cyan))
            binding.btnToggleEngine.text = "เปิดใช้งาน Air Gesture (Start Engine)"
            binding.btnToggleEngine.setBackgroundColor(ContextCompat.getColor(this, R.color.accent_cyan))
        }

        binding.tvLastAction.text = t.lastActionSummary
        binding.tvTelemetryStream.text = t.activeStreamSummary
        binding.tvTelemetryAiBuffer.text = "${t.visionBufferSummary} (${t.delegateName})"
        binding.tvTelemetryPerf.text = String.format(
            java.util.Locale.US,
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
        val overlayOk = Settings.canDrawOverlays(this)
        val a11yOk = AirAccessibilityService.isEnabledInSettings(this)

        binding.tvStatusDisclosure.text =
            if (disclosureOk) "✅ 1. Prominent Disclosure (ยอมรับแล้ว)" else "⚪ 1. Prominent Disclosure"
        binding.btnDisclosure.text = if (disclosureOk) "ดูข้อมูล" else "อ่านและยอมรับ"

        binding.tvStatusCamera.text =
            if (cameraOk) "✅ 2. กล้องถ่ายรูป (Volatile RAM Ready)" else "⚪ 2. กล้องถ่ายรูป (จำเป็น)"
        binding.btnGrantCamera.isEnabled = !cameraOk
        binding.btnGrantCamera.text = if (cameraOk) "พร้อมแล้ว" else "อนุญาตกล้อง"

        binding.tvStatusOverlay.text =
            if (overlayOk) "✅ 3. Dynamic Island HUD Overlay" else "⚪ 3. Dynamic Island Overlay (จำเป็น)"
        binding.btnGrantOverlay.text = if (overlayOk) "ตั้งค่า" else "เปิด Overlay"

        binding.tvStatusA11y.text =
            if (a11yOk) "✅ 4. Touchless Scroll & Back/Recents" else "⚪ 4. Touchless Scroll (เปิดในช่วยเหลือพิเศษ)"
        binding.btnGrantA11y.text = if (a11yOk) "ตั้งค่า" else "เปิดการช่วยเหลือ"
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }
}
