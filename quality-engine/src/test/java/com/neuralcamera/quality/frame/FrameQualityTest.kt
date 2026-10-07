package com.neuralcamera.quality.frame

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class FrameQualityTest {
    private val w = 64
    private val h = 64

    /** Checkerboard of 4x4 blocks (sharp), optionally box-blurred and noised; deterministic. */
    private fun scene(blur: Int, noiseSigma: Double, seed: Long = 1, offset: Int = 0): ByteArray {
        val base = DoubleArray(w * h) { i -> if (((i % w) / 4 + (i / w) / 4) % 2 == 0) 60.0 else 180.0 }
        val out = DoubleArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0.0; var n = 0
            for (dy in -blur..blur) for (dx in -blur..blur) {
                val xx = (x + dx).coerceIn(0, w - 1); val yy = (y + dy).coerceIn(0, h - 1); s += base[yy * w + xx]; n++
            }
            out[y * w + x] = s / n
        }
        val rnd = Random(seed)
        return ByteArray(w * h) { (out[it] + offset + rnd.nextGaussian() * noiseSigma).toInt().coerceIn(0, 255).toByte() }
    }

    @Test
    fun sharpnessDropsWithBlur() {
        val sharp = FrameQualityEngine.measure(scene(0, 0.0), w, h).sharpness
        val blurred = FrameQualityEngine.measure(scene(2, 0.0), w, h).sharpness
        assertTrue(sharp > 2 * blurred)
    }

    @Test
    fun noiseEstimateTracksInjectedNoise() {
        val flat = ByteArray(w * h) { 120 }
        val rnd = Random(3)
        val noisy = ByteArray(w * h) { (120 + rnd.nextGaussian() * 4.0).toInt().toByte() }
        assertEquals(0.0, FrameQualityEngine.measure(flat, w, h).noiseSigma, 1e-9)
        assertEquals(4.0, FrameQualityEngine.measure(noisy, w, h).noiseSigma, 1.0)
    }

    @Test
    fun clippingIsMeasured() {
        val m = FrameQualityEngine.measure(ByteArray(w * h) { if (it < w * h / 4) 255.toByte() else 100 }, w, h)
        assertEquals(0.25, m.highlightClipFraction, 1e-9)
    }

    @Test
    fun rankingPrefersSharpFocusedAlignedFrame() {
        val frames = listOf(scene(2, 2.0), scene(0, 2.0), scene(1, 2.0))
        val metrics = frames.map { FrameQualityEngine.measure(it, w, h) }
        val ctx = List(3) { FrameContext(focusConverged = true, gyroBlurPx = 0.3, alignmentWeight = 0.9) }
        val ranked = FrameQualityEngine.rank(metrics, ctx)
        assertEquals(1, FrameQualityEngine.bestFrame(ranked))
        assertEquals(listOf(1, 2, 0), ranked.map { it.index })
    }

    @Test
    fun hardRejectionsOverrideScore() {
        val metrics = listOf(scene(0, 0.0), scene(1, 0.0)).map { FrameQualityEngine.measure(it, w, h) }
        val ctx = listOf(FrameContext(false, 0.1, 1.0), FrameContext(true, 0.1, 1.0))
        val ranked = FrameQualityEngine.rank(metrics, ctx)
        assertEquals(1, FrameQualityEngine.bestFrame(ranked))
        assertTrue(ranked.last().rejected && ranked.last().notes.contains("AF not converged"))
    }

    @Test
    fun allRejectedMeansNoBestFrame() {
        val m = FrameQualityEngine.measure(ByteArray(w * h) { 255.toByte() }, w, h)
        assertNull(FrameQualityEngine.bestFrame(FrameQualityEngine.rank(listOf(m), listOf(FrameContext(null, null, null)))))
    }

    @Test
    fun unknownContextIsNeutralAndNoted() {
        val m = FrameQualityEngine.measure(scene(0, 1.0), w, h)
        val s = FrameQualityEngine.rank(listOf(m), listOf(FrameContext(null, null, null))).single()
        assertEquals(0.5, s.focusConfidence, 0.0)
        assertTrue(s.notes.containsAll(listOf("focus state unknown", "gyro blur unknown")))
    }
}
