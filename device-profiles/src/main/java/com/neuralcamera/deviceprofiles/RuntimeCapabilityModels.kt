package com.neuralcamera.deviceprofiles

/**
 * Capability interrogation lifecycle status as specified by Phase 1:
 * DECLARED: Advertised by device specs or metadata.
 * DISCOVERED: Read from actual CameraCharacteristics / HAL APIs at runtime.
 * VERIFIED: Explicitly tested through actual session creation / capture execution.
 * UNSUPPORTED: Tested or declared unavailable on this device.
 */
enum class CapabilityStatus {
    DECLARED,
    DISCOVERED,
    VERIFIED,
    UNSUPPORTED
}

/**
 * Provenance tracking for every capability.
 */
data class CapabilityRecord<T>(
    val name: String,
    val value: T,
    val status: CapabilityStatus,
    val sourceApi: String,
    val tested: Boolean = false,
    val verified: Boolean = false,
    val testResult: String? = null,
    val details: String? = null
)

/**
 * Detailed sensor characteristics discovered from Camera2.
 */
data class SensorProfile(
    val activeArrayWidth: Int,
    val activeArrayHeight: Int,
    val pixelArrayWidth: Int,
    val pixelArrayHeight: Int,
    val physicalWidthMm: Float,
    val physicalHeightMm: Float,
    val isoRange: ClosedRange<Int>,
    val exposureTimeRangeNs: ClosedRange<Long>,
    val maxFrameDurationNs: Long,
    val blackLevelPattern: List<Int>? = null,
    val whiteLevel: Int? = null,
    val colorFilterArrangement: String = "RGGB"
)

/**
 * Detailed lens characteristics discovered from Camera2.
 */
data class LensProfile(
    val focalLengthsMm: List<Float>,
    val apertures: List<Float>,
    val minimumFocusDistanceMeters: Float,
    val hyperfocalDistanceMeters: Float = 0f,
    val oisModes: List<String> = emptyList(),
    val focusCalibration: String = "CALIBRATED",
    val lensShadingModes: List<String> = emptyList()
)

/**
 * Detailed 3A capabilities discovered from Camera2.
 */
data class ThreeAProfile(
    val aeModes: List<String>,
    val afModes: List<String>,
    val awbModes: List<String>,
    val exposureCompensationRange: ClosedRange<Int>,
    val exposureCompensationStep: Float,
    val aeLockSupported: Boolean,
    val awbLockSupported: Boolean,
    val maxRegionsAe: Int = 1,
    val maxRegionsAf: Int = 1,
    val maxRegionsAwb: Int = 1
)

/**
 * Stream configuration matrix profile.
 */
data class StreamMatrixProfile(
    val supportedFormats: Set<String>,
    val outputSizesByFormat: Map<String, List<Pair<Int, Int>>>,
    val highResolutionSizes: Map<String, List<Pair<Int, Int>>> = emptyMap(),
    val streamUseCases: List<String> = emptyList(),
    val dynamicRangeProfiles: List<String> = listOf("SDR"),
    val dynamicRangeConstraints: Map<String, List<String>> = emptyMap()
)

/**
 * Android 16-specific camera capabilities.
 */
data class Android16Profile(
    val hybridAeModesSupported: List<String> = emptyList(),
    val nightModeIndicatorSupported: Boolean = false,
    val ultraHdrSupported: Boolean = false
)

/**
 * Physical lens within a logical camera arrangement.
 */
data class PhysicalCameraRecord(
    val physicalCameraId: String,
    val lensFacing: LensFacing,
    val physicalSensorSizeMm: Pair<Float, Float>,
    val focalLengthMm: Float,
    val apertureFNumber: Float,
    val supportedOutputSizes: List<Pair<Int, Int>>
)

/**
 * Complete discovered camera profile for a specific camera ID.
 */
data class DiscoveredCameraProfile(
    val cameraId: String,
    val hardwareLevel: String,
    val lensFacing: LensFacing,
    val sensorOrientation: Int,
    val isLogical: Boolean,
    val physicalCameraIds: List<String>,
    val sensor: SensorProfile,
    val lens: LensProfile,
    val threeA: ThreeAProfile,
    val streams: StreamMatrixProfile,
    val android16: Android16Profile,
    val vendorExtensions: List<String> = emptyList(),
    val capabilities: Map<String, CapabilityRecord<*>> = emptyMap()
)

/**
 * Stream configuration test result state.
 */
enum class StreamTestResultState {
    SUPPORTED,
    SESSION_CREATION_SUCCESS,
    ACTUAL_CAPTURE_SUCCESS,
    FAILED
}

/**
 * Stream test result record.
 */
data class StreamTestResult(
    val combinationName: String,
    val streams: List<String>,
    val state: StreamTestResultState,
    val latencyMs: Long = 0L,
    val errorMessage: String? = null
)

/**
 * Runtime device verification report container for serialization.
 */
data class RuntimeDeviceVerification(
    val timestamp: Long,
    val deviceModel: String,
    val manufacturer: String,
    val brand: String,
    val product: String,
    val androidRelease: String,
    val sdkInt: Int,
    val cameras: Map<String, DiscoveredCameraProfile>,
    val logicalPhysicalMappings: Map<String, List<PhysicalCameraRecord>>,
    val streamMatrixResults: List<StreamTestResult>,
    val zeroCopyDesignPass: Boolean = true,
    val zeroCopyHardwareState: String = "PARTIAL",
    val actualBufferCopies: List<String> = emptyList(),
    val notes: String = ""
)
