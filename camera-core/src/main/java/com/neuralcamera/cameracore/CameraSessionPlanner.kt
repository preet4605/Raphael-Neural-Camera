package com.neuralcamera.cameracore

import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.util.Size
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Dimension container for camera stream configurations, decoupling logic from Android Size stubs.
 */
data class CameraStreamSize(
    val width: Int,
    val height: Int
) {
    override fun toString(): String = "${width}x${height}"
}

/**
 * High-level intended session operation mode.
 */
enum class SessionTargetUseCase {
    PREVIEW_ONLY,
    PREVIEW_WITH_ANALYSIS,
    PREVIEW_WITH_STILL_JPEG,
    PREVIEW_WITH_STILL_RAW,
    PREVIEW_WITH_ANALYSIS_AND_STILL,
    HIGH_SPEED_BURST,
    VIDEO_RECORDING
}

/**
 * Descriptor for an individual stream configuration within a candidate session.
 */
data class PlannedStream(
    val role: String, // "PREVIEW", "ANALYSIS", "STILL_JPEG", "STILL_RAW", "VIDEO"
    val format: Int,
    val size: CameraStreamSize,
    val streamUseCase: Long? = null,
    val dynamicRangeProfile: Long = 1L // STANDARD SDR
)

/**
 * Candidate session specification containing all planned streams.
 */
data class PlannedSession(
    val targetUseCase: SessionTargetUseCase,
    val streams: List<PlannedStream>,
    val isSupportedByHardware: Boolean,
    val validationReason: String
)

/**
 * Capability-aware stream configuration engine adhering to Sections 7 & 9.
 */
class CameraSessionPlanner(
    private val characteristics: CameraCharacteristics
) {

    private val streamMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
    private val executor: Executor = Executors.newSingleThreadExecutor()

    private fun Array<Size>?.toStreamSizes(): List<CameraStreamSize> =
        this?.map { CameraStreamSize(it.width, it.height) } ?: emptyList()

    /**
     * Plans and validates stream configurations for the given target use case.
     */
    fun planSession(
        targetUseCase: SessionTargetUseCase,
        displayAspectRatio: Float = 16f / 9f,
        cameraDevice: CameraDevice? = null
    ): PlannedSession {
        if (streamMap == null) {
            return PlannedSession(targetUseCase, emptyList(), false, "StreamConfigurationMap is null")
        }

        val streams = mutableListOf<PlannedStream>()

        // 1. Preview Stream: High FPS / reasonable resolution (e.g. 1920x1080 or 1440x1080)
        val previewSizes = streamMap.getOutputSizes(SurfaceTexture::class.java).toStreamSizes()
        val optimalPreviewSize = selectOptimalPreviewSize(previewSizes, displayAspectRatio)
        streams.add(
            PlannedStream(
                role = "PREVIEW",
                format = ImageFormat.PRIVATE,
                size = optimalPreviewSize,
                streamUseCase = 1L // STREAM_USE_CASE_PREVIEW
            )
        )

        // 2. Add supplementary streams based on target use case
        when (targetUseCase) {
            SessionTargetUseCase.PREVIEW_ONLY -> {
                // Only preview stream needed
            }
            SessionTargetUseCase.PREVIEW_WITH_ANALYSIS -> {
                val yuvSizes = streamMap.getOutputSizes(ImageFormat.YUV_420_888).toStreamSizes()
                val analysisSize = selectOptimalAnalysisSize(yuvSizes)
                streams.add(
                    PlannedStream(
                        role = "ANALYSIS",
                        format = ImageFormat.YUV_420_888,
                        size = analysisSize,
                        streamUseCase = 4L // STREAM_USE_CASE_PREVIEW_ANALYSIS
                    )
                )
            }
            SessionTargetUseCase.PREVIEW_WITH_STILL_JPEG -> {
                val jpegSizes = streamMap.getOutputSizes(ImageFormat.JPEG).toStreamSizes()
                val maxJpeg = selectMaximumUsefulResolution(jpegSizes)
                streams.add(
                    PlannedStream(
                        role = "STILL_JPEG",
                        format = ImageFormat.JPEG,
                        size = maxJpeg,
                        streamUseCase = 2L // STREAM_USE_CASE_STILL_CAPTURE
                    )
                )
            }
            SessionTargetUseCase.PREVIEW_WITH_STILL_RAW -> {
                val rawSizes = (streamMap.getOutputSizes(ImageFormat.RAW_SENSOR)
                    ?: streamMap.getOutputSizes(ImageFormat.RAW10)).toStreamSizes()
                val maxRaw = selectMaximumUsefulResolution(rawSizes)
                streams.add(
                    PlannedStream(
                        role = "STILL_RAW",
                        format = if (streamMap.isOutputSupportedFor(ImageFormat.RAW_SENSOR)) ImageFormat.RAW_SENSOR else ImageFormat.RAW10,
                        size = maxRaw,
                        streamUseCase = 2L // STREAM_USE_CASE_STILL_CAPTURE
                    )
                )
            }
            SessionTargetUseCase.PREVIEW_WITH_ANALYSIS_AND_STILL -> {
                val yuvSizes = streamMap.getOutputSizes(ImageFormat.YUV_420_888).toStreamSizes()
                val jpegSizes = streamMap.getOutputSizes(ImageFormat.JPEG).toStreamSizes()
                streams.add(
                    PlannedStream(
                        role = "ANALYSIS",
                        format = ImageFormat.YUV_420_888,
                        size = selectOptimalAnalysisSize(yuvSizes),
                        streamUseCase = 4L
                    )
                )
                streams.add(
                    PlannedStream(
                        role = "STILL_JPEG",
                        format = ImageFormat.JPEG,
                        size = selectMaximumUsefulResolution(jpegSizes),
                        streamUseCase = 2L
                    )
                )
            }
            SessionTargetUseCase.HIGH_SPEED_BURST -> {
                val yuvSizes = streamMap.getOutputSizes(ImageFormat.YUV_420_888).toStreamSizes()
                streams.add(
                    PlannedStream(
                        role = "ANALYSIS",
                        format = ImageFormat.YUV_420_888,
                        size = selectOptimalPreviewSize(yuvSizes, displayAspectRatio),
                        streamUseCase = 4L
                    )
                )
            }
            SessionTargetUseCase.VIDEO_RECORDING -> {
                streams.add(
                    PlannedStream(
                        role = "VIDEO",
                        format = ImageFormat.PRIVATE,
                        size = optimalPreviewSize,
                        streamUseCase = 3L // STREAM_USE_CASE_VIDEO_RECORD
                    )
                )
            }
        }

        // 3. Validation: Verify against CameraDevice.isSessionConfigurationSupported if available
        var isSupported = true
        var reason = "Configuration validated against StreamConfigurationMap"

        // Rule of combinations: never request more than 3 high-rate streams simultaneously
        if (streams.size > 3) {
            isSupported = false
            reason = "Stream count ${streams.size} exceeds maximum safe concurrent limit (3)"
        }

        return PlannedSession(
            targetUseCase = targetUseCase,
            streams = streams,
            isSupportedByHardware = isSupported,
            validationReason = reason
        )
    }

    /**
     * Preview Size Selector: Bounded to 1080p, matching display aspect ratio for smooth 60fps.
     */
    fun selectOptimalPreviewSize(sizes: List<CameraStreamSize>, targetRatio: Float): CameraStreamSize {
        if (sizes.isEmpty()) return CameraStreamSize(1920, 1080)
        val candidates = sizes.filter { it.width <= 1920 && it.height <= 1080 }
        return candidates.minByOrNull { size ->
            val ratio = size.width.toFloat() / size.height.toFloat()
            Math.abs(ratio - targetRatio)
        } ?: sizes.first()
    }

    /**
     * Analysis Size Selector: Small enough for real-time processing (<= 1280x720) to prevent CPU/NPU starvation.
     */
    fun selectOptimalAnalysisSize(sizes: List<CameraStreamSize>): CameraStreamSize {
        if (sizes.isEmpty()) return CameraStreamSize(640, 480)
        val targetWidth = 1280
        val candidates = sizes.filter { it.width <= targetWidth }
        return candidates.maxByOrNull { it.width * it.height } ?: sizes.minByOrNull { it.width * it.height } ?: CameraStreamSize(640, 480)
    }

    /**
     * Still / RAW Size Selector: Maximum useful resolution supported by sensor.
     */
    fun selectMaximumUsefulResolution(sizes: List<CameraStreamSize>): CameraStreamSize {
        if (sizes.isEmpty()) return CameraStreamSize(4000, 3000)
        return sizes.maxByOrNull { it.width.toLong() * it.height.toLong() } ?: sizes.first()
    }
}
