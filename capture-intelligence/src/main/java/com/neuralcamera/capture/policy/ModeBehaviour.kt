package com.neuralcamera.capture.policy

import com.neuralcamera.capture.CameraShootingMode

/**
 * What a shooting mode changes, beyond its label: the scheduling policy it requests (frame budget, backend preference)
 * and the processing it asks for. Frame counts and exposure per scene come from UniversalCapturePlanner; thermal and
 * battery overrides from [QualityPolicies.resolve]. Every mode stays captured -> reconstructed.
 *
 * No mode requests a neural stage: none is VERIFIED on a device (Gate 2), so MASTER's policy allows one but the
 * pipeline runs classical stages only, and says so.
 */
data class ModeBehaviour(
    val mode: CameraShootingMode,
    val policy: QualityPolicyId,
    /** Apply the display contrast S-curve to luma; false keeps the merged luma as captured (linear in 8-bit code values). */
    val contrastCurve: Boolean,
    /** Merge chroma across frames along the luma motion; false takes colour from the reference frame alone. */
    val mergeChroma: Boolean,
    val summary: String
)

object ModeBehaviours {
    fun of(mode: CameraShootingMode): ModeBehaviour = when (mode) {
        CameraShootingMode.AUTO -> ModeBehaviour(mode, QualityPolicyId.BALANCED, contrastCurve = true, mergeChroma = true,
            summary = "balanced burst, luma and chroma merged, display contrast curve")
        CameraShootingMode.PRO -> ModeBehaviour(mode, QualityPolicyId.BALANCED, contrastCurve = false, mergeChroma = true,
            summary = "manual exposure honoured, luma and chroma merged, no contrast curve (flat for grading)")
        CameraShootingMode.MASTER -> ModeBehaviour(mode, QualityPolicyId.MAXIMUM, contrastCurve = true, mergeChroma = true,
            summary = "maximum burst budget, luma and chroma merged, display contrast curve; neural stages only once verified")
        CameraShootingMode.AUTHENTIC -> ModeBehaviour(mode, QualityPolicyId.BALANCED, contrastCurve = false, mergeChroma = false,
            summary = "luma noise averaging only: colour from one exposure, no contrast curve")
    }
}
