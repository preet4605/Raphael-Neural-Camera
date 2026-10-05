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

data class CameraCapabilities(
    val isLogical: Boolean = true,
    val physicalCameraIds: List<String> = emptyList(),
    val sensorName: String = "Sony LYT-808",
    val resolution: Pair<Int, Int> = Pair(8192, 6144),
    val hasRaw: Boolean = true,
    val hasYuv: Boolean = true,
    val hasJpeg: Boolean = true,
    val hasHeif: Boolean = true,
    val hasHdr: Boolean = true,
    val has10Bit: Boolean = true,
    val hasOis: Boolean = true,
    val hasAf: Boolean = true,
    val manualControlsSupported: Boolean = true,
    val streamCombinations: List<String> = listOf("RAW+YUV", "YUV+JPEG"),
    val extensionsSupported: List<String> = listOf("NIGHT", "HDR", "BOKEH")
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
    val sensorActiveArraySize: Pair<Int, Int>,
    val capabilities: CameraCapabilities = CameraCapabilities(
        hasRaw = hasRawSupport,
        hasHdr = hasUltraHdrSupport,
        resolution = sensorActiveArraySize
    )
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

data class DeviceIdentity(
    val manufacturer: String,
    val model: String,
    val brand: String = manufacturer,
    val product: String = model
)

data class OsIdentity(
    val osName: String = "Android",
    val apiLevel: Int,
    val supportedAbis: List<String> = listOf("arm64-v8a"),
    val buildIncremental: String = "release"
)

data class HardwareIdentity(
    val cpuArchitecture: String,
    val gpuRenderer: String = "Adreno",
    val npuName: String = "Hexagon NPU",
    val totalRamBytes: Long,
    val cpuCores: Int = 8
)

data class BenchmarkProfile(
    val coldInferenceLatencyMs: Map<String, Long> = emptyMap(),
    val warmInferenceLatencyMs: Map<String, Long> = emptyMap(),
    val peakMemoryUsageBytes: Long = 0L
)

data class CalibrationProfile(
    val blackLevel: Int = 64,
    val whiteLevel: Int = 1023,
    val colorMatrix: FloatArray = floatArrayOf(
        1f, 0f, 0f,
        0f, 1f, 0f,
        0f, 0f, 1f
    ),
    val lensDistortionCoefficients: FloatArray = FloatArray(5)
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CalibrationProfile) return false
        return blackLevel == other.blackLevel &&
                whiteLevel == other.whiteLevel &&
                colorMatrix.contentEquals(other.colorMatrix) &&
                lensDistortionCoefficients.contentEquals(other.lensDistortionCoefficients)
    }

    override fun hashCode(): Int {
        var result = blackLevel
        result = 31 * result + whiteLevel
        result = 31 * result + colorMatrix.contentHashCode()
        result = 31 * result + lensDistortionCoefficients.contentHashCode()
        return result
    }
}

/**
 * Versioned Device Profile format (Section 13 of the Constitution).
 * Contains declarative configuration for device identity, hardware, cameras,
 * runtime backends, thermal thresholds, benchmarking baselines, and calibration.
 */
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
    val isProfileVerifiedAtRuntime: Boolean = false,
    val profileVersion: Int = 1,
    val deviceIdentity: DeviceIdentity = DeviceIdentity(manufacturer, deviceModel),
    val os: OsIdentity = OsIdentity("Android", osVersionSdk, listOf("arm64-v8a")),
    val hardware: HardwareIdentity = HardwareIdentity(socFamily, "Adreno", "Hexagon NPU", totalRamBytes),
    val cameraProfiles: Map<String, CameraProfile> = cameras,
    val runtimeProfiles: Map<HardwareBackendType, BackendProfile> = backends,
    val thermalProfile: ThermalLimits = thermalLimits,
    val benchmarkProfile: BenchmarkProfile = BenchmarkProfile(),
    val calibrationProfile: CalibrationProfile = CalibrationProfile()
)

/**
 * Declarative serializer & deserializer for versioned device profiles (Section 23).
 */
object DeviceProfileSerializer {

    fun serializeToKeyValue(profile: DeviceProfile): Map<String, String> {
        return mapOf(
            "profileVersion" to profile.profileVersion.toString(),
            "profileId" to profile.profileId,
            "manufacturer" to profile.manufacturer,
            "deviceModel" to profile.deviceModel,
            "socFamily" to profile.socFamily,
            "totalRamBytes" to profile.totalRamBytes.toString(),
            "osVersionSdk" to profile.osVersionSdk.toString(),
            "cameraCount" to profile.cameras.size.toString(),
            "isProfileVerifiedAtRuntime" to profile.isProfileVerifiedAtRuntime.toString()
        )
    }

    fun deserializeFromKeyValue(data: Map<String, String>): DeviceProfile {
        val manufacturer = data["manufacturer"] ?: "Generic"
        val deviceModel = data["deviceModel"] ?: "Android Device"
        val soc = data["socFamily"] ?: "Generic SoC"
        val ram = data["totalRamBytes"]?.toLongOrNull() ?: (8L * 1024 * 1024 * 1024)
        val osSdk = data["osVersionSdk"]?.toIntOrNull() ?: 34
        val version = data["profileVersion"]?.toIntOrNull() ?: 1

        return DeviceProfile(
            profileId = data["profileId"] ?: "custom-profile",
            manufacturer = manufacturer,
            deviceModel = deviceModel,
            socFamily = soc,
            totalRamBytes = ram,
            osVersionSdk = osSdk,
            cameras = emptyMap(),
            backends = emptyMap(),
            thermalLimits = ThermalLimits(),
            memoryLimits = MemoryLimits(128 * 1024 * 1024L, 512 * 1024 * 1024L, 64 * 1024 * 1024L),
            isProfileVerifiedAtRuntime = data["isProfileVerifiedAtRuntime"]?.toBooleanStrictOrNull() ?: false,
            profileVersion = version
        )
    }
}
