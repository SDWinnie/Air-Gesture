package com.sdwinnie.airgesture.vision

import android.graphics.Bitmap
import android.media.Image

/**
 * Crash-Hardened Decoupled Dual-Stream Volatile-RAM ROI Cropper & Downscaler (v2.0).
 *
 * - High-resolution Camera2 frames (1080p / 2K / 4K) are NEVER converted in full.
 * - Samples ONLY targetDim × targetDim pixels (192×192 or 256×256) inside the tracked ROI.
 * - Produces an isolated per-frame volatile Bitmap from pre-allocated scratch RAM so
 *   MediaPipe's MPImage.close() lifecycle can NEVER recycle a shared buffer across frames.
 */
class VolatileRoiDownscaler(initialDimension: Int = 192) {

    var targetDimension: Int = initialDimension
        private set

    private var argbScratch = IntArray(initialDimension * initialDimension)

    @Synchronized
    fun ensureDimension(dimension: Int) {
        if (dimension == targetDimension && argbScratch.size == dimension * dimension) return
        argbScratch.fill(0)
        targetDimension = dimension
        argbScratch = IntArray(dimension * dimension)
    }

    @Synchronized
    fun cropAndDownscaleToVolatileBitmap(
        image: Image,
        roi: RoiTracker.NormalizedRect,
        uprightRotationDeg: Int,
        mirrorHorizontally: Boolean
    ): Bitmap? {
        val planes = image.planes
        if (planes == null || planes.size < 3) return null

        val dim = targetDimension
        val srcW = image.width
        val srcH = image.height
        if (srcW <= 1 || srcH <= 1) return null

        val yBuffer = planes[0].buffer ?: return null
        val uBuffer = planes[1].buffer ?: return null
        val vBuffer = planes[2].buffer ?: return null

        val yRowStride = planes[0].rowStride
        val yPixelStride = planes[0].pixelStride
        val uRowStride = planes[1].rowStride
        val uPixelStride = planes[1].pixelStride
        val vRowStride = planes[2].rowStride
        val vPixelStride = planes[2].pixelStride

        val yLimit = yBuffer.limit()
        val uLimit = uBuffer.limit()
        val vLimit = vBuffer.limit()
        if (yLimit <= 0 || uLimit <= 0 || vLimit <= 0) return null

        val invDim = 1.0f / dim.toFloat()
        val roiLeft = roi.left
        val roiTop = roi.top
        val roiW = roi.width
        val roiH = roi.height
        val rot = ((uprightRotationDeg % 360) + 360) % 360

        val out = argbScratch
        var outIdx = 0

        for (oy in 0 until dim) {
            val uy = (roiTop + (oy + 0.5f) * invDim * roiH).coerceIn(0f, 0.9999f)
            for (ox in 0 until dim) {
                var ux = (roiLeft + (ox + 0.5f) * invDim * roiW).coerceIn(0f, 0.9999f)
                if (mirrorHorizontally) {
                    ux = (1.0f - ux).coerceIn(0f, 0.9999f)
                }

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
                val yIndex = (sy * yRowStride + sx * yPixelStride).coerceIn(0, yLimit - 1)
                val uIndex = (hvY * uRowStride + hvX * uPixelStride).coerceIn(0, uLimit - 1)
                val vIndex = (hvY * vRowStride + hvX * vPixelStride).coerceIn(0, vLimit - 1)

                val yVal = (yBuffer.get(yIndex).toInt() and 0xFF) - 16
                val uVal = (uBuffer.get(uIndex).toInt() and 0xFF) - 128
                val vVal = (vBuffer.get(vIndex).toInt() and 0xFF) - 128

                val y1192 = if (yVal < 0) 0 else 1192 * yVal
                var r = (y1192 + 1634 * vVal) shr 10
                var g = (y1192 - 833 * vVal - 400 * uVal) shr 10
                var b = (y1192 + 2066 * uVal) shr 10

                if (r < 0) r = 0 else if (r > 255) r = 255
                if (g < 0) g = 0 else if (g > 255) g = 255
                if (b < 0) b = 0 else if (b > 255) b = 255

                out[outIdx++] = (-0x1000000) or (r shl 16) or (g shl 8) or b
            }
        }

        // Creates an isolated immutable volatile Bitmap (144 KB for 192×192) safely owned by MPImage
        return Bitmap.createBitmap(out, dim, dim, Bitmap.Config.ARGB_8888)
    }

    @Synchronized
    fun wipeVolatileMemory() {
        argbScratch.fill(0)
    }
}
