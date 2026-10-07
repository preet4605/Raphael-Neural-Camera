package com.neuralcamera.capture.scene

import com.neuralcamera.capture.motion.MotionClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneAnalyzerTest {

    private fun flat(v: Int, w: Int = 64, h: Int = 64) = ByteArray(w * h) { v.toByte() }

    @Test
    fun statsOfFlatAndSplitFrames() {
        val s = LumaStats.of(flat(118), 64, 64)
        assertEquals(118.0, s.mean, 1e-9)
        assertEquals(0.0, s.textureEnergy, 1e-9)
        val split = ByteArray(64 * 64) { if (it % 64 < 32) 0 else 255.toByte() }
        val t = LumaStats.of(split, 64, 64)
        assertEquals(0.5, t.highlightClipFraction, 1e-9)
        assertEquals(0.5, t.shadowCrushFraction, 1e-9)
    }

    @Test
    fun ev100FromSunny16() {
        // Sunny 16: f/16, 1/100 s, ISO 100 at mid-grey is about EV 14.6.
        val a = SceneAnalyzer.assess(LumaStats.of(flat(118), 64, 64), 10_000_000L, 100, 16f, MotionClass.STILL)
        assertEquals(14.64, a.ev100!!, 0.01)
        assertEquals(IlluminationClass.DAYLIGHT, a.illumination)
        assertFalse(a.nightScene)
    }

    @Test
    fun darkExposureIsNight() {
        val a = SceneAnalyzer.assess(LumaStats.of(flat(60), 64, 64), 100_000_000L, 6400, 1.8f, MotionClass.HANDHELD)
        assertEquals(IlluminationClass.NIGHT, a.illumination)
        assertTrue(a.nightScene)
        assertTrue(a.complexity > 0.4)
    }

    @Test
    fun hdrDetectedFromClippingOnBothEnds() {
        val split = ByteArray(64 * 64) { if (it % 64 < 16) 0 else if (it % 64 > 60) 255.toByte() else 120 }
        val a = SceneAnalyzer.assess(LumaStats.of(split, 64, 64), 10_000_000L, 100, 1.8f, MotionClass.STILL)
        assertTrue(a.hdrScene)
    }

    @Test
    fun missingExposureMeansUnknownIllumination() {
        val a = SceneAnalyzer.assess(LumaStats.of(flat(118), 64, 64), null, 100, 1.8f, MotionClass.STILL)
        assertNull(a.ev100); assertNull(a.estimatedLux)
        assertEquals(IlluminationClass.UNKNOWN, a.illumination)
        assertTrue(a.reasons.isNotEmpty())
    }

    @Test
    fun semanticSignalsAreNeverInvented() {
        val a = SceneAnalyzer.assess(LumaStats.of(flat(118), 64, 64), 10_000_000L, 100, 1.8f, MotionClass.STILL)
        assertTrue(a.semanticSignals.all { it.availability == SignalAvailability.NOT_AVAILABLE && it.value == null })
    }

    @Test
    fun luxFromAutoExposureFollowsEv100AndRefusesMissingInputs() {
        // f/1.8, 1/100 s, ISO 100: N^2/t = 324, so EV100 = log2(324) and lux = 2.5 * 324.
        val lux = SceneAnalyzer.estimatedLuxFromAutoExposure(10_000_000L, 100, 1.8f)!!
        org.junit.Assert.assertEquals(810.0, lux, 0.5)
        // Doubling ISO at the same shutter means half the light.
        org.junit.Assert.assertEquals(405.0, SceneAnalyzer.estimatedLuxFromAutoExposure(10_000_000L, 200, 1.8f)!!, 0.5)
        org.junit.Assert.assertNull(SceneAnalyzer.estimatedLuxFromAutoExposure(null, 100, 1.8f))
        org.junit.Assert.assertNull(SceneAnalyzer.estimatedLuxFromAutoExposure(10_000_000L, 0, 1.8f))
        org.junit.Assert.assertNull(SceneAnalyzer.estimatedLuxFromAutoExposure(10_000_000L, 100, null))
    }
}
