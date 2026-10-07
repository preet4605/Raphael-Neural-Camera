package com.neuralcamera.isp

import com.neuralcamera.isp.temporal.AlignmentProxy
import com.neuralcamera.isp.temporal.Frame
import com.neuralcamera.isp.temporal.FrameAnalysis
import com.neuralcamera.isp.temporal.Radiometry
import com.neuralcamera.isp.temporal.U16Plane
import com.neuralcamera.quality.frame.FrameContext
import com.neuralcamera.quality.frame.FrameMetrics
import com.neuralcamera.quality.frame.FrameQualityEngine
import com.neuralcamera.quality.frame.FrameScore

/** The merge reference and how it was chosen. */
data class ReferenceChoice(
    val index: Int,
    val ranked: List<FrameScore>,
    /** True when every frame failed a hard quality check and the sharpest frame was used anyway; record it as degraded. */
    val allFramesRejected: Boolean
) {
    val note: String
        get() = if (allFramesRejected) "reference: sharpest frame, every frame failed quality checks (${ranked.first().notes.joinToString()})"
        else "reference: frame $index by quality rank"
}

/**
 * Picks the temporal-merge reference with [FrameQualityEngine.rank] instead of sharpness alone, so a frame with clipped
 * highlights or a near-black exposure is never the reference while a usable one exists. Inputs are 8-bit luma in
 * U16 planes. Per-frame AF state, gyro blur and alignment weight are not available on this path yet, so they are
 * passed as unknown (the engine notes them; they never count as good).
 */
object ReferenceSelector {
    fun select(lumaPlanes: List<U16Plane>, contexts: List<FrameContext>? = null): ReferenceChoice {
        require(lumaPlanes.isNotEmpty()) { "no frames" }
        val metrics = lumaPlanes.map(::metricsOf)
        val ctx = contexts ?: List(lumaPlanes.size) { FrameContext(focusConverged = null, gyroBlurPx = null, alignmentWeight = null) }
        val ranked = FrameQualityEngine.rank(metrics, ctx)
        val best = FrameQualityEngine.bestFrame(ranked)
        return if (best != null) ReferenceChoice(best, ranked, allFramesRejected = false)
        else ReferenceChoice(FrameAnalysis.sharpestIndex(lumaPlanes), ranked, allFramesRejected = true)
    }

    /** Same sharpness measure the merge used before (noise-robust, downsampled Laplacian); exposure stats at full res. */
    private fun metricsOf(plane: U16Plane): FrameMetrics {
        var sum = 0L; var hi = 0; var lo = 0
        for (s in plane.data) { val v = s.toInt() and 0xFFFF; sum += v; if (v >= 250) hi++; if (v <= 5) lo++ }
        val n = plane.data.size.toDouble()
        val sigmaDn = FrameAnalysis.noiseSigma(AlignmentProxy.of(Frame(plane, EIGHT_BIT))) * 255.0
        return FrameMetrics(FrameAnalysis.laplacianVariance(plane), sigmaDn, sum / n, hi / n, lo / n)
    }

    private val EIGHT_BIT = Radiometry(blackLevel = 0.0, whiteLevel = 255.0)
}
