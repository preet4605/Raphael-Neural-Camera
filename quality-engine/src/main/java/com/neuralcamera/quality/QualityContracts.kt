package com.neuralcamera.quality

data class ImageQualityScore(
    val overallScore: Float, // 0.0 to 1.0
    val noiseScore: Float, // 1.0 = clean, 0.0 = extreme noise
    val sharpnessScore: Float, // 1.0 = crisp, 0.0 = severe blur
    val dynamicRangeScore: Float, // 1.0 = balanced, 0.0 = severe clipping
    val artifactRiskScore: Float, // 0.0 = authentic, 1.0 = heavy artifacts
    val isAcceptable: Boolean
)

data class ConfidenceMap(
    val width: Int,
    val height: Int,
    val tileConfidence: FloatArray // 0.0 (unreliable/noisy/clipped) to 1.0 (high confidence ground truth)
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ConfidenceMap) return false
        return width == other.width && height == other.height && tileConfidence.contentEquals(other.tileConfidence)
    }

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + height
        result = 31 * result + tileConfidence.contentHashCode()
        return result
    }
}

enum class GuardAction {
    KEEP_RECONSTRUCTED,
    BLEND_WITH_ORIGINAL,
    REVERT_TO_ORIGINAL,
    DISCARD_NEURAL_STAGE
}

data class RealityGuardDecision(
    val action: GuardAction,
    val blendRatio: Float, // 1.0 = full reconstructed, 0.0 = full original
    val reason: String,
    val hallucinationDetected: Boolean,
    val confidenceMean: Float
)

interface QualityEvaluator {
    fun evaluateQuality(
        width: Int,
        height: Int,
        lumaPlane: ByteArray
    ): ImageQualityScore
}

interface ConfidenceEstimator {
    fun estimateConfidence(
        width: Int,
        height: Int,
        lumaPlane: ByteArray,
        iso: Int,
        exposureTimeNs: Long
    ): ConfidenceMap
}

interface RealityGuard {
    fun inspectAndProtect(
        originalLuma: ByteArray,
        reconstructedLuma: ByteArray,
        confidenceMap: ConfidenceMap,
        width: Int,
        height: Int
    ): RealityGuardDecision
}
