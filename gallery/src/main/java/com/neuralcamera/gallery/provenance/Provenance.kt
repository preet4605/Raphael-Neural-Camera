package com.neuralcamera.gallery.provenance

import com.neuralcamera.models.execution.ContentOrigin
import com.neuralcamera.models.execution.PipelinePath
import com.neuralcamera.models.execution.StageOutcome
import com.neuralcamera.models.execution.StageStatus

/*
 * Output provenance. Every saved image carries an internal record of how it was made: which pipeline path, which stages
 * ran (and whether they ran as intended or degraded), and the origin of its information: CAPTURED, RECONSTRUCTED or
 * GENERATED. Normal capture is captured -> reconstructed; GENERATED is only valid on the AI_STUDIO path and must be
 * declared. This is internal bookkeeping written to processing metadata, not cryptographic provenance (no C2PA signing).
 */

data class StageRecord(
    val stage: String,
    val status: StageStatus,
    /** What actually executed (null for a failed stage). */
    val implementation: String?,
    val intended: String?,
    val note: String?,
    val origin: ContentOrigin
)

data class ProvenanceRecord(
    val mediaId: String,
    val path: PipelinePath,
    val sourceFormat: String,
    val frameCount: Int,
    val stages: List<StageRecord>,
    /** True when capture proceeded without 3A convergence (from the capture orchestrator). */
    val capturedWithoutConvergence: Boolean,
    val policy: String?,
    val policyOverride: String?
) {
    init {
        require(frameCount >= 1) { "at least one captured frame" }
        stages.firstOrNull { it.origin == ContentOrigin.GENERATED && !path.mayGenerateContent }?.let {
            throw IllegalArgumentException("stage ${it.stage} generated content on the $path path; only AI_STUDIO may")
        }
    }

    /** The strongest origin present: GENERATED > RECONSTRUCTED > CAPTURED. */
    val outputOrigin: ContentOrigin
        get() = when {
            stages.any { it.origin == ContentOrigin.GENERATED } -> ContentOrigin.GENERATED
            stages.any { it.origin == ContentOrigin.RECONSTRUCTED && it.status != StageStatus.FAILED } -> ContentOrigin.RECONSTRUCTED
            else -> ContentOrigin.CAPTURED
        }

    val degraded: Boolean get() = stages.any { it.status != StageStatus.SUCCESS }

    fun toJson(): String = buildString {
        append("{\"media_id\":").append(q(mediaId))
        append(",\"pipeline_path\":").append(q(path.name))
        append(",\"output_origin\":").append(q(outputOrigin.name))
        append(",\"source_format\":").append(q(sourceFormat))
        append(",\"frames\":").append(frameCount)
        append(",\"degraded\":").append(degraded)
        append(",\"captured_without_3a_convergence\":").append(capturedWithoutConvergence)
        append(",\"policy\":").append(policy?.let(::q) ?: "null")
        append(",\"policy_override\":").append(policyOverride?.let(::q) ?: "null")
        append(",\"stages\":[")
        stages.forEachIndexed { i, s ->
            if (i > 0) append(',')
            append("{\"stage\":").append(q(s.stage)).append(",\"status\":").append(q(s.status.name))
            append(",\"implementation\":").append(s.implementation?.let(::q) ?: "null")
            append(",\"intended\":").append(s.intended?.let(::q) ?: "null")
            append(",\"origin\":").append(q(s.origin.name))
            append(",\"note\":").append(s.note?.let(::q) ?: "null").append('}')
        }
        append("]}")
    }

    /** One-line summary suitable for the EXIF ImageDescription / UserComment fields. */
    fun exifSummary(): String =
        "${path.name}; origin=${outputOrigin.name}; frames=$frameCount" + if (degraded) "; degraded" else ""

    companion object {
        fun q(s: String): String = buildString {
            append('"')
            for (c in s) when (c) {
                '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
            append('"')
        }

        /** Converts a stage outcome to a record with the given origin. */
        fun <T> recordOf(outcome: StageOutcome<T>, origin: ContentOrigin): StageRecord = when (outcome) {
            is StageOutcome.Success -> StageRecord(outcome.stage, StageStatus.SUCCESS, outcome.implementation, outcome.implementation, null, origin)
            is StageOutcome.Degraded -> StageRecord(outcome.stage, StageStatus.DEGRADED, outcome.actual, outcome.intended, outcome.reason, origin)
            is StageOutcome.Failed -> StageRecord(outcome.stage, StageStatus.FAILED, null, null, "${outcome.reason} -> ${outcome.fallback}", origin)
        }
    }
}
