package com.neuralcamera.video

import com.neuralcamera.cameracore.CameraFrame

data class VideoRecordingConfig(
    val width: Int = 3840,
    val height: Int = 2160,
    val targetFps: Int = 30,
    val bitRateBps: Int = 50_000_000, // 50 Mbps
    val is10BitHdr: Boolean = false,
    val enableGyroStabilization: Boolean = true
)

data class VideoFrameProcessingResult(
    val frameSequence: Long,
    val processingLatencyMs: Long,
    /** True only when an enhancement stage actually processed this frame. None exists yet, so always false. */
    val neuralEnhanced: Boolean,
    val dropped: Boolean,
    /** Whether the keyframe budget (Rule 24) would allow enhancement on this frame. Not evidence that any ran. */
    val isEnhancementKeyframe: Boolean = false
)

interface VideoPipeline {
    suspend fun prepare(config: VideoRecordingConfig): Boolean
    suspend fun processFrame(frame: CameraFrame): VideoFrameProcessingResult
    suspend fun finalizeRecording(): Boolean
}
