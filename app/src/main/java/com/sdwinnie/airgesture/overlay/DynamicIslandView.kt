package com.sdwinnie.airgesture.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.sdwinnie.airgesture.model.HudPalmStyle
import com.sdwinnie.airgesture.model.HudThemeColor
import com.sdwinnie.airgesture.model.HudVisualState
import com.sdwinnie.airgesture.model.Landmark3D
import com.sdwinnie.airgesture.model.VolumeDirection
import kotlin.math.abs

/**
 * Hardware-accelerated Dynamic Island & Volume Status HUD Capsule (v3.0).
 * - User-customizable Palm Visual Styles (21-Joint Skeleton, Glowing Palm Silhouette, Posture Emoji Badge, Spatial Orb).
 * - User-customizable Neon Theme Colors.
 * - Explicit Directional Volume Status Banner (`🔉 กำลังลดเสียง ▼`, `🔊 กำลังเพิ่มเสียง ▲`, `🔇 ปิดเสียง 0%`).
 */
class DynamicIslandView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private fun dp(v: Float): Float = v * density

    private val containerWidthPx = dp(312f).toInt()
    private val containerHeightPx = dp(64f).toInt()

    private var visualState: HudVisualState = HudVisualState.Dormant
    private var palmStyle: HudPalmStyle = HudPalmStyle.SKELETON_21
    private var themeColor: HudThemeColor = HudThemeColor.CYBER_CYAN

    private var currentPillWidth = dp(48f)
    private var currentPillHeight = dp(8f)
    private var targetPillWidth = dp(48f)
    private var targetPillHeight = dp(8f)
    private var displayedVolumePct = 50f

    private val pillRect = RectF()
    private val sliderTrackRect = RectF()
    private val sliderFillRect = RectF()
    private val palmEmblemPath = Path()

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#F5080D18")
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.7f)
        color = Color.parseColor("#00E5FF")
    }

    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F4F8FF")
        textSize = dp(12.5f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#93A7C7")
        textSize = dp(10.0f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val emojiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(17f)
        textAlign = Paint.Align.CENTER
    }

    private val bonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#00E5FF")
    }

    private val jointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#00E676")
    }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#23304A")
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    fun applyVisualStyle(style: HudPalmStyle, theme: HudThemeColor) {
        if (palmStyle != style || themeColor != theme) {
            palmStyle = style
            themeColor = theme
            postInvalidateOnAnimation()
        }
    }

    fun updateState(newState: HudVisualState) {
        visualState = newState
        when (newState) {
            HudVisualState.Dormant -> {
                targetPillWidth = dp(48f)
                targetPillHeight = dp(8f)
            }
            is HudVisualState.PalmTracking -> {
                targetPillWidth = dp(268f)
                targetPillHeight = dp(46f)
            }
            is HudVisualState.VolumeSlider -> {
                targetPillWidth = dp(296f)
                targetPillHeight = dp(52f)
            }
            is HudVisualState.RadialSelectorActive -> {
                targetPillWidth = dp(254f)
                targetPillHeight = dp(42f)
            }
            is HudVisualState.PausedNotice -> {
                targetPillWidth = dp(228f)
                targetPillHeight = dp(38f)
            }
        }
        postInvalidateOnAnimation()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val measuredW = resolveSize(containerWidthPx, widthMeasureSpec)
        val measuredH = resolveSize(containerHeightPx, heightMeasureSpec)
        setMeasuredDimension(measuredW, measuredH)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        var needsNextFrame = false
        currentPillWidth += (targetPillWidth - currentPillWidth) * 0.30f
        currentPillHeight += (targetPillHeight - currentPillHeight) * 0.30f
        if (abs(targetPillWidth - currentPillWidth) > 0.6f || abs(targetPillHeight - currentPillHeight) > 0.6f) {
            needsNextFrame = true
        }

        val cx = width * 0.5f
        val topOffset = dp(5f)
        val halfW = currentPillWidth * 0.5f
        pillRect.set(cx - halfW, topOffset, cx + halfW, topOffset + currentPillHeight)
        val cornerRadius = currentPillHeight * 0.5f

        val accentColor = resolveAccentColor(visualState)
        borderPaint.color = accentColor
        borderPaint.alpha = if (visualState is HudVisualState.Dormant) 115 else 240

        canvas.drawRoundRect(pillRect, cornerRadius, cornerRadius, bgPaint)
        canvas.drawRoundRect(pillRect, cornerRadius, cornerRadius, borderPaint)

        if (currentPillWidth < dp(126f)) {
            if (visualState is HudVisualState.Dormant) {
                jointPaint.color = Color.parseColor(themeColor.primaryHex)
                canvas.drawCircle(cx, pillRect.centerY(), dp(2.2f), jointPaint)
            }
            if (needsNextFrame) postInvalidateOnAnimation()
            return
        }

        when (val st = visualState) {
            HudVisualState.Dormant -> Unit

            is HudVisualState.PalmTracking -> {
                drawCustomizablePalmGraphic(
                    canvas = canvas,
                    centerX = pillRect.left + dp(25f),
                    centerY = pillRect.centerY(),
                    radiusPx = dp(14f),
                    landmarks = st.landmarks,
                    emoji = st.postureEmoji,
                    accentColor = accentColor
                )

                titlePaint.textAlign = Paint.Align.LEFT
                titlePaint.color = Color.parseColor("#F5F9FF")
                canvas.drawText(
                    st.statusText,
                    pillRect.left + dp(47f),
                    pillRect.centerY() - dp(1.5f),
                    titlePaint
                )

                val metaLabel = if (st.thermalWarning) {
                    "โหมดลดความร้อน"
                } else {
                    "ล็อก ${st.handSideLabel} • ${st.fps.toInt().coerceAtLeast(1)} FPS"
                }
                subPaint.textAlign = Paint.Align.LEFT
                subPaint.color = if (st.thermalWarning) Color.parseColor("#FFB300") else Color.parseColor("#8CA6CA")
                canvas.drawText(
                    metaLabel,
                    pillRect.left + dp(47f),
                    pillRect.centerY() + dp(12.5f),
                    subPaint
                )
            }

            is HudVisualState.VolumeSlider -> {
                displayedVolumePct += (st.volumePercent.toFloat() - displayedVolumePct) * 0.40f
                if (abs(st.volumePercent - displayedVolumePct) > 0.4f) {
                    needsNextFrame = true
                }

                val statusTitle: String
                val barStartColor: Int
                val barEndColor: Int
                when (st.direction) {
                    VolumeDirection.MUTED -> {
                        statusTitle = "🔇 ปิดเสียงแล้ว (MUTE)"
                        barStartColor = Color.parseColor("#FF4D6D")
                        barEndColor = Color.parseColor("#FF758F")
                    }
                    VolumeDirection.DOWN -> {
                        statusTitle = "🔉 กำลังลดเสียง ▼"
                        barStartColor = Color.parseColor("#FFB300")
                        barEndColor = Color.parseColor("#FF5C77")
                    }
                    VolumeDirection.UP -> {
                        statusTitle = "🔊 กำลังเพิ่มเสียง ▲"
                        barStartColor = Color.parseColor(themeColor.primaryHex)
                        barEndColor = Color.parseColor("#00E676")
                    }
                    VolumeDirection.STEADY -> {
                        statusTitle = "🔊 ระดับเสียงปัจจุบัน •"
                        barStartColor = Color.parseColor(themeColor.primaryHex)
                        barEndColor = Color.parseColor(themeColor.secondaryHex)
                    }
                }

                // Top Row: Clear Thai Direction Status (Left) + Bold Percentage & Delta (Right)
                titlePaint.textAlign = Paint.Align.LEFT
                titlePaint.color = barEndColor
                canvas.drawText(statusTitle, pillRect.left + dp(18f), pillRect.top + dp(20f), titlePaint)

                val deltaSuffix = when {
                    st.deltaStepPercent > 0 -> " (+${st.deltaStepPercent}%)"
                    st.deltaStepPercent < 0 -> " (${st.deltaStepPercent}%)"
                    else -> ""
                }
                val pctStr = "${st.volumePercent}%$deltaSuffix"
                titlePaint.textAlign = Paint.Align.RIGHT
                titlePaint.color = Color.WHITE
                canvas.drawText(pctStr, pillRect.right - dp(18f), pillRect.top + dp(20f), titlePaint)
                titlePaint.textAlign = Paint.Align.LEFT

                // Bottom Row: Wide Glowing Progress Bar
                val trackLeft = pillRect.left + dp(18f)
                val trackRight = pillRect.right - dp(18f)
                val barTop = pillRect.top + dp(31f)
                val barBottom = barTop + dp(9f)
                val barR = dp(4.5f)
                sliderTrackRect.set(trackLeft, barTop, trackRight, barBottom)
                canvas.drawRoundRect(sliderTrackRect, barR, barR, trackPaint)

                val fillRatio = (displayedVolumePct / 100f).coerceIn(0f, 1f)
                val fillRight = trackLeft + (trackRight - trackLeft) * fillRatio
                if (fillRight > trackLeft + dp(3f)) {
                    sliderFillRect.set(trackLeft, barTop, fillRight, barBottom)
                    fillPaint.shader = LinearGradient(
                        trackLeft, barTop, trackRight, barTop,
                        barStartColor, barEndColor,
                        Shader.TileMode.CLAMP
                    )
                    canvas.drawRoundRect(sliderFillRect, barR, barR, fillPaint)
                }
            }

            is HudVisualState.RadialSelectorActive -> {
                val label = if (st.inCancelZone) {
                    "⭕ เลื่อนมือเลือกแอปในวงกลม"
                } else {
                    "🎡 ช่อง #${st.selectedSlot + 1}/${st.totalSlots} • แบมือเพื่อเปิด"
                }
                titlePaint.textAlign = Paint.Align.CENTER
                titlePaint.color = Color.parseColor("#F3ECFF")
                canvas.drawText(label, cx, pillRect.centerY() + dp(4.2f), titlePaint)
                titlePaint.textAlign = Paint.Align.LEFT
            }

            is HudVisualState.PausedNotice -> {
                titlePaint.textAlign = Paint.Align.CENTER
                titlePaint.color = Color.parseColor("#FFD54F")
                canvas.drawText(st.reason, cx, pillRect.centerY() + dp(4.2f), titlePaint)
                titlePaint.textAlign = Paint.Align.LEFT
            }
        }

        if (needsNextFrame) {
            postInvalidateOnAnimation()
        }
    }

    private fun drawCustomizablePalmGraphic(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        radiusPx: Float,
        landmarks: List<Landmark3D>,
        emoji: String,
        accentColor: Int
    ) {
        when (palmStyle) {
            HudPalmStyle.SKELETON_21 -> {
                drawHandMiniSilhouette(canvas, centerX, centerY, radiusPx, landmarks, accentColor)
            }
            HudPalmStyle.GLOWING_PALM -> {
                drawStylizedGlowingPalm(canvas, centerX, centerY, radiusPx, accentColor)
            }
            HudPalmStyle.POSTURE_BADGE -> {
                jointPaint.color = Color.argb(45, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
                canvas.drawCircle(centerX, centerY, radiusPx, jointPaint)
                bonePaint.color = accentColor
                canvas.drawCircle(centerX, centerY, radiusPx, bonePaint)
                canvas.drawText(emoji, centerX, centerY + dp(6f), emojiPaint)
            }
            HudPalmStyle.MINIMAL_ORB -> {
                bonePaint.color = accentColor
                canvas.drawCircle(centerX, centerY, radiusPx * 0.86f, bonePaint)
                jointPaint.color = Color.argb(90, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
                canvas.drawCircle(centerX, centerY, radiusPx * 0.56f, jointPaint)
                jointPaint.color = accentColor
                canvas.drawCircle(centerX, centerY, dp(3.2f), jointPaint)
            }
        }
    }

    private fun drawStylizedGlowingPalm(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        r: Float,
        accentColor: Int
    ) {
        jointPaint.color = Color.argb(42, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
        canvas.drawCircle(cx, cy, r, jointPaint)

        bonePaint.color = accentColor
        bonePaint.strokeWidth = dp(2.0f)
        // Stylized 5-finger human palm rays + palm base arc
        val palmBaseY = cy + r * 0.25f
        canvas.drawCircle(cx, palmBaseY, r * 0.34f, bonePaint)
        val fingerAngles = floatArrayOf(-52f, -24f, 0f, 22f, 44f)
        val fingerLengths = floatArrayOf(0.58f, 0.80f, 0.90f, 0.82f, 0.66f)
        for (i in fingerAngles.indices) {
            val rad = Math.toRadians((fingerAngles[i] - 90f).toDouble())
            val len = r * fingerLengths[i]
            val tx = cx + (len * kotlin.math.cos(rad)).toFloat()
            val ty = palmBaseY + (len * kotlin.math.sin(rad)).toFloat()
            canvas.drawLine(cx, palmBaseY - r * 0.1f, tx, ty, bonePaint)
        }
        bonePaint.strokeWidth = dp(1.5f)
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
            drawStylizedGlowingPalm(canvas, centerX, centerY, radiusPx, accentColor)
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

        jointPaint.color = Color.parseColor(themeColor.secondaryHex)
        for (tipIdx in FINGERTIP_INDICES) {
            val pt = landmarks[tipIdx]
            canvas.drawCircle(mapX(pt), mapY(pt), dp(1.6f), jointPaint)
        }
    }

    private fun resolveAccentColor(state: HudVisualState): Int {
        val primaryTheme = Color.parseColor(themeColor.primaryHex)
        return when (state) {
            HudVisualState.Dormant -> Color.parseColor("#354766")
            is HudVisualState.PalmTracking -> when (state.accentHighlight) {
                HudVisualState.AccentKind.CYAN -> primaryTheme
                HudVisualState.AccentKind.EMERALD -> Color.parseColor("#00E676")
                HudVisualState.AccentKind.VIOLET -> Color.parseColor("#A374FF")
                HudVisualState.AccentKind.AMBER -> Color.parseColor("#FFB300")
            }
            is HudVisualState.VolumeSlider -> when (state.direction) {
                VolumeDirection.MUTED -> Color.parseColor("#FF4D6D")
                VolumeDirection.DOWN -> Color.parseColor("#FFB300")
                VolumeDirection.UP -> Color.parseColor("#00E676")
                VolumeDirection.STEADY -> primaryTheme
            }
            is HudVisualState.RadialSelectorActive -> primaryTheme
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
