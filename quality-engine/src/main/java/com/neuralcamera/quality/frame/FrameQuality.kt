package com.neuralcamera.quality.frame

import kotlin.math.abs

/*
 * Per-frame quality engine: sharpness, blur, exposure, clipping, focus confidence, noise, alignment quality and motion,
 * combined into a ranking used for reference-frame (best-frame) selection.
 *
 * Each metric is a measurement on the frame's own 8-bit luma (or on reported metadata); none is learned. Scores are
 * comparable within one burst, not across scenes. Weights are design defaults: their agreement with human judgement on
 * real OnePlus 15 captures is NOT_TESTED. (The older StandardQualityEvaluator derives "sharpness" and "noise" from the
 * same global standard deviation; it is kept for the Reality Guard path but is not a sharpness or noise measure.)
 */

data class FrameMetrics(
    /** Variance of a 4-neighbour Laplacian on a 2x box-downsampled plane (noise-robust sharpness). */
    val sharpness: Double,
    /** Global noise sigma in 8-bit DN (Immerkaer median estimator). */
    val noiseSigma: Double,
    val meanLuma: Double,
    val highlightClipFraction: Double,
    val shadowClipFraction: Double
)

/** Facts about the frame from capture metadata and other stages; null = not available (never assumed good). */
data class FrameContext(
    /** AF reported FOCUSED_LOCKED / PASSIVE_FOCUSED for this frame. */
    val focusConverged: Boolean?,
    /** Gyro-estimated blur in pixels during exposure. */
    val gyroBlurPx: Double?,
    /** Mean merge weight of this frame against the reference (1 = fully usable), from FrameMergeStats. */
    val alignmentWeight: Double?
)

data class FrameScore(
    val index: Int,
    val metrics: FrameMetrics,
    /** Sharpness relative to the sharpest frame of the burst, 0..1. */
    val relativeSharpness: Double,
    val exposureScore: Double,
    val focusConfidence: Double,
    val motionScore: Double,
    val alignmentScore: Double?,
    val total: Double,
    /** A frame that fails a hard check is never selected as reference, whatever its total. */
    val rejected: Boolean,
    val notes: List<String>
)

object FrameQualityEngine {
    fun measure(luma: ByteArray, width: Int, height: Int): FrameMetrics {
        require(luma.size == width * height && width >= 4 && height >= 4) { "size mismatch" }
        var sum = 0L; var hi = 0; var lo = 0
        for (b in luma) { val v = b.toInt() and 0xFF; sum += v; if (v >= 250) hi++; if (v <= 5) lo++ }
        val n = luma.size.toDouble()
        return FrameMetrics(laplacianVariance(luma, width, height), noiseSigma(luma, width, height), sum / n, hi / n, lo / n)
    }

    fun laplacianVariance(luma: ByteArray, width: Int, height: Int): Double {
        val w = width / 2; val h = height / 2
        val s = DoubleArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val i = 2 * y * width + 2 * x
            s[y * w + x] = ((luma[i].toInt() and 0xFF) + (luma[i + 1].toInt() and 0xFF) +
                (luma[i + width].toInt() and 0xFF) + (luma[i + width + 1].toInt() and 0xFF)) / 4.0
        }
        var m = 0.0; var m2 = 0.0; var c = 0
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val v = 4 * s[i] - s[i - 1] - s[i + 1] - s[i - w] - s[i + w]
            m += v; m2 += v * v; c++
        }
        if (c == 0) return 0.0
        m /= c
        return m2 / c - m * m
    }

    /** Immerkaer noise estimate with a median (robust to edges). */
    fun noiseSigma(luma: ByteArray, width: Int, height: Int): Double {
        val r = DoubleArray((width - 2) * (height - 2))
        var k = 0
        fun p(x: Int, y: Int) = (luma[y * width + x].toInt() and 0xFF).toDouble()
        for (y in 1 until height - 1) for (x in 1 until width - 1) {
            r[k++] = abs(p(x - 1, y - 1) - 2 * p(x, y - 1) + p(x + 1, y - 1) - 2 * p(x - 1, y) + 4 * p(x, y) -
                2 * p(x + 1, y) + p(x - 1, y + 1) - 2 * p(x, y + 1) + p(x + 1, y + 1))
        }
        r.sort()
        return r[r.size / 2] / (0.6745 * 6.0)
    }

    /**
     * Scores and ranks a burst. Hard rejections: unusable exposure (> 30% clipped highlights or a near-black frame),
     * AF explicitly not converged, or an alignment weight below 0.1. Ranking is by total score, ties broken by index.
     */
    fun rank(metrics: List<FrameMetrics>, contexts: List<FrameContext>): List<FrameScore> {
        require(metrics.isNotEmpty() && metrics.size == contexts.size) { "one context per frame" }
        val maxSharp = metrics.maxOf { it.sharpness }.coerceAtLeast(1e-9)
        val scores = metrics.indices.map { i ->
            val m = metrics[i]; val c = contexts[i]
            val notes = ArrayList<String>()
            val rel = m.sharpness / maxSharp
            val exposure = (1.0 - abs(m.meanLuma - 118.0) / 118.0).coerceIn(0.0, 1.0) *
                (1.0 - (m.highlightClipFraction * 5).coerceAtMost(1.0))
            val focus = when (c.focusConverged) { true -> 1.0; false -> 0.0; null -> 0.5.also { notes.add("focus state unknown") } }
            val motion = c.gyroBlurPx?.let { 1.0 / (1.0 + it / 2.0) } ?: 0.5.also { notes.add("gyro blur unknown") }
            val align = c.alignmentWeight
            var rejected = false
            if (m.highlightClipFraction > 0.3) { rejected = true; notes.add("over 30% highlights clipped") }
            if (m.meanLuma < 3.0) { rejected = true; notes.add("frame is near black") }
            if (c.focusConverged == false) { rejected = true; notes.add("AF not converged") }
            if (align != null && align < 0.1) { rejected = true; notes.add("alignment weight ${"%.2f".format(align)} < 0.1") }
            val total = 0.45 * rel + 0.15 * exposure + 0.15 * focus + 0.15 * motion + 0.10 * (align ?: 0.5)
            FrameScore(i, m, rel, exposure, focus, motion, align, total, rejected, notes)
        }
        return scores.sortedWith(compareBy<FrameScore>({ it.rejected }, { -it.total }, { it.index }))
    }

    /** Index of the best non-rejected frame, or null when every frame was rejected (the caller must fail, not guess). */
    fun bestFrame(ranked: List<FrameScore>): Int? = ranked.firstOrNull { !it.rejected }?.index
}
