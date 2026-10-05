package com.neuralcamera.deviceprofiles

import com.neuralcamera.models.HardwareBackendType

object PredefinedDeviceProfiles {

    /**
     * Target Profile: OnePlus 15 (12 GB RAM variant, Android 16)
     * Authoritative Hardware: SM8850 (Snapdragon 8 Elite Gen 5), 3rd-generation Qualcomm Oryon CPU,
     * Qualcomm Adreno 840 GPU (Vulkan 1.4.0), Qualcomm Hexagon HTP V81 cDSP (QNN 2.37.4).
     * Camera Topology: 5 LEVEL_3 devices. Camera 0 is a logical rear camera combining physical IDs [3, 2, 4].
     * Direct HAL HEIC Ultra HDR is disabled via ro.camera.disableHeicUltraHDR=true; software gainmap pipeline supported.
     */
    val ONEPLUS_15 = DeviceProfile(
        profileId = "oneplus-15-12gb-android16",
        manufacturer = "OnePlus",
        deviceModel = "OnePlus 15",
        socFamily = "SM8850 (Snapdragon 8 Elite Gen 5)",
        totalRamBytes = 12L * 1024 * 1024 * 1024, // 12GB RAM
        osVersionSdk = 36, // Android 16 (Baklava)
        hardware = HardwareIdentity(
            cpuArchitecture = "3rd-generation Qualcomm Oryon",
            gpuRenderer = "Qualcomm Adreno 840",
            npuName = "Qualcomm Hexagon HTP V81",
            totalRamBytes = 12L * 1024 * 1024 * 1024,
            cpuCores = 8
        ),
        cameras = mapOf(
            "0" to CameraProfile(
                cameraId = "0",
                facing = LensFacing.BACK_WIDE,
                physicalLenses = listOf(
                    PhysicalLensInfo("lens-wide-phys2", 5.59f, 1.88f, 9.8f, 7.3f, 4.3f, 1.0f),
                    PhysicalLensInfo("lens-uw-phys3", 2.31f, 2.0f, 6.4f, 4.8f, 6.0f, 0.6f),
                    PhysicalLensInfo("lens-tele-phys4", 12.19f, 2.85f, 6.4f, 4.8f, 6.0f, 3.0f)
                ),
                supportedFormats = setOf("RAW_SENSOR", "YUV_420_888", "JPEG", "HEIC"),
                hasRawSupport = true,
                hasUltraHdrSupport = false, // Direct HAL Ultra HDR disabled by HAL; software pipeline handles HDR
                hasConcurrentStreams = true,
                isoRange = 50..25600,
                exposureTimeRangeNs = 31250L..30_000_000_000L, // 1/32000s to 30s
                maxZslBufferFrames = 15,
                sensorActiveArraySize = Pair(8192, 6144), // 50MP
                capabilities = CameraCapabilities(
                    isLogical = true,
                    physicalCameraIds = listOf("3", "2", "4"),
                    sensorName = "Sony Main Multi-Camera",
                    resolution = Pair(8192, 6144),
                    hasRaw = true,
                    hasYuv = true,
                    hasJpeg = true,
                    hasHeif = true,
                    hasHdr = false,
                    hasOis = true
                )
            ),
            "1" to CameraProfile(
                cameraId = "1",
                facing = LensFacing.FRONT,
                physicalLenses = listOf(
                    PhysicalLensInfo("lens-front", 3.23f, 2.4f, 4.6f, 3.5f, 7.5f, 1.0f)
                ),
                supportedFormats = setOf("YUV_420_888", "JPEG"),
                hasRawSupport = false,
                hasUltraHdrSupport = false,
                hasConcurrentStreams = false,
                isoRange = 100..6400,
                exposureTimeRangeNs = 50000L..1_000_000_000L,
                maxZslBufferFrames = 8,
                sensorActiveArraySize = Pair(3280, 2464),
                capabilities = CameraCapabilities(
                    isLogical = false,
                    physicalCameraIds = emptyList(),
                    sensorName = "Front Sensor",
                    resolution = Pair(3280, 2464),
                    hasRaw = false,
                    hasYuv = true,
                    hasJpeg = true,
                    hasHeif = false,
                    hasHdr = false,
                    hasOis = false
                )
            ),
            "2" to CameraProfile(
                cameraId = "2",
                facing = LensFacing.BACK_WIDE,
                physicalLenses = listOf(
                    PhysicalLensInfo("lens-main-phys", 5.59f, 1.88f, 9.8f, 7.3f, 4.3f, 1.0f)
                ),
                supportedFormats = setOf("RAW_SENSOR", "YUV_420_888", "JPEG"),
                hasRawSupport = true,
                hasUltraHdrSupport = false,
                hasConcurrentStreams = true,
                isoRange = 50..25600,
                exposureTimeRangeNs = 31250L..30_000_000_000L,
                maxZslBufferFrames = 15,
                sensorActiveArraySize = Pair(8192, 6144),
                capabilities = CameraCapabilities(
                    isLogical = false,
                    physicalCameraIds = emptyList(),
                    sensorName = "Sony Main Physical (50MP)",
                    resolution = Pair(8192, 6144),
                    hasRaw = true,
                    hasYuv = true,
                    hasJpeg = true,
                    hasHeif = false,
                    hasHdr = false,
                    hasOis = true
                )
            ),
            "3" to CameraProfile(
                cameraId = "3",
                facing = LensFacing.BACK_ULTRAWIDE,
                physicalLenses = listOf(
                    PhysicalLensInfo("lens-uw-phys", 2.31f, 2.0f, 6.4f, 4.8f, 6.0f, 0.6f)
                ),
                supportedFormats = setOf("RAW_SENSOR", "YUV_420_888", "JPEG"),
                hasRawSupport = true,
                hasUltraHdrSupport = false,
                hasConcurrentStreams = true,
                isoRange = 50..12800,
                exposureTimeRangeNs = 31250L..20_000_000_000L,
                maxZslBufferFrames = 12,
                sensorActiveArraySize = Pair(4096, 3072),
                capabilities = CameraCapabilities(
                    isLogical = false,
                    physicalCameraIds = emptyList(),
                    sensorName = "Sony Ultra-Wide Physical",
                    resolution = Pair(4096, 3072),
                    hasRaw = true,
                    hasYuv = true,
                    hasJpeg = true,
                    hasHeif = false,
                    hasHdr = false,
                    hasOis = false
                )
            ),
            "4" to CameraProfile(
                cameraId = "4",
                facing = LensFacing.BACK_TELEPHOTO,
                physicalLenses = listOf(
                    PhysicalLensInfo("lens-tele-phys", 12.19f, 2.85f, 6.4f, 4.8f, 6.0f, 3.0f)
                ),
                supportedFormats = setOf("RAW_SENSOR", "YUV_420_888", "JPEG"),
                hasRawSupport = true,
                hasUltraHdrSupport = false,
                hasConcurrentStreams = true,
                isoRange = 50..12800,
                exposureTimeRangeNs = 31250L..20_000_000_000L,
                maxZslBufferFrames = 12,
                sensorActiveArraySize = Pair(4096, 3072),
                capabilities = CameraCapabilities(
                    isLogical = false,
                    physicalCameraIds = emptyList(),
                    sensorName = "Sony Telephoto Physical",
                    resolution = Pair(4096, 3072),
                    hasRaw = true,
                    hasYuv = true,
                    hasJpeg = true,
                    hasHeif = false,
                    hasHdr = false,
                    hasOis = true
                )
            )
        ),
        backends = mapOf(
            HardwareBackendType.QUALCOMM_QNN_NPU to BackendProfile(
                backendType = HardwareBackendType.QUALCOMM_QNN_NPU,
                isSupported = true,
                isUsable = false
            ),
            HardwareBackendType.VULKAN_GPU to BackendProfile(
                backendType = HardwareBackendType.VULKAN_GPU,
                isSupported = true,
                isUsable = false
            ),
            HardwareBackendType.XNNPACK_CPU to BackendProfile(
                backendType = HardwareBackendType.XNNPACK_CPU,
                isSupported = true,
                isUsable = true
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
        isProfileVerifiedAtRuntime = false
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
                isUsable = false
            ),
            HardwareBackendType.XNNPACK_CPU to BackendProfile(
                backendType = HardwareBackendType.XNNPACK_CPU,
                isSupported = true,
                isUsable = true
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
                isUsable = true
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
