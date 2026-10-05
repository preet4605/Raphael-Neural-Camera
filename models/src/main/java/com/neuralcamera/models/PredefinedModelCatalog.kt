package com.neuralcamera.models

/**
 * Catalog of planned model descriptors. Nothing here is verified: no model artifact exists in this
 * repository and no model has been executed on any backend.
 *
 * - Measured fields (latency, memory, thermal cost) are null until measured on-device.
 * - `fileSizeBytes` is 0 until an artifact is provisioned; `license` stays UNSPECIFIED until confirmed.
 * - Every entry stays [ModelCompatibilityState.UNVERIFIED] until a run with in-process backend
 *   attribution promotes it.
 * - Formats, resolutions, precision and backend lists are design intent, not evidence.
 */
object PredefinedModelCatalog {

    val CLASSICAL_BASELINE_ISP = ModelDescriptor(
        modelId = "classical-baseline-isp-v1",
        name = "Classical Production ISP Baseline",
        purpose = SemanticPurpose.NEURAL_ISP,
        version = ModelVersion(1, 0, 0),
        license = "UNSPECIFIED",
        fileSizeBytes = 0L,
        inputFormat = "RAW_SENSOR/YUV_420_888",
        outputFormat = "RGBA_8888/JPEG",
        inputResolution = Pair(4096, 3072),
        outputResolution = Pair(4096, 3072),
        tensorPrecision = TensorPrecision.FP32,
        supportedBackends = listOf(HardwareBackendType.XNNPACK_CPU),
        memoryRequirementBytes = null,
        expectedLatencyMs = null,
        thermalCostScore = null,
        compatibilityRequirements = listOf("Android 11+"),
        fallbackModelId = null,
        isClassicalFallback = true,
        compatibilityState = ModelCompatibilityState.UNVERIFIED
    )

    val NEURAL_ISP_LITE = ModelDescriptor(
        modelId = "neural-isp-lite-v1",
        name = "Neural ISP Lite (HDR & Temporal Denoise)",
        purpose = SemanticPurpose.NEURAL_ISP,
        version = ModelVersion(1, 1, 0),
        license = "UNSPECIFIED",
        fileSizeBytes = 0L,
        inputFormat = "RAW10/RAW12/YUV_420_888",
        outputFormat = "RGBA_8888",
        inputResolution = Pair(1920, 1440),
        outputResolution = Pair(1920, 1440),
        tensorPrecision = TensorPrecision.INT8,
        supportedBackends = listOf(
            HardwareBackendType.QUALCOMM_QNN_NPU,
            HardwareBackendType.VULKAN_GPU,
            HardwareBackendType.XNNPACK_CPU
        ),
        memoryRequirementBytes = null,
        expectedLatencyMs = null,
        thermalCostScore = null,
        compatibilityRequirements = emptyList(),
        fallbackModelId = CLASSICAL_BASELINE_ISP.modelId,
        isClassicalFallback = false,
        compatibilityState = ModelCompatibilityState.UNVERIFIED
    )

    val PERCEPTION_SCENE_ALIGNMENT = ModelDescriptor(
        modelId = "perception-motion-alignment-v1",
        name = "Perception Fast Optical Alignment",
        purpose = SemanticPurpose.TEMPORAL_RECONSTRUCTION,
        version = ModelVersion(1, 0, 2),
        license = "UNSPECIFIED",
        fileSizeBytes = 0L,
        inputFormat = "YUV_420_888_LUMA",
        outputFormat = "FLOW_VECTORS_FLOAT",
        inputResolution = Pair(512, 384),
        outputResolution = Pair(512, 384),
        tensorPrecision = TensorPrecision.INT8,
        supportedBackends = listOf(
            HardwareBackendType.QUALCOMM_QNN_NPU,
            HardwareBackendType.VULKAN_GPU,
            HardwareBackendType.XNNPACK_CPU
        ),
        memoryRequirementBytes = null,
        expectedLatencyMs = null,
        thermalCostScore = null,
        compatibilityRequirements = emptyList(),
        fallbackModelId = null,
        isClassicalFallback = false,
        compatibilityState = ModelCompatibilityState.UNVERIFIED
    )

    /** Intended orchestrator role only; model details are unverified. Outside the pixel hot path. */
    val OMNI_NEURAL_4B_MOBILE = ModelDescriptor(
        modelId = "omnineural-4b-mobile-v1",
        name = "OmniNeural 4B Mobile Semantic Director",
        purpose = SemanticPurpose.SEMANTIC_DIRECTOR,
        version = ModelVersion(1, 0, 0),
        license = "UNSPECIFIED",
        fileSizeBytes = 0L,
        inputFormat = "TOKEN_STREAM/IMAGE_EMBEDDING",
        outputFormat = "STRUCTURED_INTENT_JSON",
        inputResolution = Pair(384, 384),
        outputResolution = Pair(0, 0),
        tensorPrecision = TensorPrecision.INT4,
        supportedBackends = listOf(
            HardwareBackendType.QUALCOMM_QNN_NPU,
            HardwareBackendType.VULKAN_GPU
        ),
        memoryRequirementBytes = null,
        expectedLatencyMs = null,
        thermalCostScore = null,
        compatibilityRequirements = listOf("Not in camera hot path"),
        fallbackModelId = null,
        isClassicalFallback = false,
        compatibilityState = ModelCompatibilityState.UNVERIFIED
    )

    /** AI Studio only; runtime integration is not implemented or verified. */
    val FLUX_KLEIN_4B_STUDIO = ModelDescriptor(
        modelId = "flux-klein-4b-studio-v1",
        name = "FLUX.2 Klein 4B Generative Studio",
        purpose = SemanticPurpose.GENERATIVE,
        version = ModelVersion(1, 0, 0),
        license = "UNSPECIFIED",
        fileSizeBytes = 0L,
        inputFormat = "LATENT_TENSOR_RGB",
        outputFormat = "RGB_IMAGE_1024",
        inputResolution = Pair(512, 512),
        outputResolution = Pair(1024, 1024),
        tensorPrecision = TensorPrecision.INT4,
        supportedBackends = listOf(
            HardwareBackendType.QUALCOMM_QNN_NPU,
            HardwareBackendType.VULKAN_GPU
        ),
        memoryRequirementBytes = null,
        expectedLatencyMs = null,
        thermalCostScore = null,
        compatibilityRequirements = listOf("AI Studio mode only", "Not in camera hot path"),
        fallbackModelId = null,
        isClassicalFallback = false,
        compatibilityState = ModelCompatibilityState.UNVERIFIED
    )
}
