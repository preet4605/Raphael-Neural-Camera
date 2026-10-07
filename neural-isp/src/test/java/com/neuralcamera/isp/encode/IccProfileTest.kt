package com.neuralcamera.isp.encode

import com.neuralcamera.isp.color.ColorTransform
import com.neuralcamera.isp.color.OutputSpace
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ICC profiles are checked with an independent parser: the JDK's java.awt.color (LittleCMS), reached by reflection
 * because Android unit tests compile against android.jar.
 */
class IccProfileTest {
    private class JdkIcc(bytes: ByteArray) {
        private val profileClass = Class.forName("java.awt.color.ICC_Profile")
        private val profile = profileClass.getMethod("getInstance", ByteArray::class.java).invoke(null, bytes)
        private val space = Class.forName("java.awt.color.ICC_ColorSpace").getConstructor(profileClass).newInstance(profile)
        val majorVersion = profileClass.getMethod("getMajorVersion").invoke(profile) as Int
        fun toXyz(rgb: FloatArray) = space.javaClass.getMethod("toCIEXYZ", FloatArray::class.java).invoke(space, rgb) as FloatArray
    }

    @Test
    fun srgbMatrixDerivedFromPrimariesMatchesThePublishedOne() {
        // Lindbloom's XYZ (D50, Bradford) -> linear sRGB, as hardcoded in ColorTransform. He derives it from the D65
        // XYZ (0.95047, 1, 1.08883); with that white the derivation must reproduce it exactly. The standard D65 xy
        // (0.3127, 0.3290) used for the output spaces differs from that rounding by < 5e-4.
        val published = doubleArrayOf(3.1338561, -1.6168667, -0.4906146, -0.9787684, 1.9161415, 0.0334540, 0.0719453, -0.2289914, 1.4052427)
        val derived = OutputSpace.xyzD50ToRgb(doubleArrayOf(0.64, 0.33, 0.30, 0.60, 0.15, 0.06), doubleArrayOf(0.95047, 1.0, 1.08883))
        assertArrayEquals(published, derived.m, 1e-6)
        assertArrayEquals(published, OutputSpace.SRGB.fromXyzD50.m, 5e-4)
    }

    @Test
    fun displayP3PrimariesMatchTheD50AdaptedReference() {
        // Columns of the P3 -> XYZ D50 matrix (the rXYZ/gXYZ/bXYZ values of common Display P3 profiles).
        val m = OutputSpace.DISPLAY_P3.toXyzD50.m
        assertArrayEquals(doubleArrayOf(0.5151, 0.2412, -0.0011), doubleArrayOf(m[0], m[3], m[6]), 1e-3)
        assertArrayEquals(doubleArrayOf(0.2920, 0.6922, 0.0419), doubleArrayOf(m[1], m[4], m[7]), 1e-3)
        assertArrayEquals(doubleArrayOf(0.1571, 0.0666, 0.7841), doubleArrayOf(m[2], m[5], m[8]), 1e-3)
    }

    @Test
    fun anIndependentCmmReadsTheProfileAsItsPrimariesAndCurve() {
        val icc = JdkIcc(IccProfile.forOutput(OutputSpace.DISPLAY_P3))
        assertEquals(4, icc.majorVersion)
        val red = icc.toXyz(floatArrayOf(1f, 0f, 0f))
        assertEquals(0.5151f, red[0], 3e-3f); assertEquals(0.2412f, red[1], 3e-3f)
        val white = icc.toXyz(floatArrayOf(1f, 1f, 1f))
        assertEquals(0.9642f, white[0], 3e-3f); assertEquals(1f, white[1], 3e-3f); assertEquals(0.8249f, white[2], 3e-3f)
        // sRGB curve: code value 0.5 decodes to 0.2140 linear.
        assertEquals(0.2140f, icc.toXyz(floatArrayOf(0.5f, 0.5f, 0.5f))[1], 3e-3f)
    }

    @Test
    fun jpegCarriesTheProfileInApp2AndStillDecodes() {
        val icc = IccProfile.forOutput(OutputSpace.DISPLAY_P3)
        val jpeg = JpegEncoder.encodeRgb(ByteArray(16 * 16 * 3) { 120 }, 16, 16, iccProfile = icc)
        val tag = "ICC_PROFILE".toByteArray()
        val at = (0 until jpeg.size - tag.size).first { i -> jpeg[i] == 0xFF.toByte() && jpeg[i + 1] == 0xE2.toByte() && tag.indices.all { jpeg[i + 4 + it] == tag[it] } }
        val length = ((jpeg[at + 2].toInt() and 255) shl 8) or (jpeg[at + 3].toInt() and 255)
        assertArrayEquals(icc, jpeg.copyOfRange(at + 4 + 14, at + 2 + length))
        assertNotNull(JdkDecodedImage.read(jpeg))
    }

    @Test
    fun p3AndSrgbTransformsDescribeTheSameColour() {
        val neutral = doubleArrayOf(0.5, 1.0, 0.7)
        val fm = doubleArrayOf(0.6, 0.3, 0.06, 0.25, 0.7, 0.05, 0.02, 0.1, 0.7)
        val srgb = ColorTransform.from(neutral, fm, null)
        val p3 = ColorTransform.from(neutral, fm, null, OutputSpace.DISPLAY_P3)
        assertEquals(OutputSpace.DISPLAY_P3, p3.output)
        val a = FloatArray(3); val b = FloatArray(3)
        srgb.apply(0.3f, 0.6f, 0.2f, a, 0); p3.apply(0.3f, 0.6f, 0.2f, b, 0)
        val xyzA = OutputSpace.SRGB.toXyzD50.apply(doubleArrayOf(a[0].toDouble(), a[1].toDouble(), a[2].toDouble()))
        val xyzB = OutputSpace.DISPLAY_P3.toXyzD50.apply(doubleArrayOf(b[0].toDouble(), b[1].toDouble(), b[2].toDouble()))
        assertArrayEquals(xyzA, xyzB, 1e-4)
        assertTrue("a saturated green should sit inside P3 but outside sRGB", a.any { it < 0f } || b.all { it >= 0f })
    }
}
