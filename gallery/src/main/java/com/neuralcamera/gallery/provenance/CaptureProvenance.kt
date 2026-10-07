package com.neuralcamera.gallery.provenance

import com.neuralcamera.cameracore.BurstCapture
import com.neuralcamera.cameracore.orchestration.OrchestrationState
import com.neuralcamera.models.execution.ContentOrigin
import com.neuralcamera.models.execution.PipelinePath
import com.neuralcamera.models.execution.StageOutcome

/** What the classical merge actually did, as reported by the image pipeline. */
data class MergeFacts(
    val framesIn: Int,
    /** Reality Guard action name: KEEP_RECONSTRUCTED, BLEND_WITH_ORIGINAL, REVERT_TO_ORIGINAL or DISCARD_NEURAL_STAGE. */
    val guardAction: String,
    val blendRatio: Float,
    /** Every frame failed the quality checks and the sharpest was used as reference anyway. */
    val referenceFallback: Boolean,
    val colour: Boolean
)

/**
 * Builds the provenance record of one photo from the capture report and the merge facts, so each stage states whether
 * it ran as intended, ran degraded, or was replaced. Nothing here measures image quality; it records what happened.
 */
object CaptureProvenance {
    const val INTENDED_MERGE = "tile-aligned noise-aware temporal merge (luma)"

    fun forBurst(mediaId: String, burst: BurstCapture, merge: MergeFacts, policy: String? = null, policyOverride: String? = null): ProvenanceRecord {
        val stages = listOf(
            ProvenanceRecord.recordOf(captureOutcome(burst), ContentOrigin.CAPTURED),
            mergeRecord(merge),
            ProvenanceRecord.recordOf(colourOutcome(merge.colour), ContentOrigin.CAPTURED)
        )
        return ProvenanceRecord(
            mediaId = mediaId,
            path = PipelinePath.COMPUTATIONAL_PHOTOGRAPHY,
            sourceFormat = burst.frames.firstOrNull()?.format ?: "NONE",
            frameCount = burst.frames.size,
            stages = stages,
            capturedWithoutConvergence = burst.result.capturedWithoutConvergence,
            policy = policy,
            policyOverride = policyOverride
        )
    }

    private fun captureOutcome(burst: BurstCapture): StageOutcome<Unit> {
        val intended = "${burst.result.requested}-frame Camera2 burst, 3A converged and locked"
        val caveats = burst.caveats()
        return if (burst.result.state == OrchestrationState.COMPLETE && caveats.isEmpty()) {
            StageOutcome.Success("capture", Unit, intended)
        } else {
            StageOutcome.Degraded("capture", Unit, intended, "${burst.frames.size}-frame Camera2 burst", caveats.joinToString("; ").ifBlank { burst.result.reason })
        }
    }

    private fun mergeRecord(m: MergeFacts): StageRecord {
        val fallbackNote = if (m.referenceFallback) "every frame failed quality checks; sharpest used as reference" else null
        val outcome: StageOutcome<Unit> = when {
            m.framesIn < 2 -> StageOutcome.Degraded("temporal_merge", Unit, INTENDED_MERGE, "single frame, no merge", "only one frame captured")
            m.guardAction == "REVERT_TO_ORIGINAL" ->
                StageOutcome.Degraded("temporal_merge", Unit, INTENDED_MERGE, "unmerged reference frame", "Reality Guard reverted the merge")
            m.guardAction == "BLEND_WITH_ORIGINAL" -> StageOutcome.Degraded(
                "temporal_merge", Unit, INTENDED_MERGE, "merge blended with reference at ${"%.2f".format(java.util.Locale.ROOT, m.blendRatio)}",
                "Reality Guard diluted the merge"
            )
            fallbackNote != null -> StageOutcome.Degraded("temporal_merge", Unit, INTENDED_MERGE, "$INTENDED_MERGE on a rejected reference", fallbackNote)
            else -> StageOutcome.Success("temporal_merge", Unit, INTENDED_MERGE)
        }
        // A reverted or single-frame result carries captured pixels only; anything merged is reconstructed.
        val origin = if (m.framesIn < 2 || m.guardAction == "REVERT_TO_ORIGINAL") ContentOrigin.CAPTURED else ContentOrigin.RECONSTRUCTED
        return ProvenanceRecord.recordOf(outcome, origin)
    }

    private fun colourOutcome(colour: Boolean): StageOutcome<Unit> =
        if (colour) StageOutcome.Success("colour", Unit, "camera chroma from the reference frame (not merged)")
        else StageOutcome.Degraded("colour", Unit, "colour from chroma planes", "grayscale", "frames had no chroma planes")
}
