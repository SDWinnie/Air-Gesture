package com.sdwinnie.airgesture.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.sdwinnie.airgesture.model.HudThemeColor

/**
 * Hardware-Accelerated Touchless Air Cursor & Tap Ripple Overlay (v4.0).
 *
 * Renders:
 * 1. A glowing precision target reticle tracking the user's hand pointer across any app.
 * 2. Expanding shockwave ring ripple when a touchless Air Tap (เคาะจอ / จดจอ) occurs.
 * Bound to WindowManager via [TYPE_ACCESSIBILITY_OVERLAY] with FLAG_NOT_TOUCHABLE.
 */
class AirCursorOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private fun dp(v: Float): Float = v * density

    private var themeColor: HudThemeColor = HudThemeColor.CYBER_CYAN
    private var isCursorVisible = false

    // Normalized screen coordinates [0..1]
    private var normTargetX = 0.5f
    private var normTargetY = 0.5f
    private var currentScreenX = 0f
    private var currentScreenY = 0f

    // Ripple animation
    private var rippleProgress = 1.0f // 0.0 -> 1.0
    private var rippleAnim: ValueAnimator? = null

    private val reticlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.8f)
        color = Color.parseColor("#00E5FF")
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#00E676")
    }

    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    fun applyTheme(theme: HudThemeColor) {
        themeColor = theme
        reticlePaint.color = Color.parseColor(theme.primaryHex)
        dotPaint.color = Color.parseColor(theme.secondaryHex)
        postInvalidateOnAnimation()
    }

    fun updatePointer(normX: Float, normY: Float, visible: Boolean) {
        normTargetX = normX.coerceIn(0f, 1f)
        normTargetY = normY.coerceIn(0f, 1f)
        if (isCursorVisible != visible) {
            isCursorVisible = visible
        }
        postInvalidateOnAnimation()
    }

    fun triggerAirTapRipple() {
        rippleAnim?.cancel()
        rippleAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260L
            interpolator = DecelerateInterpolator()
            addUpdateListener { va ->
                rippleProgress = va.animatedValue as Float
                postInvalidateOnAnimation()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!isCursorVisible) return

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 10f || h <= 10f) return

        val targetPixelX = normTargetX * w
        val targetPixelY = normTargetY * h

        if (currentScreenX <= 0f && currentScreenY <= 0f) {
            currentScreenX = targetPixelX
            currentScreenY = targetPixelY
        } else {
            currentScreenX += (targetPixelX - currentScreenX) * 0.45f
            currentScreenY += (targetPixelY - currentScreenY) * 0.45f
        }

        val cx = currentScreenX
        val cy = currentScreenY
        val reticleRadius = dp(18f)

        // 1. Ambient Glow Halo
        val haloRadius = dp(32f)
        val primaryCol = Color.parseColor(themeColor.primaryHex)
        haloPaint.shader = RadialGradient(
            cx, cy, haloRadius,
            Color.argb(55, Color.red(primaryCol), Color.green(primaryCol), Color.blue(primaryCol)),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, haloRadius, haloPaint)

        // 2. Precision Outer Ring with Tick Crosshairs
        canvas.drawCircle(cx, cy, reticleRadius, reticlePaint)
        val tickLen = dp(6f)
        canvas.drawLine(cx - reticleRadius - tickLen, cy, cx - reticleRadius + dp(2f), cy, reticlePaint)
        canvas.drawLine(cx + reticleRadius - dp(2f), cy, cx + reticleRadius + tickLen, cy, reticlePaint)
        canvas.drawLine(cx, cy - reticleRadius - tickLen, cx, cy - reticleRadius + dp(2f), reticlePaint)
        canvas.drawLine(cx, cy + reticleRadius - dp(2f), cx, cy + reticleRadius + tickLen, reticlePaint)

        // 3. Center Target Dot
        canvas.drawCircle(cx, cy, dp(3.5f), dotPaint)

        // 4. Expanding Shockwave Ripple on Air Tap
        if (rippleProgress < 1.0f) {
            val rMax = dp(54f)
            val rCurr = reticleRadius + (rMax - reticleRadius) * rippleProgress
            val alpha = ((1.0f - rippleProgress) * 255).toInt().coerceIn(0, 255)
            val secCol = Color.parseColor(themeColor.secondaryHex)
            ripplePaint.color = Color.argb(alpha, Color.red(secCol), Color.green(secCol), Color.blue(secCol))
            ripplePaint.strokeWidth = dp(3.2f) * (1.0f - rippleProgress * 0.6f)
            canvas.drawCircle(cx, cy, rCurr, ripplePaint)
        }

        if (kotlin.math.abs(targetPixelX - currentScreenX) > 1f ||
            kotlin.math.abs(targetPixelY - currentScreenY) > 1f ||
            rippleProgress < 1.0f
        ) {
            postInvalidateOnAnimation()
        }
    }
}
