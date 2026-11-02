package com.sdwinnie.airgesture.engine

import com.sdwinnie.airgesture.model.AirGestureConfig
import com.sdwinnie.airgesture.model.GestureEvent
import com.sdwinnie.airgesture.model.HandFrame
import com.sdwinnie.airgesture.model.HandLockMode
import com.sdwinnie.airgesture.model.HandPosture
import com.sdwinnie.airgesture.model.HandSide
import com.sdwinnie.airgesture.model.Landmark3D
import com.sdwinnie.airgesture.model.RadialSlotCount
import com.sdwinnie.airgesture.model.SkeletonBoneDensity
import com.sdwinnie.airgesture.model.VolumeGesturePreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class AirGestureEngineTest {

    private fun makeOpenPalm(
        cx: Float = 0.5f,
        cy: Float = 0.6f,
        scale: Float = 0.14f,
        angleDeg: Float = 0f,
        timestampMs: Long = 100L,
        handSide: HandSide = HandSide.RIGHT
    ): HandFrame {
        val rad = Math.toRadians(angleDeg.toDouble())
        val sinA = sin(rad).toFloat()
        val cosA = cos(rad).toFloat()

        fun pt(localX: Float, localY: Float): Landmark3D {
            val rx = localX * cosA + localY * sinA
            val ry = -localX * sinA + localY * cosA
            return Landmark3D(
                x = cx + rx * scale,
                y = cy - ry * scale,
                z = 0f
            )
        }

        val pts = ArrayList<Landmark3D>(21)
        pts.add(pt(0.0f, 0.0f)) // 0: WRIST
        pts.add(pt(-0.32f, 0.22f))
        pts.add(pt(-0.55f, 0.48f))
        pts.add(pt(-0.74f, 0.75f))
        pts.add(pt(-0.92f, 1.02f)) // 4: THUMB_TIP

        // Index (5..8)
        pts.add(pt(-0.35f, 0.95f))
        pts.add(pt(-0.38f, 1.35f))
        pts.add(pt(-0.40f, 1.65f))
        pts.add(pt(-0.42f, 1.92f))

        // Middle (9..12)
        pts.add(pt(0.0f, 1.00f))
        pts.add(pt(0.0f, 1.42f))
        pts.add(pt(0.0f, 1.75f))
        pts.add(pt(0.0f, 2.04f))

        // Ring (13..16)
        pts.add(pt(0.32f, 0.94f))
        pts.add(pt(0.34f, 1.34f))
        pts.add(pt(0.36f, 1.62f))
        pts.add(pt(0.38f, 1.88f))

        // Pinky (17..20)
        pts.add(pt(0.58f, 0.84f))
        pts.add(pt(0.62f, 1.18f))
        pts.add(pt(0.65f, 1.42f))
        pts.add(pt(0.68f, 1.64f))

        return HandFrame(pts, timestampMs, confidence = 0.92f, handSide = handSide)
    }

    private fun makePinchHand(
        cx: Float = 0.5f,
        cy: Float = 0.55f,
        scale: Float = 0.14f,
        pinchGapNorm: Float = 0.12f,
        timestampMs: Long = 100L
    ): HandFrame {
        val base = makeOpenPalm(cx = cx, cy = cy, scale = scale, timestampMs = timestampMs).landmarks.toMutableList()
        val meetX = cx - 0.36f * scale
        val meetY = cy - 1.35f * scale
        base[HandGeometry.THUMB_TIP] = Landmark3D(meetX - pinchGapNorm * 0.5f * scale, meetY, 0f)
        base[HandGeometry.INDEX_TIP] = Landmark3D(meetX + pinchGapNorm * 0.5f * scale, meetY, 0f)
        return HandFrame(base, timestampMs, confidence = 0.92f, handSide = HandSide.RIGHT)
    }

    private fun makeFistHand(
        cx: Float = 0.5f,
        cy: Float = 0.6f,
        scale: Float = 0.14f,
        angleDeg: Float = 0f,
        timestampMs: Long = 100L
    ): HandFrame {
        val rad = Math.toRadians(angleDeg.toDouble())
        val sinA = sin(rad).toFloat()
        val cosA = cos(rad).toFloat()

        fun pt(localX: Float, localY: Float): Landmark3D {
            val rx = localX * cosA + localY * sinA
            val ry = -localX * sinA + localY * cosA
            return Landmark3D(cx + rx * scale, cy - ry * scale, 0f)
        }

        val pts = ArrayList<Landmark3D>(21)
        pts.add(pt(0.0f, 0.0f)) // 0: WRIST
        pts.add(pt(-0.28f, 0.25f))
        pts.add(pt(-0.38f, 0.55f))
        pts.add(pt(-0.22f, 0.78f))
        pts.add(pt(-0.08f, 0.82f)) // 4: Thumb tucked across curled fingers

        val xOffsets = floatArrayOf(-0.34f, 0.0f, 0.32f, 0.56f)
        val mcpYs = floatArrayOf(0.95f, 1.00f, 0.94f, 0.84f)
        for (f in 0 until 4) {
            val xo = xOffsets[f]
            val my = mcpYs[f]
            pts.add(pt(xo, my))
            pts.add(pt(xo, my + 0.18f))
            pts.add(pt(xo, my - 0.05f))
            pts.add(pt(xo, my - 0.22f))
        }

        return HandFrame(pts, timestampMs, confidence = 0.92f, handSide = HandSide.RIGHT)
    }

    private fun makeThumbHand(
        thumbUp: Boolean,
        cx: Float = 0.5f,
        cy: Float = 0.6f,
        scale: Float = 0.14f,
        timestampMs: Long = 100L
    ): HandFrame {
        val fist = makeFistHand(cx = cx, cy = cy, scale = scale, timestampMs = timestampMs).landmarks.toMutableList()
        val dir = if (thumbUp) -1f else 1f
        fist[HandGeometry.THUMB_MCP] = Landmark3D(cx - 0.35f * scale, cy - 0.60f * scale, 0f)
        fist[HandGeometry.THUMB_IP] = Landmark3D(cx - 0.35f * scale, cy - 0.60f * scale + dir * 0.45f * scale, 0f)
        fist[HandGeometry.THUMB_TIP] = Landmark3D(cx - 0.35f * scale, cy - 0.60f * scale + dir * 0.90f * scale, 0f)
        return HandFrame(fist, timestampMs, confidence = 0.94f, handSide = HandSide.RIGHT)
    }

    private fun makeVictoryHand(
        cx: Float = 0.5f,
        cy: Float = 0.6f,
        scale: Float = 0.14f,
        timestampMs: Long = 100L
    ): HandFrame {
        val open = makeOpenPalm(cx = cx, cy = cy, scale = scale, timestampMs = timestampMs).landmarks
        val fist = makeFistHand(cx = cx, cy = cy, scale = scale, timestampMs = timestampMs).landmarks.toMutableList()
        // Extend Index (5..8) and Middle (9..12); keep Ring & Pinky curled
        for (idx in 5..12) {
            fist[idx] = open[idx]
        }
        return HandFrame(fist, timestampMs, confidence = 0.93f, handSide = HandSide.RIGHT)
    }

    @Test
    fun `distance normalization and anatomical anti-ghost plausibility work reliably`() {
        val nearPalm = makeOpenPalm(scale = 0.28f)
        val farPalm = makeOpenPalm(scale = 0.06f)
        val tinyGhostNoise = makeOpenPalm(scale = 0.02f)

        assertTrue(HandGeometry.isAnatomicallyPlausibleHand(nearPalm.landmarks))
        assertTrue(HandGeometry.isAnatomicallyPlausibleHand(farPalm.landmarks))
        assertFalse("Tiny background phantom hand must be rejected", HandGeometry.isAnatomicallyPlausibleHand(tinyGhostNoise.landmarks))

        assertTrue(HandGeometry.isOpenPalm(nearPalm.landmarks))
        assertTrue(HandGeometry.isOpenPalm(farPalm.landmarks))
        assertTrue(HandGeometry.isFullFist(makeFistHand(scale = 0.28f).landmarks))
        assertTrue(HandGeometry.isFullFist(makeFistHand(scale = 0.06f).landmarks))
    }

    @Test
    fun `single-hand lock tracker strictly rejects second hand and cross-screen jumps`() {
        val tracker = SingleHandLockTracker()
        val rightHand = makeOpenPalm(cx = 0.45f, cy = 0.55f, timestampMs = 100L, handSide = HandSide.RIGHT)
        val intrudingLeftHand = makeOpenPalm(cx = 0.50f, cy = 0.55f, timestampMs = 130L, handSide = HandSide.LEFT)
        val teleportedSecondRight = makeOpenPalm(cx = 0.92f, cy = 0.15f, timestampMs = 150L, handSide = HandSide.RIGHT)

        assertNotNull(tracker.filterSingleHand(rightHand, 100L, HandLockMode.AUTO_SINGLE))
        assertEquals(HandSide.RIGHT, tracker.lockedSide)

        // Second opposite hand must be blocked!
        assertNull(tracker.filterSingleHand(intrudingLeftHand, 130L, HandLockMode.AUTO_SINGLE))
        // Instantaneous teleport > 0.38 across screen must be blocked!
        assertNull(tracker.filterSingleHand(teleportedSecondRight, 150L, HandLockMode.AUTO_SINGLE))

        // Explicit LEFT_ONLY mode rejects right hand immediately
        tracker.reset()
        assertNull(tracker.filterSingleHand(rightHand, 200L, HandLockMode.LEFT_ONLY))
        assertNotNull(tracker.filterSingleHand(intrudingLeftHand, 220L, HandLockMode.LEFT_ONLY))
    }

    @Test
    fun `canonical human postures classify without cross-confusion`() {
        assertEquals(HandPosture.OPEN_PALM, HandGeometry.classifyPosture(makeOpenPalm().landmarks))
        assertEquals(HandPosture.PINCH_OK, HandGeometry.classifyPosture(makePinchHand().landmarks))
        assertEquals(HandPosture.CLOSED_FIST, HandGeometry.classifyPosture(makeFistHand().landmarks))
        assertEquals(HandPosture.THUMB_UP, HandGeometry.classifyPosture(makeThumbHand(thumbUp = true).landmarks))
        assertEquals(HandPosture.THUMB_DOWN, HandGeometry.classifyPosture(makeThumbHand(thumbUp = false).landmarks))
        assertEquals(HandPosture.VICTORY_PEACE, HandGeometry.classifyPosture(makeVictoryHand().landmarks))
        assertFalse("Thumbs-Up must never misfire as Closed Fist", HandGeometry.isFullFist(makeThumbHand(thumbUp = true).landmarks))
    }

    @Test
    fun `vertical sweep emits scroll and suppresses immediate return-stroke clutch`() {
        val fsm = AirGestureStateMachine()
        fsm.process(makeOpenPalm(cy = 0.65f, timestampMs = 100L), 100L)
        fsm.process(makeOpenPalm(cy = 0.65f, timestampMs = 120L), 120L)
        fsm.process(makeOpenPalm(cy = 0.65f, timestampMs = 140L), 140L)

        val upStep1 = fsm.process(makeOpenPalm(cy = 0.54f, timestampMs = 170L), 170L)
        val upStep2 = fsm.process(makeOpenPalm(cy = 0.42f, timestampMs = 200L), 200L)

        val scrollEvents = (upStep1.events + upStep2.events).filterIsInstance<GestureEvent.VerticalScroll>()
        assertTrue(scrollEvents.isNotEmpty())
        assertTrue(scrollEvents.first().scrollUp)

        val ret1 = fsm.process(makeOpenPalm(cy = 0.54f, timestampMs = 315L), 315L)
        val ret2 = fsm.process(makeOpenPalm(cy = 0.66f, timestampMs = 340L), 340L)
        val unintendedReverse = (ret1.events + ret2.events).filterIsInstance<GestureEvent.VerticalScroll>()
        assertTrue(unintendedReverse.isEmpty())
    }

    @Test
    fun `pinch and thumbs-up-down volume presets both modulate volume accurately`() {
        val fsm = AirGestureStateMachine()
        fsm.process(makeOpenPalm(timestampMs = 100L), 100L)
        fsm.process(makeOpenPalm(timestampMs = 120L), 120L)
        fsm.process(makeOpenPalm(timestampMs = 140L), 140L)

        // 1. Pinch Slide Mode
        fsm.process(makePinchHand(cy = 0.60f, pinchGapNorm = 0.14f, timestampMs = 165L), 165L)
        fsm.process(makePinchHand(cy = 0.60f, pinchGapNorm = 0.14f, timestampMs = 182L), 182L)
        val enterStep = fsm.process(makePinchHand(cy = 0.60f, pinchGapNorm = 0.14f, timestampMs = 200L), 200L)
        assertEquals(AirGestureStateMachine.Mode.PINCH_VOLUME, enterStep.mode)

        // Slide pinched hand DOWN (cy: 0.60 -> 0.70) -> negative volume delta (Volume Down!)
        val slideDown = fsm.process(makePinchHand(cy = 0.70f, pinchGapNorm = 0.32f, timestampMs = 235L), 235L)
        val downEvt = slideDown.events.filterIsInstance<GestureEvent.PinchVolumeModulate>().firstOrNull()
        assertTrue("Expected negative volume delta when lowering hand", downEvt != null && downEvt.normalizedDeltaY < 0f)

        // 2. Ergonomic Thumbs-Up / Thumbs-Down Volume Preset
        val thumbCfg = AirGestureConfig(volumePreset = VolumeGesturePreset.THUMBS_UP_DOWN)
        val fsm2 = AirGestureStateMachine()
        fsm2.process(makeOpenPalm(timestampMs = 100L), 100L, thumbCfg)
        fsm2.process(makeOpenPalm(timestampMs = 120L), 120L, thumbCfg)
        fsm2.process(makeOpenPalm(timestampMs = 140L), 140L, thumbCfg)

        val t1 = fsm2.process(makeThumbHand(thumbUp = false, timestampMs = 170L), 170L, thumbCfg)
        val t2 = fsm2.process(makeThumbHand(thumbUp = false, timestampMs = 190L), 190L, thumbCfg)
        val thumbDownStep = fsm2.process(makeThumbHand(thumbUp = false, timestampMs = 210L), 210L, thumbCfg)
        assertEquals(AirGestureStateMachine.Mode.PINCH_VOLUME, thumbDownStep.mode)
        val entryVolEvt = (t1.events + t2.events + thumbDownStep.events)
            .filterIsInstance<GestureEvent.PinchVolumeModulate>()
            .firstOrNull()
        assertTrue("Thumbs-Down must emit Volume Down (-delta) on entry", entryVolEvt != null && entryVolEvt.normalizedDeltaY < 0f)

        // Holding Thumbs-Down past repeat interval emits another continuous Volume Down step
        val repeatStep = fsm2.process(makeThumbHand(thumbUp = false, timestampMs = 365L), 365L, thumbCfg)
        val repeatEvt = repeatStep.events.filterIsInstance<GestureEvent.PinchVolumeModulate>().firstOrNull()
        assertTrue("Holding Thumbs-Down must repeat Volume Down step", repeatEvt != null && repeatEvt.normalizedDeltaY < 0f)
    }

    @Test
    fun `spatial virtual joystick selects circular apps effortlessly and cancels in center safe zone`() {
        val cfg8 = AirGestureConfig(radialSlotCount = RadialSlotCount.SLOTS_8)
        val fsm = AirGestureStateMachine()
        fsm.process(makeOpenPalm(cx = 0.50f, cy = 0.55f, timestampMs = 100L), 100L, cfg8)
        fsm.process(makeOpenPalm(cx = 0.50f, cy = 0.55f, timestampMs = 120L), 120L, cfg8)
        fsm.process(makeOpenPalm(cx = 0.50f, cy = 0.55f, timestampMs = 140L), 140L, cfg8)

        // Hold upright Fist at center (0.50, 0.55) -> summons wheel inside Center Safe Cancel Zone!
        fsm.process(makeFistHand(cx = 0.50f, cy = 0.55f, angleDeg = 0f, timestampMs = 170L), 170L, cfg8)
        val summon = fsm.process(makeFistHand(cx = 0.50f, cy = 0.55f, angleDeg = 0f, timestampMs = 340L), 340L, cfg8)
        assertEquals(AirGestureStateMachine.Mode.FIST_RADIAL_MENU, summon.mode)
        assertTrue(fsm.activeRadialInCancelZone)

        // Move fist slightly RIGHT (cx: 0.50 -> 0.62, 90° = 3 o'clock = Slot #2 in an 8-slot wheel!) without twisting wrist!
        fsm.process(makeFistHand(cx = 0.62f, cy = 0.55f, angleDeg = 0f, timestampMs = 380L), 380L, cfg8)
        val aimed = fsm.process(makeFistHand(cx = 0.62f, cy = 0.55f, angleDeg = 0f, timestampMs = 410L), 410L, cfg8)
        assertFalse(fsm.activeRadialInCancelZone)
        val upd = aimed.events.filterIsInstance<GestureEvent.RadialMenuUpdated>().last()
        assertEquals(2, upd.selectedSlot) // 90° / 45° = Slot 2

        // Open hand -> launches Slot 2!
        fsm.process(makeOpenPalm(cx = 0.62f, cy = 0.55f, timestampMs = 440L), 440L, cfg8)
        val commit = fsm.process(makeOpenPalm(cx = 0.62f, cy = 0.55f, timestampMs = 470L), 470L, cfg8)
        val committed = commit.events.filterIsInstance<GestureEvent.RadialMenuCommitted>().first()
        assertEquals(2, committed.selectedSlot)
    }

    @Test
    fun `hand skeleton analyzer extracts 20, 36, and 48 bones, palm normal, and detects touchless air tap`() {
        val palm = makeOpenPalm(timestampMs = 100L)
        val skeleton20 = HandSkeletonAnalyzer.analyze(palm.landmarks, 100L, density = SkeletonBoneDensity.BONES_20)
        assertEquals(23, skeleton20.bones.size)

        val skeleton36 = HandSkeletonAnalyzer.analyze(palm.landmarks, 100L, density = SkeletonBoneDensity.BONES_36)
        assertEquals(36, skeleton36.bones.size)

        val skeleton48 = HandSkeletonAnalyzer.analyze(palm.landmarks, 100L, density = SkeletonBoneDensity.BONES_48)
        assertEquals(48, skeleton48.bones.size)

        assertTrue("Open palm must face camera", skeleton48.isPalmFacingCamera)
        assertTrue("Index finger must be extended in open palm", skeleton48.fingerFlexion[1] > 0.60f)

        // Simulate an index fingertip forward knock in Z (Z plunges from 0f to -0.12f relative to palm)
        HandSkeletonAnalyzer.reset()
        val baseHand = makeOpenPalm(timestampMs = 100L).landmarks.toMutableList()
        val s1 = HandSkeletonAnalyzer.analyze(baseHand, 100L)
        assertFalse(s1.isAirTapDetected)

        // Rapid forward plunge 120ms later
        val tapHand = makeOpenPalm(timestampMs = 220L).landmarks.toMutableList()
        tapHand[HandGeometry.INDEX_TIP] = Landmark3D(
            x = tapHand[HandGeometry.INDEX_TIP].x,
            y = tapHand[HandGeometry.INDEX_TIP].y - 0.04f,
            z = -0.12f
        )
        val s2 = HandSkeletonAnalyzer.analyze(tapHand, 220L)
        assertTrue("Index forward knock must trigger touchless Air Tap", s2.isAirTapDetected)
    }

    @Test
    fun `eye gaze blink analyzer executes 1 blink scroll down, 2 blink scroll up, and 3 blink send heart`() {
        val analyzer = EyeGazeBlinkAnalyzer(baselineEar = 0.32f)
        val cfg = AirGestureConfig(
            enableEyeContactBlink = true,
            requireEyeContact = true,
            singleBlinkAction = com.sdwinnie.airgesture.model.BlinkAction.SCROLL_DOWN,
            doubleBlinkAction = com.sdwinnie.airgesture.model.BlinkAction.SCROLL_UP,
            tripleBlinkAction = com.sdwinnie.airgesture.model.BlinkAction.SEND_HEART_LIKE,
            blinkSpeedWindowMs = 500L
        )

        // Helper to simulate a complete eye blink cycle (open -> closed for 150ms -> open)
        fun simulateSingleBlink(startMs: Long, isLookingAtScreen: Boolean = true): EyeGazeBlinkAnalyzer.AnalysisResult {
            val yaw = if (isLookingAtScreen) 0f else 35f // 35 deg = looking away!
            // Baseline open eye
            analyzer.process(EyeGazeBlinkAnalyzer.EyeFrameData(leftEar = 0.32f, rightEar = 0.32f, gazeYawDeg = yaw), startMs, cfg)
            // Eye closed (EAR 0.10)
            analyzer.process(EyeGazeBlinkAnalyzer.EyeFrameData(leftEar = 0.10f, rightEar = 0.10f, gazeYawDeg = yaw), startMs + 40L, cfg)
            // Eye reopened (EAR 0.32) 150ms later
            return analyzer.process(EyeGazeBlinkAnalyzer.EyeFrameData(leftEar = 0.32f, rightEar = 0.32f, gazeYawDeg = yaw), startMs + 190L, cfg)
        }

        // Test 1: Single Blink -> Scroll Down
        analyzer.reset()
        simulateSingleBlink(100L)
        // Advance time past the 500ms window
        val res1 = analyzer.process(EyeGazeBlinkAnalyzer.EyeFrameData(leftEar = 0.32f, rightEar = 0.32f), 800L, cfg)
        val blink1 = res1.events.filterIsInstance<GestureEvent.EyeBlinkTriggered>().firstOrNull()
        assertNotNull("1 Blink must trigger EyeBlinkTriggered event", blink1)
        assertEquals(1, blink1?.blinkCount)
        assertEquals(com.sdwinnie.airgesture.model.BlinkAction.SCROLL_DOWN, blink1?.action)

        // Test 2: Double Blink -> Scroll Up
        analyzer.reset()
        simulateSingleBlink(1000L)
        simulateSingleBlink(1300L) // 2nd blink 300ms later within window
        val res2 = analyzer.process(EyeGazeBlinkAnalyzer.EyeFrameData(leftEar = 0.32f, rightEar = 0.32f), 2000L, cfg)
        val blink2 = res2.events.filterIsInstance<GestureEvent.EyeBlinkTriggered>().firstOrNull()
        assertNotNull("2 Blinks must trigger EyeBlinkTriggered event", blink2)
        assertEquals(2, blink2?.blinkCount)
        assertEquals(com.sdwinnie.airgesture.model.BlinkAction.SCROLL_UP, blink2?.action)

        // Test 3: Triple Blink -> Send Heart / Like (fires immediately on 3rd blink!)
        analyzer.reset()
        simulateSingleBlink(3000L)
        simulateSingleBlink(3300L)
        val res3 = simulateSingleBlink(3600L)
        val blink3 = res3.events.filterIsInstance<GestureEvent.EyeBlinkTriggered>().firstOrNull()
        assertNotNull("3 Blinks must trigger Heart Like", blink3)
        assertEquals(3, blink3?.blinkCount)
        assertEquals(com.sdwinnie.airgesture.model.BlinkAction.SEND_HEART_LIKE, blink3?.action)

        // Test 4: Looking Away (Eye contact check) -> Ignored!
        analyzer.reset()
        simulateSingleBlink(5000L, isLookingAtScreen = false)
        val res4 = analyzer.process(EyeGazeBlinkAnalyzer.EyeFrameData(leftEar = 0.32f, rightEar = 0.32f), 5800L, cfg)
        val blinkIgnored = res4.events.filterIsInstance<GestureEvent.EyeBlinkTriggered>().firstOrNull()
        assertNull("Blink while looking away must be ignored", blinkIgnored)
    }

    @Test
    fun `balanced vision calibrator normalizes landmarks with 1-euro spatial filter`() {
        val calibrator = com.sdwinnie.airgesture.vision.BalancedVisionCalibrator()
        val raw = makeOpenPalm(timestampMs = 100L).landmarks
        val balanced = calibrator.calibrateLandmarks(
            raw = raw,
            timestampMs = 100L,
            mode = com.sdwinnie.airgesture.model.VisionBalanceMode.ISOTROPIC_CENTER_WEIGHTED
        )

        assertEquals(21, balanced.size)
        for (pt in balanced) {
            assertTrue("X must be inside normalized bounds", pt.x in 0.0f..1.0f)
            assertTrue("Y must be inside normalized bounds", pt.y in 0.0f..1.0f)
        }
    }
}
