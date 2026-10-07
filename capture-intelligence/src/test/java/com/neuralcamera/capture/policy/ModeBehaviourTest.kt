package com.neuralcamera.capture.policy

import com.neuralcamera.capture.CameraShootingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModeBehaviourTest {

    @Test
    fun everyModeDiffersFromEveryOtherInWhatItDoes() {
        val all = CameraShootingMode.entries.map { ModeBehaviours.of(it) }
        all.forEach { assertTrue(it.summary.isNotBlank()) }
        // Same knobs, not just a different label: each pair must differ in policy, tone or chroma handling.
        val knobs = all.map { Triple(it.policy, it.contrastCurve, it.mergeChroma) }
        assertEquals(knobs.size, knobs.toSet().size)
    }

    @Test
    fun policiesFollowTheModeAndStayRealityPreserving() {
        assertEquals(QualityPolicyId.MAXIMUM, QualityPolicies.requestedFor(CameraShootingMode.MASTER))
        assertEquals(QualityPolicyId.BALANCED, QualityPolicies.requestedFor(CameraShootingMode.AUTO))
        CameraShootingMode.entries.forEach {
            assertEquals(SchedulingPolicy.REALITY_PRESERVING, QualityPolicies.of(ModeBehaviours.of(it).policy).permittedOrigins)
        }
        val authentic = ModeBehaviours.of(CameraShootingMode.AUTHENTIC)
        assertTrue(!authentic.contrastCurve && !authentic.mergeChroma)
    }
}
