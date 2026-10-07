package com.neuralcamera.isp.raw

import com.neuralcamera.isp.calibration.CalibrationSource
import com.neuralcamera.isp.calibration.CameraKey
import com.neuralcamera.isp.calibration.DefectPixel
import com.neuralcamera.isp.calibration.LensShadingMap
import com.neuralcamera.isp.calibration.SensorCalibration
import com.neuralcamera.isp.calibration.Sourced
import com.neuralcamera.isp.dng.CfaPattern
import com.neuralcamera.isp.temporal.NoiseModel
import com.neuralcamera.isp.temporal.U16Plane
import com.neuralcamera.models.execution.FallbackAction
import com.neuralcamera.models.execution.StageOutcome
import com.neuralcamera.models.execution.StageStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawFrontEndTest {
    private val black = doubleArrayOf(64.0, 64.0, 64.0, 64.0)

    private fun cal(
        shading: LensShadingMap? = null, noise: List<NoiseModel>? = null, defects: List<DefectPixel> = emptyList(),
        blackLevels: DoubleArray = black
    ) = SensorCalibration(
        camera = CameraKey("0", "0", null),
        cfa = Sourced(CfaPattern.RGGB, CalibrationSource.CHARACTERISTICS),
        blackLevels = Sourced(blackLevels, CalibrationSource.CHARACTERISTICS),
        whiteLevel = Sourced(1023.0, CalibrationSource.CHARACTERISTICS),
        defects = defects,
        lensShading = shading?.let { Sourced(it, CalibrationSource.CAPTURE_RESULT) },
        noise = noise?.let { Sourced(it, CalibrationSource.CAPTURE_RESULT) }
    )

    private fun flat(dn: Int, w: Int = 16, h: Int = 16) = U16Plane.fromInts(w, h, IntArray(w * h) { dn })

    @Test
    fun canonicalOrderValidates() {
        assertTrue(RawStage.validateOrder(RawStage.entries.filter { it.implemented }).isEmpty())
        val bad = RawStage.validateOrder(listOf(RawStage.UNPACK, RawStage.LENS_SHADING, RawStage.BLACK_LEVEL))
        assertTrue(bad.any { it.contains("BLACK_LEVEL must not follow LENS_SHADING") })
        assertTrue(bad.any { it.contains("mandatory stage DEMOSAIC missing") })
        assertFalse(RawStage.CHROMATIC_ABERRATION.implemented)
        assertFalse(RawStage.DISTORTION.implemented)
    }

    @Test
    fun blackLevelIsPerCfaPositionAndUnclamped() {
        val p = RawFrontEnd.blackLevel(flat(64), doubleArrayOf(60.0, 64.0, 64.0, 70.0), 1023.0)
        assertEquals(4.0 / 963.0, p.at(0, 0).toDouble(), 1e-6)
        assertEquals(0.0, p.at(1, 0).toDouble(), 1e-9)
        assertTrue("noise below black stays negative", p.at(1, 1) < 0f)
    }

    @Test
    fun tableDefectsAreReplacedBySameColourMedian() {
        val raw = IntArray(16 * 16) { 300 }.also { it[5 * 16 + 5] = 1023 }
        val r = RawFrontEnd.process(U16Plane.fromInts(16, 16, raw), cal(defects = listOf(DefectPixel(5, 5))), dynamicDefects = false)
        assertEquals(r.mosaic.at(4, 4), r.mosaic.at(5, 5), 1e-6f)
        assertEquals(1, r.correctedDefects)
    }

    @Test
    fun dynamicDefectsNeedANoiseProfile() {
        val raw = IntArray(16 * 16) { 300 }.also { it[6 * 16 + 7] = 1000 }
        val noisy = List(4) { NoiseModel(1e-5, 1e-6) }
        val r = RawFrontEnd.process(U16Plane.fromInts(16, 16, raw), cal(noise = noisy))
        assertEquals(1, r.correctedDefects)
        assertEquals(r.mosaic.at(5, 6), r.mosaic.at(7, 6), 1e-6f)
        assertEquals(StageStatus.SUCCESS, r.stages.first { it.stage == "DEFECT_CORRECTION" }.status)

        val without = RawFrontEnd.process(U16Plane.fromInts(16, 16, raw), cal())
        assertEquals(0, without.correctedDefects)
        assertEquals(StageStatus.DEGRADED, without.stages.first { it.stage == "DEFECT_CORRECTION" }.status)
    }

    @Test
    fun lensShadingAppliesPerChannelGains() {
        // R gain 2, greens 1, B 1.5, uniform across the grid.
        val gains = FloatArray(2 * 2 * 4) { i -> when (i % 4) { 0 -> 2f; 3 -> 1.5f; else -> 1f } }
        val r = RawFrontEnd.process(flat(64 + 96), cal(shading = LensShadingMap(2, 2, gains)), dynamicDefects = false)
        val base = 96.0 / (1023 - 64)
        assertEquals(2 * base, r.mosaic.at(0, 0).toDouble(), 1e-6) // R
        assertEquals(base, r.mosaic.at(1, 0).toDouble(), 1e-6)     // G
        assertEquals(1.5 * base, r.mosaic.at(1, 1).toDouble(), 1e-6) // B
        assertFalse(r.degraded)
    }

    @Test
    fun missingShadingIsAFailedSkippedStageNotASilentPass() {
        val r = RawFrontEnd.process(flat(200), cal(), dynamicDefects = false)
        val s = r.stages.first { it.stage == "LENS_SHADING" }
        assertTrue(s is StageOutcome.Failed)
        assertEquals(FallbackAction.SKIP_STAGE, (s as StageOutcome.Failed).fallback)
        assertTrue(r.degraded)
    }

    @Test
    fun shadingChannelMappingFollowsCfa() {
        assertEquals(listOf(0, 1, 2, 3), (0..3).map { LensShadingMap.channelOf(CfaPattern.RGGB, it) })
        assertEquals(listOf(1, 0, 3, 2), (0..3).map { LensShadingMap.channelOf(CfaPattern.GRBG, it) })
        assertEquals(listOf(3, 1, 2, 0), (0..3).map { LensShadingMap.channelOf(CfaPattern.BGGR, it) })
    }
}
