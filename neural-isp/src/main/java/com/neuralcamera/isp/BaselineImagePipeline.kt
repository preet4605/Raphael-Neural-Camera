package com.neuralcamera.isp

import com.neuralcamera.benchmarks.BenchmarkRunner
import com.neuralcamera.benchmarks.StandardBenchmarkRunner
import com.neuralcamera.cameracore.CameraFrame
import com.neuralcamera.isp.temporal.FrameAnalysis
import com.neuralcamera.isp.temporal.Frame
import com.neuralcamera.isp.temporal.NoiseModel
import com.neuralcamera.isp.temporal.Radiometry
import com.neuralcamera.isp.temporal.TemporalMerge
import com.neuralcamera.isp.temporal.AlignmentProxy
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
            // Only 8-bit luma planes are handled here; RAW goes through BayerTemporalMerge.
            val lumaPlanes = frames.map { LumaExtractor.toU16(it) }
            val width = lumaPlanes[0].width
            val height = lumaPlanes[0].height
            val pixelCount = width * height
            require(lumaPlanes.all { it.width == width && it.height == height }) { "All frames must have the same size" }

            // 1. Temporal fusion: sharpest frame as reference, tile alignment, motion-robust noise-aware merge.
            val referenceIndex = FrameAnalysis.sharpestIndex(lumaPlanes)
            val refFrame = frames[referenceIndex]
            val radiometry = Radiometry(blackLevel = 0.0, whiteLevel = 255.0)
            val temporalFrames = lumaPlanes.map { Frame(it, radiometry) }
            // 8-bit display-referred luma: homoscedastic noise, estimated from the reference frame itself.
            val sigma = maxOf(FrameAnalysis.noiseSigma(AlignmentProxy.of(temporalFrames[referenceIndex])), MIN_SIGMA)
            val merge = TemporalMerge.run(temporalFrames, referenceIndex, NoiseModel(shot = 0.0, read = sigma * sigma))

            val originalPlane = ByteArray(pixelCount) { lumaPlanes[referenceIndex].data[it].toByte() }
            val reconstructedLuma = ByteArray(pixelCount) {
                Math.round(merge.output.data[it].coerceIn(0f, 1f) * 255f).toByte()
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
                temporalStats = merge.frameStats,
                appliedPipelineName = "Classical baseline ISP (tile-aligned, noise-aware, motion-robust temporal merge; luma only)"
            )
        }

        return result.copy(metrics = metrics)
    }

    private companion object {
        /** About half an 8-bit quantization step; keeps noise-free input from producing a zero tolerance. */
        const val MIN_SIGMA = 0.5 / 255.0
    }
}
