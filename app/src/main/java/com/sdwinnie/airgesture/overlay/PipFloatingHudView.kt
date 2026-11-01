package com.sdwinnie.airgesture.overlay

import android.content.Context
import android.graphics.Bitmap
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
import com.sdwinnie.airgesture.engine.HandGeometry
import com.sdwinnie.airgesture.input.RadialAppSlotManager
import com.sdwinnie.airgesture.model.HudVisualState
import com.sdwinnie.airgesture.model.Landmark3D
import com.sdwinnie.airgesture.model.RadialAppSlot
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Adaptive Full-Window Floating HUD View rendered inside Android's Official Picture-in-Picture (PiP)
 * Floating Dynamic Island Window (zero SYSTEM_ALERT_WINDOW permission required!).
 * Automatically adapts to both wide capsule PiP (2.2:1) and square Radial Wheel PiP (1:1).
 */
class PipFloatingHudView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private fun dp(v: Float): Float = v * density

    private var state: HudVisualState = HudVisualState.Dormant
    private var slotManager: RadialAppSlotManager? = null
    private var slots: List<RadialAppSlot> = emptyList()
    private var icons: Array<Bitmap?> = arrayOfNulls(HandGeometry.RADIAL_SLOTS)

    private val cardRect = RectF()
    private val barRect = RectF()
    private val fillRect = RectF()
    private val outerRect = RectF()
    private val innerRect = RectF()
    private val iconRect = RectF()
    private val wedgePath = Path()

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#0A0F1C")
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.0f)
        color = Color.parseColor("#00E5FF")
    }

    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(13.5f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9DB2D0")
        textSize = dp(10.5f)
    }

    private val bonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.8f)
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#00E5FF")
    }

    private val jointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#00E676")
    }

    private val wedgeFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#4D00E5FF")
    }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#1E2B44")
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    fun attachSlotManager(manager: RadialAppSlotManager) {
        slotManager = manager
        refreshRadialSlots()
    }

    fun refreshRadialSlots() {
        val mgr = slotManager ?: return
        val loaded = mgr.loadResolvedSlots()
        slots = loaded
        val arr = arrayOfNulls<Bitmap>(HandGeometry.RADIAL_SLOTS)
        for (i in loaded.indices) {
            arr[i] = mgr.getCachedAppIcon(loaded[i].packageName, 64)
        }
        icons = arr
        invalidate()
    }

    fun renderState(newState: HudVisualState) {
        state = newState
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 4f || h <= 4f) return

        val pad = dp(2f)
        cardRect.set(pad, pad, w - pad, h - pad)
        val radius = dp(18f)
        canvas.drawRoundRect(cardRect, radius, radius, bgPaint)

        when (val st = state) {
            is HudVisualState.RadialSelectorActive -> {
                borderPaint.color = Color.parseColor("#9E7BFF")
                canvas.drawRoundRect(cardRect, radius, radius, borderPaint)
                drawCompactRadialWheel(canvas, w, h, st.angleDegrees, st.selectedSlot)
            }

            is HudVisualState.VolumeSlider -> {
                borderPaint.color = Color.parseColor("#00E676")
                canvas.drawRoundRect(cardRect, radius, radius, borderPaint)
                drawPipVolumeBar(canvas, w, h, st.volumePercent)
            }

            is HudVisualState.PalmTracking -> {
                val accent = when (st.accentHighlight) {
                    HudVisualState.AccentKind.CYAN -> Color.parseColor("#00E5FF")
                    HudVisualState.AccentKind.EMERALD -> Color.parseColor("#00E676")
                    HudVisualState.AccentKind.VIOLET -> Color.parseColor("#9E7BFF")
                    HudVisualState.AccentKind.AMBER -> Color.parseColor("#FFB300")
                }
                borderPaint.color = accent
                canvas.drawRoundRect(cardRect, radius, radius, borderPaint)
                drawPipPalmCapsule(canvas, w, h, st.statusText, st.landmarks, st.fps, accent)
            }

            is HudVisualState.PausedNotice -> {
                borderPaint.color = Color.parseColor("#FFB300")
                canvas.drawRoundRect(cardRect, radius, radius, borderPaint)
                titlePaint.textAlign = Paint.Align.CENTER
                titlePaint.color = Color.parseColor("#FFD54F")
                canvas.drawText(st.reason, w * 0.5f, h * 0.54f, titlePaint)
            }

            HudVisualState.Dormant -> {
                borderPaint.color = Color.parseColor("#283A5A")
                canvas.drawRoundRect(cardRect, radius, radius, borderPaint)
                jointPaint.color = Color.parseColor("#00E676")
                canvas.drawCircle(dp(22f), h * 0.5f, dp(4f), jointPaint)
                titlePaint.textAlign = Paint.Align.LEFT
                titlePaint.color = Color.parseColor("#DCE7FA")
                canvas.drawText("AIR GESTURE READY", dp(34f), h * 0.48f, titlePaint)
                subPaint.textAlign = Paint.Align.LEFT
                canvas.drawText("ยกฝ่ามือ • จีบนิ้วปรับเสียง • กำมือ 15 แอป", dp(34f), h * 0.72f, subPaint)
            }
        }
    }

    private fun drawPipPalmCapsule(
        canvas: Canvas,
        w: Float,
        h: Float,
        status: String,
        landmarks: List<Landmark3D>,
        fps: Float,
        accent: Int
    ) {
        val handR = min(h * 0.34f, dp(22f))
        val handCx = dp(28f)
        val handCy = h * 0.5f
        bonePaint.color = accent

        if (landmarks.size >= 21) {
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
            val mx = (minX + maxX) * 0.5f
            val my = (minY + maxY) * 0.5f
            val sc = (handR * 1.8f) / span
            for (e in BONES) {
                val a = landmarks[e[0]]
                val b = landmarks[e[1]]
                canvas.drawLine(
                    handCx + (a.x - mx) * sc,
                    handCy + (a.y - my) * sc,
                    handCx + (b.x - mx) * sc,
                    handCy + (b.y - my) * sc,
                    bonePaint
                )
            }
        } else {
            canvas.drawCircle(handCx, handCy, handR * 0.65f, bonePaint)
        }

        titlePaint.textAlign = Paint.Align.LEFT
        titlePaint.color = Color.WHITE
        canvas.drawText(status, dp(56f), h * 0.48f, titlePaint)

        subPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("100% Edge RAM • ${fps.toInt().coerceAtLeast(1)} FPS", dp(56f), h * 0.74f, subPaint)
    }

    private fun drawPipVolumeBar(canvas: Canvas, w: Float, h: Float, pct: Int) {
        titlePaint.textAlign = Paint.Align.LEFT
        titlePaint.color = Color.parseColor("#00E676")
        canvas.drawText("🤏 VOLUME $pct%", dp(18f), h * 0.38f, titlePaint)

        val left = dp(18f)
        val right = w - dp(18f)
        val top = h * 0.56f
        val bottom = top + dp(10f)
        val r = dp(5f)
        barRect.set(left, top, right, bottom)
        canvas.drawRoundRect(barRect, r, r, trackPaint)

        val fillW = (right - left) * (pct / 100f).coerceIn(0f, 1f)
        if (fillW > dp(3f)) {
            fillRect.set(left, top, left + fillW, bottom)
            fillPaint.shader = LinearGradient(
                left, top, right, top,
                Color.parseColor("#00E5FF"),
                Color.parseColor("#00E676"),
                Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(fillRect, r, r, fillPaint)
        }
    }

    private fun drawCompactRadialWheel(
        canvas: Canvas,
        w: Float,
        h: Float,
        angleDeg: Float,
        selectedSlot: Int
    ) {
        if (slots.isEmpty()) refreshRadialSlots()
        val cx = w * 0.5f
        val cy = h * 0.5f
        val maxR = min(w, h) * 0.45f
        val innerR = maxR * 0.40f
        val orbitR = maxR * 0.72f

        outerRect.set(cx - maxR, cy - maxR, cx + maxR, cy + maxR)
        innerRect.set(cx - innerR, cy - innerR, cx + innerR, cy + innerR)

        val slice = HandGeometry.SLICE_DEGREES
        val startDeg = (selectedSlot * slice) - slice * 0.5f - 90f
        wedgePath.reset()
        wedgePath.arcTo(outerRect, startDeg, slice, true)
        wedgePath.arcTo(innerRect, startDeg + slice, -slice, false)
        wedgePath.close()
        canvas.drawPath(wedgePath, wedgeFillPaint)

        for (i in 0 until HandGeometry.RADIAL_SLOTS) {
            val cRad = Math.toRadians(((i * slice) - 90f).toDouble())
            val ix = cx + (orbitR * cos(cRad)).toFloat()
            val iy = cy + (orbitR * sin(cRad)).toFloat()
            val iconSz = if (i == selectedSlot) maxR * 0.14f else maxR * 0.105f
            val bmp = icons.getOrNull(i)
            if (bmp != null && !bmp.isRecycled) {
                iconRect.set(ix - iconSz, iy - iconSz, ix + iconSz, iy + iconSz)
                canvas.drawBitmap(bmp, null, iconRect, null)
            } else {
                jointPaint.color = if (i == selectedSlot) Color.parseColor("#00E5FF") else Color.parseColor("#475C82")
                canvas.drawCircle(ix, iy, iconSz * 0.5f, jointPaint)
            }
        }

        val focusedName = slots.getOrNull(selectedSlot)?.label?.take(10) ?: "#${selectedSlot + 1}"
        titlePaint.textAlign = Paint.Align.CENTER
        titlePaint.color = Color.WHITE
        canvas.drawText(focusedName, cx, cy + dp(2f), titlePaint)
        subPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("${angleDeg.toInt()}° • คลายมือเปิด", cx, cy + dp(15f), subPaint)
    }

    companion object {
        private val BONES = arrayOf(
            intArrayOf(0, 1), intArrayOf(1, 2), intArrayOf(2, 3), intArrayOf(3, 4),
            intArrayOf(0, 5), intArrayOf(5, 6), intArrayOf(6, 7), intArrayOf(7, 8),
            intArrayOf(0, 9), intArrayOf(9, 10), intArrayOf(10, 11), intArrayOf(11, 12),
            intArrayOf(0, 13), intArrayOf(13, 14), intArrayOf(14, 15), intArrayOf(15, 16),
            intArrayOf(0, 17), intArrayOf(17, 18), intArrayOf(18, 19), intArrayOf(19, 20),
            intArrayOf(5, 9), intArrayOf(9, 13), intArrayOf(13, 17)
        )
    }
}
