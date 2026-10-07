package com.neuralcamera.isp.color

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CIEDE2000 against the Sharma, Wu & Dalal (2005) test pairs; every expected value was cross-checked with the
 * colour-science package. Chart measurement on synthetic patches (not a real chart capture).
 */
class ColorDifferenceTest {

    @Test
    fun deltaE2000MatchesTheSharmaTestData() {
        val pairs = listOf(
            Triple(Lab(50.0, 2.6772, -79.7751), Lab(50.0, 0.0, -82.7485), 2.0425),
            Triple(Lab(50.0, 3.1571, -77.2803), Lab(50.0, 0.0, -82.7485), 2.8615),
            Triple(Lab(50.0, 2.8361, -74.0200), Lab(50.0, 0.0, -82.7485), 3.4412),
            Triple(Lab(50.0, 0.0, 0.0), Lab(50.0, -1.0, 2.0), 2.3669),
            Triple(Lab(50.0, -0.001, 2.49), Lab(50.0, 0.0009, -2.49), 4.8045),
            Triple(Lab(50.0, -0.001, 2.49), Lab(50.0, 0.0011, -2.49), 4.7461),
            Triple(Lab(50.0, 2.49, -0.001), Lab(50.0, -2.49, 0.0009), 7.1792),
            Triple(Lab(50.0, 2.5, 0.0), Lab(73.0, 25.0, -18.0), 27.1492),
            Triple(Lab(50.0, 2.5, 0.0), Lab(61.0, -5.0, 29.0), 22.8977),
            Triple(Lab(50.0, 2.5, 0.0), Lab(56.0, -27.0, -3.0), 31.9030),
            Triple(Lab(50.0, 2.5, 0.0), Lab(58.0, 24.0, 15.0), 19.4535),
            Triple(Lab(60.2574, -34.0099, 36.2677), Lab(60.4626, -34.1751, 39.4387), 1.2644),
            Triple(Lab(63.0109, -31.0961, -5.8663), Lab(62.8187, -29.7946, -4.0864), 1.2630)
        )
        for ((a, b, expected) in pairs) {
            assertEquals("$a vs $b", expected, ColorDifference.deltaE2000(a, b), 1e-4)
            assertEquals("symmetric", expected, ColorDifference.deltaE2000(b, a), 1e-4)
        }
    }

    @Test
    fun labMatchesTheReferenceAndRoundTrips() {
        // colour-science XYZ_to_Lab with the pipeline's D50 white (0.96422, 1, 0.82521).
        val lab = ColorDifference.labFromXyz(doubleArrayOf(0.2, 0.15, 0.1))
        assertEquals(45.63419701, lab.l, 1e-6); assertEquals(30.31008181, lab.a, 1e-6); assertEquals(7.29473312, lab.b, 1e-6)
        val dark = ColorDifference.labFromXyz(doubleArrayOf(0.005, 0.004, 0.003)) // below the linear-segment knee
        assertEquals(3.61318519, dark.l, 1e-6); assertEquals(4.61591638, dark.a, 1e-6); assertEquals(0.56777117, dark.b, 1e-6)
        for (xyz in listOf(doubleArrayOf(0.2, 0.15, 0.1), doubleArrayOf(0.005, 0.004, 0.003))) {
            val back = ColorDifference.xyzFromLab(ColorDifference.labFromXyz(xyz))
            for (i in 0..2) assertEquals(xyz[i], back[i], 1e-12)
        }
    }

    private val refs = listOf(
        "white" to Lab(96.0, -0.4, 1.5), "grey" to Lab(50.9, -0.3, 0.1), "red" to Lab(42.0, 53.0, 28.0),
        "green" to Lab(55.0, -38.0, 31.0), "blue" to Lab(29.0, 14.0, -50.0)
    )

    /** One 40x40 patch per reference colour, rendered exactly in linear [space] RGB times [gain]. */
    private fun chart(space: OutputSpace, gain: Double, colourOf: (Lab) -> Lab = { it }): Pair<RgbImage, List<ChartPatch>> {
        val img = RgbImage(40 * refs.size, 40)
        val patches = refs.mapIndexed { i, (name, lab) ->
            val rgb = space.fromXyzD50.apply(ColorDifference.xyzFromLab(colourOf(lab)))
            for (y in 0 until 40) for (x in 40 * i until 40 * i + 40) for (c in 0..2) img.data[(y * img.width + x) * 3 + c] = (rgb[c] * gain).toFloat()
            ChartPatch(name, 40 * i, 0, 40, 40, lab)
        }
        return img to patches
    }

    @Test
    fun anExactRenderMeasuresZeroAndExposureIsNormalizedAway() {
        val (img, patches) = chart(OutputSpace.DISPLAY_P3, 1.0)
        assertTrue(ChartMeasurement.measure(img, OutputSpace.DISPLAY_P3, patches).maxDeltaE < 1e-5)
        val (dim, p2) = chart(OutputSpace.DISPLAY_P3, 0.7)
        assertTrue(ChartMeasurement.measure(dim, OutputSpace.DISPLAY_P3, p2).maxDeltaE > 5)
        val normalized = ChartMeasurement.measure(dim, OutputSpace.DISPLAY_P3, p2, normalizeTo = "white")
        assertEquals(1 / 0.7, normalized.exposureScale, 1e-6) // pixels are float
        assertTrue(normalized.note, normalized.maxDeltaE < 1e-5)
    }

    @Test
    fun aColourErrorIsReportedAsItsDeltaE() {
        val shift = { l: Lab -> Lab(l.l, l.a + 4, l.b - 2) }
        val (img, patches) = chart(OutputSpace.SRGB, 1.0, shift)
        val report = ChartMeasurement.measure(img, OutputSpace.SRGB, patches)
        for (p in report.patches) assertEquals(p.name, ColorDifference.deltaE2000(p.reference, shift(p.reference)), p.deltaE2000, 1e-4)
    }
}
