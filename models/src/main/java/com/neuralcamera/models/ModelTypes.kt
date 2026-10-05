package com.neuralcamera.models

enum class TensorPrecision {
    FP32,
    FP16,
    INT8,
    INT4,
    DYNAMIC_QUANTIZED
}

enum class HardwareBackendType {
    QUALCOMM_QNN_NPU,
    VULKAN_GPU,
    XNNPACK_CPU,
    OPENCL_GPU,
    NNAPI_FALLBACK
}

enum class ModelCompatibilityState {
    /** No on-device execution evidence exists. Any metric on the descriptor is unmeasured. */
    UNVERIFIED,

    /** Hardware execution verified with real benchmark meeting SLA. */
    VERIFIED,

    /** Model can load on accelerator, latency within acceptable margins. */
    AVAILABLE,

    /** Accelerator failure or performance degradation; automatic classical/CPU fallback active. */
    FALLBACK_ACTIVE,

    /** Model incompatible with current hardware/OS capabilities. */
    UNSUPPORTED
}

data class ModelVersion(
    val major: Int,
    val minor: Int,
    val patch: Int
) : Comparable<ModelVersion> {
    override fun compareTo(other: ModelVersion): Int {
        if (major != other.major) return major.compareTo(other.major)
        if (minor != other.minor) return minor.compareTo(other.minor)
        return patch.compareTo(other.patch)
    }

    override fun toString(): String = "$major.$minor.$patch"
}
