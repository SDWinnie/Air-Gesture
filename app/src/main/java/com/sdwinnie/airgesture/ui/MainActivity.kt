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
import com.sdwinnie.airgesture.input.RadialAppSlotManager
import com.sdwinnie.airgesture.model.AirGestureConfig
import com.sdwinnie.airgesture.model.AirTapAction
import com.sdwinnie.airgesture.model.AirTapTriggerPreset
import com.sdwinnie.airgesture.model.BackTriggerPreset
import com.sdwinnie.airgesture.model.BlinkAction
import com.sdwinnie.airgesture.model.CameraResolution
import com.sdwinnie.airgesture.model.EyeBlinkTriggerMode
import com.sdwinnie.airgesture.model.HandLockMode
import com.sdwinnie.airgesture.model.HardwareDelegateMode
import com.sdwinnie.airgesture.model.HorizontalNavMode
import com.sdwinnie.airgesture.model.HudPalmStyle
import com.sdwinnie.airgesture.model.HudThemeColor
import com.sdwinnie.airgesture.model.HudVisualState
import com.sdwinnie.airgesture.model.RadialAppSlot
import com.sdwinnie.airgesture.model.RadialSlotCount
import com.sdwinnie.airgesture.model.RadialTriggerPreset
import com.sdwinnie.airgesture.model.ScrollGesturePreset
import com.sdwinnie.airgesture.model.SkeletonBoneDensity
import com.sdwinnie.airgesture.model.TargetFps
import com.sdwinnie.airgesture.model.TelemetrySnapshot
import com.sdwinnie.airgesture.model.VisionBalanceMode
import com.sdwinnie.airgesture.model.VisionBufferSize
import com.sdwinnie.airgesture.model.VolumeDirection
import com.sdwinnie.airgesture.model.VolumeGesturePreset
import com.sdwinnie.airgesture.security.PreferenceStore
import com.sdwinnie.airgesture.security.SecuritySelfAuditor
import com.sdwinnie.airgesture.service.AirAccessibilityService
import com.sdwinnie.airgesture.service.AirGestureForegroundService
import com.sdwinnie.airgesture.service.AirGestureRuntimeBus
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

class MainActivity : AppCompatActivity() {

    private enum class DashboardTab {
        CONTROL,
        GESTURES,
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

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            currentConfig = currentConfig.copy(enableVoiceCommands = true)
            preferenceStore.saveConfig(currentConfig)
            AirGestureForegroundService.notifyConfigUpdated(this)
            binding.switchVoiceCommands.isChecked = true
            Toast.makeText(this, "🎙️ เปิดระบบสั่งงานด้วยเสียงแล้ว", Toast.LENGTH_SHORT).show()
        } else {
            binding.switchVoiceCommands.isChecked = false
            currentConfig = currentConfig.copy(enableVoiceCommands = false)
            preferenceStore.saveConfig(currentConfig)
            Toast.makeText(this, "⚠️ โปรดอนุญาตสิทธิ์ไมโครโฟนเพื่อใช้งานระบบเสียง", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        preferenceStore = PreferenceStore(this)
        radialSlotManager = RadialAppSlotManager(this, preferenceStore)
        currentConfig = preferenceStore.loadConfig()
        AirGestureRuntimeBus.updateVisualConfig(currentConfig)

        val count = currentConfig.radialSlotCount.count
        binding.pipHudView.attachSlotManager(radialSlotManager, count)
        binding.liveRadialPreviewView.attachSlotManager(radialSlotManager, count)
        binding.inAppFloatingWheel.attachSlotManager(radialSlotManager, count)

        setupSegmentedTabs()
        setupPermissionAndControlActions()
        setupGestureAndVisualStudioControls()
        setupPipelineConfigControls()
        setupCalibrationPreviewSurface()
        setupRadialSlotsGrid()
        applyVisualCustomizationsToViews()
        refreshSecurityAudit()
        applyPipLayoutState(isInPictureInPictureMode)
        syncPipParams(squareWheel = false, serviceActive = AirGestureRuntimeBus.telemetry.value.serviceRunning)
        observeRuntimeStreams()
    }

    override fun onResume() {
        super.onResume()
        AirGestureRuntimeBus.setDashboardForeground(!isInPictureInPictureMode)
        applyPipLayoutState(isInPictureInPictureMode)
        refreshPermissionChecklist()
        refreshSecurityAudit()
        refreshAllRadialViews()
    }

    override fun onPause() {
        AirGestureRuntimeBus.setDashboardForeground(false)
        super.onPause()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S &&
            AirGestureRuntimeBus.telemetry.value.serviceRunning &&
            !AirAccessibilityService.isEnabledInSettings(this) &&
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
        AirGestureRuntimeBus.setDashboardForeground(!isInPictureInPictureMode && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        applyPipLayoutState(isInPictureInPictureMode)
    }

    override fun onDestroy() {
        AirGestureRuntimeBus.setDashboardForeground(false)
        AirGestureRuntimeBus.bindCalibrationPreviewSurface(null)
        activePreviewSurface?.release()
        activePreviewSurface = null
        super.onDestroy()
    }

    private fun applyPipLayoutState(inPip: Boolean) {
        binding.pipHudView.visibility = if (inPip) View.VISIBLE else View.GONE
        binding.dashboardRoot.visibility = if (inPip) View.GONE else View.VISIBLE
        if (inPip) {
            binding.inAppCenteredWheelOverlay.visibility = View.GONE
        }
    }

    private fun setupSegmentedTabs() {
        binding.tabControl.setOnClickListener { switchTab(DashboardTab.CONTROL) }
        binding.tabGestures.setOnClickListener { switchTab(DashboardTab.GESTURES) }
        binding.tabCameraAi.setOnClickListener { switchTab(DashboardTab.CAMERA_AI) }
        binding.tabRadialApps.setOnClickListener { switchTab(DashboardTab.RADIAL_APPS) }
        switchTab(DashboardTab.CONTROL)
    }

    private fun switchTab(tab: DashboardTab) {
        binding.panelControl.visibility = if (tab == DashboardTab.CONTROL) View.VISIBLE else View.GONE
        binding.panelGestures.visibility = if (tab == DashboardTab.GESTURES) View.VISIBLE else View.GONE
        binding.panelCameraAi.visibility = if (tab == DashboardTab.CAMERA_AI) View.VISIBLE else View.GONE
        binding.panelRadialApps.visibility = if (tab == DashboardTab.RADIAL_APPS) View.VISIBLE else View.GONE

        val primaryText = ContextCompat.getColor(this, R.color.text_primary)
        val secondaryText = ContextCompat.getColor(this, R.color.text_secondary)

        fun styleTab(tv: android.widget.TextView, selected: Boolean) {
            tv.setBackgroundResource(if (selected) R.drawable.bg_tab_selected else 0)
            tv.setTextColor(if (selected) primaryText else secondaryText)
        }

        styleTab(binding.tabControl, tab == DashboardTab.CONTROL)
        styleTab(binding.tabGestures, tab == DashboardTab.GESTURES)
        styleTab(binding.tabCameraAi, tab == DashboardTab.CAMERA_AI)
        styleTab(binding.tabRadialApps, tab == DashboardTab.RADIAL_APPS)

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
            Toast.makeText(this, "สแกนไบนารีและความปลอดภัยสำเร็จ", Toast.LENGTH_SHORT).show()
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

    private fun setupGestureAndVisualStudioControls() {
        refreshGestureStudioLabels()

        binding.btnPickHandLock.setOnClickListener {
            showEnumSingleChoiceDialog(
                title = "เลือกโหมดล็อกจับ 1 มือ (Single-Hand Lock)",
                entries = HandLockMode.entries,
                current = currentConfig.handLockMode,
                labelOf = { it.displayName }
            ) { chosen ->
                persistAndReloadConfig(currentConfig.copy(handLockMode = chosen))
                refreshGestureStudioLabels()
            }
        }

        binding.switchVoiceCommands.isChecked = currentConfig.enableVoiceCommands
        binding.switchVoiceTts.isChecked = currentConfig.enableVoiceTts

        binding.switchVoiceCommands.setOnCheckedChangeListener { _, isChecked ->
            if (suppressConfigListeners) return@setOnCheckedChangeListener
            if (isChecked) {
                val hasAudioPerm = ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                if (!hasAudioPerm) {
                    audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    return@setOnCheckedChangeListener
                }
            }
            persistAndReloadConfig(currentConfig.copy(enableVoiceCommands = isChecked))
            Toast.makeText(
                this,
                if (isChecked) "🎙️ เปิดระบบสั่งงานด้วยเสียง (พูด 'เลื่อนลง', 'กดใจ', ฯลฯ)" else "ปิดระบบสั่งงานด้วยเสียง",
                Toast.LENGTH_SHORT
            ).show()
        }

        binding.switchVoiceTts.setOnCheckedChangeListener { _, isChecked ->
            if (suppressConfigListeners) return@setOnCheckedChangeListener
            persistAndReloadConfig(currentConfig.copy(enableVoiceTts = isChecked))
        }

        binding.switchEnableAirTap.isChecked = currentConfig.enableAirTap
        binding.switchEnableAirTap.setOnCheckedChangeListener { _, isChecked ->
            if (suppressConfigListeners) return@setOnCheckedChangeListener
            persistAndReloadConfig(currentConfig.copy(enableAirTap = isChecked))
            refreshGestureStudioLabels()
        }

        binding.btnPickAirTapStyle.setOnClickListener {
            showEnumSingleChoiceDialog(
                title = "เลือกท่าทางที่ใช้เคาะจอ (Air Tap Trigger)",
                entries = AirTapTriggerPreset.entries,
                current = currentConfig.airTapTrigger,
                labelOf = { it.displayName }
            ) { chosen ->
                persistAndReloadConfig(currentConfig.copy(airTapTrigger = chosen))
                refreshGestureStudioLabels()
            }
        }

        binding.btnPickAirTapAction.setOnClickListener {
            showEnumSingleChoiceDialog(
                title = "เลือกคำสั่งที่จะทำงานเมื่อเคาะจอ",
                entries = AirTapAction.entries,
                current = currentConfig.airTapAction,
                labelOf = { it.displayName }
            ) { chosen ->
                persistAndReloadConfig(currentConfig.copy(airTapAction = chosen))
                refreshGestureStudioLabels()
            }
        }

        binding.switchShowAirCursor.isChecked = currentConfig.showAirCursor
        binding.switchShowAirCursor.setOnCheckedChangeListener { _, isChecked ->
            if (suppressConfigListeners) return@setOnCheckedChangeListener
            persistAndReloadConfig(currentConfig.copy(showAirCursor = isChecked))
        }

        val airTapSens = ((currentConfig.airTapSensitivity * 10f).roundToInt() / 10f).coerceIn(0.5f, 2.5f)
        binding.sliderAirTapSens.value = airTapSens
        binding.tvAirTapSensLabel.text = "ความไวในการเคาะจอ: ${String.format(Locale.US, "%.1f", airTapSens)}x"
        binding.sliderAirTapSens.addOnChangeListener { _, value, fromUser ->
            if (!fromUser || suppressConfigListeners) return@addOnChangeListener
            val clean = (value * 10f).roundToInt() / 10f
            binding.tvAirTapSensLabel.text = "ความไวในการเคาะจอ: ${String.format(Locale.US, "%.1f", clean)}x"
            persistAndReloadConfig(currentConfig.copy(airTapSensitivity = clean))
        }

        // --- Eye Contact & Multi-Blink Controls ---
        binding.switchEnableEyeBlink.isChecked = currentConfig.enableEyeContactBlink
        binding.switchEnableEyeBlink.setOnCheckedChangeListener { _, isChecked ->
            if (suppressConfigListeners) return@setOnCheckedChangeListener
            persistAndReloadConfig(currentConfig.copy(enableEyeContactBlink = isChecked))
            refreshGestureStudioLabels()
        }

        binding.switchRequireEyeContact.isChecked = currentConfig.requireEyeContact
        binding.switchRequireEyeContact.setOnCheckedChangeListener { _, isChecked ->
            if (suppressConfigListeners) return@setOnCheckedChangeListener
            persistAndReloadConfig(currentConfig.copy(requireEyeContact = isChecked))
            refreshGestureStudioLabels()
        }

        binding.btnPickEyeBlinkScope.setOnClickListener {
            showEnumSingleChoiceDialog(
                title = "เลือกโหมดตรวจจับดวงตา (Scope)",
                entries = EyeBlinkTriggerMode.entries,
                current = currentConfig.eyeBlinkTriggerMode,
                labelOf = { it.displayName }
            ) { chosen ->
                persistAndReloadConfig(currentConfig.copy(eyeBlinkTriggerMode = chosen))
                refreshGestureStudioLabels()
            }
        }

        binding.btnPickSingleBlink.setOnClickListener {
            showEnumSingleChoiceDialog(
                title = "เลือกคำสั่งกระพริบตา 1 ครั้ง (Single Blink)",
                entries = BlinkAction.entries,
                current = currentConfig.singleBlinkAction,
                labelOf = { it.displayName }
            ) { chosen ->
                persistAndReloadConfig(currentConfig.copy(singleBlinkAction = chosen))
                refreshGestureStudioLabels()
            }
        }

        binding.btnPickDoubleBlink.setOnClickListener {
            showEnumSingleChoiceDialog(
                title = "เลือกคำสั่งกระพริบตา 2 ครั้ง (Double Blink)",
                entries = BlinkAction.entries,
                current = currentConfig.doubleBlinkAction,
                labelOf = { it.displayName }
            ) { chosen ->
                persistAndReloadConfig(currentConfig.copy(doubleBlinkAction = chosen))
                refreshGestureStudioLabels()
            }
        }

        binding.btnPickTripleBlink.setOnClickListener {
            showEnumSingleChoiceDialog(
                title = "เลือกคำสั่งกระพริบตา 3 ครั้ง (Triple Blink - กดใจ/ถูกใจ)",
                entries = BlinkAction.entries,
                current = currentConfig.tripleBlinkAction,
                labelOf = { it.displayName }
            ) { chosen ->
                persistAndReloadConfig(currentConfig.copy(tripleBlinkAction = chosen))
                refreshGestureStudioLabels()
            }
        }

        val blinkSens = ((currentConfig.blinkSensitivity * 10f).roundToInt() / 10f).coerceIn(0.5f, 2.5f)
        binding.sliderBlinkSens.value = blinkSens
        binding.tvBlinkSensLabel.text = "ความไวการตรวจจับกระพริบตา: ${String.format(Locale.US, "%.1f", blinkSens)}x (EAR สมดุล)"
        binding.sliderBlinkSens.addOnChangeListener { _, value, fromUser ->
            if (!fromUser || suppressConfigListeners) return@addOnChangeListener
            val clean = (value * 10f).roundToInt() / 10f
            binding.tvBlinkSensLabel.text = "ความไวการตรวจจับกระพริบตา: ${String.format(Locale.US, "%.1f", clean)}x (EAR สมดุล)"
            persistAndReloadConfig(currentConfig.copy(blinkSensitivity = clean))
        }

        // --- Bone Density & Vision Balance Controls ---
        binding.btnPickBoneDensity.setOnClickListener {
            showEnumSingleChoiceDialog(
                title = "เลือกระดับความละเอียดกระดูกมือ (20, 36, 48 ชิ้น)",
                entries = SkeletonBoneDensity.entries,
                current = currentConfig.skeletonDensity,
                labelOf = { it.displayName }
            ) { chosen ->
                persistAndReloadConfig(currentConfig.copy(skeletonDensity = chosen))
                refreshGestureStudioLabels()
            }
        }

        binding.btnPickVisionBalance.setOnClickListener {
            showEnumSingleChoiceDialog(
                title = "เลือกโหมดความสมดุลการมองเห็นของ AI",
                entries = VisionBalanceMode.entries,
                current = currentConfig.visionBalanceMode,
                labelOf = { it.displayName }
            ) { chosen ->
                persistAndReloadConfig(currentConfig.copy(visionBalanceMode = chosen))
                refreshGestureStudioLabels()
            }
        }

        val balanceSmoothing = ((currentConfig.balanceSmoothingFactor * 10f).roundToInt() / 10f).coerceIn(0.5f, 2.5f)
        binding.sliderBalanceSmoothing.value = balanceSmoothing
        binding.tvBalanceSmoothingLabel.text = "ตัวกรองความนิ่งและสมดุลพิกัด (1-Euro Smoothing): ${String.format(Locale.US, "%.1f", balanceSmoothing)}x"
        binding.sliderBalanceSmoothing.addOnChangeListener { _, value, fromUser ->
            if (!fromUser || suppressConfigListeners) return@addOnChangeListener
            val clean = (value * 10f).roundToInt() / 10f
            binding.tvBalanceSmoothingLabel.text = "ตัวกรองความนิ่งและสมดุลพิกัด (1-Euro Smoothing): ${String.format(Locale.US, "%.1f", clean)}x"
            persistAndReloadConfig(currentConfig.copy(balanceSmoothingFactor = clean))
        }

        binding.btnPickScrollPreset.setOnClickListener {
            showEnumSingleChoiceDialog(
                title = "เลือกท่าเลื่อนหน้าจอขึ้น / ลง",
                entries = ScrollGesturePreset.entries,
                current = currentConfig.scrollPreset,
                labelOf = { it.displayName }
            ) { chosen ->
                persistAndReloadConfig(currentConfig.copy(scrollPreset = chosen))
                refreshGestureStudioLabels()
            }
        }

        binding.btnPickVolumePreset.setOnClickListener {
            showEnumSingleChoiceDialog(
                title = "เลือกท่าลดเสียง / เพิ่มเสียง",
                entries = VolumeGesturePreset.entries,
                current = currentConfig.volumePreset,
                labelOf = { it.displayName }
            ) { chosen ->
                persistAndReloadConfig(currentConfig.copy(volumePreset = chosen))
                refreshGestureStudioLabels()
            }
        }

        binding.btnPickRadialPreset.setOnClickListener {
            showEnumSingleChoiceDialog(
                title = "เลือกท่าเปิดวงกลมเลือกแอปกลางหน้าจอ",
                entries = RadialTriggerPreset.entries,
                current = currentConfig.radialPreset,
                labelOf = { it.displayName }
            ) { chosen ->
                persistAndReloadConfig(currentConfig.copy(radialPreset = chosen))
                refreshGestureStudioLabels()
            }
        }

        binding.btnPickBackPreset.setOnClickListener {
            showEnumSingleChoiceDialog(
                title = "เลือกท่าย้อนกลับ / สลับแอป",
                entries = BackTriggerPreset.entries,
                current = currentConfig.backPreset,
                labelOf = { it.displayName }
            ) { chosen ->
                persistAndReloadConfig(currentConfig.copy(backPreset = chosen))
                refreshGestureStudioLabels()
            }
        }

        binding.btnPickPalmStyle.setOnClickListener {
            showEnumSingleChoiceDialog(
                title = "เลือกรูปฝ่ามือและไอคอนบน Dynamic Island",
                entries = HudPalmStyle.entries,
                current = currentConfig.hudPalmStyle,
                labelOf = { it.displayName }
            ) { chosen ->
                persistAndReloadConfig(currentConfig.copy(hudPalmStyle = chosen))
                applyVisualCustomizationsToViews()
                refreshGestureStudioLabels()
                // Live preview wake on the in-app Dynamic Island Dock
                binding.liveDynamicIslandView.updateState(
                    HudVisualState.PalmTracking(
                        statusText = "ตัวอย่างรูปฝ่ามือ HUD",
                        postureEmoji = currentConfig.scrollPreset.badgeEmoji,
                        handSideLabel = "1 มือ",
                        landmarks = emptyList(),
                        fps = 60f
                    )
                )
            }
        }

        binding.btnPickThemeColor.setOnClickListener {
            showEnumSingleChoiceDialog(
                title = "เลือกสีธีม HUD & วงกลมเลือกแอป",
                entries = HudThemeColor.entries,
                current = currentConfig.hudThemeColor,
                labelOf = { it.displayName }
            ) { chosen ->
                persistAndReloadConfig(currentConfig.copy(hudThemeColor = chosen))
                applyVisualCustomizationsToViews()
                refreshGestureStudioLabels()
            }
        }

        binding.switchShowSystemVolumeUi.isChecked = currentConfig.showSystemVolumeUi
        binding.switchShowSystemVolumeUi.setOnCheckedChangeListener { _, isChecked ->
            if (suppressConfigListeners) return@setOnCheckedChangeListener
            persistAndReloadConfig(currentConfig.copy(showSystemVolumeUi = isChecked))
        }

        binding.btnPreviewVolumeDown.setOnClickListener {
            binding.liveDynamicIslandView.updateState(
                HudVisualState.VolumeSlider(
                    volumePercent = 34,
                    deltaStepPercent = -8,
                    direction = VolumeDirection.DOWN,
                    isMuted = false
                )
            )
        }

        binding.btnPreviewVolumeUp.setOnClickListener {
            binding.liveDynamicIslandView.updateState(
                HudVisualState.VolumeSlider(
                    volumePercent = 76,
                    deltaStepPercent = 10,
                    direction = VolumeDirection.UP,
                    isMuted = false
                )
            )
        }
    }

    private fun <T> showEnumSingleChoiceDialog(
        title: String,
        entries: List<T>,
        current: T,
        labelOf: (T) -> String,
        onSelected: (T) -> Unit
    ) {
        val labels = entries.map(labelOf).toTypedArray()
        val checkedIdx = entries.indexOf(current).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setSingleChoiceItems(labels, checkedIdx) { dialog, which ->
                dialog.dismiss()
                onSelected(entries[which])
            }
            .setNegativeButton("ยกเลิก", null)
            .show()
    }

    @SuppressLint("SetTextI18n")
    private fun refreshGestureStudioLabels() {
        binding.btnPickHandLock.text = currentConfig.handLockMode.displayName
        binding.btnPickAirTapStyle.text = currentConfig.airTapTrigger.displayName
        binding.btnPickAirTapAction.text = currentConfig.airTapAction.displayName
        binding.btnPickEyeBlinkScope.text = currentConfig.eyeBlinkTriggerMode.displayName
        binding.btnPickSingleBlink.text = currentConfig.singleBlinkAction.displayName
        binding.btnPickDoubleBlink.text = currentConfig.doubleBlinkAction.displayName
        binding.btnPickTripleBlink.text = currentConfig.tripleBlinkAction.displayName
        binding.btnPickBoneDensity.text = currentConfig.skeletonDensity.displayName
        binding.btnPickVisionBalance.text = currentConfig.visionBalanceMode.displayName
        binding.btnPickScrollPreset.text = currentConfig.scrollPreset.displayName
        binding.btnPickVolumePreset.text = currentConfig.volumePreset.displayName
        binding.btnPickRadialPreset.text = currentConfig.radialPreset.displayName
        binding.btnPickBackPreset.text = currentConfig.backPreset.displayName
        binding.btnPickPalmStyle.text = currentConfig.hudPalmStyle.displayName
        binding.btnPickThemeColor.text = "สีธีม: ${currentConfig.hudThemeColor.displayName}"

        binding.tvActiveGestureSummary.text =
            "• เคาะจอสัมผัส (Air Tap): ${if (currentConfig.enableAirTap) "${currentConfig.airTapTrigger.badgeEmoji} ${currentConfig.airTapTrigger.displayName} -> ${currentConfig.airTapAction.displayName}" else "ปิดการใช้งาน"}\n" +
                "• สบตา & กระพริบตา (Eye Contact): ${if (currentConfig.enableEyeContactBlink) "1 ครั้ง=${currentConfig.singleBlinkAction.badgeEmoji} • 2 ครั้ง=${currentConfig.doubleBlinkAction.badgeEmoji} • 3 ครั้ง=${currentConfig.tripleBlinkAction.badgeEmoji}" else "ปิดการใช้งาน"}\n" +
                "• โครงกระดูกมือ: ${currentConfig.skeletonDensity.displayName}\n" +
                "• สมดุลการมองเห็น AI: ${currentConfig.visionBalanceMode.displayName}\n" +
                "• ล็อกมือ: ${currentConfig.handLockMode.displayName}\n" +
                "• เลื่อนจอ: ${currentConfig.scrollPreset.displayName}\n" +
                "• ลด/เพิ่มเสียง: ${currentConfig.volumePreset.displayName}\n" +
                "• วงกลมเลือกแอป (${currentConfig.radialSlotCount.count} แอป): ${currentConfig.radialPreset.displayName}\n" +
                "• ย้อนกลับ: ${currentConfig.backPreset.displayName}\n" +
                "• รูป HUD: ${currentConfig.hudPalmStyle.displayName} (${currentConfig.hudThemeColor.displayName})"
    }

    private fun applyVisualCustomizationsToViews() {
        val style = currentConfig.hudPalmStyle
        val theme = currentConfig.hudThemeColor
        val count = currentConfig.radialSlotCount.count

        binding.liveDynamicIslandView.applyVisualStyle(style, theme)
        binding.pipHudView.applyVisualStyle(style, theme, count)
        binding.liveRadialPreviewView.applyThemeColor(theme)
        binding.inAppFloatingWheel.applyThemeColor(theme)
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
            serviceActive = AirGestureRuntimeBus.telemetry.value.serviceRunning &&
                !AirAccessibilityService.isEnabledInSettings(this)
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
            // Auto-enter PiP only if Accessibility On-Screen HUD is NOT already active
            builder.setAutoEnterEnabled(serviceActive && !AirAccessibilityService.isEnabledInSettings(this))
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
            TargetFps.FPS_30 -> binding.chipFps30.isChecked = true
            TargetFps.FPS_60 -> binding.chipFps60.isChecked = true
            TargetFps.FPS_120 -> binding.chipFps120.isChecked = true
        }

        when (currentConfig.visionBufferSize) {
            VisionBufferSize.ROI_192 -> binding.chipRoi192.isChecked = true
            VisionBufferSize.ROI_256 -> binding.chipRoi256.isChecked = true
        }

        when (currentConfig.radialSlotCount) {
            RadialSlotCount.SLOTS_6 -> binding.chipSlots6.isChecked = true
            RadialSlotCount.SLOTS_8 -> binding.chipSlots8.isChecked = true
            RadialSlotCount.SLOTS_12 -> binding.chipSlots12.isChecked = true
            RadialSlotCount.SLOTS_15 -> binding.chipSlots15.isChecked = true
        }

        val snappedScroll = ((currentConfig.scrollSensitivity * 10f).roundToInt() / 10f).coerceIn(0.5f, 2.0f)
        val snappedVol = ((currentConfig.volumeSensitivity * 10f).roundToInt() / 10f).coerceIn(0.5f, 2.0f)
        binding.sliderScrollSens.value = snappedScroll
        binding.sliderVolumeSens.value = snappedVol
        binding.tvScrollSensValue.text = String.format(Locale.US, "%.1f×", snappedScroll)
        binding.tvVolumeSensValue.text = String.format(Locale.US, "%.1f×", snappedVol)

        binding.switchFrontCamera.isChecked = currentConfig.useFrontCamera
        binding.switchHardwareGpu.isChecked = currentConfig.hardwareDelegate == HardwareDelegateMode.AUTO_GPU_CPU
        binding.switchShowFpsCounter.isChecked = currentConfig.showFpsCounter
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
            val fps = when (checkedIds.firstOrNull()) {
                R.id.chipFps30 -> TargetFps.FPS_30
                R.id.chipFps120 -> TargetFps.FPS_120
                else -> TargetFps.FPS_60
            }
            persistAndReloadConfig(currentConfig.copy(targetFps = fps))
        }

        binding.switchHardwareGpu.setOnCheckedChangeListener { _, isChecked ->
            if (suppressConfigListeners) return@setOnCheckedChangeListener
            val mode = if (isChecked) HardwareDelegateMode.AUTO_GPU_CPU else HardwareDelegateMode.CPU_ONLY
            persistAndReloadConfig(currentConfig.copy(hardwareDelegate = mode))
        }

        binding.switchShowFpsCounter.setOnCheckedChangeListener { _, isChecked ->
            if (suppressConfigListeners) return@setOnCheckedChangeListener
            persistAndReloadConfig(currentConfig.copy(showFpsCounter = isChecked))
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

        binding.chipGroupRadialCount.setOnCheckedStateChangeListener { _, checkedIds ->
            if (suppressConfigListeners) return@setOnCheckedStateChangeListener
            val slotCount = when (checkedIds.firstOrNull()) {
                R.id.chipSlots6 -> RadialSlotCount.SLOTS_6
                R.id.chipSlots12 -> RadialSlotCount.SLOTS_12
                R.id.chipSlots15 -> RadialSlotCount.SLOTS_15
                else -> RadialSlotCount.SLOTS_8
            }
            persistAndReloadConfig(currentConfig.copy(radialSlotCount = slotCount))
            refreshGestureStudioLabels()
            refreshAllRadialViews()
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

        binding.switchGeminiAi.isChecked = currentConfig.enableGeminiAi
        binding.switchLongDistanceBooster.isChecked = currentConfig.enableLongDistanceBooster
        updateAiModelStatusText()

        binding.switchGeminiAi.setOnCheckedChangeListener { _, isChecked ->
            if (suppressConfigListeners) return@setOnCheckedChangeListener
            if (isChecked) {
                val targetModel = if (currentConfig.geminiModelName.equals("on-device", ignoreCase = true)) {
                    "gemini-3.8-flash"
                } else {
                    currentConfig.geminiModelName
                }
                persistAndReloadConfig(currentConfig.copy(enableGeminiAi = true, geminiModelName = targetModel))
                Toast.makeText(this, "เปิดใช้งานผู้ช่วย AI Co-Processor", Toast.LENGTH_SHORT).show()
            } else {
                persistAndReloadConfig(currentConfig.copy(enableGeminiAi = false))
                Toast.makeText(this, "สลับใช้ระบบประมวลผลบนเครื่อง 100%", Toast.LENGTH_SHORT).show()
            }
            updateAiModelStatusText()
        }

        binding.btnSelectAiModel.setOnClickListener {
            showAiModelSelectionDialog()
        }

        binding.btnTestGeminiKey.setOnClickListener {
            binding.btnTestGeminiKey.isEnabled = false
            val isCloud = currentConfig.enableGeminiAi && !currentConfig.geminiModelName.equals("on-device", ignoreCase = true)
            Toast.makeText(
                this,
                if (isCloud) "กำลังทดสอบโมเดล AI Cloud (${currentConfig.geminiModelName})..." else "กำลังตรวจสอบระบบ AI บนเครื่อง...",
                Toast.LENGTH_SHORT
            ).show()

            com.sdwinnie.airgesture.ai.GeminiAiCoProcessor().testConnection(
                apiKey = currentConfig.geminiApiKey,
                preferredModel = if (currentConfig.enableGeminiAi) currentConfig.geminiModelName else "on-device"
            ) { success, msg, verifiedModel ->
                binding.btnTestGeminiKey.isEnabled = true
                if (success && verifiedModel.isNotBlank() && verifiedModel != currentConfig.geminiModelName && verifiedModel != "on-device") {
                    persistAndReloadConfig(currentConfig.copy(geminiModelName = verifiedModel))
                }
                updateAiModelStatusText()
                MaterialAlertDialogBuilder(this)
                    .setTitle(if (success) "การเชื่อมต่อสำเร็จ" else "การเชื่อมต่อไม่สำเร็จ")
                    .setMessage(msg)
                    .setPositiveButton("ตกลง", null)
                    .show()
            }
        }

        binding.btnCustomKeyOptional.setOnClickListener {
            showGeminiApiKeyDialog()
        }

        binding.switchLongDistanceBooster.setOnCheckedChangeListener { _, isChecked ->
            if (suppressConfigListeners) return@setOnCheckedChangeListener
            persistAndReloadConfig(currentConfig.copy(enableLongDistanceBooster = isChecked))
            Toast.makeText(
                this,
                if (isChecked) "🔭 เปิดตัวเร่งระยะไกล 1.5 - 3.5 ม. (Focal Zoom + Contrast)" else "ปิดตัวเร่งระยะไกล",
                Toast.LENGTH_SHORT
            ).show()
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
        AirGestureRuntimeBus.updateVisualConfig(updated)
        AirGestureForegroundService.notifyConfigUpdated(this)
    }

    private fun showAiModelSelectionDialog() {
        val currentChoice = if (!currentConfig.enableGeminiAi) {
            com.sdwinnie.airgesture.model.AiModelChoice.ON_DEVICE_NEURAL
        } else {
            com.sdwinnie.airgesture.model.AiModelChoice.fromModelId(currentConfig.geminiModelName)
        }

        val entries = com.sdwinnie.airgesture.model.AiModelChoice.entries.toTypedArray()
        val items = entries.map { "${it.displayName}\n• ${it.description}" }.toTypedArray()
        val selectedIndex = entries.indexOf(currentChoice).coerceAtLeast(0)

        MaterialAlertDialogBuilder(this)
            .setTitle("🤖 เลือกโมเดล AI สำหรับประมวลผล")
            .setSingleChoiceItems(items, selectedIndex) { dialog, which ->
                val chosen = entries[which]
                val updatedConfig = if (chosen == com.sdwinnie.airgesture.model.AiModelChoice.ON_DEVICE_NEURAL) {
                    currentConfig.copy(
                        enableGeminiAi = false,
                        geminiModelName = "on-device"
                    )
                } else {
                    currentConfig.copy(
                        enableGeminiAi = true,
                        geminiModelName = chosen.modelId
                    )
                }
                persistAndReloadConfig(updatedConfig)
                binding.switchGeminiAi.isChecked = updatedConfig.enableGeminiAi
                updateAiModelStatusText()
                dialog.dismiss()
                Toast.makeText(this, "เลือกโมเดล: ${chosen.displayName}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("ปิด", null)
            .show()
    }

    private fun showGeminiApiKeyDialog() {
        val input = android.widget.EditText(this).apply {
            hint = "คัดลอกคีย์ส่วนตัวมาใส่ที่นี่ (ปล่อยว่างเพื่อใช้คีย์ฟรีของแอป)"
            setText(currentConfig.geminiApiKey)
            setSingleLine(true)
            setPadding(48, 32, 48, 32)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("🔑 Custom Google AI Studio API Key (ตัวเลือกเสริม)")
            .setMessage("หากคุณมี Google AI Studio API Key ส่วนตัว สามารถใส่ได้ที่นี่ หรือกด 'ใช้คีย์เริ่มต้นของแอป' เพื่อใช้งาน Built-in Free Cloud ได้ทันที")
            .setView(input)
            .setPositiveButton("บันทึก") { _, _ ->
                val key = input.text.toString().trim()
                currentConfig = currentConfig.copy(geminiApiKey = key)
                preferenceStore.saveConfig(currentConfig)
                updateAiModelStatusText()
                AirGestureForegroundService.notifyConfigUpdated(this)
                Toast.makeText(this, if (key.isNotBlank()) "บันทึก Custom Key เรียบร้อย" else "สลับใช้ Built-in Free Cloud ของแอป", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("ใช้คีย์เริ่มต้นของแอป") { _, _ ->
                currentConfig = currentConfig.copy(geminiApiKey = "")
                preferenceStore.saveConfig(currentConfig)
                updateAiModelStatusText()
                AirGestureForegroundService.notifyConfigUpdated(this)
                Toast.makeText(this, "สลับใช้คีย์เริ่มต้นของแอปแล้ว", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("ยกเลิก", null)
            .show()
    }

    private fun updateAiModelStatusText() {
        val choice = com.sdwinnie.airgesture.model.AiModelChoice.fromModelId(currentConfig.geminiModelName)
        if (!currentConfig.enableGeminiAi || choice == com.sdwinnie.airgesture.model.AiModelChoice.ON_DEVICE_NEURAL) {
            binding.tvGeminiKeyStatus.text = "โมเดลที่ใช้งาน: 📱 On-Device Neural (ประมวลผลบนเครื่อง 100% ไม่ใช้เน็ต)"
            binding.tvGeminiKeyStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_emerald))
        } else {
            val key = currentConfig.geminiApiKey
            val keyInfo = if (key.isNotBlank()) {
                val masked = if (key.length > 8) "${key.take(4)}...${key.takeLast(4)}" else "***"
                "Custom Key: $masked"
            } else {
                "พร้อมใช้งานทันที ไม่ต้องใส่คีย์"
            }
            binding.tvGeminiKeyStatus.text = "โมเดลที่ใช้งาน: ${choice.displayName} ($keyInfo)"
            binding.tvGeminiKeyStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_emerald))
        }
    }

    @SuppressLint("SetTextI18n")
    private fun refreshAllRadialViews() {
        val count = currentConfig.radialSlotCount.count
        binding.tvRadialGridTitle.text = "📱 กำหนดแอปในวงกลมทั้ง $count ช่อง (แตะช่องเพื่อเปลี่ยนแอป)"
        radialSlotAdapter.submitList(radialSlotManager.loadResolvedSlots(count))
        binding.liveRadialPreviewView.refreshSlots(count)
        binding.inAppFloatingWheel.refreshSlots(count)
        binding.pipHudView.refreshRadialSlots(count)
    }

    private fun setupRadialSlotsGrid() {
        radialSlotAdapter = RadialSlotAdapter(radialSlotManager) { clickedSlot ->
            previewTestSlotIndex = clickedSlot.slotIndex
            val rad = Math.toRadians((clickedSlot.centerAngleDegrees - 90f).toDouble())
            binding.liveRadialPreviewView.updateSelection(
                angleDegrees = clickedSlot.centerAngleDegrees,
                selectedSlot = clickedSlot.slotIndex,
                totalSlots = currentConfig.radialSlotCount.count,
                normCursorX = (cos(rad) * 0.75).toFloat(),
                normCursorY = (sin(rad) * 0.75).toFloat(),
                cancelZone = false
            )
            showAppPickerForRadialSlot(clickedSlot)
        }
        binding.rvRadialSlots.layoutManager = GridLayoutManager(this, 3)
        binding.rvRadialSlots.adapter = radialSlotAdapter
        refreshAllRadialViews()

        binding.btnCyclePreviewSlot.setOnClickListener {
            val total = currentConfig.radialSlotCount.count
            previewTestSlotIndex = (previewTestSlotIndex + 1) % total
            val sliceDeg = 360.0f / total
            val angle = previewTestSlotIndex * sliceDeg
            val rad = Math.toRadians((angle - 90f).toDouble())
            binding.liveRadialPreviewView.updateSelection(
                angleDegrees = angle,
                selectedSlot = previewTestSlotIndex,
                totalSlots = total,
                normCursorX = (cos(rad) * 0.78).toFloat(),
                normCursorY = (sin(rad) * 0.78).toFloat(),
                cancelZone = false
            )
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
                refreshAllRadialViews()
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
                            binding.liveRadialPreviewView.updateSelection(
                                angleDegrees = hud.angleDegrees,
                                selectedSlot = hud.selectedSlot,
                                totalSlots = hud.totalSlots,
                                normCursorX = hud.cursorNormX,
                                normCursorY = hud.cursorNormY,
                                cancelZone = hud.inCancelZone
                            )

                            // If Accessibility On-Screen HUD is not enabled yet, pop up the centered circle inside the app!
                            if (!AirGestureRuntimeBus.accessibilityConnected.value && !isInPictureInPictureMode) {
                                binding.inAppFloatingWheel.updateSelection(
                                    angleDegrees = hud.angleDegrees,
                                    selectedSlot = hud.selectedSlot,
                                    totalSlots = hud.totalSlots,
                                    normCursorX = hud.cursorNormX,
                                    normCursorY = hud.cursorNormY,
                                    cancelZone = hud.inCancelZone
                                )
                                binding.inAppCenteredWheelOverlay.visibility = View.VISIBLE
                            } else {
                                binding.inAppCenteredWheelOverlay.visibility = View.GONE
                            }
                        } else {
                            binding.inAppCenteredWheelOverlay.visibility = View.GONE
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
                        refreshAllRadialViews()
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
        binding.tvTelemetryHandLock.text = t.lockedHandLabel
        binding.tvTelemetryStream.text = t.activeStreamSummary
        binding.tvTelemetryAiBuffer.text = "${t.visionBufferSummary} • 1:1"
        val thermLabel = if (t.thermalStatus >= 2) "Warm" else "Cool"
        binding.tvTelemetryPerf.text = String.format(
            Locale.US,
            "%.1f ms • %.0f fps • %s",
            t.inferenceLatencyMs,
            t.inferenceFps,
            thermLabel
        )
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
            if (cameraOk) "✅ 2. กล้องถ่ายรูป (ล็อกจับ 1 มือพร้อม)" else "⚪ 2. กล้องถ่ายรูป (จำเป็น)"
        binding.btnGrantCamera.isEnabled = !cameraOk
        binding.btnGrantCamera.text = if (cameraOk) "พร้อมแล้ว" else "อนุญาตกล้อง"

        binding.tvStatusA11y.text =
            if (a11yOk) "✅ 3. เลื่อนจอ & วงกลมแอปกลางหน้าจอ (พร้อม)" else "⚪ 3. เลื่อนจอ & วงกลมแอปกลางจอ (แนะนำเปิด)"
        binding.btnGrantA11y.text = if (a11yOk) "ตั้งค่า" else "เปิดใช้งาน"
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }
}
