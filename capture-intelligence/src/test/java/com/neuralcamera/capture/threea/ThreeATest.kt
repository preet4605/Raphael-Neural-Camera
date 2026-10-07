package com.neuralcamera.capture.threea

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.log2

class ThreeATest {

    private fun rec(
        i: Long, t: Long? = 10_000_000L, iso: Int? = 100,
        ae: AeState? = AeState.CONVERGED, af: AfState? = AfState.FOCUSED_LOCKED, awb: AwbState? = AwbState.CONVERGED,
        manual: Boolean = false
    ) = ExposureRecord(i, i * 33_000_000L, t, iso, 100, 33_000_000L, 1.8f, ae, af, awb, false, false, 2f, manual)

    @Test
    fun camera2CodesMapExactlyAndUnknownCodesAreNull() {
        assertEquals(AeState.CONVERGED, AeState.fromCamera2(2))
        assertEquals(AfState.PASSIVE_UNFOCUSED, AfState.fromCamera2(6))
        assertEquals(AwbState.LOCKED, AwbState.fromCamera2(3))
        assertNull(AeState.fromCamera2(99))
        assertNull(AfState.fromCamera2(null))
    }

    @Test
    fun convergesOnlyAfterStableExposureWindow() {
        val d = ConvergenceDetector(ConvergenceParams(stableFrames = 3))
        assertEquals(ConvergenceVerdict.PENDING, d.onResult(rec(0)))
        assertEquals(ConvergenceVerdict.PENDING, d.onResult(rec(1)))
        assertEquals(ConvergenceVerdict.CONVERGED, d.onResult(rec(2)))
    }

    @Test
    fun convergedStateWithDriftingExposureIsNotConverged() {
        val d = ConvergenceDetector(ConvergenceParams(stableFrames = 3, exposureTolerance = 0.05))
        d.onResult(rec(0, t = 10_000_000L)); d.onResult(rec(1, t = 12_000_000L))
        assertEquals(ConvergenceVerdict.PENDING, d.onResult(rec(2, t = 14_000_000L)))
    }

    @Test
    fun searchingTimesOut() {
        val d = ConvergenceDetector(ConvergenceParams(timeoutNs = 100_000_000L))
        assertEquals(ConvergenceVerdict.PENDING, d.onResult(rec(0, ae = AeState.SEARCHING)))
        assertEquals(ConvergenceVerdict.PENDING, d.onResult(rec(1, ae = AeState.SEARCHING)))
        assertEquals(ConvergenceVerdict.PENDING, d.onResult(rec(2, ae = AeState.SEARCHING)))
        assertEquals(ConvergenceVerdict.TIMED_OUT, d.onResult(rec(4, ae = AeState.SEARCHING)))
    }

    @Test
    fun focusFailureAndMissingKeysAreReportedNotAssumed() {
        assertEquals(ConvergenceVerdict.FOCUS_FAILED, ConvergenceDetector().onResult(rec(0, af = AfState.NOT_FOCUSED_LOCKED)))
        assertEquals(ConvergenceVerdict.UNKNOWN, ConvergenceDetector().onResult(rec(0, ae = null)))
        val d = ConvergenceDetector(ConvergenceParams(stableFrames = 1))
        assertEquals(ConvergenceVerdict.UNKNOWN, d.onResult(rec(0, t = null)))
    }

    @Test
    fun meteringRegionMapsThroughZoomCrop() {
        val r = MeteringRegion.around(0.5, 0.5, size = 0.2)
        val px = r.toActiveArray(cropLeft = 1000, cropTop = 500, cropWidth = 2000, cropHeight = 1500)
        assertTrue(px.contentEquals(intArrayOf(1800, 1100, 400, 300)))
        val edge = MeteringRegion.around(0.99, 0.0, size = 0.1)
        assertEquals(0.9, edge.left, 1e-9); assertEquals(0.0, edge.top, 1e-9)
    }

    @Test(expected = IllegalArgumentException::class)
    fun meteringWeightIsBounded() { MeteringRegion(0.0, 0.0, 0.5, 0.5, 2000) }

    private val limits = ExposureLimits(100_000L..500_000_000L, 50..6400, handheldMaxExposureNs = 66_000_000L)

    @Test
    fun bracketsHitTheirEvTargetsWithinLimits() {
        val base = rec(0, t = 10_000_000L, iso = 400)
        val plan = ExposureBracketPlanner.plan(base, listOf(-2.0, 0.0, 2.0), limits)
        val p0 = base.exposureTimeNs!! / 1e9 * base.sensitivityIso!!
        plan.forEach {
            val ev = log2(it.exposureTimeNs / 1e9 * it.iso / p0)
            assertEquals(it.evOffset, ev, 0.05)
            assertFalse(it.clamped)
            assertTrue(it.exposureTimeNs <= limits.handheldMaxExposureNs)
        }
    }

    @Test
    fun unreachableBracketIsFlaggedAsClamped() {
        val base = rec(0, t = 60_000_000L, iso = 6400)
        val s = ExposureBracketPlanner.plan(base, listOf(3.0), limits).single()
        assertTrue(s.clamped)
        assertEquals(6400, s.iso)
        val dark = ExposureBracketPlanner.plan(rec(0, t = 100_000L, iso = 50), listOf(-3.0), limits).single()
        assertTrue(dark.clamped)
    }

    @Test
    fun manualSeedsFromLastConvergedAutoAndAutoResets() {
        val c = ExposureModeController(limits)
        assertFalse("no converged seed yet", c.switchToManual())
        c.onAutoResult(rec(5, t = 20_000_000L, iso = 800), ConvergenceVerdict.CONVERGED)
        assertTrue(c.switchToManual())
        assertEquals(20_000_000L, c.manual!!.exposureTimeNs)
        assertEquals(800, c.manual!!.iso)
        val s = c.setManual(1_000_000_000L, 100)
        assertTrue(s.clamped); assertEquals(500_000_000L, s.exposureTimeNs)
        c.switchToAuto()
        assertEquals(ExposureControlMode.AUTO, c.mode); assertNull(c.manual)
        assertFalse("seed is cleared on return to auto", c.switchToManual())
    }

    @Test
    fun noManualWithoutSensorLimits() {
        val c = ExposureModeController(null)
        c.onAutoResult(rec(0), ConvergenceVerdict.CONVERGED)
        assertFalse(c.switchToManual())
    }

    @Test
    fun exposureProductIncludesPostRawBoost() {
        val r = rec(0, t = 10_000_000L, iso = 100).copy(postRawBoost = 200)
        assertTrue(abs(r.exposureProduct!! - 2.0) < 1e-9)
    }
}
