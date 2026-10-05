package com.neuralcamera.cameracore

import com.neuralcamera.cameracore.buffers.FrameBufferHandle
import com.neuralcamera.deviceprofiles.LensFacing

data class FramePlane(
    val buffer: ByteArray,
    val pixelStride: Int,
    val rowStride: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FramePlane) return false
        return buffer.contentEquals(other.buffer) && pixelStride == other.pixelStride && rowStride == other.rowStride
    }

    override fun hashCode(): Int {
        var result = buffer.contentHashCode()
        result = 31 * result + pixelStride
        result = 31 * result + rowStride
        return result
    }
}

data class SensorSampleAssociation(
    val timestampNs: Long,
    val gyroRadS: FloatArray? = null,
    val accelMps2: FloatArray? = null,
    val isInterpolated: Boolean = false
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SensorSampleAssociation) return false
        return timestampNs == other.timestampNs &&
                gyroRadS.contentEquals(other.gyroRadS) &&
                accelMps2.contentEquals(other.accelMps2) &&
                isInterpolated == other.isInterpolated
    }

    override fun hashCode(): Int {
        var result = timestampNs.hashCode()
        result = 31 * result + (gyroRadS?.contentHashCode() ?: 0)
        result = 31 * result + (accelMps2?.contentHashCode() ?: 0)
        result = 31 * result + isInterpolated.hashCode()
        return result
    }
}

data class CropRegion(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int
)

data class QualityMetadata(
    val sceneLux: Float = 0f,
    val sharpnessScore: Float = 0f,
    val noiseFloorEstimate: Float = 0f
)

data class FrameMetadata(
    val frameSequence: Long,
    val timestampNs: Long,
    val exposureTimeNs: Long,
    val iso: Int,
    val focalLengthMm: Float,
    val focusDistanceMeters: Float,
    val apertureFNumber: Float,
    val lensFacing: LensFacing,
    val physicalCameraId: String,
    val sensorOrientation: Int,
    val gyroAnglesRad: FloatArray? = null,
    val dynamicRange: String = "SDR"
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FrameMetadata) return false
        return frameSequence == other.frameSequence && timestampNs == other.timestampNs && physicalCameraId == other.physicalCameraId
    }

    override fun hashCode(): Int {
        var result = frameSequence.hashCode()
        result = 31 * result + timestampNs.hashCode()
        result = 31 * result + physicalCameraId.hashCode()
        return result
    }
}

/**
 * Real CapturedFrame model specified by Section 15 of Neural Camera Phase 1.
 * Supports the temporal pipeline and associates raw image buffers with comprehensive
 * Camera2 metadata and synchronized IMU sensor telemetry without duplicating payload bytes.
 */
data class CapturedFrame(
    val frameId: String,
    val cameraId: String,
    val physicalCameraId: String,
    val timestamp: Long,
    val frameNumber: Long,
    val sequenceId: Int,
    val width: Int,
    val height: Int,
    val format: String,
    val exposureTime: Long,
    val sensitivity: Int,
    val frameDuration: Long,
    val focusState: String,
    val focusDistance: Float,
    val aeState: String,
    val awbState: String,
    val lensPosition: Float,
    val focalLength: Float,
    val sensorOrientation: Int,
    val cropRegion: CropRegion,
    val dynamicRangeProfile: String,
    val stabilizationState: String,
    val bufferHandle: FrameBufferHandle? = null,
    val sensorAssociations: SensorSampleAssociation? = null,
    val qualityMetadata: QualityMetadata? = null,
    val planes: List<FramePlane> = emptyList()
) {
    val sizeBytes: Long
        get() = bufferHandle?.sizeBytes ?: planes.sumOf { it.buffer.size.toLong() }
}

/**
 * Main frame container maintained for backward compatibility across modules.
 */
data class CameraFrame(
    val frameId: String,
    val format: String, // "RAW_SENSOR", "YUV_420_888", "JPEG", "HEIC"
    val width: Int,
    val height: Int,
    val planes: List<FramePlane>,
    val metadata: FrameMetadata,
    val bufferHandle: FrameBufferHandle? = null,
    val capturedFrame: CapturedFrame? = null
) {
    val sizeBytes: Long get() = bufferHandle?.sizeBytes ?: planes.sumOf { it.buffer.size.toLong() }
}

enum class CameraOperationalState {
    UNINITIALIZED,
    OPENING,
    PREVIEW_STREAMING,
    CAPTURING_BURST,
    CLOSING,
    ERROR
}
