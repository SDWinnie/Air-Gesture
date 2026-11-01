package com.sdwinnie.airgesture.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import com.sdwinnie.airgesture.engine.HandGeometry
import com.sdwinnie.airgesture.input.RadialAppSlotManager
import com.sdwinnie.airgesture.model.RadialAppSlot
import kotlin.math.cos
import kotlin.math.sin

/**
 * 360-Degree 15-App Radial Pie Menu summoned by the Fist Gesture (ท่ากำมือ).
 * - Renders 15 circular app shortcuts spaced at 24° intervals around the center hub.
 * - Highlights the segment targeted by the user's Wrist-to-Palm angle vector.
 * - Releasing the fist launches the focused app.
 */
class RadialAppSelectorView(
    context: Context,
    private val slotManager: RadialAppSlotManager
) : View(context) {

    private val density = resources.displayMetrics.density
    private fun dp(v: Float): Float = v * density

    private val wheelSizePx = dp(344f).toInt()
    private val iconPx = dp(32f).toInt().coerceAtLeast(48)

    private var slots: List<RadialAppSlot> = emptyList()
    private var slotIcons: Array<Bitmap?> = arrayOfNulls(HandGeometry.RADIAL_SLOTS)

    private var currentAngleDeg: Float = 0f
    private var selectedSlotIndex: Int = 0

    private val outerArcRect = RectF()
    private val innerArcRect = RectF()
    private val iconDstRect = RectF()
    private val wedgePath = Path()

    private val ringBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#EB0B101B")
    }

    private val ringStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        color = Color.parseColor("#2E3E5C")
    }

    private val activeWedgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#4D00E5FF")
    }

    private val activeWedgeBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.0f)
        color = Color.parseColor("#00E5FF")
    }

    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.0f)
        color = Color.parseColor("#223049")
    }

    private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#182236")
    }

    private val hubBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#F5080C14")
    }

    private val pointerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.8f)
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#00E676")
    }

    private val centerTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFFFF")
        textSize = dp(13.0f)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val centerSubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8FA3C0")
        textSize = dp(10.0f)
        textAlign = Paint.Align.CENTER
    }

    private val monogramPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        textSize = dp(12.0f)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    fun refreshSlots() {
        val loaded = slotManager.loadResolvedSlots()
        slots = loaded
        val icons = arrayOfNulls<Bitmap>(HandGeometry.RADIAL_SLOTS)
        for (i in loaded.indices) {
            icons[i] = slotManager.getCachedAppIcon(loaded[i].packageName, iconPx)
        }
        slotIcons = icons
        invalidate()
    }

    fun updateSelection(angleDegrees: Float, selectedSlot: Int) {
        if (slots.isEmpty()) {
            refreshSlots()
        }
        currentAngleDeg = angleDegrees
        selectedSlotIndex = selectedSlot.coerceIn(0, HandGeometry.RADIAL_SLOTS - 1)
        postInvalidateOnAnimation()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(wheelSizePx, wheelSizePx)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width * 0.5f
        val cy = height * 0.5f
        val outerR = dp(162f)
        val innerR = dp(64f)
        val orbitR = dp(115f)

        outerArcRect.set(cx - outerR, cy - outerR, cx + outerR, cy + outerR)
        innerArcRect.set(cx - innerR, cy - innerR, cx + innerR, cy + innerR)

        // Outer radial backdrop wheel
        canvas.drawCircle(cx, cy, outerR, ringBgPaint)
        canvas.drawCircle(cx, cy, outerR, ringStrokePaint)

        // Draw highlighted 24-degree wedge for selectedSlotIndex
        // Android Canvas 0° is 3 o'clock; our clockwise 0° is 12 o'clock => canvasAngle = ourAngle - 90°
        val sliceDeg = HandGeometry.SLICE_DEGREES
        val activeStartCanvasDeg = (selectedSlotIndex * sliceDeg) - (sliceDeg * 0.5f) - 90.0f
        wedgePath.reset()
        wedgePath.arcTo(outerArcRect, activeStartCanvasDeg, sliceDeg, true)
        wedgePath.arcTo(innerArcRect, activeStartCanvasDeg + sliceDeg, -sliceDeg, false)
        wedgePath.close()
        canvas.drawPath(wedgePath, activeWedgePaint)
        canvas.drawPath(wedgePath, activeWedgeBorderPaint)

        // Draw 15 radial slice dividers & 15 circular app shortcut icons
        for (i in 0 until HandGeometry.RADIAL_SLOTS) {
            val boundaryDeg = (i * sliceDeg) - (sliceDeg * 0.5f) - 90.0f
            val bRad = Math.toRadians(boundaryDeg.toDouble())
            val bx0 = cx + (innerR * cos(bRad)).toFloat()
            val by0 = cy + (innerR * sin(bRad)).toFloat()
            val bx1 = cx + (outerR * cos(bRad)).toFloat()
            val by1 = cy + (outerR * sin(bRad)).toFloat()
            canvas.drawLine(bx0, by0, bx1, by1, dividerPaint)

            val centerCanvasDeg = (i * sliceDeg) - 90.0f
            val cRad = Math.toRadians(centerCanvasDeg.toDouble())
            val ix = cx + (orbitR * cos(cRad)).toFloat()
            val iy = cy + (orbitR * sin(cRad)).toFloat()

            val isFocused = (i == selectedSlotIndex)
            val badgeR = if (isFocused) dp(21f) else dp(17f)

            badgeBgPaint.color = if (isFocused) Color.parseColor("#20344F") else Color.parseColor("#141C2B")
            canvas.drawCircle(ix, iy, badgeR, badgeBgPaint)
            if (isFocused) {
                canvas.drawCircle(ix, iy, badgeR, activeWedgeBorderPaint)
            }

            val iconBmp = slotIcons.getOrNull(i)
            if (iconBmp != null && !iconBmp.isRecycled) {
                val halfIcon = badgeR * 0.72f
                iconDstRect.set(ix - halfIcon, iy - halfIcon, ix + halfIcon, iy + halfIcon)
                canvas.drawBitmap(iconBmp, null, iconDstRect, null)
            } else {
                val label = slots.getOrNull(i)?.label?.take(2)?.uppercase() ?: "${i + 1}"
                canvas.drawText(label, ix, iy + dp(4f), monogramPaint)
            }
        }

        // Inner hub
        canvas.drawCircle(cx, cy, innerR, hubBgPaint)
        canvas.drawCircle(cx, cy, innerR, ringStrokePaint)

        // Live Wrist-to-Palm angle needle inside hub
        val pointerRad = Math.toRadians((currentAngleDeg - 90.0f).toDouble())
        val px = cx + ((innerR - dp(8f)) * cos(pointerRad)).toFloat()
        val py = cy + ((innerR - dp(8f)) * sin(pointerRad)).toFloat()
        canvas.drawLine(cx, cy, px, py, pointerPaint)
        canvas.drawCircle(px, py, dp(3.5f), activeWedgeBorderPaint)

        // Focused App Title & Release instruction
        val focusedSlot = slots.getOrNull(selectedSlotIndex)
        val appTitle = (focusedSlot?.label ?: "App #${selectedSlotIndex + 1}").let {
            if (it.length > 13) it.take(12) + "…" else it
        }
        canvas.drawText(appTitle, cx, cy - dp(4f), centerTitlePaint)
        canvas.drawText(
            "Slot ${selectedSlotIndex + 1}/15 • ${currentAngleDeg.toInt()}°",
            cx,
            cy + dp(12f),
            centerSubPaint
        )
        centerSubPaint.color = Color.parseColor("#00E676")
        canvas.drawText("คลายมือเพื่อเปิด", cx, cy + dp(25f), centerSubPaint)
        centerSubPaint.color = Color.parseColor("#8FA3C0")
    }
}
