package com.neuralcamera.deviceprofiles

import com.neuralcamera.models.HardwareBackendType

enum class LensFacing {
    BACK_WIDE,
    BACK_ULTRAWIDE,
    BACK_TELEPHOTO,
    FRONT
}

data class PhysicalLensInfo(
    val lensId: String,
    val focalLengthMm: Float,
    val apertureFNumber: Float,
    val sensorWidthMm: Float,
    val sensorHeightMm: Float,
    val cropFactor: Float,
    val opticalZoomFactor: Float
)

data class CameraProfile(
    val cameraId: String,
    val facing: LensFacing,
    val physicalLenses: List<PhysicalLensInfo>,
    val supportedFormats: Set<String>, // "RAW_SENSOR", "YUV_420_888", "JPEG", "HEIC"
    val hasRawSupport: Boolean,
    val hasUltraHdrSupport: Boolean,
    val hasConcurrentStreams: Boolean,
    val isoRange: ClosedRange<Int>,
    val exposureTimeRangeNs: ClosedRange<Long>,
    val maxZslBufferFrames: Int,
    val sensorActiveArraySize: Pair<Int, Int>
)

data class BackendProfile(
    val backendType: HardwareBackendType,
    val isSupported: Boolean,
    val isUsable: Boolean,
    val latencyMultiplier: Float,
    val thermalEfficiencyScore: Float
)

data class ThermalLimits(
    val normalMaxFps: Int = 60,
    val throttleLightMaxFps: Int = 30,
    val throttleModerateMaxFps: Int = 24,
    val throttleSevereMaxFps: Int = 15,
    val maxBurstFramesNormal: Int = 12,
    val maxBurstFramesThrottled: Int = 4
)

data class MemoryLimits(
    val maxFrameRingBufferBytes: Long,
    val maxModelResidencyBytes: Long,
    val maxIntermediateTensorBytes: Long
)

data class DeviceProfile(
    val profileId: String,
    val manufacturer: String,
    val deviceModel: String,
    val socFamily: String,
    val totalRamBytes: Long,
    val osVersionSdk: Int,
    val cameras: Map<String, CameraProfile>,
    val backends: Map<HardwareBackendType, BackendProfile>,
    val thermalLimits: ThermalLimits,
    val memoryLimits: MemoryLimits,
    val isProfileVerifiedAtRuntime: Boolean
)
