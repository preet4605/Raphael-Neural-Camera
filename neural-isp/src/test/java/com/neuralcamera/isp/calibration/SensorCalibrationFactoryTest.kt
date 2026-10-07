package com.neuralcamera.isp.calibration

import com.neuralcamera.isp.dng.CfaPattern
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic Camera2 values (test fixtures, not device data). */
class SensorCalibrationFactoryTest {
    private val key = CameraKey("0", "0", null)
    private val identity = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)

    @Test
    fun resultValuesWinOverCharacteristics() {
        val build = SensorCalibrationFactory.fromCamera2(key, Camera2CalibrationInput(
            colorFilterArrangement = 3, blackLevelPattern = intArrayOf(64, 64, 64, 64), whiteLevel = 1023,
            dynamicBlackLevel = floatArrayOf(63.5f, 64f, 64.25f, 65f), dynamicWhiteLevel = 1000,
            noiseProfile = List(4) { (1e-4 * (it + 1)) to 1e-6 },
            lensShading = LensShadingMap(2, 2, FloatArray(16) { 1.5f }),
            colorTransform1 = identity, referenceIlluminant1 = 21
        ))
        val cal = build.calibration!!
        assertEquals(CfaPattern.BGGR, cal.cfa.value)
        assertEquals(CalibrationSource.CAPTURE_RESULT, cal.blackLevels.source)
        assertArrayEquals(doubleArrayOf(63.5, 64.0, 64.25, 65.0), cal.blackLevels.value, 0.0)
        assertEquals(1000.0, cal.whiteLevel.value, 0.0)
        assertEquals(CalibrationSource.CAPTURE_RESULT, cal.whiteLevel.source)
        assertEquals(4e-4, cal.noise!!.value[3].shot, 1e-12)
        assertEquals(21, cal.color!!.value.illuminant1)
        assertTrue(cal.missing().isEmpty())
    }

    @Test
    fun staticValuesAreUsedWhenTheResultHasNone() {
        val cal = SensorCalibrationFactory.fromCamera2(key, Camera2CalibrationInput(0, intArrayOf(64, 63, 62, 61), 4095)).calibration!!
        assertEquals(CalibrationSource.CHARACTERISTICS, cal.blackLevels.source)
        assertArrayEquals(doubleArrayOf(64.0, 63.0, 62.0, 61.0), cal.blackLevels.value, 0.0)
        assertEquals(listOf("lens shading map", "noise profile", "colour matrices"), cal.missing())
    }

    @Test
    fun missingRequiredValuesGiveNoCalibration() {
        assertNull(SensorCalibrationFactory.fromCamera2(key, Camera2CalibrationInput(null, intArrayOf(64, 64, 64, 64), 1023)).calibration)
        assertNull(SensorCalibrationFactory.fromCamera2(key, Camera2CalibrationInput(5, intArrayOf(64, 64, 64, 64), 1023)).calibration)
        assertNull(SensorCalibrationFactory.fromCamera2(key, Camera2CalibrationInput(0, null, 1023)).calibration)
        assertNull(SensorCalibrationFactory.fromCamera2(key, Camera2CalibrationInput(0, intArrayOf(64, 64, 64, 64), null)).calibration)
        val inverted = SensorCalibrationFactory.fromCamera2(key, Camera2CalibrationInput(0, intArrayOf(64, 64, 64, 64), 60))
        assertNull(inverted.calibration)
        assertTrue(inverted.notes.last(), inverted.notes.last().contains("not below white level"))
    }

    @Test
    fun threeChannelNoiseExpandsByCfaColourAndBadNoiseIsIgnored() {
        val rgb = listOf(1e-4 to 1e-6, 2e-4 to 2e-6, 3e-4 to 3e-6)
        val cal = SensorCalibrationFactory.fromCamera2(key, Camera2CalibrationInput(1, intArrayOf(0, 0, 0, 0), 1023, noiseProfile = rgb)).calibration!!
        // GRBG: positions are G, R, B, G.
        assertEquals(listOf(2e-4, 1e-4, 3e-4, 2e-4), cal.noise!!.value.map { it.shot })
        val bad = SensorCalibrationFactory.fromCamera2(key, Camera2CalibrationInput(0, intArrayOf(0, 0, 0, 0), 1023, noiseProfile = listOf(-1.0 to 0.0)))
        assertNull(bad.calibration!!.noise)
        assertTrue(bad.notes.any { it.contains("noise profile") && it.contains("ignored") })
    }
}
