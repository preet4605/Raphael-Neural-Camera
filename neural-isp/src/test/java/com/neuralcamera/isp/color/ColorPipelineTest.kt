package com.neuralcamera.isp.color

import com.neuralcamera.isp.dng.CfaPattern
import com.neuralcamera.isp.temporal.FloatPlane
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin

/** Synthetic-data checks of the colour stages (known ground truth). Not evidence about real sensor colour. */
class ColorPipelineTest {

    private val w = 64
    private val h = 48

    /** Smooth, band-limited ground-truth RGB scene. */
    private fun truth(x: Int, y: Int, c: Int): Double = when (c) {
        0 -> 0.5 + 0.35 * sin(x * 0.11 + y * 0.05)
        1 -> 0.4 + 0.3 * sin(y * 0.09 - x * 0.03)
        else -> 0.3 + 0.2 * sin((x + y) * 0.07)
    }

    private fun mosaicOf(cfa: CfaPattern): FloatPlane {
        val p = FloatPlane(w, h)
        for (y in 0 until h) for (x in 0 until w) p.data[y * w + x] = truth(x, y, cfa.colors[(y % 2) * 2 + x % 2]).toFloat()
        return p
    }

    @Test
    fun demosaicReconstructsSmoothSceneForEveryCfaLayout() {
        for (cfa in CfaPattern.entries) {
            val rgb = Demosaic.run(mosaicOf(cfa), cfa)
            var se = 0.0
            var n = 0
            for (y in 4 until h - 4) for (x in 4 until w - 4) for (c in 0 until 3) {
                val d = rgb.data[(y * w + x) * 3 + c] - truth(x, y, c)
                se += d * d; n++
            }
            val psnr = -10 * log10(se / n)
            assertTrue("$cfa PSNR $psnr", psnr > 45.0)
        }
    }

    @Test
    fun demosaicPassesSensorSamplesThroughUnchanged() {
        val cfa = CfaPattern.RGGB
        val m = mosaicOf(cfa)
        val rgb = Demosaic.run(m, cfa)
        for (y in 0 until h) for (x in 0 until w) {
            assertEquals(m.data[y * w + x], rgb.data[(y * w + x) * 3 + cfa.colors[(y % 2) * 2 + x % 2]], 0f)
        }
    }

    @Test
    fun flatGreyStaysGreyIncludingBorders() {
        val m = FloatPlane(w, h).also { it.data.fill(0.3f) }
        val rgb = Demosaic.run(m, CfaPattern.GBRG)
        assertTrue(rgb.data.all { abs(it - 0.3f) < 1e-5f })
    }

    @Test
    fun forwardMatrixOfD50IdentityMapsNeutralToWhite() {
        // FM that maps (1,1,1) white-balanced camera to D50 white XYZ.
        val fm = doubleArrayOf(0.9642, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.8249)
        val neutral = doubleArrayOf(0.5, 0.8, 0.4)
        val t = ColorTransform.from(neutral, fm, null)
        assertEquals(ColorTransform.Source.FORWARD_MATRIX, t.source)
        val out = FloatArray(3)
        t.apply(0.5f, 0.8f, 0.4f, out, 0)
        out.forEach { assertEquals(1.0f, it, 0.01f) }
    }

    @Test
    fun colorMatrixPathMapsSceneNeutralToWhiteToo() {
        // XYZ->camera for a made-up sensor; the scene neutral in camera space is whatever that sensor reports for white.
        val cm = doubleArrayOf(0.9, -0.1, 0.0, -0.2, 1.1, 0.05, 0.0, 0.1, 0.8)
        val neutral = doubleArrayOf(0.62, 0.9, 0.5)
        val t = ColorTransform.from(neutral, null, cm)
        assertEquals(ColorTransform.Source.COLOR_MATRIX, t.source)
        val out = FloatArray(3)
        t.apply(0.62f, 0.9f, 0.5f, out, 0)
        out.forEach { assertEquals(1.0f, it, 0.01f) }
    }

    @Test
    fun missingCalibrationFallsBackToWhiteBalanceOnlyAndSaysSo() {
        val t = ColorTransform.from(doubleArrayOf(0.5, 1.0, 0.25), null, null)
        assertEquals(ColorTransform.Source.WHITE_BALANCE_ONLY, t.source)
        val out = FloatArray(3)
        t.apply(0.5f, 1.0f, 0.25f, out, 0)
        out.forEach { assertEquals(1.0f, it, 1e-6f) }
        assertEquals(ColorTransform.Source.WHITE_BALANCE_ONLY, ColorTransform.from(null, null, null).source)
    }

    @Test
    fun toneCurveIsMonotonicContinuousAndBounded() {
        val start = 0.75
        var prev = -1.0
        var x = 0.0
        while (x <= 20.0) {
            val y = ToneMapper.shoulder(x, start)
            assertTrue(y >= prev && y <= 1.0)
            prev = y
            x += 0.01
        }
        assertEquals(0.5, ToneMapper.shoulder(0.5, start), 0.0)
        assertEquals(start, ToneMapper.shoulder(start + 1e-9, start), 1e-6)
        assertTrue(ToneMapper.shoulder(20.0, start) > 0.99)
    }

    @Test
    fun shoulderPreservesHueAndEncodesSrgbEndpoints() {
        val img = RgbImage(2, 1, floatArrayOf(0f, 0f, 0f, 4f, 2f, 1f))
        val out = ToneMapper.toSrgb8(img, ToneParams(highlightDesaturation = 0.0))
        assertEquals(0, out[0].toInt() and 255)
        val r = (out[3].toInt() and 255).toDouble()
        val g = (out[4].toInt() and 255).toDouble()
        val b = (out[5].toInt() and 255).toDouble()
        assertTrue(r >= g && g >= b && r > 240)
        // Linear ratio preserved through the shoulder: decode and compare.
        fun lin(v: Double) = Math.pow((v / 255.0 + 0.055) / 1.055, 2.4)
        assertEquals(2.0, lin(r) / lin(g), 0.15)
    }

    @Test
    fun pipelineProducesFullSizeOutput() {
        val cfa = CfaPattern.RGGB
        val t = ColorTransform.from(doubleArrayOf(0.5, 1.0, 0.6), null, null)
        val res = ColorPipeline.render(mosaicOf(cfa), cfa, t)
        assertEquals(w * h * 3, res.rgb8.size)
        assertEquals(ColorTransform.Source.WHITE_BALANCE_ONLY, res.transformSource)
    }

    private fun decode(out: ByteArray, i: Int) = Math.pow(((out[i].toInt() and 255) / 255.0 + 0.055) / 1.055, 2.4)

    @Test
    fun gamutMappingKeepsLuminanceAndHueWhereAClampWouldNot() {
        val w = GamutMapping.luminanceWeights(OutputSpace.SRGB)
        assertEquals(1.0, w.sum(), 1e-9)
        // The P3 green primary: outside sRGB, so its sRGB coordinates have negative red and blue.
        val xyz = OutputSpace.DISPLAY_P3.toXyzD50.apply(doubleArrayOf(0.0, 0.6, 0.0))
        val rgb = OutputSpace.SRGB.fromXyzD50.apply(xyz)
        assertTrue(rgb[0] < 0 && rgb[2] < 0)
        val out = DoubleArray(3)
        assertTrue(GamutMapping.toNonNegative(rgb[0], rgb[1], rgb[2], w, out))
        assertTrue(out.all { it >= 0.0 } && out.min() == 0.0)
        val y = w[0] * rgb[0] + w[1] * rgb[1] + w[2] * rgb[2]
        assertEquals(y, w[0] * out[0] + w[1] * out[1] + w[2] * out[2], 1e-12)
        // Same direction from grey: the mapped chroma is a positive multiple of the original.
        val k = (out[1] - y) / (rgb[1] - y)
        assertTrue(k > 0 && k < 1)
        for (c in 0..2) assertEquals(rgb[c] - y, (out[c] - y) / k, 1e-9)
        val clampedY = w[1] * rgb[1] // the old clamp to zero, for comparison
        assertTrue("clamp raises luminance", clampedY > y * 1.05)
        // In-gamut colours are untouched; zero or negative luminance becomes black.
        assertTrue(!GamutMapping.toNonNegative(0.2, 0.3, 0.4, w, out) && out.contentEquals(doubleArrayOf(0.2, 0.3, 0.4)))
        GamutMapping.toNonNegative(-0.5, 0.1, 0.0, w, out)
        assertTrue(out.all { it == 0.0 })
    }

    @Test
    fun highlightsDesaturateTowardsWhiteMonotonicallyAndMidtonesAreUntouched() {
        val params = ToneParams()
        var prev = DoubleArray(3)
        var prevSpread = Double.MAX_VALUE
        var k = 0.5
        while (k < 64.0) {
            val out = ToneMapper.toSrgb8(RgbImage(1, 1, floatArrayOf((0.8 * k).toFloat(), (0.4 * k).toFloat(), (0.2 * k).toFloat())), params)
            val lin = DoubleArray(3) { decode(out, it) }
            for (c in 0..2) assertTrue("channel $c at $k", lin[c] >= prev[c] - 1e-9)
            val spread = lin[0] - lin[2]
            if (k > 2.0) assertTrue("saturation falls in the shoulder at $k", spread <= prevSpread + 2e-3)
            prev = lin; prevSpread = spread; k *= 1.1
        }
        assertTrue("very bright saturated light reaches white", prev.all { it > 0.97 })
        // Below the shoulder nothing changes, desaturation or not.
        val mid = RgbImage(1, 1, floatArrayOf(0.6f, 0.3f, 0.1f))
        assertTrue(ToneMapper.toSrgb8(mid, params).contentEquals(ToneMapper.toSrgb8(mid, ToneParams(highlightDesaturation = 0.0))))
        // Without desaturation the same light stays saturated: red over blue keeps the scene's 4:1.
        val hot = ToneMapper.toSrgb8(RgbImage(1, 1, floatArrayOf(40f, 20f, 10f)), ToneParams(highlightDesaturation = 0.0))
        assertEquals(4.0, decode(hot, 0) / decode(hot, 2), 0.3)
    }
}
