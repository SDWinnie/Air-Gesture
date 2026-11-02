package com.sdwinnie.airgesture.engine

import com.sdwinnie.airgesture.model.AirGestureConfig
import com.sdwinnie.airgesture.model.BackTriggerPreset
import com.sdwinnie.airgesture.model.GestureEvent
import com.sdwinnie.airgesture.model.HandFrame
import com.sdwinnie.airgesture.model.HandPosture
import com.sdwinnie.airgesture.model.Landmark3D
import com.sdwinnie.airgesture.model.RadialTriggerPreset
import com.sdwinnie.airgesture.model.ScrollGesturePreset
import com.sdwinnie.airgesture.model.VisionBalanceMode
import com.sdwinnie.airgesture.model.VolumeGesturePreset
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Intelligent Ergonomic Gesture Finite State Machine (v3.0):
 * - Strictly 1-Hand Locked via [SingleHandLockTracker] (zero 2-hand bouncing).
 * - Multi-frame posture stability & velocity gating prevents erratic misfires during transitions.
 * - Supports user-configurable MediaPipe canonical human postures for Scroll, Volume, App Wheel, & Back.
 * - Uses Spatial Virtual Joystick with Center Safe Cancel Zone for effortless 360° circular app selection.
 */
class AirGestureStateMachine(
    private val palmWakeFramesRequired: Int = 3,
    private val islandSleepTimeoutMs: Long = 3200L,
    private val radialHoldActivationMs: Long = 150L,
    private val quickBackHoldActivationMs: Long = 260L,
    private val pinchEnterThreshold: Float = 0.28f,
    private val pinchExitThreshold: Float = 0.42f,
    private val pinchMinDeltaNormalized: Float = 0.018f,
    private val thumbVolumeRepeatIntervalMs: Long = 155L,
    private val verticalSweepSpeedThreshold: Float = 1.50f,
    private val horizontalSweepSpeedThreshold: Float = 2.00f,
    private val scrollDispatchCooldownMs: Long = 140L,
    private val scrollOppositeClutchLockMs: Long = 360L,
    private val horizontalNavCooldownMs: Long = 580L
) {
    enum class Mode {
        DORMANT,
        PALM_ACTIVE,
        PINCH_VOLUME,
        FIST_RADIAL_MENU
    }

    var currentMode: Mode = Mode.DORMANT
        private set

    var activeRadialSlot: Int = -1
        private set

    var activeRadialAngleDeg: Float = 0f
        private set

    var activeRadialCursorX: Float = 0f
        private set

    var activeRadialCursorY: Float = 0f
        private set

    var activeRadialInCancelZone: Boolean = true
        private set

    var currentDetectedPosture: HandPosture = HandPosture.NONE
        private set

    val eyeAnalyzer = EyeGazeBlinkAnalyzer()
    val visionCalibrator = com.sdwinnie.airgesture.vision.BalancedVisionCalibrator()

    private val singleHandLock = SingleHandLockTracker()
    private val filterBank = LandmarkBankOneEuroFilter()

    private var consecutiveWakeFrames = 0
    private var lastHandSeenMs = -1L

    // Palm motion history
    private var prevPalmCenter: Landmark3D? = null
    private var prevPalmTimestampMs: Long = -1L
    private var smoothedVxNormalized = 0f
    private var smoothedVyNormalized = 0f
    private var smoothedCursorX = 0.5f
    private var smoothedCursorY = 0.5f

    // Debounce & clutch timers
    private var lastScrollDispatchMs = -10_000L
    private var lastScrollWasUp: Boolean? = null
    private var lastHorizontalDispatchMs = -10_000L
    private var quickBackCandidateStartMs = -1L
    private var quickBackFiredInHold = false

    // Volume state (Pinch / Victory Slide / Thumbs Up-Down)
    private var consecutiveVolumeFrames = 0
    private var volumeExitFrames = 0
    private var lastVolumeAnchorY = 0f
    private var lastThumbStepMs = -10_000L

    // Circular App Wheel state (Spatial Virtual Joystick)
    private var radialCandidateStartMs = -1L
    private var radialAnchorCenter: Landmark3D? = null
    private var radialAnchorScale: Float = 0.14f
    private var radialEverLeftCancelZone = false
    private var radialReleaseFrames = 0

    fun reset() {
        currentMode = Mode.DORMANT
        activeRadialSlot = -1
        activeRadialAngleDeg = 0f
        activeRadialCursorX = 0f
        activeRadialCursorY = 0f
        activeRadialInCancelZone = true
        currentDetectedPosture = HandPosture.NONE
        singleHandLock.reset()
        filterBank.reset()
        consecutiveWakeFrames = 0
        lastHandSeenMs = -1L
        prevPalmCenter = null
        prevPalmTimestampMs = -1L
        smoothedVxNormalized = 0f
        smoothedVyNormalized = 0f
        smoothedCursorX = 0.5f
        smoothedCursorY = 0.5f
        lastScrollDispatchMs = -10_000L
        lastScrollWasUp = null
        lastHorizontalDispatchMs = -10_000L
        quickBackCandidateStartMs = -1L
        quickBackFiredInHold = false
        consecutiveVolumeFrames = 0
        volumeExitFrames = 0
        lastThumbStepMs = -10_000L
        radialCandidateStartMs = -1L
        radialAnchorCenter = null
        radialEverLeftCancelZone = false
        radialReleaseFrames = 0
        eyeAnalyzer.reset()
        visionCalibrator.reset()
    }

    data class StepOutput(
        val mode: Mode,
        val smoothedFrame: HandFrame?,
        val posture: HandPosture,
        val lockedHandLabel: String,
        val events: List<GestureEvent>,
        val cursorNormX: Float = 0.5f,
        val cursorNormY: Float = 0.5f,
        val skeletonData: HandSkeletonAnalyzer.HandSkeletonData? = null,
        val eyeContactActive: Boolean = false,
        val eyeFeedbackText: String? = null
    )

    fun process(
        rawFrame: HandFrame?,
        nowMs: Long,
        config: AirGestureConfig = AirGestureConfig(),
        eyeFrame: EyeGazeBlinkAnalyzer.EyeFrameData? = null
    ): StepOutput {
        val events = mutableListOf<GestureEvent>()
        val eyeRes = eyeAnalyzer.process(eyeFrame, nowMs, config)
        events.addAll(eyeRes.events)

        val gatedFrame = singleHandLock.filterSingleHand(rawFrame, nowMs, config.handLockMode)
        val lockedLabel = singleHandLock.lockedSide?.thaiLabel ?: config.handLockMode.displayName.take(14)

        if (gatedFrame == null) {
            consecutiveWakeFrames = 0
            consecutiveVolumeFrames = 0
            radialCandidateStartMs = -1L
            quickBackCandidateStartMs = -1L
            quickBackFiredInHold = false
            prevPalmCenter = null
            prevPalmTimestampMs = -1L
            smoothedVxNormalized = 0f
            smoothedVyNormalized = 0f
            currentDetectedPosture = HandPosture.NONE

            // Grace period of 260ms before dropping active modal gestures on a single missed frame
            val missingDuration = if (lastHandSeenMs > 0L) nowMs - lastHandSeenMs else Long.MAX_VALUE
            if (missingDuration >= 260L) {
                if (currentMode == Mode.PINCH_VOLUME) {
                    events.add(GestureEvent.PinchEnded)
                    currentMode = Mode.PALM_ACTIVE
                } else if (currentMode == Mode.FIST_RADIAL_MENU) {
                    events.add(GestureEvent.RadialMenuCancelled)
                    activeRadialSlot = -1
                    radialAnchorCenter = null
                    currentMode = Mode.PALM_ACTIVE
                }
            }

            if (currentMode != Mode.DORMANT && lastHandSeenMs > 0L && missingDuration >= islandSleepTimeoutMs) {
                currentMode = Mode.DORMANT
                events.add(GestureEvent.SleepDynamicIsland)
            }
            return StepOutput(
                mode = currentMode,
                smoothedFrame = null,
                posture = HandPosture.NONE,
                lockedHandLabel = lockedLabel,
                events = events,
                eyeContactActive = eyeRes.isLookingAtScreen,
                eyeFeedbackText = eyeRes.feedbackText
            )
        }

        lastHandSeenMs = nowMs
        val filteredFrame = filterBank.filter(gatedFrame)
        val frame = if (config.visionBalanceMode == VisionBalanceMode.ADAPTIVE_EDGE_SOFT_MARGIN) {
            val balanced = visionCalibrator.calibrateLandmarks(
                raw = filteredFrame.landmarks,
                timestampMs = filteredFrame.timestampMs,
                mode = config.visionBalanceMode,
                smoothingFactor = config.balanceSmoothingFactor
            )
            filteredFrame.copy(landmarks = balanced)
        } else {
            filteredFrame
        }
        val landmarks = frame.landmarks
        val scale = HandGeometry.palmScale(landmarks)
        val palmCenter = HandGeometry.palmCentroid(landmarks)

        updateNormalizedVelocity(palmCenter, scale, frame.timestampMs)

        // Smooth Air Cursor tracking (index tip or palm center)
        val targetCursorX = landmarks[HandGeometry.INDEX_TIP].x
        val targetCursorY = landmarks[HandGeometry.INDEX_TIP].y
        val cursorAlpha = (0.35f * config.airCursorSensitivity).coerceIn(0.15f, 0.70f)
        smoothedCursorX += (targetCursorX - smoothedCursorX) * cursorAlpha
        smoothedCursorY += (targetCursorY - smoothedCursorY) * cursorAlpha

        val skeleton = HandSkeletonAnalyzer.analyze(
            landmarks = landmarks,
            timestampMs = frame.timestampMs,
            palmScale = scale,
            airTapTrigger = config.airTapTrigger,
            airTapSensitivity = config.airTapSensitivity,
            enableAirTap = config.enableAirTap,
            density = config.skeletonDensity
        )

        val posture = if (skeleton.classifiedPosture != HandPosture.NONE) {
            skeleton.classifiedPosture
        } else {
            HandGeometry.classifyPosture(landmarks, pinchEnterThreshold, scale)
        }
        currentDetectedPosture = posture

        // Detect Touchless Air Tap (เคาะจอ / จดจอ) in Active mode
        if (currentMode == Mode.PALM_ACTIVE && config.enableAirTap && skeleton.isAirTapDetected) {
            events.add(
                GestureEvent.AirTapTriggered(
                    normX = smoothedCursorX.coerceIn(0f, 1f),
                    normY = smoothedCursorY.coerceIn(0f, 1f),
                    tapKind = skeleton.airTapKind
                )
            )
        }

        when (currentMode) {
            Mode.DORMANT -> handleDormantState(landmarks, palmCenter, scale, posture, frame.timestampMs, config, events)
            Mode.PALM_ACTIVE -> handlePalmActiveState(landmarks, palmCenter, scale, posture, frame.timestampMs, config, events)
            Mode.PINCH_VOLUME -> handleVolumeState(landmarks, scale, posture, frame.timestampMs, config, events)
            Mode.FIST_RADIAL_MENU -> handleCircularAppWheelState(landmarks, palmCenter, scale, posture, config, events)
        }

        return StepOutput(
            mode = currentMode,
            smoothedFrame = frame,
            posture = posture,
            lockedHandLabel = lockedLabel,
            events = events,
            cursorNormX = smoothedCursorX,
            cursorNormY = smoothedCursorY,
            skeletonData = skeleton,
            eyeContactActive = eyeRes.isLookingAtScreen,
            eyeFeedbackText = eyeRes.feedbackText
        )
    }

    private fun isScrollPostureMatch(posture: HandPosture, preset: ScrollGesturePreset): Boolean {
        return when (preset) {
            ScrollGesturePreset.OPEN_PALM_SWEEP -> posture == HandPosture.OPEN_PALM
            ScrollGesturePreset.ONE_FINGER_POINT -> posture == HandPosture.POINTING_ONE || posture == HandPosture.OPEN_PALM
            ScrollGesturePreset.VICTORY_TWO_FINGER -> posture == HandPosture.VICTORY_PEACE
            ScrollGesturePreset.KNIFE_HAND_SWIPE -> posture == HandPosture.KNIFE_HAND || posture == HandPosture.OPEN_PALM
            ScrollGesturePreset.THUMB_SWEEP -> posture == HandPosture.THUMB_UP || posture == HandPosture.THUMB_DOWN
        }
    }

    private fun isVolumePostureMatch(
        landmarks: List<Landmark3D>,
        scale: Float,
        posture: HandPosture,
        preset: VolumeGesturePreset,
        alreadyActive: Boolean
    ): Boolean {
        return when (preset) {
            VolumeGesturePreset.PINCH_OK_SLIDE -> {
                val thresh = if (alreadyActive) pinchExitThreshold else pinchEnterThreshold
                HandGeometry.isPinchCandidate(landmarks, thresh, scale)
            }
            VolumeGesturePreset.THUMBS_UP_DOWN -> {
                posture == HandPosture.THUMB_UP || posture == HandPosture.THUMB_DOWN
            }
            VolumeGesturePreset.VICTORY_SLIDE -> {
                posture == HandPosture.VICTORY_PEACE
            }
            VolumeGesturePreset.FINGER_GUN_SLIDE -> {
                posture == HandPosture.FINGER_GUN
            }
        }
    }

    private fun isRadialTriggerMatch(posture: HandPosture, preset: RadialTriggerPreset): Boolean {
        return when (preset) {
            RadialTriggerPreset.CLOSED_FIST -> posture == HandPosture.CLOSED_FIST
            RadialTriggerPreset.I_LOVE_YOU_SIGN -> posture == HandPosture.I_LOVE_YOU
            RadialTriggerPreset.THREE_FINGERS -> posture == HandPosture.THREE_FINGERS
            RadialTriggerPreset.CALL_ME_SHAKA -> posture == HandPosture.CALL_ME_SHAKA
            RadialTriggerPreset.OPEN_PALM_HOLD -> posture == HandPosture.OPEN_PALM
        }
    }

    private fun updateNormalizedVelocity(palmCenter: Landmark3D, scale: Float, timestampMs: Long) {
        val prev = prevPalmCenter
        val prevTs = prevPalmTimestampMs
        if (prev != null && prevTs > 0L && timestampMs > prevTs) {
            val dtSec = ((timestampMs - prevTs).coerceAtLeast(1L)) / 1000.0f
            if (dtSec <= 0.25f) {
                val instVx = (palmCenter.x - prev.x) / (scale * dtSec)
                val instVy = (palmCenter.y - prev.y) / (scale * dtSec)
                smoothedVxNormalized = if (instVx * smoothedVxNormalized < 0f) {
                    0.65f * instVx
                } else {
                    0.56f * instVx + 0.44f * smoothedVxNormalized
                }
                smoothedVyNormalized = if (instVy * smoothedVyNormalized < 0f) {
                    0.65f * instVy
                } else {
                    0.56f * instVy + 0.44f * smoothedVyNormalized
                }
            } else {
                smoothedVxNormalized = 0f
                smoothedVyNormalized = 0f
            }
        }
        prevPalmCenter = palmCenter
        prevPalmTimestampMs = timestampMs
    }

    private fun handleDormantState(
        landmarks: List<Landmark3D>,
        palmCenter: Landmark3D,
        scale: Float,
        posture: HandPosture,
        timestampMs: Long,
        config: AirGestureConfig,
        events: MutableList<GestureEvent>
    ) {
        if (posture == HandPosture.OPEN_PALM || isScrollPostureMatch(posture, config.scrollPreset)) {
            consecutiveWakeFrames++
            if (consecutiveWakeFrames >= palmWakeFramesRequired) {
                currentMode = Mode.PALM_ACTIVE
                consecutiveWakeFrames = 0
                events.add(GestureEvent.WakeDynamicIsland)
            }
        } else if (isRadialTriggerMatch(posture, config.radialPreset)) {
            if (radialCandidateStartMs < 0L) {
                radialCandidateStartMs = timestampMs
            } else if ((timestampMs - radialCandidateStartMs) >= radialHoldActivationMs) {
                events.add(GestureEvent.WakeDynamicIsland)
                enterCircularAppWheel(landmarks, palmCenter, scale, config, events)
            }
        } else {
            consecutiveWakeFrames = 0
            radialCandidateStartMs = -1L
        }
    }

    private fun handlePalmActiveState(
        landmarks: List<Landmark3D>,
        palmCenter: Landmark3D,
        scale: Float,
        posture: HandPosture,
        timestampMs: Long,
        config: AirGestureConfig,
        events: MutableList<GestureEvent>
    ) {
        val wholeHandSpeed = hypot(smoothedVxNormalized, smoothedVyNormalized)

        // 1. Check Circular App Wheel Trigger (steady hand hold prevents accidental trigger during fast swipe)
        if (isRadialTriggerMatch(posture, config.radialPreset) && wholeHandSpeed < 1.85f) {
            consecutiveVolumeFrames = 0
            if (radialCandidateStartMs < 0L) {
                radialCandidateStartMs = timestampMs
            } else if ((timestampMs - radialCandidateStartMs) >= radialHoldActivationMs) {
                enterCircularAppWheel(landmarks, palmCenter, scale, config, events)
                return
            }
            return
        } else {
            radialCandidateStartMs = -1L
        }

        // 2. Check Volume Control Trigger (Pinch OK Slide / Thumbs Up-Down / Victory Slide)
        if (isVolumePostureMatch(landmarks, scale, posture, config.volumePreset, alreadyActive = false) &&
            wholeHandSpeed < 1.75f
        ) {
            consecutiveVolumeFrames++
            if (consecutiveVolumeFrames >= 2) {
                currentMode = Mode.PINCH_VOLUME
                consecutiveVolumeFrames = 0
                volumeExitFrames = 0
                lastVolumeAnchorY = volumeTrackY(landmarks, config.volumePreset)
                lastThumbStepMs = timestampMs
                if (config.volumePreset == VolumeGesturePreset.THUMBS_UP_DOWN) {
                    val step = if (posture == HandPosture.THUMB_UP) 0.08f else -0.08f
                    events.add(GestureEvent.PinchVolumeModulate(step))
                } else {
                    events.add(GestureEvent.PinchVolumeModulate(0f))
                }
                return
            }
            return
        } else {
            consecutiveVolumeFrames = 0
        }

        // 3. Optional Static Quick-Back Posture (Peace Hold Back / ILY Hold Back)
        val isStaticBackMatch = when (config.backPreset) {
            BackTriggerPreset.HORIZONTAL_SWIPE -> false
            BackTriggerPreset.PEACE_HOLD_BACK ->
                posture == HandPosture.VICTORY_PEACE &&
                    config.scrollPreset != ScrollGesturePreset.VICTORY_TWO_FINGER &&
                    config.volumePreset != VolumeGesturePreset.VICTORY_SLIDE
            BackTriggerPreset.ILY_HOLD_BACK ->
                posture == HandPosture.I_LOVE_YOU &&
                    config.radialPreset != RadialTriggerPreset.I_LOVE_YOU_SIGN
            BackTriggerPreset.FINGER_GUN_BACK ->
                posture == HandPosture.FINGER_GUN &&
                    config.volumePreset != VolumeGesturePreset.FINGER_GUN_SLIDE
        }
        if (isStaticBackMatch && wholeHandSpeed < 1.35f) {
            if (quickBackCandidateStartMs < 0L) {
                quickBackCandidateStartMs = timestampMs
                quickBackFiredInHold = false
            } else if (!quickBackFiredInHold && (timestampMs - quickBackCandidateStartMs) >= quickBackHoldActivationMs) {
                if ((timestampMs - lastHorizontalDispatchMs) >= horizontalNavCooldownMs) {
                    lastHorizontalDispatchMs = timestampMs
                    quickBackFiredInHold = true
                    events.add(GestureEvent.QuickBackTriggered)
                }
            }
            return
        } else {
            quickBackCandidateStartMs = -1L
            quickBackFiredInHold = false
        }

        // 4. Directional Sweeps (Scroll Up/Down & Horizontal Back/Switch)
        if (!isScrollPostureMatch(posture, config.scrollPreset)) {
            return
        }

        val absVx = abs(smoothedVxNormalized)
        val absVy = abs(smoothedVyNormalized)

        // Horizontal Sweep
        if (absVx >= horizontalSweepSpeedThreshold && absVx > absVy * 1.70f) {
            if ((timestampMs - lastHorizontalDispatchMs) >= horizontalNavCooldownMs) {
                lastHorizontalDispatchMs = timestampMs
                val swipeLeft = smoothedVxNormalized < 0f
                events.add(
                    GestureEvent.HorizontalNavigate(
                        swipeLeft = swipeLeft,
                        normalizedSpeed = absVx.coerceIn(1.0f, 8.0f)
                    )
                )
                smoothedVxNormalized = 0f
                smoothedVyNormalized = 0f
                return
            }
        }

        // Vertical Sweep with Return-Stroke Clutch Suppression
        if (absVy >= verticalSweepSpeedThreshold && absVy > absVx * 1.60f) {
            if ((timestampMs - lastScrollDispatchMs) >= scrollDispatchCooldownMs) {
                val handMovingUp = smoothedVyNormalized < 0f
                val prevDir = lastScrollWasUp
                val elapsedSinceLastScroll = timestampMs - lastScrollDispatchMs
                if (prevDir != null && prevDir != handMovingUp && elapsedSinceLastScroll < scrollOppositeClutchLockMs) {
                    return
                }

                lastScrollDispatchMs = timestampMs
                lastScrollWasUp = handMovingUp
                events.add(
                    GestureEvent.VerticalScroll(
                        scrollUp = handMovingUp,
                        normalizedSpeed = absVy.coerceIn(1.0f, 8.0f)
                    )
                )
                smoothedVyNormalized = 0f
            }
        }
    }

    private fun enterCircularAppWheel(
        landmarks: List<Landmark3D>,
        palmCenter: Landmark3D,
        scale: Float,
        config: AirGestureConfig,
        events: MutableList<GestureEvent>
    ) {
        currentMode = Mode.FIST_RADIAL_MENU
        radialCandidateStartMs = -1L
        radialReleaseFrames = 0
        radialAnchorCenter = palmCenter
        radialAnchorScale = scale
        radialEverLeftCancelZone = false

        val totalSlots = config.radialSlotCount.count
        // Initial angle hint from wrist orientation until user moves hand out of center deadzone
        val wristAngle = HandGeometry.wristToPalmAngleDegrees(landmarks)
        val initialSlot = HandGeometry.angleToRadialSlot(wristAngle, -1, totalSlots = totalSlots)
        val wristClearlyTilted = abs(HandGeometry.shortestAngularDistanceDeg(wristAngle, 0f)) > 18f

        activeRadialAngleDeg = wristAngle
        activeRadialSlot = initialSlot
        activeRadialCursorX = 0f
        activeRadialCursorY = 0f
        activeRadialInCancelZone = !wristClearlyTilted
        if (wristClearlyTilted) {
            radialEverLeftCancelZone = true
        }

        events.add(
            GestureEvent.RadialMenuSummoned(
                angleDegrees = wristAngle,
                selectedSlot = initialSlot,
                cursorOffsetX = 0f,
                cursorOffsetY = 0f,
                inCancelZone = activeRadialInCancelZone
            )
        )
    }

    private fun handleVolumeState(
        landmarks: List<Landmark3D>,
        scale: Float,
        posture: HandPosture,
        timestampMs: Long,
        config: AirGestureConfig,
        events: MutableList<GestureEvent>
    ) {
        val stillInVolumePosture = isVolumePostureMatch(
            landmarks = landmarks,
            scale = scale,
            posture = posture,
            preset = config.volumePreset,
            alreadyActive = true
        )

        if (!stillInVolumePosture || isRadialTriggerMatch(posture, config.radialPreset)) {
            volumeExitFrames++
            val requiredExitFrames = if (config.volumePreset == VolumeGesturePreset.PINCH_OK_SLIDE) 1 else 2
            if (volumeExitFrames >= requiredExitFrames) {
                currentMode = Mode.PALM_ACTIVE
                volumeExitFrames = 0
                events.add(GestureEvent.PinchEnded)
            }
            return
        }
        volumeExitFrames = 0

        if (config.volumePreset == VolumeGesturePreset.THUMBS_UP_DOWN) {
            if ((timestampMs - lastThumbStepMs) >= thumbVolumeRepeatIntervalMs) {
                lastThumbStepMs = timestampMs
                val delta = when (posture) {
                    HandPosture.THUMB_UP -> 0.085f
                    HandPosture.THUMB_DOWN -> -0.085f
                    else -> 0f
                }
                if (delta != 0f) {
                    events.add(GestureEvent.PinchVolumeModulate(delta))
                }
            }
            return
        }

        val currentY = volumeTrackY(landmarks, config.volumePreset)
        val deltaUpNormalized = (lastVolumeAnchorY - currentY) / scale
        if (abs(deltaUpNormalized) >= pinchMinDeltaNormalized) {
            lastVolumeAnchorY = currentY
            events.add(GestureEvent.PinchVolumeModulate(deltaUpNormalized.coerceIn(-0.85f, 0.85f)))
        }
    }

    private fun handleCircularAppWheelState(
        landmarks: List<Landmark3D>,
        palmCenter: Landmark3D,
        scale: Float,
        posture: HandPosture,
        config: AirGestureConfig,
        events: MutableList<GestureEvent>
    ) {
        val totalSlots = config.radialSlotCount.count

        // Releasing into Open Palm (>= 4 fingers open) commits or cancels if inside center Safe Cancel Zone
        val releasedIntoOpenHand = when (config.radialPreset) {
            RadialTriggerPreset.CLOSED_FIST -> HandGeometry.countExtendedNonThumbFingers(landmarks, scale) >= 3
            RadialTriggerPreset.I_LOVE_YOU_SIGN -> posture == HandPosture.OPEN_PALM
            RadialTriggerPreset.THREE_FINGERS -> posture == HandPosture.OPEN_PALM || posture == HandPosture.CLOSED_FIST
            RadialTriggerPreset.CALL_ME_SHAKA -> posture == HandPosture.OPEN_PALM
            RadialTriggerPreset.OPEN_PALM_HOLD -> posture == HandPosture.CLOSED_FIST || HandGeometry.countCurledNonThumbFingers(landmarks, scale) >= 3
        }

        if (releasedIntoOpenHand) {
            radialReleaseFrames++
            if (radialReleaseFrames >= 2) {
                currentMode = Mode.PALM_ACTIVE
                radialReleaseFrames = 0
                radialAnchorCenter = null
                if (activeRadialInCancelZone && !radialEverLeftCancelZone) {
                    activeRadialSlot = -1
                    events.add(GestureEvent.RadialMenuCancelled)
                } else {
                    val committed = activeRadialSlot.coerceIn(0, totalSlots - 1)
                    activeRadialSlot = -1
                    events.add(GestureEvent.RadialMenuCommitted(committed))
                }
                return
            }
            return
        }

        radialReleaseFrames = 0

        val anchor = radialAnchorCenter ?: palmCenter
        val joystick = HandGeometry.computeJoystickRadialTarget(
            anchor = anchor,
            currentCenter = palmCenter,
            scale = radialAnchorScale,
            totalSlots = totalSlots,
            previousSlot = activeRadialSlot
        )

        activeRadialCursorX = joystick.cursorNormX
        activeRadialCursorY = joystick.cursorNormY

        if (!joystick.inCancelZone) {
            // Spatial Virtual Joystick moved outward -> effortless 360° selection!
            radialEverLeftCancelZone = true
            activeRadialInCancelZone = false
            activeRadialAngleDeg = joystick.angleDegrees
            activeRadialSlot = joystick.selectedSlot
        } else {
            // Also support direct wrist tilt if user rotates wrist in place without translating hand
            val wristAngle = HandGeometry.wristToPalmAngleDegrees(landmarks)
            val wristTilted = abs(HandGeometry.shortestAngularDistanceDeg(wristAngle, 0f)) > 16f
            if (wristTilted) {
                radialEverLeftCancelZone = true
                activeRadialInCancelZone = false
                activeRadialAngleDeg = wristAngle
                activeRadialSlot = HandGeometry.angleToRadialSlot(wristAngle, activeRadialSlot, totalSlots = totalSlots)
            } else if (!radialEverLeftCancelZone) {
                activeRadialInCancelZone = true
            }
        }

        events.add(
            GestureEvent.RadialMenuUpdated(
                angleDegrees = activeRadialAngleDeg,
                selectedSlot = activeRadialSlot.coerceIn(0, totalSlots - 1),
                cursorOffsetX = activeRadialCursorX,
                cursorOffsetY = activeRadialCursorY,
                inCancelZone = activeRadialInCancelZone
            )
        )
    }

    private fun volumeTrackY(landmarks: List<Landmark3D>, preset: VolumeGesturePreset): Float {
        return when (preset) {
            VolumeGesturePreset.PINCH_OK_SLIDE ->
                (landmarks[HandGeometry.THUMB_TIP].y + landmarks[HandGeometry.INDEX_TIP].y) * 0.5f
            VolumeGesturePreset.VICTORY_SLIDE ->
                (landmarks[HandGeometry.INDEX_TIP].y + landmarks[HandGeometry.MIDDLE_TIP].y) * 0.5f
            VolumeGesturePreset.THUMBS_UP_DOWN ->
                landmarks[HandGeometry.THUMB_TIP].y
            VolumeGesturePreset.FINGER_GUN_SLIDE ->
                landmarks[HandGeometry.INDEX_TIP].y
        }
    }
}
