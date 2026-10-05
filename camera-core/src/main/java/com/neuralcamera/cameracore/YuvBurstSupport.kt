package com.neuralcamera.cameracore

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.CaptureResult
import android.media.Image
import android.util.Size
import com.neuralcamera.deviceprofiles.LensFacing

/**
 * A YUV_420_888 frame copied out of an android.media.Image. The Image is closed straight after the copy so the camera
 * HAL gets its buffer back immediately; holding Images for a whole burst can stall the pipeline.
 * This is a real copy (about 1.5 bytes per pixel), not a zero-copy path.
 */
class CopiedYuv(val width: Int, val height: Int, val planes: List<FramePlane>) : AutoCloseable {
    override fun close() = Unit

    companion object {
        /** Copies all planes; the caller closes [image]. Plane buffers keep the camera's own row and pixel strides. */
        fun copyOf(image: Image): CopiedYuv {
            require(image.format == ImageFormat.YUV_420_888) { "expected YUV_420_888, got format ${image.format}" }
            val planes = image.planes.map { p ->
                val src = p.buffer.duplicate()
                val bytes = ByteArray(src.remaining())
                src.get(bytes)
                FramePlane(bytes, p.pixelStride, p.rowStride)
            }
            return CopiedYuv(image.width, image.height, planes)
        }
    }
}

object YuvBurstSupport {
    /** Largest YUV_420_888 output of at most [maxPixels] (4:3 preferred on ties); null when the device lists none. */
    fun chooseSize(chars: CameraCharacteristics, maxPixels: Long): Size? {
        val sizes = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(ImageFormat.YUV_420_888)
            ?: return null
        return sizes.filter { it.width.toLong() * it.height <= maxPixels }
            .maxWithOrNull(compareBy<Size>({ it.width.toLong() * it.height }, { kotlin.math.abs(it.width * 3 - it.height * 4) == 0 }))
    }

    /** Number of frames that fit a copy budget, at least 1. */
    fun framesWithinBudget(requested: Int, size: Size, budgetBytes: Long): Int {
        val perFrame = size.width.toLong() * size.height * 3 / 2
        return requested.coerceAtMost((budgetBytes / perFrame).toInt()).coerceAtLeast(1)
    }

    fun lensFacing(chars: CameraCharacteristics): LensFacing =
        if (chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT) LensFacing.FRONT else LensFacing.BACK_WIDE

    /** Metadata strictly from the TotalCaptureResult; absent values are reported as 0, never invented. */
    fun metadataOf(result: TotalCaptureResult, chars: CameraCharacteristics, cameraId: String, sensorTimestamp: Long): FrameMetadata =
        FrameMetadata(
            frameSequence = result.frameNumber,
            timestampNs = sensorTimestamp,
            exposureTimeNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L,
            iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0,
            focalLengthMm = result.get(CaptureResult.LENS_FOCAL_LENGTH) ?: 0f,
            focusDistanceMeters = result.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let { if (it > 0f) 1f / it else 0f } ?: 0f,
            apertureFNumber = result.get(CaptureResult.LENS_APERTURE) ?: 0f,
            lensFacing = lensFacing(chars),
            physicalCameraId = result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID) ?: cameraId,
            sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        )
}
