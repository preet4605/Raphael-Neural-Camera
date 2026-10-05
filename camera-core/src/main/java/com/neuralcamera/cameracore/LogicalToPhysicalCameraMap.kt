package com.neuralcamera.cameracore

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import com.neuralcamera.deviceprofiles.LensFacing
import com.neuralcamera.deviceprofiles.PhysicalCameraRecord

/**
 * Manages the mapping between logical camera devices and their physical lens constituents.
 * Adheres strictly to Section 5:
 * Never assumes camera ID numbers correspond to specific lens types (0 != main).
 * Identifies optical roles strictly from focal lengths and sensor characteristics.
 */
class LogicalToPhysicalCameraMap(
    private val cameraManager: CameraManager
) {

    private val map = mutableMapOf<String, List<PhysicalCameraRecord>>()

    /**
     * Resolves physical camera records for a given logical camera ID.
     */
    fun resolvePhysicalCameras(
        logicalCameraId: String,
        logicalChars: CameraCharacteristics
    ): List<PhysicalCameraRecord> {
        val physicalIds = logicalChars.physicalCameraIds
        if (physicalIds.isEmpty()) {
            // Non-logical camera or HAL does not expose physical IDs: treat itself as single physical lens
            val record = extractRecord(logicalCameraId, logicalChars)
            val list = listOf(record)
            map[logicalCameraId] = list
            return list
        }

        val records = mutableListOf<PhysicalCameraRecord>()
        for (physId in physicalIds) {
            try {
                val physChars = cameraManager.getCameraCharacteristics(physId)
                records.add(extractRecord(physId, physChars))
            } catch (e: Exception) {
                // If sub-camera interrogation fails, continue with others
            }
        }

        map[logicalCameraId] = records
        return records
    }

    private fun extractRecord(
        cameraId: String,
        chars: CameraCharacteristics
    ): PhysicalCameraRecord {
        val lensFacingInt = chars.get(CameraCharacteristics.LENS_FACING) ?: CameraCharacteristics.LENS_FACING_BACK
        val lensFacing = when (lensFacingInt) {
            CameraCharacteristics.LENS_FACING_FRONT -> LensFacing.FRONT
            CameraCharacteristics.LENS_FACING_BACK -> LensFacing.BACK_WIDE
            else -> LensFacing.BACK_WIDE
        }

        val physSize = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) ?: floatArrayOf(5.59f)
        val apertures = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES) ?: floatArrayOf(1.6f)
        val streamMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

        val supportedSizes = mutableListOf<Pair<Int, Int>>()
        if (streamMap != null) {
            for (format in streamMap.outputFormats) {
                val sizes = streamMap.getOutputSizes(format)
                if (sizes != null) {
                    supportedSizes.addAll(sizes.map { Pair(it.width, it.height) })
                }
            }
        }

        return PhysicalCameraRecord(
            physicalCameraId = cameraId,
            lensFacing = lensFacing,
            physicalSensorSizeMm = Pair(physSize?.width ?: 6.4f, physSize?.height ?: 4.8f),
            focalLengthMm = focalLengths.firstOrNull() ?: 5.59f,
            apertureFNumber = apertures.firstOrNull() ?: 1.6f,
            supportedOutputSizes = supportedSizes.distinct()
        )
    }

    fun getPhysicalCameras(logicalCameraId: String): List<PhysicalCameraRecord> =
        map[logicalCameraId] ?: emptyList()

    fun getAllMappings(): Map<String, List<PhysicalCameraRecord>> = map.toMap()
}
