package com.sdwinnie.airgesture.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import com.sdwinnie.airgesture.model.HudVisualState
import com.sdwinnie.airgesture.model.Landmark3D
import kotlin.math.abs

/**
 * Hardware-accelerated Dynamic Island HUD Capsule rendered at the top screen edge.
 * Displays:
 * 1. Compact dormant pill when idle
 * 2. Live 21-joint hand skeleton silhouette + active gesture telemetry when palm wakes
 * 3. Smooth morphing Volume Pill Slider during Pinch Gesture (ท่ามือจีบ)
 */
class DynamicIslandView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density
    private fun dp(v: Float): Float = v * density

    private val containerWidthPx = dp(288f).toInt()
    private val containerHeightPx = dp(60f).toInt()

    private var visualState: HudVisualState = HudVisualState.Dormant

    // Smoothly interpolated pill dimensions
    private var currentPillWidth = dp(48f)
    private var currentPillHeight = dp(8f)
    private var targetPillWidth = dp(48f)
    private var targetPillHeight = dp(8f)

    private var displayedVolumePct = 50f

    private val pillRect = RectF()
    private val sliderTrackRect = RectF()
    private val sliderFillRect = RectF()

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#F2090D16")
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        color = Color.parseColor("#00E5FF")
    }

    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F4F8FF")
        textSize = dp(12.5f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8FA3C0")
        textSize = dp(10.0f)
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    }

    private val bonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.4f)
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#00E5FF")
    }

    private val jointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#00E676")
    }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#2A364F")
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    fun updateState(newState: HudVisualState) {
        visualState = newState
        when (newState) {
            HudVisualState.Dormant -> {
                targetPillWidth = dp(46f)
                targetPillHeight = dp(7.5f)
            }
            is HudVisualState.PalmTracking -> {
                targetPillWidth = dp(246f)
                targetPillHeight = dp(44f)
            }
            is HudVisualState.VolumeSlider -> {
                targetPillWidth = dp(268f)
                targetPillHeight = dp(46f)
            }
            is HudVisualState.RadialSelectorActive -> {
                targetPillWidth = dp(224f)
                targetPillHeight = dp(38f)
            }
            is HudVisualState.PausedNotice -> {
                targetPillWidth = dp(218f)
                targetPillHeight = dp(36f)
            }
        }
        postInvalidateOnAnimation()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(containerWidthPx, containerHeightPx)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Spring-like smooth interpolation
        var needsNextFrame = false
        currentPillWidth += (targetPillWidth - currentPillWidth) * 0.28f
        currentPillHeight += (targetPillHeight - currentPillHeight) * 0.28f
        if (abs(targetPillWidth - currentPillWidth) > 0.6f || abs(targetPillHeight - currentPillHeight) > 0.6f) {
            needsNextFrame = true
        }

        val cx = width * 0.5f
        val topOffset = dp(6f)
        val halfW = currentPillWidth * 0.5f
        pillRect.set(cx - halfW, topOffset, cx + halfW, topOffset + currentPillHeight)
        val cornerRadius = currentPillHeight * 0.5f

        val accentColor = resolveAccentColor(visualState)
        borderPaint.color = accentColor
        borderPaint.alpha = if (visualState is HudVisualState.Dormant) 110 else 235

        canvas.drawRoundRect(pillRect, cornerRadius, cornerRadius, bgPaint)
        canvas.drawRoundRect(pillRect, cornerRadius, cornerRadius, borderPaint)

        // Wait until capsule expands sufficiently before drawing interior HUD widgets
        if (currentPillWidth < dp(120f)) {
            if (visualState is HudVisualState.Dormant) {
                jointPaint.color = Color.parseColor("#00E676")
                canvas.drawCircle(cx, pillRect.centerY(), dp(2.0f), jointPaint)
            }
            if (needsNextFrame) postInvalidateOnAnimation()
            return
        }

        when (val st = visualState) {
            HudVisualState.Dormant -> Unit

            is HudVisualState.PalmTracking -> {
                drawHandMiniSilhouette(canvas, pillRect.left + dp(24f), pillRect.centerY(), dp(14f), st.landmarks, accentColor)

                titlePaint.color = Color.parseColor("#F4F8FF")
                canvas.drawText(
                    st.statusText,
                    pillRect.left + dp(46f),
                    pillRect.centerY() + dp(4.2f),
                    titlePaint
                )

                val fpsLabel = if (st.thermalWarning) "THERM" else "${st.fps.toInt().coerceAtLeast(1)}fps"
                subPaint.color = if (st.thermalWarning) Color.parseColor("#FFB300") else Color.parseColor("#7CE8FF")
                val tw = subPaint.measureText(fpsLabel)
                canvas.drawText(
                    fpsLabel,
                    pillRect.right - tw - dp(14f),
                    pillRect.centerY() + dp(3.5f),
                    subPaint
                )
            }

            is HudVisualState.VolumeSlider -> {
                displayedVolumePct += (st.volumePercent.toFloat() - displayedVolumePct) * 0.38f
                if (abs(st.volumePercent - displayedVolumePct) > 0.4f) {
                    needsNextFrame = true
                }

                // Speaker badge on left
                titlePaint.color = Color.parseColor("#00E5FF")
                val iconLabel = if (st.isMuted || st.volumePercent == 0) "MUTE" else "VOL"
                canvas.drawText(iconLabel, pillRect.left + dp(16f), pillRect.centerY() + dp(4.2f), titlePaint)

                // Slider bar in middle
                val trackLeft = pillRect.left + dp(58f)
                val trackRight = pillRect.right - dp(54f)
                val trackHalfH = dp(4.5f)
                val cy = pillRect.centerY()
                sliderTrackRect.set(trackLeft, cy - trackHalfH, trackRight, cy + trackHalfH)
                canvas.drawRoundRect(sliderTrackRect, trackHalfH, trackHalfH, trackPaint)

                val fillRatio = (displayedVolumePct / 100f).coerceIn(0f, 1f)
                val fillRight = trackLeft + (trackRight - trackLeft) * fillRatio
                if (fillRight > trackLeft + dp(2f)) {
                    sliderFillRect.set(trackLeft, cy - trackHalfH, fillRight, cy + trackHalfH)
                    fillPaint.shader = LinearGradient(
                        trackLeft,
                        cy,
                        trackRight,
                        cy,
                        Color.parseColor("#00E5FF"),
                        Color.parseColor("#00E676"),
                        Shader.TileMode.CLAMP
                    )
                    canvas.drawRoundRect(sliderFillRect, trackHalfH, trackHalfH, fillPaint)
                }

                // Numeric percentage on right
                val pctStr = "${st.volumePercent}%"
                titlePaint.color = Color.parseColor("#F4F8FF")
                val pw = titlePaint.measureText(pctStr)
                canvas.drawText(pctStr, pillRect.right - pw - dp(14f), cy + dp(4.2f), titlePaint)
            }

            is HudVisualState.RadialSelectorActive -> {
                val label = "RADIAL #${st.selectedSlot + 1} • RELEASE TO OPEN"
                titlePaint.color = Color.parseColor("#E5DEFF")
                val lw = titlePaint.measureText(label)
                canvas.drawText(label, cx - lw * 0.5f, pillRect.centerY() + dp(4.0f), titlePaint)
            }

            is HudVisualState.PausedNotice -> {
                titlePaint.color = Color.parseColor("#FFD54F")
                val lw = titlePaint.measureText(st.reason)
                canvas.drawText(st.reason, cx - lw * 0.5f, pillRect.centerY() + dp(4.0f), titlePaint)
            }
        }

        if (needsNextFrame) {
            postInvalidateOnAnimation()
        }
    }

    private fun drawHandMiniSilhouette(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        radiusPx: Float,
        landmarks: List<Landmark3D>,
        accentColor: Int
    ) {
        bonePaint.color = accentColor
        if (landmarks.size < 21) {
            // Stylized palm pulse ring fallback
            canvas.drawCircle(centerX, centerY, radiusPx * 0.62f, bonePaint)
            jointPaint.color = accentColor
            canvas.drawCircle(centerX, centerY, dp(2.5f), jointPaint)
            return
        }

        var minX = 1f
        var maxX = 0f
        var minY = 1f
        var maxY = 0f
        for (p in landmarks) {
            if (p.x < minX) minX = p.x
            if (p.x > maxX) maxX = p.x
            if (p.y < minY) minY = p.y
            if (p.y > maxY) maxY = p.y
        }
        val span = maxOf(maxX - minX, maxY - minY).coerceAtLeast(0.08f)
        val midX = (minX + maxX) * 0.5f
        val midY = (minY + maxY) * 0.5f
        val scale = (radiusPx * 1.75f) / span

        fun mapX(pt: Landmark3D): Float = centerX + (pt.x - midX) * scale
        fun mapY(pt: Landmark3D): Float = centerY + (pt.y - midY) * scale

        for (edge in HAND_BONE_EDGES) {
            val a = landmarks[edge[0]]
            val b = landmarks[edge[1]]
            canvas.drawLine(mapX(a), mapY(a), mapX(b), mapY(b), bonePaint)
        }

        jointPaint.color = Color.parseColor("#00E676")
        for (tipIdx in FINGERTIP_INDICES) {
            val pt = landmarks[tipIdx]
            canvas.drawCircle(mapX(pt), mapY(pt), dp(1.5f), jointPaint)
        }
    }

    private fun resolveAccentColor(state: HudVisualState): Int {
        return when (state) {
            HudVisualState.Dormant -> Color.parseColor("#354766")
            is HudVisualState.PalmTracking -> when (state.accentHighlight) {
                HudVisualState.AccentKind.CYAN -> Color.parseColor("#00E5FF")
                HudVisualState.AccentKind.EMERALD -> Color.parseColor("#00E676")
                HudVisualState.AccentKind.VIOLET -> Color.parseColor("#9E7BFF")
                HudVisualState.AccentKind.AMBER -> Color.parseColor("#FFB300")
            }
            is HudVisualState.VolumeSlider -> Color.parseColor("#00E676")
            is HudVisualState.RadialSelectorActive -> Color.parseColor("#9E7BFF")
            is HudVisualState.PausedNotice -> Color.parseColor("#FFB300")
        }
    }

    companion object {
        private val FINGERTIP_INDICES = intArrayOf(4, 8, 12, 16, 20)
        private val HAND_BONE_EDGES = arrayOf(
            intArrayOf(0, 1), intArrayOf(1, 2), intArrayOf(2, 3), intArrayOf(3, 4),
            intArrayOf(0, 5), intArrayOf(5, 6), intArrayOf(6, 7), intArrayOf(7, 8),
            intArrayOf(0, 9), intArrayOf(9, 10), intArrayOf(10, 11), intArrayOf(11, 12),
            intArrayOf(0, 13), intArrayOf(13, 14), intArrayOf(14, 15), intArrayOf(15, 16),
            intArrayOf(0, 17), intArrayOf(17, 18), intArrayOf(18, 19), intArrayOf(19, 20),
            intArrayOf(5, 9), intArrayOf(9, 13), intArrayOf(13, 17)
        )
    }
}
