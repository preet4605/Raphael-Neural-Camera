package com.neuralcamera.quality

import kotlin.math.abs
import kotlin.math.sqrt

class StandardQualityEvaluator : QualityEvaluator {

    override fun evaluateQuality(width: Int, height: Int, lumaPlane: ByteArray): ImageQualityScore {
        if (lumaPlane.isEmpty() || width <= 0 || height <= 0) {
            return ImageQualityScore(0f, 0f, 0f, 0f, 1f, false)
        }

        var sum = 0.0
        var clippedCount = 0
        val sampleStep = 4
        var sampledPixels = 0

        // Subsample for fast evaluation
        for (i in 0 until (width * height) step sampleStep) {
            val v = lumaPlane[i].toInt() and 0xFF
            sum += v
            if (v <= 2 || v >= 253) clippedCount++
            sampledPixels++
        }

        val mean = if (sampledPixels > 0) sum / sampledPixels else 128.0

        // Compute variance (contrast/noise estimation)
        var varSum = 0.0
        for (i in 0 until (width * height) step sampleStep) {
            val v = lumaPlane[i].toInt() and 0xFF
            val diff = v - mean
            varSum += diff * diff
        }
        val variance = if (sampledPixels > 0) varSum / sampledPixels else 0.0
        val stdDev = sqrt(variance)

        val noiseScore = (1.0 - (stdDev / 128.0)).toFloat().coerceIn(0.1f, 1.0f)
        val dynamicRangeScore = (1.0 - (clippedCount.toDouble() / sampledPixels.coerceAtLeast(1))).toFloat().coerceIn(0f, 1f)
        val sharpnessScore = (stdDev / 64.0).toFloat().coerceIn(0.1f, 1.0f)
        val artifactRisk = if (clippedCount > sampledPixels * 0.5) 0.8f else 0.1f

        val overall = (noiseScore * 0.3f + dynamicRangeScore * 0.3f + sharpnessScore * 0.4f).coerceIn(0f, 1f)

        return ImageQualityScore(
            overallScore = overall,
            noiseScore = noiseScore,
            sharpnessScore = sharpnessScore,
            dynamicRangeScore = dynamicRangeScore,
            artifactRiskScore = artifactRisk,
            isAcceptable = overall >= 0.35f
        )
    }
}

class StandardConfidenceEstimator : ConfidenceEstimator {

    override fun estimateConfidence(
        width: Int,
        height: Int,
        lumaPlane: ByteArray,
        iso: Int,
        exposureTimeNs: Long
    ): ConfidenceMap {
        val tileSize = 32
        val tilesX = (width + tileSize - 1) / tileSize
        val tilesY = (height + tileSize - 1) / tileSize
        val confidenceArray = FloatArray(tilesX * tilesY)

        val baseNoiseFactor = (iso / 100f).coerceAtLeast(1.0f)
        val noisePenalty = (baseNoiseFactor / 64f).coerceIn(0.0f, 0.5f)

        for (ty in 0 until tilesY) {
            for (tx in 0 until tilesX) {
                var sumVal = 0
                var pixelCount = 0
                val startX = tx * tileSize
                val startY = ty * tileSize

                for (y in startY until minOf(startY + tileSize, height)) {
                    for (x in startX until minOf(startX + tileSize, width)) {
                        val idx = y * width + x
                        sumVal += (lumaPlane[idx].toInt() and 0xFF)
                        pixelCount++
                    }
                }

                val avg = if (pixelCount > 0) sumVal / pixelCount else 128
                // Overexposed (>245) or underexposed (<12) regions have low confidence
                val exposureConfidence = when {
                    avg > 245 -> 0.2f
                    avg < 12 -> 0.3f
                    avg in 40..215 -> 0.95f
                    else -> 0.70f
                }

                val finalConfidence = (exposureConfidence - noisePenalty).coerceIn(0.05f, 1.0f)
                confidenceArray[ty * tilesX + tx] = finalConfidence
            }
        }

        return ConfidenceMap(tilesX, tilesY, confidenceArray)
    }
}

class StandardRealityGuard : RealityGuard {

    override fun inspectAndProtect(
        originalLuma: ByteArray,
        reconstructedLuma: ByteArray,
        confidenceMap: ConfidenceMap,
        width: Int,
        height: Int
    ): RealityGuardDecision {
        if (originalLuma.size != reconstructedLuma.size) {
            return RealityGuardDecision(
                action = GuardAction.REVERT_TO_ORIGINAL,
                blendRatio = 0f,
                reason = "Buffer dimension mismatch between original and reconstructed",
                hallucinationDetected = false,
                confidenceMean = 0f
            )
        }

        var totalConfidence = 0f
        for (c in confidenceMap.tileConfidence) {
            totalConfidence += c
        }
        val meanConfidence = if (confidenceMap.tileConfidence.isNotEmpty()) {
            totalConfidence / confidenceMap.tileConfidence.size
        } else {
            0.5f
        }

        // Compare high-confidence original sensor pixels with reconstructed pixels
        var highConfidencePixelCount = 0
        var largeDiscrepancyCount = 0
        val sampleStep = 8

        val tileSize = 32
        val tilesX = confidenceMap.width

        for (y in 0 until height step sampleStep) {
            val ty = (y / tileSize).coerceAtMost(confidenceMap.height - 1)
            for (x in 0 until width step sampleStep) {
                val tx = (x / tileSize).coerceAtMost(tilesX - 1)
                val conf = confidenceMap.tileConfidence[ty * tilesX + tx]

                if (conf >= 0.75f) {
                    highConfidencePixelCount++
                    val idx = y * width + x
                    val origVal = originalLuma[idx].toInt() and 0xFF
                    val reconVal = reconstructedLuma[idx].toInt() and 0xFF

                    // Deviation greater than 45 intensity levels on high-confidence sensor data indicates hallucination
                    if (abs(origVal - reconVal) > 45) {
                        largeDiscrepancyCount++
                    }
                }
            }
        }

        val discrepancyRatio = if (highConfidencePixelCount > 0) {
            largeDiscrepancyCount.toFloat() / highConfidencePixelCount
        } else {
            0f
        }

        return when {
            discrepancyRatio > 0.20f -> RealityGuardDecision(
                action = GuardAction.REVERT_TO_ORIGINAL,
                blendRatio = 0.0f,
                reason = "Hallucination risk: ${(discrepancyRatio * 100).toInt()}% deviation in high-confidence sensor regions",
                hallucinationDetected = true,
                confidenceMean = meanConfidence
            )
            discrepancyRatio > 0.05f -> RealityGuardDecision(
                action = GuardAction.BLEND_WITH_ORIGINAL,
                blendRatio = 0.65f, // Blend to protect ground truth details
                reason = "Moderate divergence: blending ground truth to protect sensor details",
                hallucinationDetected = false,
                confidenceMean = meanConfidence
            )
            else -> RealityGuardDecision(
                action = GuardAction.KEEP_RECONSTRUCTED,
                blendRatio = 1.0f,
                reason = "Reconstruction valid and preserves ground truth sensor data",
                hallucinationDetected = false,
                confidenceMean = meanConfidence
            )
        }
    }
}
