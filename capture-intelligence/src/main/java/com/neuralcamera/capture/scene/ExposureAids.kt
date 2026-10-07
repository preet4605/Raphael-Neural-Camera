package com.neuralcamera.capture.scene

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/*
 * Viewfinder exposure and focus aids computed from an 8-bit luma plane (a preview/analysis YUV frame's Y plane,
 * pixelStride 1, any rowStride). They describe the display-referred preview, not the RAW sensor data: a zebra on the
 * preview means the preview is near clipping, which on a RAW capture may still have headroom.
 */

/** Value histogram of one luma plane (every [step]-th pixel in both directions). */
class LumaHistogram(val bins: IntArray, val samples: Int) {
    fun fractionAtOrAbove(level: Int): Double = (level.coerceIn(0, 256) until 256).sumOf { bins[it] } / samples.toDouble()
    fun fractionAtOrBelow(level: Int): Double = (0..level.coerceIn(-1, 255)).sumOf { bins[it] } / samples.toDouble()

    companion object {
        fun of(luma: ByteArray, width: Int, height: Int, rowStride: Int = width, step: Int = 1): LumaHistogram {
            checkPlane(luma, width, height, rowStride)
            require(step >= 1) { "step must be >= 1" }
            val bins = IntArray(256)
            var n = 0
            for (y in 0 until height step step) {
                val row = y * rowStride
                for (x in 0 until width step step) { bins[luma[row + x].toInt() and 0xFF]++; n++ }
            }
            return LumaHistogram(bins, n)
        }
    }
}

/** A per-pixel overlay mask at the plane's resolution (row-major, width x height). */
class AidMask(val width: Int, val height: Int, val bits: BooleanArray) {
    val count: Int get() = bits.count { it }
    operator fun get(x: Int, y: Int) = bits[y * width + x]
}

object Zebra {
    /** Default: 8-bit code 235, about 92 % of full scale on the preview. */
    const val DEFAULT_LEVEL = 235

    fun mask(luma: ByteArray, width: Int, height: Int, rowStride: Int = width, level: Int = DEFAULT_LEVEL): AidMask {
        checkPlane(luma, width, height, rowStride)
        require(level in 1..255) { "level must be in 1..255" }
        val bits = BooleanArray(width * height)
        for (y in 0 until height) for (x in 0 until width) bits[y * width + x] = (luma[y * rowStride + x].toInt() and 0xFF) >= level
        return AidMask(width, height, bits)
    }
}

/**
 * Focus peaking: pixels whose Sobel gradient magnitude exceeds a contrast threshold for the chosen sensitivity, and
 * also a noise floor (5 sigma of the gradient a noise-only frame would show), so sensor noise in a dark or flat
 * preview does not light up. Sharp edges peak; the same edge defocused has a lower gradient and stops peaking.
 */
object FocusPeaking {
    /** Sobel magnitude threshold on 8-bit values. A sharp step of 100 code values has a magnitude of 400. */
    enum class Sensitivity(val threshold: Double) { LOW(160.0), MEDIUM(100.0), HIGH(60.0) }

    class Result(val mask: AidMask, val noiseSigma: Double, val threshold: Double)

    fun compute(luma: ByteArray, width: Int, height: Int, rowStride: Int = width, sensitivity: Sensitivity = Sensitivity.MEDIUM): Result {
        checkPlane(luma, width, height, rowStride)
        require(width >= 3 && height >= 3) { "plane too small" }
        fun v(x: Int, y: Int) = luma[y * rowStride + x].toInt() and 0xFF
        val sigma = noiseSigma(luma, width, height, rowStride)
        // Sobel x on white noise: weights (1, 2, 1) on each side, variance 2 * (1 + 4 + 1) * sigma^2.
        val threshold = max(sensitivity.threshold, 5.0 * sqrt(12.0) * sigma)
        val t2 = threshold * threshold
        val bits = BooleanArray(width * height)
        for (y in 1 until height - 1) for (x in 1 until width - 1) {
            val gx = (v(x + 1, y - 1) + 2 * v(x + 1, y) + v(x + 1, y + 1)) - (v(x - 1, y - 1) + 2 * v(x - 1, y) + v(x - 1, y + 1))
            val gy = (v(x - 1, y + 1) + 2 * v(x, y + 1) + v(x + 1, y + 1)) - (v(x - 1, y - 1) + 2 * v(x, y - 1) + v(x + 1, y - 1))
            bits[y * width + x] = gx.toDouble() * gx + gy.toDouble() * gy > t2
        }
        return Result(AidMask(width, height, bits), sigma, threshold)
    }

    /**
     * Noise sigma from the median absolute horizontal difference (robust to edges, which are few): for white noise a
     * difference has sigma * sqrt(2), and the median absolute value of a Gaussian is 0.6745 sigma.
     */
    fun noiseSigma(luma: ByteArray, width: Int, height: Int, rowStride: Int = width): Double {
        val hist = IntArray(256)
        var n = 0
        for (y in 0 until height) {
            val row = y * rowStride
            for (x in 0 until width - 1) { hist[abs((luma[row + x + 1].toInt() and 0xFF) - (luma[row + x].toInt() and 0xFF))]++; n++ }
        }
        var acc = 0
        var median = 0
        for (d in 0..255) { acc += hist[d]; if (acc * 2 >= n) { median = d; break } }
        return median / 0.6745 / sqrt(2.0)
    }
}

private fun checkPlane(luma: ByteArray, width: Int, height: Int, rowStride: Int) {
    require(width > 0 && height > 0 && rowStride >= width) { "bad plane geometry" }
    require(luma.size >= (height - 1) * rowStride + width) { "plane buffer too small for ${width}x$height, rowStride $rowStride" }
}
