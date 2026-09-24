package com.neuralcamera.cameracore

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

data class CameraFrame(
    val frameId: String,
    val format: String, // "RAW_SENSOR", "YUV_420_888", "JPEG"
    val width: Int,
    val height: Int,
    val planes: List<FramePlane>,
    val metadata: FrameMetadata
) {
    val sizeBytes: Long get() = planes.sumOf { it.buffer.size.toLong() }
}

enum class CameraOperationalState {
    UNINITIALIZED,
    OPENING,
    PREVIEW_STREAMING,
    CAPTURING_BURST,
    CLOSING,
    ERROR
}
