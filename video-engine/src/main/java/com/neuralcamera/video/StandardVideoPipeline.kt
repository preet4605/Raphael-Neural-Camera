package com.neuralcamera.video

import com.neuralcamera.cameracore.CameraFrame
import kotlin.system.measureTimeMillis

class StandardVideoPipeline : VideoPipeline {

    private var currentConfig: VideoRecordingConfig? = null
    private var isRecording = false
    private var frameCounter = 0L

    override suspend fun prepare(config: VideoRecordingConfig): Boolean {
        currentConfig = config
        isRecording = true
        frameCounter = 0L
        return true
    }

    override suspend fun processFrame(frame: CameraFrame): VideoFrameProcessingResult {
        if (!isRecording) {
            return VideoFrameProcessingResult(frame.metadata.frameSequence, 0L, false, true)
        }

        frameCounter++
        // Rule 24: heavyweight enhancement may only run on keyframes, never on every frame. No enhancement stage exists
        // yet, so every frame passes through unprocessed and nothing is reported as enhanced.
        val durationMs = measureTimeMillis { }

        return VideoFrameProcessingResult(
            frameSequence = frame.metadata.frameSequence,
            processingLatencyMs = durationMs,
            neuralEnhanced = false,
            dropped = false,
            isEnhancementKeyframe = isEnhancementKeyframe(frameCounter)
        )
    }

    /** Frames on which an enhancement stage would be allowed to run (every 15th), once one exists. */
    fun isEnhancementKeyframe(index: Long): Boolean = index % 15 == 0L

    override suspend fun finalizeRecording(): Boolean {
        isRecording = false
        return true
    }
}
