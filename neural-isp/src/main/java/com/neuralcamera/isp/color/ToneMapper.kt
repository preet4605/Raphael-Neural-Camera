package com.neuralcamera.isp.color

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow

/**
 * Global tone mapping from scene-linear sRGB to display-referred 8-bit sRGB.
 *
 * - [exposure] scales scene-linear values first (1 = unchanged).
 * - Highlights above [shoulderStart] roll off smoothly towards 1.0 instead of clipping; the curve is applied to the
 *   brightest channel and the three channels are scaled together, so hue is preserved through the shoulder.
 * - Negative values (from colour-matrix overshoot) are clamped to zero.
 * No local tone mapping, sharpening or contrast boost: this is deliberately a conservative baseline.
 */
data class ToneParams(val exposure: Double = 1.0, val shoulderStart: Double = 0.75) {
    init {
        require(exposure > 0.0) { "exposure must be positive" }
        require(shoulderStart in 0.1..0.99) { "shoulderStart must lie in 0.1..0.99" }
    }
}

object ToneMapper {

    /** Monotonic curve: identity up to [start], then an exponential shoulder asymptotic to 1. C1-continuous at [start]. */
    fun shoulder(x: Double, start: Double): Double =
        if (x <= start) x else start + (1.0 - start) * (1.0 - exp(-(x - start) / (1.0 - start)))

    fun srgbEncode(linear: Double): Double =
        if (linear <= 0.0031308) 12.92 * linear else 1.055 * linear.pow(1.0 / 2.4) - 0.055

    private const val LUT_SIZE = 4096
    private val encodeLut = DoubleArray(LUT_SIZE + 1) { srgbEncode(it.toDouble() / LUT_SIZE) }

    private fun encodeFast(v: Double): Int {
        val p = v.coerceIn(0.0, 1.0) * LUT_SIZE
        val i = p.toInt().coerceAtMost(LUT_SIZE - 1)
        val e = encodeLut[i] + (encodeLut[i + 1] - encodeLut[i]) * (p - i)
        return (e * 255.0 + 0.5).toInt().coerceIn(0, 255)
    }

    /** @return interleaved RGB bytes, length width * height * 3 */
    fun toSrgb8(linear: RgbImage, params: ToneParams = ToneParams()): ByteArray {
        val out = ByteArray(linear.data.size)
        var i = 0
        while (i < linear.data.size) {
            var r = max(linear.data[i] * params.exposure, 0.0)
            var g = max(linear.data[i + 1] * params.exposure, 0.0)
            var b = max(linear.data[i + 2] * params.exposure, 0.0)
            val peak = max(r, max(g, b))
            if (peak > params.shoulderStart) {
                val scale = shoulder(peak, params.shoulderStart) / peak
                r *= scale; g *= scale; b *= scale
            }
            out[i] = encodeFast(r).toByte()
            out[i + 1] = encodeFast(g).toByte()
            out[i + 2] = encodeFast(b).toByte()
            i += 3
        }
        return out
    }
}
