package com.neuralcamera.models

object PredefinedModelCatalog {

    val CLASSICAL_BASELINE_ISP = ModelDescriptor(
        modelId = "classical-baseline-isp-v1",
        name = "Classical Production ISP Baseline",
        purpose = SemanticPurpose.NEURAL_ISP,
        version = ModelVersion(1, 0, 0),
        license = "Apache-2.0",
        fileSizeBytes = 0L,
        inputFormat = "RAW_SENSOR/YUV_420_888",
        outputFormat = "RGBA_8888/JPEG",
        inputResolution = Pair(4096, 3072),
        outputResolution = Pair(4096, 3072),
        tensorPrecision = TensorPrecision.FP32,
        supportedBackends = listOf(HardwareBackendType.XNNPACK_CPU),
        memoryRequirementBytes = 32 * 1024 * 1024L, // 32MB
        expectedLatencyMs = 28L,
        thermalCostScore = 0.1f,
        compatibilityRequirements = listOf("Android 11+"),
        fallbackModelId = null,
        isClassicalFallback = true,
        compatibilityState = ModelCompatibilityState.VERIFIED
    )

    val NEURAL_ISP_LITE = ModelDescriptor(
        modelId = "neural-isp-lite-v1",
        name = "Neural ISP Lite (HDR & Temporal Denoise)",
        purpose = SemanticPurpose.NEURAL_ISP,
        version = ModelVersion(1, 1, 0),
        license = "Apache-2.0",
        fileSizeBytes = 14 * 1024 * 1024L, // 14MB
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
        memoryRequirementBytes = 96 * 1024 * 1024L, // 96MB
        expectedLatencyMs = 42L,
        thermalCostScore = 0.35f,
        compatibilityRequirements = listOf("Qualcomm HTP or Vulkan 1.3"),
        fallbackModelId = CLASSICAL_BASELINE_ISP.modelId,
        isClassicalFallback = false,
        compatibilityState = ModelCompatibilityState.AVAILABLE
    )

    val PERCEPTION_SCENE_ALIGNMENT = ModelDescriptor(
        modelId = "perception-motion-alignment-v1",
        name = "Perception Fast Optical Alignment",
        purpose = SemanticPurpose.TEMPORAL_RECONSTRUCTION,
        version = ModelVersion(1, 0, 2),
        license = "Apache-2.0",
        fileSizeBytes = 6 * 1024 * 1024L, // 6MB
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
        memoryRequirementBytes = 32 * 1024 * 1024L,
        expectedLatencyMs = 12L,
        thermalCostScore = 0.15f,
        compatibilityRequirements = listOf("All modern Android devices"),
        fallbackModelId = null,
        isClassicalFallback = false,
        compatibilityState = ModelCompatibilityState.AVAILABLE
    )

    val OMNI_NEURAL_4B_MOBILE = ModelDescriptor(
        modelId = "omnineural-4b-mobile-v1",
        name = "OmniNeural 4B Mobile Semantic Director",
        purpose = SemanticPurpose.SEMANTIC_DIRECTOR,
        version = ModelVersion(1, 0, 0),
        license = "Open RAIL-M",
        fileSizeBytes = 2_200_000_000L, // 2.2GB INT4
        inputFormat = "TOKEN_STREAM/IMAGE_EMBEDDING",
        outputFormat = "STRUCTURED_INTENT_JSON",
        inputResolution = Pair(384, 384),
        outputResolution = Pair(0, 0),
        tensorPrecision = TensorPrecision.INT4,
        supportedBackends = listOf(
            HardwareBackendType.QUALCOMM_QNN_NPU,
            HardwareBackendType.VULKAN_GPU
        ),
        memoryRequirementBytes = 2_600_000_000L, // ~2.6GB RAM
        expectedLatencyMs = 350L,
        thermalCostScore = 0.85f,
        compatibilityRequirements = listOf("Minimum 12GB device RAM", "Qualcomm Snapdragon 8 Gen 3/Elite"),
        fallbackModelId = null,
        isClassicalFallback = false,
        compatibilityState = ModelCompatibilityState.AVAILABLE
    )

    val FLUX_KLEIN_4B_STUDIO = ModelDescriptor(
        modelId = "flux-klein-4b-studio-v1",
        name = "FLUX.2 Klein 4B Generative Studio",
        purpose = SemanticPurpose.GENERATIVE,
        version = ModelVersion(1, 0, 0),
        license = "Custom Research / Studio",
        fileSizeBytes = 2_400_000_000L,
        inputFormat = "LATENT_TENSOR_RGB",
        outputFormat = "RGB_IMAGE_1024",
        inputResolution = Pair(512, 512),
        outputResolution = Pair(1024, 1024),
        tensorPrecision = TensorPrecision.INT4,
        supportedBackends = listOf(
            HardwareBackendType.QUALCOMM_QNN_NPU,
            HardwareBackendType.VULKAN_GPU
        ),
        memoryRequirementBytes = 3_200_000_000L,
        expectedLatencyMs = 2800L,
        thermalCostScore = 0.95f,
        compatibilityRequirements = listOf("AI Studio mode only", "Not in camera hot path"),
        fallbackModelId = null,
        isClassicalFallback = false,
        compatibilityState = ModelCompatibilityState.AVAILABLE
    )
}
