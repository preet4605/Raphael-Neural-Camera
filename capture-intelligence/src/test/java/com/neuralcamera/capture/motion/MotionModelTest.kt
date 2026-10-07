package com.neuralcamera.capture.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionModelTest {

    private fun constantGyro(rateZ: Double, fromNs: Long, toNs: Long, stepNs: Long = 1_000_000L) =
        (fromNs..toNs step stepNs).map { GyroSample(it, 0.0, 0.0, rateZ) }

    private val noEis = StabilizationContext(oisActive = false, eisActive = false, oisSamplesAvailable = false,
        cropRegion = intArrayOf(0, 0, 4000, 3000), activeArrayWidth = 4000, activeArrayHeight = 3000)

    @Test
    fun rollingShutterRowTimes() {
        val m = RollingShutterModel(frameStartNs = 1_000, exposureNs = 10_000, skewNs = 30_000, rows = 4)
        assertEquals(1_000L, m.rowStartNs(0))
        assertEquals(31_000L, m.rowStartNs(3))
        assertEquals(16_000L, m.rowMidExposureNs(1))
        assertEquals(1_000L..41_000L, m.windowNs)
    }

    @Test
    fun integratesConstantRateExactly() {
        val r = GyroIntegrator.integrate(constantGyro(0.5, 0, 100_000_000L), 10_500_000L, 30_500_000L)!!
        assertEquals(0.5 * 0.02, r.z, 1e-12)
        assertEquals(0.0, r.x, 0.0)
    }

    @Test
    fun missingGyroCoverageIsUnknownNotZero() {
        assertNull(GyroIntegrator.integrate(constantGyro(0.5, 50_000_000L, 100_000_000L), 0, 60_000_000L))
        val est = MotionEstimator.estimate(emptyList(), RollingShutterModel(0, 10_000_000, 20_000_000, 3000), null, noEis, 3000.0)
        assertNull(est.blurPx)
        assertEquals(MotionClass.UNKNOWN, est.motionClass)
        assertEquals(0.0, est.confidence, 0.0)
    }

    @Test
    fun blurScalesWithRateAndClassifies() {
        val frame = RollingShutterModel(10_000_000L, 10_000_000L, 20_000_000L, 3000)
        val still = MotionEstimator.estimate(constantGyro(0.001, 0, 100_000_000L), frame, null, noEis, 3000.0)
        assertEquals(MotionClass.STILL, still.motionClass)
        assertEquals(1.0, still.confidence, 1e-12)
        val moving = MotionEstimator.estimate(constantGyro(0.5, 0, 100_000_000L), frame, null, noEis, 3000.0)
        // 0.5 rad/s over 30 ms = 0.015 rad -> about 45 px at f = 3000 px.
        assertEquals(45.0, moving.blurPx!!, 0.1)
        assertEquals(MotionClass.MOVING, moving.motionClass)
    }

    @Test
    fun eisAndUnmeasuredOisLowerConfidence() {
        val frame = RollingShutterModel(10_000_000L, 10_000_000L, 20_000_000L, 3000)
        val eis = noEis.copy(eisActive = true)
        assertTrue(!eis.geometryIsSensorAligned)
        val e = MotionEstimator.estimate(constantGyro(0.01, 0, 100_000_000L), frame, null, eis, 3000.0)
        assertTrue(e.confidence < 0.5)
        val ois = noEis.copy(oisActive = true, oisSamplesAvailable = false)
        assertTrue(MotionEstimator.estimate(constantGyro(0.01, 0, 100_000_000L), frame, null, ois, 3000.0).confidence < 1.0)
        assertTrue(noEis.copy(oisActive = true, oisSamplesAvailable = true).geometryIsSensorAligned)
    }

    @Test
    fun cropMagnifiesImageMotion() {
        val frame = RollingShutterModel(10_000_000L, 10_000_000L, 20_000_000L, 3000)
        val zoomed = noEis.copy(cropRegion = intArrayOf(1000, 750, 2000, 1500))
        val a = MotionEstimator.estimate(constantGyro(0.1, 0, 100_000_000L), frame, null, noEis, 3000.0).blurPx!!
        val b = MotionEstimator.estimate(constantGyro(0.1, 0, 100_000_000L), frame, null, zoomed, 3000.0).blurPx!!
        assertEquals(2.0, b / a, 1e-9)
    }

    @Test
    fun interFrameShiftUsesMidExposureTimes() {
        val prev = RollingShutterModel(0L, 10_000_000L, 20_000_000L, 3)
        val cur = RollingShutterModel(33_000_000L, 10_000_000L, 20_000_000L, 3)
        val e = MotionEstimator.estimate(constantGyro(0.1, 0, 100_000_000L), cur, prev, noEis, 1000.0)
        // Mid rows 33 ms apart: 0.1 rad/s * 0.033 s = 0.0033 rad -> 3.3 px at f = 1000 px.
        assertEquals(3.3, e.interFrameShiftPx!!, 0.01)
    }
}
