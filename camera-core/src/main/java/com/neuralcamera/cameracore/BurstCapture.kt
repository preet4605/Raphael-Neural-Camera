package com.neuralcamera.cameracore

import com.neuralcamera.cameracore.orchestration.OrchestrationResult
import com.neuralcamera.cameracore.orchestration.OrchestrationState
import com.neuralcamera.cameracore.threea.ConvergenceVerdict
import com.neuralcamera.cameracore.threea.ExposureSetting
import com.neuralcamera.cameracore.threea.ManualFrameCheck
import com.neuralcamera.cameracore.threea.ManualOutcome

/**
 * One orchestrated burst: the frames actually captured plus how the capture went. Downstream processing and saved
 * metadata must carry [result] state, [convergence] and [lockedFrames] so a partial or unconverged capture is never
 * presented as a clean one.
 */
data class BurstCapture(
    val frames: List<CameraFrame>,
    val result: OrchestrationResult,
    /** Final 3A verdict before the burst; null when convergence was never checked (e.g. the camera was not ready). */
    val convergence: ConvergenceVerdict?,
    /** Frames of the delivered attempt whose result reported AE LOCKED; null when no burst ran. */
    val lockedFrames: Int?,
    /** Whether AE/AWB lock was requested (false when the camera reports neither lock as available). */
    val lockRequested: Boolean,
    /** Measured camera-buffer -> heap copies of this burst (every attempt); null when nothing was captured. */
    val copy: BufferCopyRecord? = null,
    /** Manual exposure requested for the burst frames (AE off); null = auto exposure. */
    val manual: ExposureSetting? = null,
    /** Per delivered frame: whether its result shows the manual request applied. Empty for auto exposure. */
    val manualChecks: List<ManualFrameCheck> = emptyList()
) {
    val usable: Boolean get() = frames.isNotEmpty() && result.state in setOf(OrchestrationState.COMPLETE, OrchestrationState.PARTIAL)

    /** Short human-readable caveats (empty for a complete, converged, locked burst). */
    fun caveats(): List<String> = buildList {
        if (result.state == OrchestrationState.PARTIAL) add("partial burst: ${frames.size} of ${result.requested} frames")
        if (result.capturedWithoutConvergence) add("3A not converged (${convergence ?: "unknown"})")
        if (lockRequested && lockedFrames != null && lockedFrames < frames.size) add("AE lock confirmed on $lockedFrames of ${frames.size} frames")
        if (result.attempts > 1) add("${result.attempts} attempts")
        if (manual != null && manualChecks.any { it.outcome != ManualOutcome.APPLIED }) add(ManualFrameCheck.summary(manual, manualChecks))
    }

    companion object {
        fun failed(requested: Int, reason: String) = BurstCapture(
            frames = emptyList(),
            result = OrchestrationResult(OrchestrationState.FAILED, emptyList(), requested, 0, 0, false, reason),
            convergence = null, lockedFrames = null, lockRequested = false
        )
    }
}
