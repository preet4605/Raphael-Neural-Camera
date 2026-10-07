package com.neuralcamera.isp.color

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow

/**
 * Global tone mapping from scene-linear RGB (sRGB or Display P3 primaries) to display-referred 8-bit, sRGB curve.
 *
 * - [exposure] scales scene-linear values first (1 = unchanged).
 * - Out-of-gamut colours (a negative channel, from colour-matrix overshoot on saturated light) are desaturated towards
 *   the grey of the same luminance just far enough to bring the lowest channel to zero ([GamutMapping]), instead of
 *   clamping the channel, which shifts hue and raises luminance. In-gamut colours are untouched.
 * - Highlights above [shoulderStart] roll off smoothly towards 1.0 instead of clipping; the curve is applied to the
 *   brightest channel and the three channels are scaled together, so hue is preserved through the shoulder.
 * - [highlightDesaturation] (0..1) then blends shouldered colours towards neutral, by the square of how far into the
 *   shoulder they are: nothing at [shoulderStart], all the way to white as the input goes to infinity. Without it a
 *   bright saturated light renders as a flat saturated patch that never reaches white; 0 keeps the hue-only shoulder.
 * No local tone mapping, sharpening or contrast boost: this is deliberately a conservative baseline.
 */
data class ToneParams(val exposure: Double = 1.0, val shoulderStart: Double = 0.75, val highlightDesaturation: Double = 1.0) {
    init {
        require(exposure > 0.0) { "exposure must be positive" }
        require(shoulderStart in 0.1..0.99) { "shoulderStart must lie in 0.1..0.99" }
        require(highlightDesaturation in 0.0..1.0) { "highlightDesaturation must lie in 0..1" }
    }
}

/** Luminance-preserving gamut mapping into the non-negative RGB cube of one output space. */
object GamutMapping {
    /** Weights of linear R, G, B in luminance Y: the Y row of the space's RGB to XYZ matrix (they sum to 1). */
    fun luminanceWeights(space: OutputSpace): DoubleArray = space.toXyzD50.m.copyOfRange(3, 6)

    /**
     * Moves (r, g, b) along the line to the grey of equal luminance until no channel is negative; writes the result to
     * [out] at [offset] and returns whether it moved. Non-positive luminance gives black.
     */
    fun toNonNegative(r: Double, g: Double, b: Double, w: DoubleArray, out: DoubleArray, offset: Int = 0): Boolean {
        val lo = minOf(r, g, b)
        if (lo >= 0.0) { out[offset] = r; out[offset + 1] = g; out[offset + 2] = b; return false }
        val y = w[0] * r + w[1] * g + w[2] * b
        if (y <= 0.0) { out[offset] = 0.0; out[offset + 1] = 0.0; out[offset + 2] = 0.0; return true }
        val t = y / (y - lo) // lo < 0 < y, so 0 < t < 1, and y + t * (lo - y) = 0
        // max(0, ..) only removes rounding residue (about -1e-17) on the channel that lands on zero.
        out[offset] = max(0.0, y + t * (r - y)); out[offset + 1] = max(0.0, y + t * (g - y)); out[offset + 2] = max(0.0, y + t * (b - y))
        return true
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

    /**
     * @param space the primaries of [linear]; only its luminance weights are used (gamut mapping)
     * @return interleaved RGB bytes, length width * height * 3
     */
    fun toSrgb8(linear: RgbImage, params: ToneParams = ToneParams(), space: OutputSpace = OutputSpace.SRGB): ByteArray {
        val out = ByteArray(linear.data.size)
        val w = GamutMapping.luminanceWeights(space)
        val px = DoubleArray(3)
        val start = params.shoulderStart
        var i = 0
        while (i < linear.data.size) {
            GamutMapping.toNonNegative(linear.data[i] * params.exposure, linear.data[i + 1] * params.exposure, linear.data[i + 2] * params.exposure, w, px)
            var r = px[0]; var g = px[1]; var b = px[2]
            val peak = max(r, max(g, b))
            if (peak > start) {
                val mapped = shoulder(peak, start)
                val scale = mapped / peak
                r *= scale; g *= scale; b *= scale
                if (params.highlightDesaturation > 0.0) {
                    val into = (mapped - start) / (1.0 - start)
                    val d = params.highlightDesaturation * into * into
                    r += d * (mapped - r); g += d * (mapped - g); b += d * (mapped - b)
                }
            }
            out[i] = encodeFast(r).toByte()
            out[i + 1] = encodeFast(g).toByte()
            out[i + 2] = encodeFast(b).toByte()
            i += 3
        }
        return out
    }
}
