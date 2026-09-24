package com.neuralcamera.quality

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealityGuardTest {

    @Test
    fun testQualityEvaluatorMetrics() {
        val evaluator = StandardQualityEvaluator()
        val dummyLuma = ByteArray(100 * 100) { ((it % 256).toByte()) }
        val score = evaluator.evaluateQuality(100, 100, dummyLuma)

        assertTrue(score.overallScore in 0.0f..1.0f)
        assertTrue(score.isAcceptable)
    }

    @Test
    fun testRealityGuardDetectsHallucinations() {
        val guard = StandardRealityGuard()
        val width = 64
        val height = 64
        val original = ByteArray(width * height) { 100.toByte() }
        val hallucinated = ByteArray(width * height) { 220.toByte() } // wild divergence

        val confMap = ConfidenceMap(2, 2, floatArrayOf(0.9f, 0.9f, 0.9f, 0.9f)) // high confidence sensor data
        val decision = guard.inspectAndProtect(original, hallucinated, confMap, width, height)

        assertEquals(GuardAction.REVERT_TO_ORIGINAL, decision.action)
        assertTrue(decision.hallucinationDetected)
        assertEquals(0.0f, decision.blendRatio, 0.001f)
    }

    @Test
    fun testRealityGuardAcceptsFaithfulReconstruction() {
        val guard = StandardRealityGuard()
        val width = 64
        val height = 64
        val original = ByteArray(width * height) { 100.toByte() }
        val subtleCleaned = ByteArray(width * height) { 102.toByte() } // minimal deviation

        val confMap = ConfidenceMap(2, 2, floatArrayOf(0.9f, 0.9f, 0.9f, 0.9f))
        val decision = guard.inspectAndProtect(original, subtleCleaned, confMap, width, height)

        assertEquals(GuardAction.KEEP_RECONSTRUCTED, decision.action)
        assertFalse(decision.hallucinationDetected)
        assertEquals(1.0f, decision.blendRatio, 0.001f)
    }
}
