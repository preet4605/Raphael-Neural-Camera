package com.neuralcamera.isp.calibration

import com.neuralcamera.isp.dng.CfaPattern
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorCalibrationTest {

    @Test
    fun noiseModelFitRecoversGainLaw() {
        val truth = GainNoiseModel(s1 = 2e-5, s0 = 1e-6, o2 = 3e-8, o0 = 2e-7)
        val samples = listOf(100, 400, 1600, 6400).map { iso ->
            val n = truth.at(iso); GainNoiseModel.Companion.Sample(iso, n.shot, n.read)
        }
        val fit = GainNoiseModel.fit(samples)
        assertEquals(truth.s1, fit.s1, 1e-12); assertEquals(truth.s0, fit.s0, 1e-12)
        assertEquals(truth.o2, fit.o2, 1e-14); assertEquals(truth.o0, fit.o0, 1e-12)
        assertEquals(truth.at(800).shot, fit.at(800).shot, 1e-12)
    }

    @Test(expected = IllegalArgumentException::class)
    fun fitNeedsTwoIsoValues() {
        GainNoiseModel.fit(listOf(GainNoiseModel.Companion.Sample(100, 1.0, 1.0), GainNoiseModel.Companion.Sample(100, 1.0, 1.0)))
    }

    @Test
    fun shadingInterpolatesBilinearly() {
        val g = FloatArray(2 * 2 * 4) { i -> 1f + (i / 4) } // corner gains 1,2,3,4 for every channel
        val m = LensShadingMap(2, 2, g)
        assertEquals(1f, m.interpolate(0, 0.0, 0.0), 1e-6f)
        assertEquals(4f, m.interpolate(2, 1.0, 1.0), 1e-6f)
        assertEquals(2.5f, m.interpolate(1, 0.5, 0.5), 1e-6f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun shadingGainsBelowOneAreRejected() { LensShadingMap(2, 2, FloatArray(16) { 0.5f }) }

    @Test
    fun missingInputsAreListedAndTemperatureHookIsANoOp() {
        val c = SensorCalibration(
            CameraKey("0", "2", "unknown"),
            Sourced(CfaPattern.RGGB, CalibrationSource.CHARACTERISTICS),
            Sourced(doubleArrayOf(64.0, 64.0, 64.0, 64.0), CalibrationSource.CHARACTERISTICS),
            Sourced(1023.0, CalibrationSource.CHARACTERISTICS)
        )
        assertEquals(listOf("lens shading map", "noise profile", "colour matrices"), c.missing())
        val (same, note) = TemperatureCompensation.NONE.adjust(c, 41.0)
        assertTrue(same === c)
        assertTrue(note.contains("no temperature model"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun blackAboveWhiteIsRejected() {
        SensorCalibration(
            CameraKey("0", "0", null),
            Sourced(CfaPattern.RGGB, CalibrationSource.CHARACTERISTICS),
            Sourced(doubleArrayOf(64.0, 64.0, 64.0, 2000.0), CalibrationSource.CHARACTERISTICS),
            Sourced(1023.0, CalibrationSource.CHARACTERISTICS)
        )
    }
}
