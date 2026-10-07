package com.neuralcamera.cameracore.threea

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualExposureTest {

    @Test
    fun stopsComeOnlyFromTheReportedRanges() {
        assertEquals(listOf(100, 125, 160, 200), ManualStops.iso(100..220))
        val shutter = ManualStops.shutterNs(1_000_000L..34_000_000L) // 1/1000 s .. 1/29 s
        assertEquals("1/1000", ManualStops.shutterLabel(shutter.first()))
        assertEquals("1/30", ManualStops.shutterLabel(shutter.last()))
        assertEquals(listOf("1/4", "0.3s", "1s", "30s"), listOf(250_000_000L, 300_000_000L, 1_000_000_000L, 30_000_000_000L).map(ManualStops::shutterLabel))
        assertEquals(3, ManualStops.nearest(listOf(1_000_000L, 2_000_000L, 4_000_000L, 8_000_000L), 7_000_000L))
        assertTrue(ManualStops.iso(1..10).isEmpty())
    }

    private fun record(t: Long?, iso: Int?, manual: Boolean = true, boost: Int? = 100) =
        ExposureRecord(7, 0, t, iso, boost, null, null, null, null, null, null, null, null, manual)

    @Test
    fun eachFrameIsCheckedAgainstTheRequest() {
        val req = ExposureSetting(8_000_000L, 400, 0.0, clamped = false)
        assertEquals(ManualOutcome.APPLIED, ManualFrameCheck.of(req, record(8_010_000L, 400)).outcome) // line-time rounding
        val wrongIso = ManualFrameCheck.of(req, record(8_000_000L, 320))
        assertEquals(ManualOutcome.DEVIATED, wrongIso.outcome); assertTrue(wrongIso.detail.contains("ISO 320"))
        assertEquals(ManualOutcome.DEVIATED, ManualFrameCheck.of(req, record(8_000_000L, 400, manual = false)).outcome)
        assertEquals(ManualOutcome.DEVIATED, ManualFrameCheck.of(req, record(8_000_000L, 400, boost = 200)).outcome)
        assertEquals(ManualOutcome.NOT_REPORTED, ManualFrameCheck.of(req, record(null, 400)).outcome)
        val checks = listOf(ManualFrameCheck.of(req, record(8_000_000L, 400)), wrongIso)
        assertEquals("requested 1/125 ISO 400; applied on 1 of 2 frames: DEVIATED ISO 320 vs requested 400", ManualFrameCheck.summary(req, checks))
    }
}
