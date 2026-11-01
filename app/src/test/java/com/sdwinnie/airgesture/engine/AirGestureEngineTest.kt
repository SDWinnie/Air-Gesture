package com.sdwinnie.airgesture.engine

import com.sdwinnie.airgesture.model.GestureEvent
import com.sdwinnie.airgesture.model.HandFrame
import com.sdwinnie.airgesture.model.Landmark3D
import com.sdwinnie.airgesture.vision.RoiTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class AirGestureEngineTest {

    /**
     * Synthesizes a realistic 21-landmark hand centered at (cx, cy) with palm scale [scale],
     * rotated clockwise from 12 o'clock by [angleDeg].
     */
    private fun makeOpenPalm(
        cx: Float = 0.5f,
        cy: Float = 0.6f,
        scale: Float = 0.14f,
        angleDeg: Float = 0f,
        timestampMs: Long = 100L
    ): HandFrame {
        val rad = Math.toRadians(angleDeg.toDouble())
        val sinA = sin(rad).toFloat()
        val cosA = cos(rad).toFloat()

        // Local coordinates where +localX = right, +localY = UP (screen Y = cy - rotatedY)
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
        pts.add(pt(-0.32f, 0.22f)) // 1: THUMB_CMC
        pts.add(pt(-0.55f, 0.48f)) // 2: THUMB_MCP
        pts.add(pt(-0.74f, 0.75f)) // 3: THUMB_IP
        pts.add(pt(-0.92f, 1.02f)) // 4: THUMB_TIP

        // Index (5..8)
        pts.add(pt(-0.35f, 0.95f))
        pts.add(pt(-0.38f, 1.35f))
        pts.add(pt(-0.40f, 1.65f))
        pts.add(pt(-0.42f, 1.92f))

        // Middle (9..12) -> dist(0, 9) == 1.0 * scale
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

        return HandFrame(pts, timestampMs)
    }

    private fun makePinchHand(
        cx: Float = 0.5f,
        cy: Float = 0.55f,
        scale: Float = 0.14f,
        pinchGapNorm: Float = 0.12f,
        timestampMs: Long = 100L
    ): HandFrame {
        val base = makeOpenPalm(cx = cx, cy = cy, scale = scale, timestampMs = timestampMs).landmarks.toMutableList()
        // Bring thumb tip (4) and index tip (8) together forward/above index MCP while keeping middle/ring/pinky extended
        val meetX = cx - 0.36f * scale
        val meetY = cy - 1.35f * scale
        base[HandGeometry.THUMB_TIP] = Landmark3D(meetX - pinchGapNorm * 0.5f * scale, meetY, 0f)
        base[HandGeometry.INDEX_TIP] = Landmark3D(meetX + pinchGapNorm * 0.5f * scale, meetY, 0f)
        return HandFrame(base, timestampMs)
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
        pts.add(pt(-0.08f, 0.82f)) // 4: Thumb folded across curled fingers

        // Curled fingers: tips folded tightly back toward MCP bases (< 0.55 * scale from MCP)
        val xOffsets = floatArrayOf(-0.34f, 0.0f, 0.32f, 0.56f)
        val mcpYs = floatArrayOf(0.95f, 1.00f, 0.94f, 0.84f)
        for (f in 0 until 4) {
            val xo = xOffsets[f]
            val my = mcpYs[f]
            pts.add(pt(xo, my))            // MCP
            pts.add(pt(xo, my + 0.18f))    // PIP
            pts.add(pt(xo, my - 0.05f))    // DIP curled inward
            pts.add(pt(xo, my - 0.22f))    // TIP curled toward palm base
        }

        return HandFrame(pts, timestampMs)
    }

    @Test
    fun `distance normalization is invariant between near and far hands`() {
        val nearPalm = makeOpenPalm(scale = 0.28f) // Close to lens (~30 cm)
        val farPalm = makeOpenPalm(scale = 0.06f)  // Far from lens (~1.4 m)

        assertTrue(HandGeometry.isOpenPalm(nearPalm.landmarks))
        assertTrue(HandGeometry.isOpenPalm(farPalm.landmarks))
        assertFalse(HandGeometry.isFullFist(nearPalm.landmarks))
        assertFalse(HandGeometry.isFullFist(farPalm.landmarks))

        val nearFist = makeFistHand(scale = 0.28f)
        val farFist = makeFistHand(scale = 0.06f)
        assertTrue(HandGeometry.isFullFist(nearFist.landmarks))
        assertTrue(HandGeometry.isFullFist(farFist.landmarks))
    }

    @Test
    fun `one euro filter attenuates micro jitter while tracking fast sweeps`() {
        val filter = OneEuroFilter1D(minCutoffHz = 1.7f, beta = 18.0f, derivativeCutoffHz = 2.5f)
        var t = 100L
        val stationaryOutputs = mutableListOf<Float>()
        for (i in 0 until 12) {
            val noisy = 0.5f + if (i % 2 == 0) 0.008f else -0.008f
            stationaryOutputs.add(filter.filter(noisy, t))
            t += 16L
        }
        val lastSpread = kotlin.math.abs(stationaryOutputs.last() - 0.5f)
        assertTrue("Stationary jitter should be damped below raw amplitude", lastSpread < 0.006f)

        // Rapid sweep to 0.9f should catch up quickly within 4 frames (64 ms)
        var fastVal = 0.5f
        for (i in 1..4) {
            fastVal = filter.filter(0.9f, t)
            t += 16L
        }
        assertTrue("Fast sweep should converge rapidly (>0.85), got $fastVal", fastVal > 0.85f)
    }

    @Test
    fun `open palm wakes Dynamic Island and idle timeout sleeps HUD`() {
        val fsm = AirGestureStateMachine()
        assertEquals(AirGestureStateMachine.Mode.DORMANT, fsm.currentMode)

        fsm.process(makeOpenPalm(timestampMs = 100L), 100L)
        fsm.process(makeOpenPalm(timestampMs = 120L), 120L)
        val step3 = fsm.process(makeOpenPalm(timestampMs = 140L), 140L)

        assertEquals(AirGestureStateMachine.Mode.PALM_ACTIVE, step3.mode)
        assertTrue(step3.events.contains(GestureEvent.WakeDynamicIsland))

        // Disappear for > 3000ms -> SleepDynamicIsland
        val sleepStep = fsm.process(null, 3300L)
        assertEquals(AirGestureStateMachine.Mode.DORMANT, sleepStep.mode)
        assertTrue(sleepStep.events.contains(GestureEvent.SleepDynamicIsland))
    }

    @Test
    fun `vertical sweep emits scroll and suppresses immediate return-stroke clutch`() {
        val fsm = AirGestureStateMachine()
        fsm.process(makeOpenPalm(cy = 0.65f, timestampMs = 100L), 100L)
        fsm.process(makeOpenPalm(cy = 0.65f, timestampMs = 120L), 120L)
        fsm.process(makeOpenPalm(cy = 0.65f, timestampMs = 140L), 140L)

        // Fast upward sweep (cy decreases from 0.65 -> 0.42 over 60ms)
        val upStep1 = fsm.process(makeOpenPalm(cy = 0.54f, timestampMs = 170L), 170L)
        val upStep2 = fsm.process(makeOpenPalm(cy = 0.42f, timestampMs = 200L), 200L)

        val scrollEvents = (upStep1.events + upStep2.events).filterIsInstance<GestureEvent.VerticalScroll>()
        assertTrue("Expected upward palm sweep to trigger VerticalScroll", scrollEvents.isNotEmpty())
        assertTrue(scrollEvents.first().scrollUp)

        // Immediate return stroke downward within 340ms clutch window must be suppressed!
        val ret1 = fsm.process(makeOpenPalm(cy = 0.54f, timestampMs = 315L), 315L)
        val ret2 = fsm.process(makeOpenPalm(cy = 0.66f, timestampMs = 340L), 340L)
        val unintendedReverse = (ret1.events + ret2.events).filterIsInstance<GestureEvent.VerticalScroll>()
        assertTrue("Return-stroke clutch must block unintended reverse scroll", unintendedReverse.isEmpty())
    }

    @Test
    fun `horizontal sweep triggers navigation with refractory lock`() {
        val fsm = AirGestureStateMachine()
        fsm.process(makeOpenPalm(cx = 0.65f, timestampMs = 100L), 100L)
        fsm.process(makeOpenPalm(cx = 0.65f, timestampMs = 120L), 120L)
        fsm.process(makeOpenPalm(cx = 0.65f, timestampMs = 140L), 140L)

        // Fast horizontal left sweep (cx: 0.65 -> 0.35)
        val nav1 = fsm.process(makeOpenPalm(cx = 0.50f, timestampMs = 170L), 170L)
        val nav2 = fsm.process(makeOpenPalm(cx = 0.35f, timestampMs = 200L), 200L)

        val navEvents = (nav1.events + nav2.events).filterIsInstance<GestureEvent.HorizontalNavigate>()
        assertEquals(1, navEvents.size)
        assertTrue(navEvents.first().swipeLeft)
    }

    @Test
    fun `pinch gesture modulates volume smoothly and respects hysteresis`() {
        val fsm = AirGestureStateMachine()
        fsm.process(makeOpenPalm(timestampMs = 100L), 100L)
        fsm.process(makeOpenPalm(timestampMs = 120L), 120L)
        fsm.process(makeOpenPalm(timestampMs = 140L), 140L)

        // Enter Pinch (ท่ามือจีบ) over 3 frames @ 60 FPS (~48 ms debounce + 1-Euro convergence)
        fsm.process(makePinchHand(cy = 0.60f, pinchGapNorm = 0.14f, timestampMs = 165L), 165L)
        fsm.process(makePinchHand(cy = 0.60f, pinchGapNorm = 0.14f, timestampMs = 182L), 182L)
        val enterStep = fsm.process(makePinchHand(cy = 0.60f, pinchGapNorm = 0.14f, timestampMs = 198L), 198L)
        assertEquals(AirGestureStateMachine.Mode.PINCH_VOLUME, enterStep.mode)

        // Move pinched hand UP (cy: 0.60 -> 0.50) -> positive volume delta!
        val slideUp = fsm.process(makePinchHand(cy = 0.50f, pinchGapNorm = 0.33f, timestampMs = 230L), 230L)
        // Notice pinchGapNorm = 0.33f is BETWEEN enter (0.28) and exit (0.40) -> Schmitt hysteresis keeps PINCH_VOLUME latched!
        assertEquals(AirGestureStateMachine.Mode.PINCH_VOLUME, slideUp.mode)
        val volEvent = slideUp.events.filterIsInstance<GestureEvent.PinchVolumeModulate>().firstOrNull()
        assertTrue("Expected positive volume delta on upward pinch slide", volEvent != null && volEvent.normalizedDeltaY > 0f)

        // Release pinch (> 0.40) -> PinchEnded
        val releaseStep = fsm.process(makeOpenPalm(cy = 0.50f, timestampMs = 260L), 260L)
        assertEquals(AirGestureStateMachine.Mode.PALM_ACTIVE, releaseStep.mode)
        assertTrue(releaseStep.events.contains(GestureEvent.PinchEnded))
    }

    @Test
    fun `fist gesture summons 15-app radial menu tracks 360 angles with hysteresis and launches on release`() {
        // Verify 15-slice angle math & ±2.5° boundary hysteresis directly
        assertEquals(0, HandGeometry.angleToRadialSlot(0f))
        assertEquals(1, HandGeometry.angleToRadialSlot(24f))
        assertEquals(3, HandGeometry.angleToRadialSlot(72f))
        assertEquals(14, HandGeometry.angleToRadialSlot(336f))
        // Boundary between Slot 0 (0°) and Slot 1 (24°) is at 12°; at 13.5° hysteresis keeps Slot 0!
        assertEquals(0, HandGeometry.angleToRadialSlot(13.5f, previousSlot = 0, hysteresisDeg = 2.5f))
        // Moving past 14.5° (e.g. 16°) transitions cleanly to Slot 1!
        assertEquals(1, HandGeometry.angleToRadialSlot(16.0f, previousSlot = 0, hysteresisDeg = 2.5f))

        val fsm = AirGestureStateMachine()
        fsm.process(makeOpenPalm(timestampMs = 100L), 100L)
        fsm.process(makeOpenPalm(timestampMs = 120L), 120L)
        fsm.process(makeOpenPalm(timestampMs = 140L), 140L)

        // Hold Fist (ท่ากำมือ) tilted to 72° (Slot #3, index 3 -> 4th app)
        fsm.process(makeFistHand(angleDeg = 72f, timestampMs = 180L), 180L)
        val summonStep = fsm.process(makeFistHand(angleDeg = 72f, timestampMs = 320L), 320L)
        assertEquals(AirGestureStateMachine.Mode.FIST_RADIAL_MENU, summonStep.mode)
        val summoned = summonStep.events.filterIsInstance<GestureEvent.RadialMenuSummoned>().first()
        assertEquals(3, summoned.selectedSlot)

        // Rotate wrist to 144° (Slot #6)
        fsm.process(makeFistHand(angleDeg = 144f, timestampMs = 360L), 360L)
        val rotateStep = fsm.process(makeFistHand(angleDeg = 144f, timestampMs = 390L), 390L)
        val updated = rotateStep.events.filterIsInstance<GestureEvent.RadialMenuUpdated>().last()
        assertEquals(6, updated.selectedSlot)

        // Release fist into open palm -> commits Slot #6!
        fsm.process(makeOpenPalm(angleDeg = 144f, timestampMs = 420L), 420L)
        val commitStep = fsm.process(makeOpenPalm(angleDeg = 144f, timestampMs = 450L), 450L)
        assertEquals(AirGestureStateMachine.Mode.PALM_ACTIVE, commitStep.mode)
        val committed = commitStep.events.filterIsInstance<GestureEvent.RadialMenuCommitted>().first()
        assertEquals(6, committed.selectedSlot)
    }

    @Test
    fun `roi tracker maps cropped volatile buffer coordinates back to full frame seamlessly`() {
        val roiTracker = RoiTracker()
        val handInCorner = makeOpenPalm(cx = 0.72f, cy = 0.34f, scale = 0.08f)
        roiTracker.onLandmarksDetected(handInCorner.landmarks)

        val zoomedRoi = roiTracker.currentRoi
        assertTrue(zoomedRoi.width < 1.0f)

        val (mappedX, mappedY) = roiTracker.mapRoiToFullFrame(0.5f, 0.5f, zoomedRoi)
        val expectedMidX = (zoomedRoi.left + zoomedRoi.right) * 0.5f
        val expectedMidY = (zoomedRoi.top + zoomedRoi.bottom) * 0.5f
        assertEquals(expectedMidX, mappedX, 1e-4f)
        assertEquals(expectedMidY, mappedY, 1e-4f)
    }
}
