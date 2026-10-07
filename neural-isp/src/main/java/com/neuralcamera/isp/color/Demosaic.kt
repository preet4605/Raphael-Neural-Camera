package com.neuralcamera.isp.color

import com.neuralcamera.isp.dng.CfaPattern
import com.neuralcamera.isp.temporal.FloatPlane

/** Interleaved RGB float image (r, g, b per pixel, row-major). */
class RgbImage(val width: Int, val height: Int, val data: FloatArray = FloatArray(width * height * 3)) {
    init {
        require(data.size == width * height * 3) { "data has ${data.size} samples for ${width}x$height RGB" }
    }
}

/**
 * Malvar-He-Cutler gradient-corrected linear demosaic (5x5 kernels). Each output sample is a fixed linear combination of
 * captured samples (no learned or invented content); the sensor sample at each site is passed through unchanged. Edges
 * use mirrored indexing. Values are not clamped here so a later stage can decide how to treat overshoot.
 */
object Demosaic {
    private const val R = 0
    private const val G = 1
    private const val B = 2

    private fun k(vararg v: Int) = FloatArray(25) { v[it] / 8f }

    private val GREEN_AT_RB = k(
        0, 0, -1, 0, 0,
        0, 0, 2, 0, 0,
        -1, 2, 4, 2, -1,
        0, 0, 2, 0, 0,
        0, 0, -1, 0, 0
    )
    /** Red/blue at a green site whose same-colour neighbours are left and right. */
    private val RB_AT_G_HORIZONTAL = FloatArray(25).also { f ->
        val v = intArrayOf(
            0, 0, 1, 0, 0,
            0, -2, 0, -2, 0,
            -2, 8, 10, 8, -2,
            0, -2, 0, -2, 0,
            0, 0, 1, 0, 0
        )
        for (i in 0 until 25) f[i] = v[i] / 16f
    }
    private val RB_AT_G_VERTICAL = FloatArray(25).also { f ->
        for (y in 0 until 5) for (x in 0 until 5) f[y * 5 + x] = RB_AT_G_HORIZONTAL[x * 5 + y]
    }
    private val RB_AT_OPPOSITE = FloatArray(25).also { f ->
        val v = intArrayOf(
            0, 0, -3, 0, 0,
            0, 4, 0, 4, 0,
            -3, 0, 12, 0, -3,
            0, 4, 0, 4, 0,
            0, 0, -3, 0, 0
        )
        for (i in 0 until 25) f[i] = v[i] / 16f
    }

    /** @param mosaic normalized (black-subtracted, white-scaled) linear mosaic */
    fun run(mosaic: FloatPlane, cfa: CfaPattern): RgbImage {
        val w = mosaic.width
        val h = mosaic.height
        require(w >= 6 && h >= 6) { "mosaic too small to demosaic" }
        val src = mosaic.data
        val out = RgbImage(w, h)
        fun mirror(i: Int, n: Int): Int = if (i < 0) -i else if (i >= n) 2 * n - 2 - i else i
        fun site(x: Int, y: Int) = cfa.colors[(y and 1) * 2 + (x and 1)]

        for (y in 0 until h) {
            val interiorY = y in 2 until h - 2
            for (x in 0 until w) {
                val own = site(x, y)
                val o = (y * w + x) * 3
                val interior = interiorY && x in 2 until w - 2
                fun conv(kernel: FloatArray): Float {
                    var s = 0f
                    var idx = 0
                    for (dy in -2..2) {
                        val yy = if (interior) y + dy else mirror(y + dy, h)
                        for (dx in -2..2) {
                            val kv = kernel[idx++]
                            if (kv != 0f) {
                                val xx = if (interior) x + dx else mirror(x + dx, w)
                                s += kv * src[yy * w + xx]
                            }
                        }
                    }
                    return s
                }
                val v = src[y * w + x]
                when (own) {
                    G -> {
                        out.data[o + G] = v
                        val rightColour = site(x + 1, y)
                        // Red/blue neighbours sit left-right on this row for one of the two colours, up-down for the other.
                        out.data[o + rightColour] = conv(RB_AT_G_HORIZONTAL)
                        out.data[o + (R + B - rightColour)] = conv(RB_AT_G_VERTICAL)
                    }
                    else -> {
                        out.data[o + own] = v
                        out.data[o + G] = conv(GREEN_AT_RB)
                        out.data[o + (R + B - own)] = conv(RB_AT_OPPOSITE)
                    }
                }
            }
        }
        return out
    }
}
