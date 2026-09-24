package com.neuralcamera.isp

import com.neuralcamera.benchmarks.BenchmarkRunner
import com.neuralcamera.benchmarks.StandardBenchmarkRunner
import com.neuralcamera.cameracore.CameraFrame
import com.neuralcamera.quality.ConfidenceEstimator
import com.neuralcamera.quality.GuardAction
import com.neuralcamera.quality.QualityEvaluator
import com.neuralcamera.quality.RealityGuard
import com.neuralcamera.quality.StandardConfidenceEstimator
import com.neuralcamera.quality.StandardQualityEvaluator
import com.neuralcamera.quality.StandardRealityGuard

class BaselineImagePipeline(
    private val qualityEvaluator: QualityEvaluator = StandardQualityEvaluator(),
    private val confidenceEstimator: ConfidenceEstimator = StandardConfidenceEstimator(),
    private val realityGuard: RealityGuard = StandardRealityGuard(),
    private val benchmarkRunner: BenchmarkRunner = StandardBenchmarkRunner()
) : ImagePipeline {

    override suspend fun processFrames(
        frames: List<CameraFrame>,
        targetWidth: Int,
        targetHeight: Int,
        requestNeuralAcceleration: Boolean
    ): ProcessedImageResult {
        require(frames.isNotEmpty()) { "Cannot process empty frames list" }

        val (result, metrics) = benchmarkRunner.benchmarkStage("classical_baseline_isp") {
            val refFrame = frames.last()
            val width = refFrame.width
            val height = refFrame.height
            val pixelCount = width * height

            val originalPlane = refFrame.planes.firstOrNull()?.buffer
                ?: ByteArray(pixelCount) { 128.toByte() }

            // 1. Temporal multi-frame noise reduction
            val accumulatedLuma = IntArray(pixelCount)
            for (f in frames) {
                val plane = f.planes.firstOrNull()?.buffer ?: originalPlane
                for (i in 0 until minOf(pixelCount, plane.size)) {
                    accumulatedLuma[i] += (plane[i].toInt() and 0xFF)
                }
            }
            val frameCount = frames.size
            val reconstructedLuma = ByteArray(pixelCount)
            for (i in 0 until pixelCount) {
                reconstructedLuma[i] = (accumulatedLuma[i] / frameCount).coerceIn(0, 255).toByte()
            }

            // 2. Confidence Estimation
            val confMap = confidenceEstimator.estimateConfidence(
                width = width,
                height = height,
                lumaPlane = originalPlane,
                iso = refFrame.metadata.iso,
                exposureTimeNs = refFrame.metadata.exposureTimeNs
            )

            // 3. Reality Guard Inspection
            val guardDecision = realityGuard.inspectAndProtect(
                originalLuma = originalPlane,
                reconstructedLuma = reconstructedLuma,
                confidenceMap = confMap,
                width = width,
                height = height
            )

            // 4. Tone Mapping & Master Plane Construction (RGB)
            val finalLuma = when (guardDecision.action) {
                GuardAction.REVERT_TO_ORIGINAL -> originalPlane
                GuardAction.BLEND_WITH_ORIGINAL -> {
                    val ratio = guardDecision.blendRatio
                    val blended = ByteArray(pixelCount)
                    for (i in 0 until pixelCount) {
                        val orig = originalPlane[i].toInt() and 0xFF
                        val recon = reconstructedLuma[i].toInt() and 0xFF
                        blended[i] = ((recon * ratio) + (orig * (1f - ratio))).toInt().coerceIn(0, 255).toByte()
                    }
                    blended
                }
                GuardAction.KEEP_RECONSTRUCTED, GuardAction.DISCARD_NEURAL_STAGE -> reconstructedLuma
            }

            // Convert to RGB master buffer
            val masterRgb = ByteArray(pixelCount * 3)
            for (i in 0 until pixelCount) {
                val luma = finalLuma[i].toInt() and 0xFF
                // S-curve subtle contrast enhancement (classical photographic tone curve)
                val normalized = luma / 255.0
                val curved = (normalized * normalized * (3 - 2 * normalized) * 255.0).toInt().coerceIn(0, 255).toByte()

                val rgbIdx = i * 3
                masterRgb[rgbIdx] = curved     // R
                masterRgb[rgbIdx + 1] = curved // G
                masterRgb[rgbIdx + 2] = curved // B
            }

            // 5. Quality Evaluation
            val qualityScore = qualityEvaluator.evaluateQuality(width, height, finalLuma)

            ProcessedImageResult(
                outputWidth = width,
                outputHeight = height,
                masterRgbPlane = masterRgb,
                originalLumaPlane = originalPlane,
                confidenceMap = confMap,
                realityGuardDecision = guardDecision,
                qualityScore = qualityScore,
                metrics = com.neuralcamera.benchmarks.ExecutionMetrics(
                    stageName = "classical_baseline_isp",
                    latencyMs = 0L,
                    memoryAllocatedBytes = 0L,
                    thermalStateBefore = 0,
                    thermalStateAfter = 0,
                    isSuccess = true
                ),
                isNeuralAccelerated = false,
                appliedPipelineName = "Classical Production ISP Baseline (Multi-frame Temporal Fusion)"
            )
        }

        return result.copy(metrics = metrics)
    }
}
