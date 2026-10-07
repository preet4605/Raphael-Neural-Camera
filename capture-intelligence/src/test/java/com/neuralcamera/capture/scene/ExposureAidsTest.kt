package com.neuralcamera.capture.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.exp

/** Synthetic planes with known content (not preview frames from a device). */
class ExposureAidsTest {
    private val w = 64
    private val h = 48
    private val stride = 80 // padded rows, as Camera2 often returns them

    private fun plane(f: (Int, Int) -> Double): ByteArray {
        val b = ByteArray(stride * h) { 0x55 } // padding holds junk that must be ignored
        for (y in 0 until h) for (x in 0 until w) b[y * stride + x] = f(x, y).coerceIn(0.0, 255.0).toInt().toByte()
        return b
    }

    @Test
    fun histogramAndZebraCountExactlyAndIgnoreRowPadding() {
        val p = plane { x, _ -> if (x < 16) 240.0 else 100.0 }
        val hist = LumaHistogram.of(p, w, h, stride)
        assertEquals(w * h, hist.samples)
        assertEquals(16 * h, hist.bins[240]); assertEquals(48 * h, hist.bins[100]); assertEquals(0, hist.bins[0x55])
        assertEquals(0.25, hist.fractionAtOrAbove(Zebra.DEFAULT_LEVEL), 1e-12)
        assertEquals(0.75, hist.fractionAtOrBelow(100), 1e-12)
        val zebra = Zebra.mask(p, w, h, stride)
        assertEquals(16 * h, zebra.count)
        assertTrue(zebra[0, 0] && !zebra[16, 0])
        assertEquals(w * h / 4, LumaHistogram.of(p, w, h, stride, step = 2).samples)
    }

    @Test
    fun aSharpEdgePeaksAlongTheEdgeAndTheSameEdgeDefocusedDoesNot() {
        val sharp = FocusPeaking.compute(plane { x, _ -> if (x < 32) 60.0 else 180.0 }, w, h, stride)
        for (y in 1 until h - 1) assertTrue(sharp.mask[31, y] && sharp.mask[32, y])
        assertEquals(2 * (h - 2), sharp.mask.count) // nothing away from the edge
        // The same 120-level edge blurred (logistic, close to a Gaussian of sigma 5 px): peak slope about 10 levels per
        // pixel, so a Sobel magnitude near 80, between HIGH's 60 and MEDIUM's 100.
        val soft = plane { x, _ -> 60.0 + 120.0 / (1 + exp(-(x - 31.5) / (5 * 0.588))) }
        assertEquals(0, FocusPeaking.compute(soft, w, h, stride).mask.count)
        assertTrue(FocusPeaking.compute(soft, w, h, stride, FocusPeaking.Sensitivity.HIGH).mask.count > 0)
    }

    @Test
    fun noiseRaisesTheThresholdSoAFlatNoisyFrameDoesNotPeak() {
        val rng = Random(7)
        val noisy = plane { _, _ -> 120.0 + 12.0 * rng.nextGaussian() }
        val r = FocusPeaking.compute(noisy, w, h, stride, FocusPeaking.Sensitivity.HIGH)
        assertEquals(12.0, r.noiseSigma, 2.5)
        assertTrue("threshold ${r.threshold} must exceed HIGH's 60 on this noise", r.threshold > 150)
        assertTrue("false peaks ${r.mask.count}", r.mask.count < w * h / 1000 + 1)
    }
}
