package com.neuralcamera.models

/**
 * Expected quality metric targets for neural model evaluation.
 */
data class QualityMetricsExpectation(
    val minPsnrDb: Float = 32.0f,
    val minSsim: Float = 0.92f,
    val maxArtifactScore: Float = 0.08f
)

/**
 * ModelDescriptor defines the complete operational contract of an on-device neural model.
 * In accordance with Section 14 of the Constitution, every model must declare:
 * modelId, version, license, size, inputDescription, outputDescription, precision,
 * supportedBackends, memoryRequirement, latencyExpectation, qualityMetrics, fallback, compatibility.
 */
data class ModelDescriptor(
    val modelId: String,
    val name: String,
    val purpose: SemanticPurpose,
    val version: ModelVersion,
    val license: String,
    val fileSizeBytes: Long,
    val inputFormat: String,
    val outputFormat: String,
    val inputResolution: Pair<Int, Int>,
    val outputResolution: Pair<Int, Int>,
    val tensorPrecision: TensorPrecision,
    val supportedBackends: List<HardwareBackendType>,
    /** Measured on-device; null until measured. */
    val memoryRequirementBytes: Long?,
    /** Measured on-device; null until measured. */
    val expectedLatencyMs: Long?,
    /** 0.0 (negligible) to 1.0 (extreme); measured on-device, null until measured. */
    val thermalCostScore: Float?,
    val compatibilityRequirements: List<String>,
    val fallbackModelId: String? = null,
    val isClassicalFallback: Boolean = false,
    val compatibilityState: ModelCompatibilityState = ModelCompatibilityState.UNVERIFIED,
    val inputDescription: String = "$inputFormat at ${inputResolution.first}x${inputResolution.second}",
    val outputDescription: String = "$outputFormat at ${outputResolution.first}x${outputResolution.second}",
    val qualityMetrics: QualityMetricsExpectation? = null
) {
    // Aliases ensuring 100% adherence to Section 14 naming
    val size: Long get() = fileSizeBytes
    val precision: TensorPrecision get() = tensorPrecision
    val memoryRequirement: Long? get() = memoryRequirementBytes
    val latencyExpectation: Long? get() = expectedLatencyMs
    val fallback: String? get() = fallbackModelId
    val compatibility: List<String> get() = compatibilityRequirements
}

/**
 * Manifest validator ensuring model manifests meet strict operational standards (Section 23).
 */
object ModelManifestValidator {

    data class ValidationResult(
        val isValid: Boolean,
        val issues: List<String>
    )

    fun validate(descriptor: ModelDescriptor): ValidationResult {
        val issues = mutableListOf<String>()

        if (descriptor.modelId.isBlank()) {
            issues.add("modelId cannot be blank")
        }
        if (descriptor.license.isBlank()) {
            issues.add("license cannot be blank")
        }
        val isVerified = descriptor.compatibilityState == ModelCompatibilityState.VERIFIED
        if (descriptor.size < 0) {
            issues.add("fileSizeBytes cannot be negative")
        }
        if (isVerified && !descriptor.isClassicalFallback && descriptor.size == 0L) {
            issues.add("Verified neural model must reference a model artifact (size > 0)")
        }
        if (descriptor.supportedBackends.isEmpty()) {
            issues.add("supportedBackends cannot be empty")
        }
        descriptor.memoryRequirement?.let {
            if (it <= 0) issues.add("memoryRequirement must be positive when specified")
        }
        descriptor.latencyExpectation?.let {
            if (it <= 0) issues.add("latencyExpectation must be positive when specified")
        }
        if (isVerified && descriptor.memoryRequirement == null) {
            issues.add("Verified model must declare a measured memoryRequirement")
        }
        if (isVerified && descriptor.latencyExpectation == null) {
            issues.add("Verified model must declare a measured latencyExpectation")
        }
        if (descriptor.inputDescription.isBlank()) {
            issues.add("inputDescription cannot be blank")
        }
        if (descriptor.outputDescription.isBlank()) {
            issues.add("outputDescription cannot be blank")
        }
        if (!descriptor.isClassicalFallback && descriptor.fallback == null) {
            issues.add("Neural models should specify a fallbackModelId for reliability")
        }

        return ValidationResult(isValid = issues.isEmpty(), issues = issues)
    }
}
