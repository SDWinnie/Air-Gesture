package com.sdwinnie.airgesture.vision

import android.graphics.Bitmap
import android.media.Image
import java.nio.ByteBuffer

/**
 * Ultra-Fast Isotropic 1:1 Volatile-RAM Downscaler & Zero-GC Buffer Reuser (v6.0).
 *
 * - Solves the 30-120 FPS bottleneck by replacing per-pixel ByteBuffer.get() JNI method calls
 *   with single-pass bulk native memcpy (ByteBuffer.get(ByteArray)).
 * - Reuses a single pre-allocated volatile Bitmap buffer across frames (zero GC pressure).
 * - Implements true 1:1 isotropic letterboxing with zero aspect ratio squashing distortion.
 * - Long-Distance & Wide-Angle Vision Booster (1.5 - 3.5m):
 *   Supports dynamic focal zoom (1.0x - 2.2x) and contrast equalization for distant hands/eyes.
 */
class VolatileRoiDownscaler(initialDimension: Int = 224) {

    var targetDimension: Int = initialDimension
        private set

    var virtualZoomFactor: Float = 1.0f
    var enableContrastBoost: Boolean = false

    fun getVolatileArgbBuffer(): IntArray = argbScratch

    private var argbScratch = IntArray(initialDimension * initialDimension)
    private var yScratch = ByteArray(0)
    private var uScratch = ByteArray(0)
    private var vScratch = ByteArray(0)

    @Volatile
    private var reusableBitmap: Bitmap? = null

    @Synchronized
    fun ensureDimension(dimension: Int) {
        if (dimension == targetDimension && argbScratch.size == dimension * dimension) return
        argbScratch.fill(0)
        targetDimension = dimension
        argbScratch = IntArray(dimension * dimension)
        reusableBitmap?.recycle()
        reusableBitmap = Bitmap.createBitmap(dimension, dimension, Bitmap.Config.ARGB_8888)
    }

    private fun ensureScratchSize(yLen: Int, uLen: Int, vLen: Int) {
        if (yScratch.size < yLen) yScratch = ByteArray((yLen * 1.15f).toInt())
        if (uScratch.size < uLen) uScratch = ByteArray((uLen * 1.15f).toInt())
        if (vScratch.size < vLen) vScratch = ByteArray((vLen * 1.15f).toInt())
    }

    @Synchronized
    fun downscaleIsotropicToVolatileBitmap(
        image: Image,
        uprightRotationDeg: Int,
        mirrorHorizontally: Boolean
    ): Bitmap? {
        val planes = image.planes ?: return null
        if (planes.size < 3) return null

        val dim = targetDimension
        val srcW = image.width
        val srcH = image.height
        if (srcW <= 1 || srcH <= 1) return null

        val yBuffer: ByteBuffer = planes[0].buffer ?: return null
        val uBuffer: ByteBuffer = planes[1].buffer ?: return null
        val vBuffer: ByteBuffer = planes[2].buffer ?: return null

        val yLen = yBuffer.remaining()
        val uLen = uBuffer.remaining()
        val vLen = vBuffer.remaining()
        if (yLen <= 0 || uLen <= 0 || vLen <= 0) return null

        val yRowStride = planes[0].rowStride
        val yPixelStride = planes[0].pixelStride
        val uRowStride = planes[1].rowStride
        val uPixelStride = planes[1].pixelStride
        val vRowStride = planes[2].rowStride
        val vPixelStride = planes[2].pixelStride

        ensureScratchSize(yLen, uLen, vLen)

        // Bulk native memory copy: 3 single calls instead of 150,000 individual JNI invocations!
        val yPos = yBuffer.position()
        yBuffer.get(yScratch, 0, yLen)
        yBuffer.position(yPos)

        val uPos = uBuffer.position()
        uBuffer.get(uScratch, 0, uLen)
        uBuffer.position(uPos)

        val vPos = vBuffer.position()
        vBuffer.get(vScratch, 0, vLen)
        vBuffer.position(vPos)

        val rot = ((uprightRotationDeg % 360) + 360) % 360
        val uprightW = if (rot == 90 || rot == 270) srcH else srcW
        val uprightH = if (rot == 90 || rot == 270) srcW else srcH
        val maxSide = maxOf(uprightW, uprightH).toFloat().coerceAtLeast(1f)

        val scaledW = ((dim * (uprightW.toFloat() / maxSide)).toInt()).coerceIn(1, dim)
        val scaledH = ((dim * (uprightH.toFloat() / maxSide)).toInt()).coerceIn(1, dim)
        val padLeft = (dim - scaledW) shr 1
        val padTop = (dim - scaledH) shr 1

        val invScaledW = 1.0f / scaledW.toFloat()
        val invScaledH = 1.0f / scaledH.toFloat()

        val out = argbScratch
        out.fill(-0x1000000) // Opaque black letterbox padding

        val yRaw = yScratch
        val uRaw = uScratch
        val vRaw = vScratch

        val zoom = virtualZoomFactor.coerceIn(1.0f, 2.4f)
        val invZoom = 1.0f / zoom
        val contrast = if (enableContrastBoost) 1.20f else 1.0f

        for (oy in 0 until scaledH) {
            val rawUy = ((oy + 0.5f) * invScaledH).coerceIn(0f, 0.9999f)
            val uy = if (zoom > 1.01f) (0.5f + (rawUy - 0.5f) * invZoom).coerceIn(0f, 0.9999f) else rawUy
            val rowOffset = (padTop + oy) * dim + padLeft

            for (ox in 0 until scaledW) {
                var rawUx = ((ox + 0.5f) * invScaledW).coerceIn(0f, 0.9999f)
                if (mirrorHorizontally) {
                    rawUx = (1.0f - rawUx).coerceIn(0f, 0.9999f)
                }
                val ux = if (zoom > 1.01f) (0.5f + (rawUx - 0.5f) * invZoom).coerceIn(0f, 0.9999f) else rawUx

                val nsx: Float
                val nsy: Float
                when (rot) {
                    90 -> {
                        nsx = uy
                        nsy = 1.0f - ux
                    }
                    180 -> {
                        nsx = 1.0f - ux
                        nsy = 1.0f - uy
                    }
                    270 -> {
                        nsx = 1.0f - uy
                        nsy = ux
                    }
                    else -> {
                        nsx = ux
                        nsy = uy
                    }
                }

                val sx = (nsx * srcW).toInt().coerceIn(0, srcW - 1)
                val sy = (nsy * srcH).toInt().coerceIn(0, srcH - 1)

                val hvX = sx shr 1
                val hvY = sy shr 1
                val yIndex = (sy * yRowStride + sx * yPixelStride).coerceIn(0, yLen - 1)
                val uIndex = (hvY * uRowStride + hvX * uPixelStride).coerceIn(0, uLen - 1)
                val vIndex = (hvY * vRowStride + hvX * vPixelStride).coerceIn(0, vLen - 1)

                val rawYVal = (yRaw[yIndex].toInt() and 0xFF) - 16
                val yVal = if (contrast > 1.01f && rawYVal > 0) ((rawYVal * contrast).toInt()).coerceIn(0, 235) else rawYVal
                val uVal = (uRaw[uIndex].toInt() and 0xFF) - 128
                val vVal = (vRaw[vIndex].toInt() and 0xFF) - 128

                val y1192 = if (yVal < 0) 0 else 1192 * yVal
                var r = (y1192 + 1634 * vVal) shr 10
                var g = (y1192 - 833 * vVal - 400 * uVal) shr 10
                var b = (y1192 + 2066 * uVal) shr 10

                if (r < 0) r = 0 else if (r > 255) r = 255
                if (g < 0) g = 0 else if (g > 255) g = 255
                if (b < 0) b = 0 else if (b > 255) b = 255

                out[rowOffset + ox] = (-0x1000000) or (r shl 16) or (g shl 8) or b
            }
        }

        var bmp = reusableBitmap
        if (bmp == null || bmp.isRecycled || bmp.width != dim || bmp.height != dim) {
            bmp?.recycle()
            bmp = Bitmap.createBitmap(dim, dim, Bitmap.Config.ARGB_8888)
            reusableBitmap = bmp
        }
        bmp.setPixels(out, 0, dim, 0, 0, dim, dim)
        return bmp
    }

    @Synchronized
    fun wipeVolatileMemory() {
        argbScratch.fill(0)
        yScratch.fill(0)
        uScratch.fill(0)
        vScratch.fill(0)
        reusableBitmap?.recycle()
        reusableBitmap = null
    }
}
