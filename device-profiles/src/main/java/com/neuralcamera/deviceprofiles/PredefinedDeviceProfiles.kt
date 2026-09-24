package com.neuralcamera.deviceprofiles

import com.neuralcamera.models.HardwareBackendType

object PredefinedDeviceProfiles {

    /**
     * Target Profile: OnePlus 15 (12 GB RAM variant, Android 16)
     * Equipped with Snapdragon 8 Elite (SM8750) Oryon architecture, Hexagon NPU (QNN),
     * Adreno GPU (Vulkan 1.3), 50MP Sony LYT main sensor with full RAW10/RAW12 support.
     */
    val ONEPLUS_15 = DeviceProfile(
        profileId = "oneplus-15-12gb-android16",
        manufacturer = "OnePlus",
        deviceModel = "OnePlus 15",
        socFamily = "Snapdragon 8 Elite",
        totalRamBytes = 12L * 1024 * 1024 * 1024, // 12GB RAM
        osVersionSdk = 36, // Android 16 (Baklava)
        cameras = mapOf(
            "0" to CameraProfile(
                cameraId = "0",
                facing = LensFacing.BACK_WIDE,
                physicalLenses = listOf(
                    PhysicalLensInfo("lens-wide", 5.59f, 1.6f, 9.8f, 7.3f, 4.3f, 1.0f)
                ),
                supportedFormats = setOf("RAW_SENSOR", "YUV_420_888", "JPEG", "HEIC"),
                hasRawSupport = true,
                hasUltraHdrSupport = true,
                hasConcurrentStreams = true,
                isoRange = 50..25600,
                exposureTimeRangeNs = 31250L..30_000_000_000L, // 1/32000s to 30s
                maxZslBufferFrames = 15,
                sensorActiveArraySize = Pair(8192, 6144) // 50MP
            ),
            "1" to CameraProfile(
                cameraId = "1",
                facing = LensFacing.BACK_ULTRAWIDE,
                physicalLenses = listOf(
                    PhysicalLensInfo("lens-uw", 2.2f, 2.0f, 6.4f, 4.8f, 6.0f, 0.6f)
                ),
                supportedFormats = setOf("RAW_SENSOR", "YUV_420_888", "JPEG"),
                hasRawSupport = true,
                hasUltraHdrSupport = true,
                hasConcurrentStreams = true,
                isoRange = 50..12800,
                exposureTimeRangeNs = 31250L..20_000_000_000L,
                maxZslBufferFrames = 12,
                sensorActiveArraySize = Pair(8192, 6144)
            ),
            "2" to CameraProfile(
                cameraId = "2",
                facing = LensFacing.BACK_TELEPHOTO,
                physicalLenses = listOf(
                    PhysicalLensInfo("lens-tele-3x", 15.0f, 2.6f, 6.4f, 4.8f, 6.0f, 3.0f)
                ),
                supportedFormats = setOf("RAW_SENSOR", "YUV_420_888", "JPEG"),
                hasRawSupport = true,
                hasUltraHdrSupport = true,
                hasConcurrentStreams = true,
                isoRange = 50..12800,
                exposureTimeRangeNs = 31250L..20_000_000_000L,
                maxZslBufferFrames = 12,
                sensorActiveArraySize = Pair(8192, 6144)
            )
        ),
        backends = mapOf(
            HardwareBackendType.QUALCOMM_QNN_NPU to BackendProfile(
                backendType = HardwareBackendType.QUALCOMM_QNN_NPU,
                isSupported = true,
                isUsable = true,
                latencyMultiplier = 0.6f,
                thermalEfficiencyScore = 0.95f
            ),
            HardwareBackendType.VULKAN_GPU to BackendProfile(
                backendType = HardwareBackendType.VULKAN_GPU,
                isSupported = true,
                isUsable = true,
                latencyMultiplier = 0.9f,
                thermalEfficiencyScore = 0.80f
            ),
            HardwareBackendType.XNNPACK_CPU to BackendProfile(
                backendType = HardwareBackendType.XNNPACK_CPU,
                isSupported = true,
                isUsable = true,
                latencyMultiplier = 2.5f,
                thermalEfficiencyScore = 0.50f
            )
        ),
        thermalLimits = ThermalLimits(
            normalMaxFps = 60,
            throttleLightMaxFps = 45,
            throttleModerateMaxFps = 30,
            throttleSevereMaxFps = 15,
            maxBurstFramesNormal = 16,
            maxBurstFramesThrottled = 6
        ),
        memoryLimits = MemoryLimits(
            maxFrameRingBufferBytes = 512 * 1024 * 1024L, // 512MB
            maxModelResidencyBytes = 2500 * 1024 * 1024L, // 2.5GB
            maxIntermediateTensorBytes = 256 * 1024 * 1024L // 256MB
        ),
        isProfileVerifiedAtRuntime = true
    )

    val GENERIC_FLAGSHIP = DeviceProfile(
        profileId = "generic-flagship",
        manufacturer = "Android Flagship",
        deviceModel = "Generic Flagship",
        socFamily = "Modern Flagship SoC",
        totalRamBytes = 8L * 1024 * 1024 * 1024,
        osVersionSdk = 35,
        cameras = mapOf(
            "0" to CameraProfile(
                cameraId = "0",
                facing = LensFacing.BACK_WIDE,
                physicalLenses = listOf(PhysicalLensInfo("lens-main", 5.0f, 1.8f, 8.0f, 6.0f, 5.0f, 1.0f)),
                supportedFormats = setOf("RAW_SENSOR", "YUV_420_888", "JPEG"),
                hasRawSupport = true,
                hasUltraHdrSupport = true,
                hasConcurrentStreams = false,
                isoRange = 100..12800,
                exposureTimeRangeNs = 100000L..10_000_000_000L,
                maxZslBufferFrames = 10,
                sensorActiveArraySize = Pair(4096, 3072)
            )
        ),
        backends = mapOf(
            HardwareBackendType.VULKAN_GPU to BackendProfile(
                backendType = HardwareBackendType.VULKAN_GPU,
                isSupported = true,
                isUsable = true,
                latencyMultiplier = 1.0f,
                thermalEfficiencyScore = 0.75f
            ),
            HardwareBackendType.XNNPACK_CPU to BackendProfile(
                backendType = HardwareBackendType.XNNPACK_CPU,
                isSupported = true,
                isUsable = true,
                latencyMultiplier = 3.0f,
                thermalEfficiencyScore = 0.45f
            )
        ),
        thermalLimits = ThermalLimits(),
        memoryLimits = MemoryLimits(
            maxFrameRingBufferBytes = 256 * 1024 * 1024L,
            maxModelResidencyBytes = 1024 * 1024 * 1024L,
            maxIntermediateTensorBytes = 128 * 1024 * 1024L
        ),
        isProfileVerifiedAtRuntime = false
    )

    val GENERIC_FALLBACK = DeviceProfile(
        profileId = "generic-fallback",
        manufacturer = "Universal Android",
        deviceModel = "Fallback Device",
        socFamily = "Standard ARM64",
        totalRamBytes = 4L * 1024 * 1024 * 1024,
        osVersionSdk = 30,
        cameras = mapOf(
            "0" to CameraProfile(
                cameraId = "0",
                facing = LensFacing.BACK_WIDE,
                physicalLenses = listOf(PhysicalLensInfo("lens-default", 4.0f, 2.0f, 6.0f, 4.5f, 6.0f, 1.0f)),
                supportedFormats = setOf("YUV_420_888", "JPEG"),
                hasRawSupport = false,
                hasUltraHdrSupport = false,
                hasConcurrentStreams = false,
                isoRange = 100..3200,
                exposureTimeRangeNs = 500000L..1_000_000_000L,
                maxZslBufferFrames = 5,
                sensorActiveArraySize = Pair(1920, 1080)
            )
        ),
        backends = mapOf(
            HardwareBackendType.XNNPACK_CPU to BackendProfile(
                backendType = HardwareBackendType.XNNPACK_CPU,
                isSupported = true,
                isUsable = true,
                latencyMultiplier = 4.0f,
                thermalEfficiencyScore = 0.40f
            )
        ),
        thermalLimits = ThermalLimits(
            normalMaxFps = 30,
            throttleLightMaxFps = 24,
            throttleModerateMaxFps = 15,
            throttleSevereMaxFps = 10,
            maxBurstFramesNormal = 6,
            maxBurstFramesThrottled = 2
        ),
        memoryLimits = MemoryLimits(
            maxFrameRingBufferBytes = 96 * 1024 * 1024L,
            maxModelResidencyBytes = 256 * 1024 * 1024L,
            maxIntermediateTensorBytes = 32 * 1024 * 1024L
        ),
        isProfileVerifiedAtRuntime = false
    )
}

class InMemoryDeviceProfileRepository(
    initialProfile: DeviceProfile = PredefinedDeviceProfiles.ONEPLUS_15
) : DeviceProfileRepository {

    private var activeProfile: DeviceProfile = initialProfile
    private val knownProfiles = mutableMapOf<String, DeviceProfile>(
        PredefinedDeviceProfiles.ONEPLUS_15.profileId to PredefinedDeviceProfiles.ONEPLUS_15,
        PredefinedDeviceProfiles.GENERIC_FLAGSHIP.profileId to PredefinedDeviceProfiles.GENERIC_FLAGSHIP,
        PredefinedDeviceProfiles.GENERIC_FALLBACK.profileId to PredefinedDeviceProfiles.GENERIC_FALLBACK
    )

    override fun getActiveProfile(): DeviceProfile = activeProfile

    override fun setActiveProfile(profile: DeviceProfile) {
        activeProfile = profile
        knownProfiles[profile.profileId] = profile
    }

    override fun findProfileById(profileId: String): DeviceProfile? = knownProfiles[profileId]

    override fun listKnownProfiles(): List<DeviceProfile> = knownProfiles.values.toList()
}
