package com.sdwinnie.airgesture.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.sdwinnie.airgesture.input.RadialAppSlotManager
import com.sdwinnie.airgesture.model.HudThemeColor
import com.sdwinnie.airgesture.model.RadialAppSlot
import kotlin.math.cos
import kotlin.math.sin

/**
 * On-Screen 360-Degree Circular App Selector Wheel (v3.0).
 * - Renders directly centered on screen (via AccessibilityScreenOverlay) and inside the App Preview!
 * - Supports configurable 6, 8, 12, or 15 circular app slices with app icons + app labels.
 * - Renders a live Spatial Virtual Joystick Cursor Dot + Center Safe Cancel Zone so humans can
 *   effortlessly pick any app by sliding their hand slightly in any direction without twisting their wrist.
 */
class RadialAppSelectorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var slotManager: RadialAppSlotManager? = null
    private var themeColor: HudThemeColor = HudThemeColor.CYBER_CYAN
    private var activeSlotCount: Int = 8

    private val density = resources.displayMetrics.density
    private fun dp(v: Float): Float = v * density

    private val defaultWheelSizePx = dp(332f).toInt()
    private val iconPx = dp(36f).toInt().coerceAtLeast(56)

    private var slots: List<RadialAppSlot> = emptyList()
    private var slotIcons: Array<Bitmap?> = emptyArray()

    private var currentAngleDeg: Float = 0f
    private var selectedSlotIndex: Int = 0
    private var cursorNormX: Float = 0f
    private var cursorNormY: Float = 0f
    private var inCancelZone: Boolean = false

    private val outerArcRect = RectF()
    private val innerArcRect = RectF()
    private val iconDstRect = RectF()
    private val centerIconRect = RectF()
    private val wedgePath = Path()

    private val ringBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#F0080D18")
    }

    private val ringStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.0f)
        color = Color.parseColor("#2B3D5E")
    }

    private val activeWedgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#5200E5FF")
    }

    private val activeWedgeBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.2f)
        color = Color.parseColor("#00E5FF")
    }

    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.0f)
        color = Color.parseColor("#1E2C45")
    }

    private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#162136")
    }

    private val hubBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#FA060A12")
    }

    private val joystickBeamPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.6f)
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#00E676")
    }

    private val cursorDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#00E676")
    }

    private val sliceLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D5E3FA")
        textSize = dp(9.5f)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val centerTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(13.5f)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val centerSubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#93A8CA")
        textSize = dp(10.0f)
        textAlign = Paint.Align.CENTER
    }

    private val monogramPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        textSize = dp(12.0f)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    fun attachSlotManager(manager: RadialAppSlotManager, slotCount: Int = activeSlotCount) {
        slotManager = manager
        activeSlotCount = slotCount.coerceIn(6, 15)
        refreshSlots(activeSlotCount)
    }

    fun applyThemeColor(theme: HudThemeColor) {
        themeColor = theme
        val primary = Color.parseColor(theme.primaryHex)
        val secondary = Color.parseColor(theme.secondaryHex)
        activeWedgeBorderPaint.color = primary
        activeWedgePaint.color = Color.argb(78, Color.red(primary), Color.green(primary), Color.blue(primary))
        monogramPaint.color = primary
        joystickBeamPaint.color = secondary
        cursorDotPaint.color = secondary
        postInvalidateOnAnimation()
    }

    fun refreshSlots(slotCount: Int = activeSlotCount) {
        activeSlotCount = slotCount.coerceIn(6, 15)
        val mgr = slotManager ?: return
        val loaded = mgr.loadResolvedSlots(activeSlotCount)
        slots = loaded
        val icons = arrayOfNulls<Bitmap>(loaded.size)
        for (i in loaded.indices) {
            icons[i] = mgr.getCachedAppIcon(loaded[i].packageName, iconPx)
        }
        slotIcons = icons
        selectedSlotIndex = selectedSlotIndex.coerceIn(0, (loaded.size - 1).coerceAtLeast(0))
        invalidate()
    }

    fun updateSelection(
        angleDegrees: Float,
        selectedSlot: Int,
        totalSlots: Int = activeSlotCount,
        normCursorX: Float = 0f,
        normCursorY: Float = 0f,
        cancelZone: Boolean = false
    ) {
        if (slots.isEmpty() || totalSlots != activeSlotCount) {
            refreshSlots(totalSlots)
        }
        currentAngleDeg = angleDegrees
        selectedSlotIndex = selectedSlot.coerceIn(0, (activeSlotCount - 1).coerceAtLeast(0))
        cursorNormX = normCursorX.coerceIn(-1f, 1f)
        cursorNormY = normCursorY.coerceIn(-1f, 1f)
        inCancelZone = cancelZone
        postInvalidateOnAnimation()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availW = MeasureSpec.getSize(widthMeasureSpec)
        val target = if (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED && availW > 0) {
            minOf(availW, defaultWheelSizePx)
        } else {
            defaultWheelSizePx
        }
        setMeasuredDimension(target, target)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val count = slots.size.coerceAtLeast(activeSlotCount).coerceIn(6, 15)
        val sliceDeg = 360.0f / count
        val cx = width * 0.5f
        val cy = height * 0.5f
        val halfMin = minOf(width, height) * 0.5f
        val outerR = (halfMin - dp(6f)).coerceAtLeast(dp(96f))
        val innerR = outerR * 0.39f
        val orbitR = outerR * 0.70f

        outerArcRect.set(cx - outerR, cy - outerR, cx + outerR, cy + outerR)
        innerArcRect.set(cx - innerR, cy - innerR, cx + innerR, cy + innerR)

        // Outer Circular Wheel Backdrop + Subtle Halo Border
        canvas.drawCircle(cx, cy, outerR, ringBgPaint)
        ringStrokePaint.color = Color.parseColor(themeColor.primaryHex)
        ringStrokePaint.alpha = 115
        canvas.drawCircle(cx, cy, outerR, ringStrokePaint)

        // Highlighted App Wedge (when outside center Safe Cancel Zone)
        if (!inCancelZone) {
            val activeStartCanvasDeg = (selectedSlotIndex * sliceDeg) - (sliceDeg * 0.5f) - 90.0f
            wedgePath.reset()
            wedgePath.arcTo(outerArcRect, activeStartCanvasDeg, sliceDeg, true)
            wedgePath.arcTo(innerArcRect, activeStartCanvasDeg + sliceDeg, -sliceDeg, false)
            wedgePath.close()
            canvas.drawPath(wedgePath, activeWedgePaint)
            canvas.drawPath(wedgePath, activeWedgeBorderPaint)
        }

        // Draw Circular App Icons & Short Names around 360° Ring
        val showSliceLabels = count <= 12
        for (i in 0 until count) {
            val boundaryDeg = (i * sliceDeg) - (sliceDeg * 0.5f) - 90.0f
            val bRad = Math.toRadians(boundaryDeg.toDouble())
            canvas.drawLine(
                cx + (innerR * cos(bRad)).toFloat(),
                cy + (innerR * sin(bRad)).toFloat(),
                cx + (outerR * cos(bRad)).toFloat(),
                cy + (outerR * sin(bRad)).toFloat(),
                dividerPaint
            )

            val centerCanvasDeg = (i * sliceDeg) - 90.0f
            val cRad = Math.toRadians(centerCanvasDeg.toDouble())
            val iconOrbit = if (showSliceLabels) orbitR - dp(5f) else orbitR
            val ix = cx + (iconOrbit * cos(cRad)).toFloat()
            val iy = cy + (iconOrbit * sin(cRad)).toFloat()

            val isFocused = (!inCancelZone && i == selectedSlotIndex)
            val baseBadgeR = if (count <= 8) dp(21f) else if (count <= 12) dp(18.5f) else dp(16.5f)
            val badgeR = if (isFocused) baseBadgeR + dp(3.5f) else baseBadgeR

            badgeBgPaint.color = if (isFocused) Color.parseColor("#223859") else Color.parseColor("#131C2E")
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
                val mono = slots.getOrNull(i)?.label?.take(2)?.uppercase() ?: "${i + 1}"
                canvas.drawText(mono, ix, iy + dp(4f), monogramPaint)
            }

            if (showSliceLabels) {
                val rawName = slots.getOrNull(i)?.label.orEmpty()
                val shortName = if (rawName.length > 8) rawName.take(7) + "…" else rawName
                sliceLabelPaint.color = if (isFocused) Color.WHITE else Color.parseColor("#B6C8E6")
                canvas.drawText(shortName, ix, iy + badgeR + dp(10.5f), sliceLabelPaint)
            }
        }

        // Center Hub (Safe Cancel Zone & Focused App Preview)
        canvas.drawCircle(cx, cy, innerR, hubBgPaint)
        ringStrokePaint.color = if (inCancelZone) Color.parseColor("#546E96") else Color.parseColor(themeColor.primaryHex)
        ringStrokePaint.alpha = 220
        canvas.drawCircle(cx, cy, innerR, ringStrokePaint)

        if (inCancelZone) {
            centerTitlePaint.color = Color.parseColor("#DCE7FA")
            canvas.drawText("⭕ เลื่อนมือเลือกแอป", cx, cy - dp(4f), centerTitlePaint)
            centerSubPaint.color = Color.parseColor("#93A8CA")
            canvas.drawText("แบมือตรงกลาง = ยกเลิก", cx, cy + dp(14f), centerSubPaint)
        } else {
            val focusedSlot = slots.getOrNull(selectedSlotIndex)
            val focusedIcon = slotIcons.getOrNull(selectedSlotIndex)
            if (focusedIcon != null && !focusedIcon.isRecycled) {
                val halfCenterIcon = dp(14f)
                centerIconRect.set(
                    cx - halfCenterIcon,
                    cy - innerR * 0.68f,
                    cx + halfCenterIcon,
                    cy - innerR * 0.68f + halfCenterIcon * 2f
                )
                canvas.drawBitmap(focusedIcon, null, centerIconRect, null)
            }

            val appTitle = (focusedSlot?.label ?: "ช่อง #${selectedSlotIndex + 1}").let {
                if (it.length > 13) it.take(12) + "…" else it
            }
            centerTitlePaint.color = Color.WHITE
            canvas.drawText(appTitle, cx, cy + dp(8f), centerTitlePaint)
            centerSubPaint.color = Color.parseColor("#00E676")
            canvas.drawText("แบมือเพื่อเปิดแอป", cx, cy + dp(24f), centerSubPaint)
        }

        // Live Spatial Virtual Joystick Cursor Dot
        val hasJoystickOffset = (cursorNormX * cursorNormX + cursorNormY * cursorNormY) > 0.002f
        val dotX: Float
        val dotY: Float
        if (hasJoystickOffset) {
            val maxCursorReach = orbitR * 0.92f
            dotX = cx + cursorNormX * maxCursorReach
            dotY = cy + cursorNormY * maxCursorReach
        } else if (!inCancelZone) {
            val pRad = Math.toRadians((currentAngleDeg - 90.0f).toDouble())
            dotX = cx + ((innerR + dp(10f)) * cos(pRad)).toFloat()
            dotY = cy + ((innerR + dp(10f)) * sin(pRad)).toFloat()
        } else {
            dotX = cx
            dotY = cy
        }

        if (hasJoystickOffset || !inCancelZone) {
            canvas.drawLine(cx, cy, dotX, dotY, joystickBeamPaint)
            canvas.drawCircle(dotX, dotY, dp(5.5f), cursorDotPaint)
            canvas.drawCircle(dotX, dotY, dp(5.5f), activeWedgeBorderPaint)
        }
    }
}
