package com.neuralcamera.isp.temporal

import kotlin.math.abs
import kotlin.math.sqrt

/** Noise and sharpness estimates used to configure and seed the temporal merge. */
object FrameAnalysis {

    /**
     * Global Gaussian noise sigma (in the plane's own normalized units) from the median absolute response of the
     * Immerkaer 3x3 Laplacian-difference kernel, which cancels smooth structure; the median keeps edges and texture
     * from inflating the estimate. NaN samples are skipped. Returns 0 if there is nothing to measure.
     */
    fun noiseSigma(plane: FloatPlane): Double {
        val w = plane.width
        val h = plane.height
        if (w < 3 || h < 3) return 0.0
        // Sample at most ~250k interior pixels on a regular grid; an exact median of those is plenty for a global estimate.
        val interior = (w - 2).toLong() * (h - 2)
        val step = maxOf(1, Math.ceil(Math.sqrt(interior / 250_000.0)).toInt())
        val responses = FloatArray(((w - 2 + step - 1) / step) * ((h - 2 + step - 1) / step))
        var n = 0
        val d = plane.data
        var y = 1
        while (y < h - 1) {
            var x = 1
            while (x < w - 1) {
                val i = y * w + x
                val v = d[i - w - 1] - 2 * d[i - w] + d[i - w + 1] - 2 * d[i - 1] + 4 * d[i] - 2 * d[i + 1] + d[i + w - 1] - 2 * d[i + w] + d[i + w + 1]
                if (!v.isNaN()) responses[n++] = abs(v)
                x += step
            }
            y += step
        }
        if (n == 0) return 0.0
        java.util.Arrays.sort(responses, 0, n)
        val median = if (n % 2 == 1) responses[n / 2].toDouble() else (responses[n / 2 - 1] + responses[n / 2]) / 2.0
        // For iid Gaussian noise the kernel response has std 6*sigma; median(|N(0, s)|) = 0.6745 s.
        return median / (0.6745 * 6.0)
    }

    /**
     * Variance of a 4-neighbour Laplacian on a plane box-downsampled by [downsample]: higher means sharper. The
     * downsampling averages away most pixel noise (which would otherwise make the noisiest frame look sharpest) while
     * motion blur still removes mid-frequency detail.
     */
    fun laplacianVariance(plane: U16Plane, downsample: Int = 4): Double {
        val f = maxOf(1, downsample)
        val w = plane.width / f
        val h = plane.height / f
        if (w < 3 || h < 3) return 0.0
        val small = DoubleArray(w * h)
        val src = plane.data
        for (y in 0 until h) for (x in 0 until w) {
            var sum = 0
            for (dy in 0 until f) for (dx in 0 until f) sum += src[(y * f + dy) * plane.width + x * f + dx].toInt() and 0xFFFF
            small[y * w + x] = sum.toDouble() / (f * f)
        }
        var sum = 0.0
        var sumSq = 0.0
        var n = 0L
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val v = 4.0 * small[i] - small[i - 1] - small[i + 1] - small[i - w] - small[i + w]
                sum += v
                sumSq += v * v
                n++
            }
        }
        val mean = sum / n
        return sumSq / n - mean * mean
    }

    /** Index of the frame with the highest (noise-robust) Laplacian variance, i.e. the least motion-blurred. */
    fun sharpestIndex(planes: List<U16Plane>): Int {
        require(planes.isNotEmpty()) { "no frames" }
        return planes.indices.maxByOrNull { laplacianVariance(planes[it]) }!!
    }

    fun sigmaToVariance(sigma: Double): Double = sigma * sigma

    internal fun rms(values: DoubleArray): Double = sqrt(values.sumOf { it * it } / values.size)
}
