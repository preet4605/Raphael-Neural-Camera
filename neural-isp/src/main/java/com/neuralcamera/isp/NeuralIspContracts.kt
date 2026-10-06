package com.neuralcamera.isp

import com.neuralcamera.benchmarks.ExecutionMetrics
import com.neuralcamera.cameracore.CameraFrame
import com.neuralcamera.isp.temporal.FrameMergeStats
import com.neuralcamera.quality.ConfidenceMap
import com.neuralcamera.quality.ImageQualityScore
import com.neuralcamera.quality.RealityGuardDecision

data class ProcessedImageResult(
    val outputWidth: Int,
    val outputHeight: Int,
    val masterRgbPlane: ByteArray, // RGB 24-bit or RGBA 32-bit
    val originalLumaPlane: ByteArray,
    val confidenceMap: ConfidenceMap,
    val realityGuardDecision: RealityGuardDecision,
    val qualityScore: ImageQualityScore,
    val metrics: ExecutionMetrics,
    val isNeuralAccelerated: Boolean,
    val appliedPipelineName: String,
    /** True when [masterRgbPlane] carries real colour (from the frame's chroma planes); false means R=G=B gray. */
    val isColour: Boolean = false,
    /** Per alternate frame: how much of it the temporal merge used (input for the quality engine). */
    val temporalStats: List<FrameMergeStats> = emptyList()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ProcessedImageResult) return false
        return outputWidth == other.outputWidth &&
                outputHeight == other.outputHeight &&
                masterRgbPlane.contentEquals(other.masterRgbPlane) &&
                appliedPipelineName == other.appliedPipelineName
    }

    override fun hashCode(): Int {
        var result = outputWidth
        result = 31 * result + outputHeight
        result = 31 * result + masterRgbPlane.contentHashCode()
        result = 31 * result + appliedPipelineName.hashCode()
        return result
    }
}

interface ImagePipeline {
    suspend fun processFrames(
        frames: List<CameraFrame>,
        targetWidth: Int,
        targetHeight: Int,
        requestNeuralAcceleration: Boolean
    ): ProcessedImageResult
}
