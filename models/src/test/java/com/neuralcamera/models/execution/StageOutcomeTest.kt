package com.neuralcamera.models.execution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StageOutcomeTest {

    @Test
    fun intendedSuccessIsSuccess() {
        val o = runWithFallback("merge", "htp", "cpu", intended = { 42 }, fallback = { -1 })
        assertEquals(StageStatus.SUCCESS, o.status)
        assertTrue(o.ranAsIntended)
        assertEquals("htp", o.executedImplementation)
        assertEquals(42, o.valueOrNull())
    }

    @Test
    fun fallbackIsDegradedAndNamesBothImplementations() {
        val o = runWithFallback<Int>("merge", "htp", "cpu", intended = { throw IllegalStateException("no HTP") }, fallback = { 7 })
        assertEquals(StageStatus.DEGRADED, o.status)
        assertFalse("a fallback must never read as the intended backend", o.ranAsIntended)
        o as StageOutcome.Degraded
        assertEquals("htp", o.intended)
        assertEquals("cpu", o.actual)
        assertTrue(o.reason.contains("no HTP"))
        assertEquals(7, o.valueOrNull())
    }

    @Test
    fun bothFailingIsFailedWithFallbackAction() {
        val o = runWithFallback<Int>(
            "merge", "htp", "cpu", onBothFailed = FallbackAction.USE_SINGLE_FRAME,
            intended = { throw IllegalStateException("a") }, fallback = { throw IllegalStateException("b") }
        )
        assertEquals(StageStatus.FAILED, o.status)
        assertNull(o.valueOrNull())
        assertNull(o.executedImplementation)
        assertEquals(FallbackAction.USE_SINGLE_FRAME, (o as StageOutcome.Failed).fallback)
    }

    @Test(expected = IllegalArgumentException::class)
    fun degradedMustNameADifferentImplementation() {
        StageOutcome.Degraded("s", 1, intended = "cpu", actual = "cpu", reason = "x")
    }

    @Test(expected = IllegalArgumentException::class)
    fun degradedMustSayWhy() {
        StageOutcome.Degraded("s", 1, intended = "htp", actual = "cpu", reason = " ")
    }

    @Test(expected = IllegalArgumentException::class)
    fun provenNeedsTheGateChecker() {
        Claim("RAW_BURST", EvidenceState.PROVEN, EvidenceSource.JVM_UNIT_TEST)
    }

    @Test
    fun syntheticEvidenceCanOnlyBeTested() {
        val c = Claim("merge PSNR", EvidenceState.TESTED, EvidenceSource.SYNTHETIC_BENCHMARK)
        assertEquals(EvidenceState.TESTED, c.state)
        Claim("HTP", EvidenceState.PROVEN, EvidenceSource.DEVICE_GATE_CHECKER)
    }

    @Test
    fun onlyAiStudioMayGenerateContent() {
        assertEquals(listOf(PipelinePath.AI_STUDIO), PipelinePath.entries.filter { it.mayGenerateContent })
    }
}
