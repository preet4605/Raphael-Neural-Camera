package com.neuralcamera.isp.color

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic calibration (test fixtures, not a real camera). */
class DualIlluminantTest {
    // XYZ -> camera for illuminant A (17) and D65 (21): deliberately different so the interpolation matters.
    private val cmA = doubleArrayOf(0.95, 0.10, -0.15, -0.30, 1.20, 0.10, -0.05, 0.25, 0.60)
    private val cmD65 = doubleArrayOf(0.80, 0.05, -0.05, -0.35, 1.25, 0.10, -0.10, 0.20, 0.90)

    /** xy of the Planckian point at [mired] from Robertson's table row (u, v). */
    private fun planckXy(u: Double, v: Double): Pair<Double, Double> {
        val d = 2 * u - 8 * v + 4
        return 3 * u / d to 2 * v / d
    }

    private fun neutralFor(cm: DoubleArray, x: Double, y: Double): DoubleArray =
        Matrix3(cm).apply(doubleArrayOf(x / y, 1.0, (1 - x - y) / y))

    private fun mix(a: DoubleArray, b: DoubleArray, g: Double) = DoubleArray(9) { g * a[it] + (1 - g) * b[it] }

    @Test
    fun robertsonCctMatchesTheStandardIlluminants() {
        assertEquals(2856.0, Cct.fromXy(0.44757, 0.40745), 3.0)
        assertEquals(5003.0, Cct.fromXy(0.34567, 0.35850), 3.0)
        assertEquals(6504.0, Cct.fromXy(0.31271, 0.32902), 3.0)
        assertEquals(7504.0, Cct.fromXy(0.29902, 0.31485), 3.0)
    }

    @Test
    fun recoversTheSceneTemperatureAndWeight() {
        val (x, y) = planckXy(0.22511, 0.33439) // 250 mired = 4000 K
        val gTrue = (1 / 4000.0 - 1 / 6504.0) / (1 / 2856.0 - 1 / 6504.0)
        val neutral = neutralFor(mix(cmA, cmD65, gTrue), x, y)
        val r = DualIlluminant.interpolate(neutral, cmA, cmD65, null, null, 17, 21)
        assertEquals(4000.0, r.cctKelvin!!, 5.0)
        assertEquals(gTrue, r.weight1, 2e-3)
        assertTrue(r.note, r.note.startsWith("dual-illuminant"))
        // Same answer with the sets swapped (the weight is then on D65).
        val swapped = DualIlluminant.interpolate(neutral, cmD65, cmA, null, null, 21, 17)
        assertEquals(1 - gTrue, swapped.weight1, 2e-3)
    }

    @Test
    fun clampsOutsideTheCalibrationRangeAndRefusesUnknownIlluminants() {
        val (x, y) = planckXy(0.30505, 0.35907) // 500 mired = 2000 K, warmer than A
        val warm = DualIlluminant.interpolate(neutralFor(cmA, x, y), cmA, cmD65, null, null, 17, 21)
        assertEquals(1.0, warm.weight1, 0.0)
        val unknown = DualIlluminant.interpolate(doubleArrayOf(0.5, 1.0, 0.7), cmA, cmD65, null, null, 17, 2)
        assertNull(unknown.cctKelvin)
        assertTrue(unknown.note, unknown.note.contains("no distinct standard CCT"))
        assertEquals(cmA.toList(), unknown.colorMatrix!!.toList())
    }

    @Test
    fun interpolatedTransformMapsTheSceneWhiteToNeutral() {
        val (x, y) = planckXy(0.22511, 0.33439)
        val neutral = neutralFor(mix(cmA, cmD65, 0.5), x, y)
        val t = ColorTransform.fromCalibration(neutral, cmA, cmD65, null, null, 17, 21)
        assertEquals(ColorTransform.Source.COLOR_MATRIX, t.source)
        val out = FloatArray(3)
        t.apply(neutral[0].toFloat(), neutral[1].toFloat(), neutral[2].toFloat(), out, 0)
        assertEquals(out[0], out[1], 1e-4f * out[1])
        assertEquals(out[2], out[1], 1e-4f * out[1])
    }
}
