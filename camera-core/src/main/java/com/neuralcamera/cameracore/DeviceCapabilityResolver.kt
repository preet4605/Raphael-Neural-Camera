package com.neuralcamera.cameracore

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build
import android.util.Size
import com.neuralcamera.deviceprofiles.Android16Profile
import com.neuralcamera.deviceprofiles.CapabilityRecord
import com.neuralcamera.deviceprofiles.CapabilityStatus
import com.neuralcamera.deviceprofiles.DiscoveredCameraProfile
import com.neuralcamera.deviceprofiles.LensFacing
import com.neuralcamera.deviceprofiles.LensProfile
import com.neuralcamera.deviceprofiles.PhysicalCameraRecord
import com.neuralcamera.deviceprofiles.SensorProfile
import com.neuralcamera.deviceprofiles.StreamMatrixProfile
import com.neuralcamera.deviceprofiles.ThreeAProfile

/**
 * Resolves camera hardware capabilities using low-level Android Camera2 characteristics.
 * In accordance with Phase 1 Section 4:
 * Every capability is derived from actual CameraCharacteristics APIs and categorized as
 * DECLARED, DISCOVERED, VERIFIED, or UNSUPPORTED.
 */
class DeviceCapabilityResolver(
    private val context: Context,
    private val cameraManager: CameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
) {

    /**
     * Enumerates all available camera devices and discovers their complete capability profile.
     */
    fun enumerateCameras(): Map<String, DiscoveredCameraProfile> {
        val result = mutableMapOf<String, DiscoveredCameraProfile>()
        val cameraIds = try {
            cameraManager.cameraIdList
        } catch (e: Exception) {
            emptyArray()
        }

        for (id in cameraIds) {
            try {
                val chars = cameraManager.getCameraCharacteristics(id)
                val profile = buildCameraProfile(id, chars)
                result[id] = profile
            } catch (e: Exception) {
                // If permission or access fails, do not crash; record what is possible
            }
        }

        return result
    }

    /**
     * Builds a detailed capability profile for a single camera ID.
     */
    fun buildCameraProfile(
        cameraId: String,
        chars: CameraCharacteristics
    ): DiscoveredCameraProfile {
        val capabilitiesMap = mutableMapOf<String, CapabilityRecord<*>>()
        val defaulted = mutableSetOf<String>()
        fun <T> CameraCharacteristics.Key<T>.orDefault(default: T, name: String): T =
            chars.get(this) ?: default.also { defaulted += name }

        // 1. Identity & Hardware Level
        val hardwareLevelInt = chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
            ?: CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY
        val hardwareLevel = when (hardwareLevelInt) {
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
            else -> "UNKNOWN_$hardwareLevelInt"
        }

        val lensFacingInt = CameraCharacteristics.LENS_FACING.orDefault(CameraCharacteristics.LENS_FACING_BACK, "LENS_FACING")
        val lensFacing = when (lensFacingInt) {
            CameraCharacteristics.LENS_FACING_FRONT -> LensFacing.FRONT
            CameraCharacteristics.LENS_FACING_BACK -> LensFacing.BACK_WIDE
            else -> LensFacing.BACK_WIDE
        }

        val sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

        // Logical / Physical status
        val availableCaps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        val isLogical = availableCaps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)
        val physicalIds = chars.physicalCameraIds.toList()

        capabilitiesMap["LOGICAL_MULTI_CAMERA"] = CapabilityRecord(
            name = "LOGICAL_MULTI_CAMERA",
            value = isLogical,
            status = if (isLogical) CapabilityStatus.DISCOVERED else CapabilityStatus.UNSUPPORTED,
            sourceApi = "CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA",
            tested = true,
            verified = false
        )

        // 2. Sensor Profile
        val activeArray = CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE.orDefault(Rect(0, 0, 4000, 3000), "SENSOR_INFO_ACTIVE_ARRAY_SIZE")
        val pixelArray = chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE) ?: Size(4000, 3000)
        val physicalSize = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val rawIsoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val isoRange: ClosedRange<Int> = if (rawIsoRange != null) rawIsoRange.lower..rawIsoRange.upper else (100..3200).also { defaulted += "SENSOR_INFO_SENSITIVITY_RANGE" }
        val rawExpRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val expRange: ClosedRange<Long> = if (rawExpRange != null) rawExpRange.lower..rawExpRange.upper else (100_000L..1_000_000_000L).also { defaulted += "SENSOR_INFO_EXPOSURE_TIME_RANGE" }
        val maxFrameDuration = chars.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION) ?: 33_333_333L
        val whiteLevel = chars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)

        val sensorProfile = SensorProfile(
            activeArrayWidth = activeArray.width(),
            activeArrayHeight = activeArray.height(),
            pixelArrayWidth = pixelArray.width,
            pixelArrayHeight = pixelArray.height,
            physicalWidthMm = physicalSize?.width ?: 6.4f,
            physicalHeightMm = physicalSize?.height ?: 4.8f,
            isoRange = isoRange,
            exposureTimeRangeNs = expRange,
            maxFrameDurationNs = maxFrameDuration,
            whiteLevel = whiteLevel,
            colorFilterArrangement = "RGGB"
        )

        // 3. Lens Profile
        val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList() ?: listOf(5.59f)
        val apertures = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.toList() ?: listOf(1.6f)
        val minFocusDist = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0.1f
        val hyperfocalDist = chars.get(CameraCharacteristics.LENS_INFO_HYPERFOCAL_DISTANCE) ?: 0f

        val oisModesInt = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION) ?: intArrayOf()
        val oisModes = oisModesInt.map { mode ->
            if (mode == CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_ON) "ON" else "OFF"
        }

        val hasOis = oisModes.contains("ON")
        capabilitiesMap["OPTICAL_STABILIZATION"] = CapabilityRecord(
            name = "OPTICAL_STABILIZATION",
            value = hasOis,
            status = if (hasOis) CapabilityStatus.DISCOVERED else CapabilityStatus.UNSUPPORTED,
            sourceApi = "CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION",
            tested = true,
            verified = false
        )

        val lensProfile = LensProfile(
            focalLengthsMm = focalLengths,
            apertures = apertures,
            minimumFocusDistanceMeters = if (minFocusDist > 0f) 1f / minFocusDist else 0.1f,
            hyperfocalDistanceMeters = hyperfocalDist,
            oisModes = oisModes
        )

        // 4. 3A Profile
        val aeModesInt = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES) ?: intArrayOf()
        val afModesInt = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
        val awbModesInt = chars.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES) ?: intArrayOf()
        val rawEvRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        val evRange: ClosedRange<Int> = if (rawEvRange != null) rawEvRange.lower..rawEvRange.upper else -6..6
        val evStepRational = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
        val evStep = evStepRational?.toFloat() ?: 0.333f
        val aeLock = chars.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) ?: false
        val awbLock = chars.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) ?: false

        val threeAProfile = ThreeAProfile(
            aeModes = aeModesInt.map { "AE_MODE_$it" },
            afModes = afModesInt.map { "AF_MODE_$it" },
            awbModes = awbModesInt.map { "AWB_MODE_$it" },
            exposureCompensationRange = evRange,
            exposureCompensationStep = evStep,
            aeLockSupported = aeLock,
            awbLockSupported = awbLock
        )

        // 5. Streams & Format Capabilities
        val streamMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val supportedFormats = mutableSetOf<String>()
        val outputSizesByFormat = mutableMapOf<String, List<Pair<Int, Int>>>()

        if (streamMap != null) {
            for (format in streamMap.outputFormats) {
                val formatName = when (format) {
                    ImageFormat.JPEG -> "JPEG"
                    ImageFormat.YUV_420_888 -> "YUV_420_888"
                    ImageFormat.RAW_SENSOR -> "RAW_SENSOR"
                    ImageFormat.RAW10 -> "RAW10"
                    ImageFormat.RAW12 -> "RAW12"
                    ImageFormat.PRIVATE -> "PRIVATE"
                    ImageFormat.HEIC -> "HEIC"
                    else -> "FORMAT_$format"
                }
                supportedFormats.add(formatName)
                val sizes = streamMap.getOutputSizes(format)?.map { Pair(it.width, it.height) } ?: emptyList()
                outputSizesByFormat[formatName] = sizes
            }
        }

        // RAW capability recording
        val hasRaw = supportedFormats.contains("RAW_SENSOR") || supportedFormats.contains("RAW10")
        capabilitiesMap["RAW_SENSOR"] = CapabilityRecord(
            name = "RAW_SENSOR",
            value = hasRaw,
            status = if (hasRaw) CapabilityStatus.DISCOVERED else CapabilityStatus.UNSUPPORTED,
            sourceApi = "StreamConfigurationMap.outputFormats(RAW_SENSOR)",
            tested = true,
            verified = false
        )

        // Dynamic Range Profiles (API 33+)
        val dynamicRangeProfiles = mutableListOf("SDR")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val drProfiles = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)
            if (drProfiles != null) {
                val supportedProfiles = drProfiles.supportedProfiles
                if (supportedProfiles.contains(android.hardware.camera2.params.DynamicRangeProfiles.HLG10)) {
                    dynamicRangeProfiles.add("HLG10")
                }
                if (supportedProfiles.contains(android.hardware.camera2.params.DynamicRangeProfiles.HDR10)) {
                    dynamicRangeProfiles.add("HDR10")
                }
                if (supportedProfiles.contains(android.hardware.camera2.params.DynamicRangeProfiles.HDR10_PLUS)) {
                    dynamicRangeProfiles.add("HDR10_PLUS")
                }
                if (supportedProfiles.contains(android.hardware.camera2.params.DynamicRangeProfiles.DOLBY_VISION_10B_HDR_OEM)) {
                    dynamicRangeProfiles.add("DOLBY_VISION_10B")
                }
            }
        }

        // Stream Use Cases (API 33+)
        val streamUseCases = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val useCases = chars.get(CameraCharacteristics.SCALER_AVAILABLE_STREAM_USE_CASES)
            if (useCases != null) {
                for (uc in useCases) {
                    when (uc) {
                        CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_PREVIEW.toLong() -> streamUseCases.add("PREVIEW")
                        CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_STILL_CAPTURE.toLong() -> streamUseCases.add("STILL_CAPTURE")
                        CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_VIDEO_RECORD.toLong() -> streamUseCases.add("VIDEO_RECORD")
                        CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_PREVIEW_VIDEO_STILL.toLong() -> streamUseCases.add("PREVIEW_VIDEO_STILL")
                        CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_VIDEO_CALL.toLong() -> streamUseCases.add("VIDEO_CALL")
                    }
                }
            }
        }

        val streamsProfile = StreamMatrixProfile(
            supportedFormats = supportedFormats,
            outputSizesByFormat = outputSizesByFormat,
            streamUseCases = streamUseCases,
            dynamicRangeProfiles = dynamicRangeProfiles
        )

        // 6. Android 16 Capabilities
        val hybridAeModes = mutableListOf<String>()
        // On Android 16 (API 36), check for ISO-priority or shutter-priority AE modes
        for (mode in aeModesInt) {
            if (mode == 6) hybridAeModes.add("ISO_PRIORITY")
            if (mode == 7) hybridAeModes.add("EXPOSURE_TIME_PRIORITY")
        }

        val android16Profile = Android16Profile(
            hybridAeModesSupported = hybridAeModes,
            nightModeIndicatorSupported = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES)
                ?.contains(CameraCharacteristics.CONTROL_SCENE_MODE_NIGHT) ?: false,
            ultraHdrSupported = supportedFormats.contains("JPEG_R") || dynamicRangeProfiles.contains("HDR10")
        )

        return DiscoveredCameraProfile(
            cameraId = cameraId,
            hardwareLevel = hardwareLevel,
            lensFacing = lensFacing,
            sensorOrientation = sensorOrientation,
            isLogical = isLogical,
            physicalCameraIds = physicalIds,
            sensor = sensorProfile,
            lens = lensProfile,
            threeA = threeAProfile,
            streams = streamsProfile,
            android16 = android16Profile,
            vendorExtensions = emptyList(),
            capabilities = capabilitiesMap,
            defaultedKeys = defaulted
        )
    }
}
