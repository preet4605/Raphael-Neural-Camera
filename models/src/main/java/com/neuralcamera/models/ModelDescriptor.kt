package com.neuralcamera.models

/**
 * ModelDescriptor defines the complete operational contract of an on-device neural model.
 * In accordance with Section 18 of the Constitution, every model must have explicit metadata,
 * input/output specifications, hardware backend targets, latency budgets, and fallback routes.
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
    val memoryRequirementBytes: Long,
    val expectedLatencyMs: Long,
    val thermalCostScore: Float, // 0.0 (negligible) to 1.0 (extreme)
    val compatibilityRequirements: List<String>,
    val fallbackModelId: String? = null,
    val isClassicalFallback: Boolean = false,
    val compatibilityState: ModelCompatibilityState = ModelCompatibilityState.AVAILABLE
)
