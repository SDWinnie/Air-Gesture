package com.sdwinnie.airgesture.engine

import com.sdwinnie.airgesture.model.GestureEvent
import com.sdwinnie.airgesture.model.HandFrame
import com.sdwinnie.airgesture.model.Landmark3D
import kotlin.math.abs

/**
 * Intelligent Gesture Finite State Machine for Air Gesture:
 * 1. Open Palm Wake -> Dynamic Island Activation & Idle Sleep
 * 2. Vertical Open-Palm Sweep -> Smooth OS Scroll Up / Down with Return-Stroke Clutch Suppression
 * 3. Horizontal Open-Palm Sweep -> OS Back / Recents / App Switch with Refractory Lock
 * 4. Pinch Gesture (ท่ามือจีบ) -> Schmitt-Trigger Hysteresis Continuous Volume Slider
 * 5. Fist Gesture (ท่ากำมือ) -> 360° 15-App Radial Pie Menu Summon, Angle Tracking & Release-to-Launch
 */
class AirGestureStateMachine(
    private val palmWakeFramesRequired: Int = 3,
    private val islandSleepTimeoutMs: Long = 3000L,
    private val fistHoldActivationMs: Long = 120L,
    private val pinchEnterThreshold: Float = 0.28f,
    private val pinchExitThreshold: Float = 0.40f,
    private val pinchMinDeltaNormalized: Float = 0.018f,
    private val verticalSweepSpeedThreshold: Float = 1.55f,
    private val horizontalSweepSpeedThreshold: Float = 2.05f,
    private val scrollDispatchCooldownMs: Long = 135L,
    private val scrollOppositeClutchLockMs: Long = 340L,
    private val horizontalNavCooldownMs: Long = 560L
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

    private val filterBank = LandmarkBankOneEuroFilter()

    private var consecutivePalmFrames = 0
    private var lastHandSeenMs = -1L

    // Palm motion history for velocity-normalized directional sweeps
    private var prevPalmCenter: Landmark3D? = null
    private var prevPalmTimestampMs: Long = -1L
    private var smoothedVxNormalized = 0f
    private var smoothedVyNormalized = 0f

    // Scroll & Horizontal navigation debounce / return-stroke clutch protection
    private var lastScrollDispatchMs = -10_000L
    private var lastScrollWasUp: Boolean? = null
    private var lastHorizontalDispatchMs = -10_000L

    // Pinch state (ท่ามือจีบ)
    private var consecutivePinchFrames = 0
    private var lastPinchAnchorY = 0f

    // Fist state (ท่ากำมือ)
    private var fistCandidateStartMs = -1L
    private var fistReleaseFrames = 0

    fun reset() {
        currentMode = Mode.DORMANT
        activeRadialSlot = -1
        activeRadialAngleDeg = 0f
        filterBank.reset()
        consecutivePalmFrames = 0
        lastHandSeenMs = -1L
        prevPalmCenter = null
        prevPalmTimestampMs = -1L
        smoothedVxNormalized = 0f
        smoothedVyNormalized = 0f
        lastScrollDispatchMs = -10_000L
        lastScrollWasUp = null
        lastHorizontalDispatchMs = -10_000L
        consecutivePinchFrames = 0
        fistCandidateStartMs = -1L
        fistReleaseFrames = 0
    }

    data class StepOutput(
        val mode: Mode,
        val smoothedFrame: HandFrame?,
        val events: List<GestureEvent>
    )

    fun process(rawFrame: HandFrame?, nowMs: Long): StepOutput {
        val events = mutableListOf<GestureEvent>()

        if (rawFrame == null) {
            consecutivePalmFrames = 0
            consecutivePinchFrames = 0
            fistCandidateStartMs = -1L
            prevPalmCenter = null
            prevPalmTimestampMs = -1L
            smoothedVxNormalized = 0f
            smoothedVyNormalized = 0f

            if (currentMode == Mode.PINCH_VOLUME) {
                events.add(GestureEvent.PinchEnded)
                currentMode = Mode.PALM_ACTIVE
            } else if (currentMode == Mode.FIST_RADIAL_MENU) {
                events.add(GestureEvent.RadialMenuCancelled)
                activeRadialSlot = -1
                currentMode = Mode.PALM_ACTIVE
            }

            if (currentMode != Mode.DORMANT && lastHandSeenMs > 0L && (nowMs - lastHandSeenMs) >= islandSleepTimeoutMs) {
                currentMode = Mode.DORMANT
                events.add(GestureEvent.SleepDynamicIsland)
            }
            return StepOutput(currentMode, null, events)
        }

        lastHandSeenMs = nowMs
        val frame = filterBank.filter(rawFrame)
        val landmarks = frame.landmarks
        val scale = HandGeometry.palmScale(landmarks)
        val palmCenter = HandGeometry.palmCentroid(landmarks)

        updateNormalizedVelocity(palmCenter, scale, frame.timestampMs)

        when (currentMode) {
            Mode.DORMANT -> {
                handleDormantState(landmarks, scale, frame.timestampMs, events)
            }
            Mode.PALM_ACTIVE -> {
                handlePalmActiveState(landmarks, scale, frame.timestampMs, events)
            }
            Mode.PINCH_VOLUME -> {
                handlePinchVolumeState(landmarks, scale, events)
            }
            Mode.FIST_RADIAL_MENU -> {
                handleFistRadialMenuState(landmarks, scale, events)
            }
        }

        return StepOutput(currentMode, frame, events)
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
                    0.58f * instVx + 0.42f * smoothedVxNormalized
                }
                smoothedVyNormalized = if (instVy * smoothedVyNormalized < 0f) {
                    0.65f * instVy
                } else {
                    0.58f * instVy + 0.42f * smoothedVyNormalized
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
        scale: Float,
        timestampMs: Long,
        events: MutableList<GestureEvent>
    ) {
        if (HandGeometry.isOpenPalm(landmarks, scale)) {
            consecutivePalmFrames++
            if (consecutivePalmFrames >= palmWakeFramesRequired) {
                currentMode = Mode.PALM_ACTIVE
                consecutivePalmFrames = 0
                events.add(GestureEvent.WakeDynamicIsland)
            }
        } else if (HandGeometry.isFullFist(landmarks, scale)) {
            // Allow waking straight into Radial Menu if user holds deliberate fist
            if (fistCandidateStartMs < 0L) {
                fistCandidateStartMs = timestampMs
            } else if ((timestampMs - fistCandidateStartMs) >= fistHoldActivationMs) {
                currentMode = Mode.FIST_RADIAL_MENU
                fistCandidateStartMs = -1L
                fistReleaseFrames = 0
                events.add(GestureEvent.WakeDynamicIsland)
                val angle = HandGeometry.wristToPalmAngleDegrees(landmarks)
                val slot = HandGeometry.angleToRadialSlot(angle, -1)
                activeRadialAngleDeg = angle
                activeRadialSlot = slot
                events.add(GestureEvent.RadialMenuSummoned(angle, slot))
            }
        } else {
            consecutivePalmFrames = 0
            fistCandidateStartMs = -1L
        }
    }

    private fun handlePalmActiveState(
        landmarks: List<Landmark3D>,
        scale: Float,
        timestampMs: Long,
        events: MutableList<GestureEvent>
    ) {
        // 1. Check Fist Gesture (ท่ากำมือ -> 15-App Radial Selector)
        if (HandGeometry.isFullFist(landmarks, scale)) {
            consecutivePinchFrames = 0
            if (fistCandidateStartMs < 0L) {
                fistCandidateStartMs = timestampMs
            } else if ((timestampMs - fistCandidateStartMs) >= fistHoldActivationMs) {
                currentMode = Mode.FIST_RADIAL_MENU
                fistCandidateStartMs = -1L
                fistReleaseFrames = 0
                val angle = HandGeometry.wristToPalmAngleDegrees(landmarks)
                val slot = HandGeometry.angleToRadialSlot(angle, -1)
                activeRadialAngleDeg = angle
                activeRadialSlot = slot
                events.add(GestureEvent.RadialMenuSummoned(angle, slot))
                return
            }
            return
        } else {
            fistCandidateStartMs = -1L
        }

        // 2. Check Pinch Gesture (ท่ามือจีบ -> Volume Control)
        if (HandGeometry.isPinchCandidate(landmarks, pinchEnterThreshold, scale)) {
            consecutivePinchFrames++
            if (consecutivePinchFrames >= 2) {
                currentMode = Mode.PINCH_VOLUME
                consecutivePinchFrames = 0
                lastPinchAnchorY = pinchMidpointY(landmarks)
                // Emit initial 0-delta modulate so HUD immediately transitions into Volume Pill Slider
                events.add(GestureEvent.PinchVolumeModulate(0f))
                return
            }
            return
        } else {
            consecutivePinchFrames = 0
        }

        // 3. Open Palm Directional Sweeps (Vertical Scroll & Horizontal Back/Switch)
        if (!HandGeometry.isOpenPalm(landmarks, scale)) {
            return
        }

        val absVx = abs(smoothedVxNormalized)
        val absVy = abs(smoothedVyNormalized)

        // Horizontal Sweep: Back / Close / Switch Tasks
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

        // Vertical Sweep: Smooth Native OS Scroll Up / Scroll Down
        if (absVy >= verticalSweepSpeedThreshold && absVy > absVx * 1.60f) {
            if ((timestampMs - lastScrollDispatchMs) >= scrollDispatchCooldownMs) {
                // Screen Y grows downward: hand sweeping UP (vy < 0) scrolls content DOWN (finger swipe up),
                // or natural directional scroll: hand UP = Scroll Down page / swipe up, hand DOWN = Scroll Up page.
                // Here scrollUp=true means hand sweeps UPWARD (scrolling feed forward/upward stroke).
                val handMovingUp = smoothedVyNormalized < 0f

                // Return-Stroke Clutch Protection: ignore immediate reverse stroke within clutch window
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

    private fun handlePinchVolumeState(
        landmarks: List<Landmark3D>,
        scale: Float,
        events: MutableList<GestureEvent>
    ) {
        val pinchDist = HandGeometry.pinchNormalizedDistance(landmarks, scale)
        // Schmitt-trigger exit hysteresis prevents chatter while sliding volume
        if (pinchDist > pinchExitThreshold || HandGeometry.isFullFist(landmarks, scale)) {
            currentMode = Mode.PALM_ACTIVE
            events.add(GestureEvent.PinchEnded)
            return
        }

        val currentPinchY = pinchMidpointY(landmarks)
        // Screen Y increases downward: moving pinched hand UP (lastY - currentY > 0) INCREASES volume (+delta)
        val deltaUpNormalized = (lastPinchAnchorY - currentPinchY) / scale
        if (abs(deltaUpNormalized) >= pinchMinDeltaNormalized) {
            lastPinchAnchorY = currentPinchY
            events.add(GestureEvent.PinchVolumeModulate(deltaUpNormalized.coerceIn(-0.85f, 0.85f)))
        }
    }

    private fun handleFistRadialMenuState(
        landmarks: List<Landmark3D>,
        scale: Float,
        events: MutableList<GestureEvent>
    ) {
        val extendedCount = HandGeometry.countExtendedNonThumbFingers(landmarks, scale)
        // Releasing the fist (>= 3 fingers extended into open hand) launches the focused app!
        if (extendedCount >= 3) {
            fistReleaseFrames++
            if (fistReleaseFrames >= 2) {
                val committedSlot = activeRadialSlot.coerceIn(0, HandGeometry.RADIAL_SLOTS - 1)
                currentMode = Mode.PALM_ACTIVE
                activeRadialSlot = -1
                fistReleaseFrames = 0
                events.add(GestureEvent.RadialMenuCommitted(committedSlot))
                return
            }
            return
        }

        fistReleaseFrames = 0
        // Keep updating focused 15-slice segment while fist remains closed
        if (HandGeometry.countCurledNonThumbFingers(landmarks, scale) >= 3) {
            val angle = HandGeometry.wristToPalmAngleDegrees(landmarks)
            val newSlot = HandGeometry.angleToRadialSlot(angle, activeRadialSlot)
            activeRadialAngleDeg = angle
            activeRadialSlot = newSlot
            events.add(GestureEvent.RadialMenuUpdated(angle, newSlot))
        }
    }

    private fun pinchMidpointY(landmarks: List<Landmark3D>): Float {
        return (landmarks[HandGeometry.THUMB_TIP].y + landmarks[HandGeometry.INDEX_TIP].y) * 0.5f
    }
}
