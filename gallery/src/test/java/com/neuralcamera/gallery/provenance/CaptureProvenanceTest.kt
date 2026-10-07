package com.neuralcamera.gallery.provenance

import com.neuralcamera.cameracore.BurstCapture
import com.neuralcamera.cameracore.CameraFrame
import com.neuralcamera.cameracore.FrameMetadata
import com.neuralcamera.cameracore.orchestration.FrameRef
import com.neuralcamera.cameracore.orchestration.OrchestrationResult
import com.neuralcamera.cameracore.orchestration.OrchestrationState
import com.neuralcamera.cameracore.threea.ConvergenceVerdict
import com.neuralcamera.deviceprofiles.LensFacing
import com.neuralcamera.models.execution.ContentOrigin
import com.neuralcamera.models.execution.StageStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureProvenanceTest {

    private fun burst(n: Int, requested: Int, state: OrchestrationState, unconverged: Boolean = false) = BurstCapture(
        frames = (0 until n).map {
            CameraFrame("f$it", "YUV_420_888", 4, 4, emptyList(),
                FrameMetadata(it.toLong(), it * 1000L, 10_000_000L, 100, 6f, 1f, 1.8f, LensFacing.BACK_WIDE, "0", 90))
        },
        result = OrchestrationResult(state, (0 until n).map { FrameRef(it, it * 1000L, it.toLong()) }, requested, 1, requested - n, unconverged, "r"),
        convergence = if (unconverged) ConvergenceVerdict.TIMED_OUT else ConvergenceVerdict.CONVERGED,
        lockedFrames = n,
        lockRequested = true
    )

    private val cleanMerge = MergeFacts(framesIn = 6, guardAction = "KEEP_RECONSTRUCTED", blendRatio = 1f, referenceFallback = false, colour = true)

    @Test
    fun cleanCaptureIsReconstructedAndNotDegraded() {
        val p = CaptureProvenance.forBurst("m1", burst(6, 6, OrchestrationState.COMPLETE), cleanMerge)
        assertFalse(p.degraded)
        assertEquals(ContentOrigin.RECONSTRUCTED, p.outputOrigin)
        assertEquals(listOf("capture", "temporal_merge", "colour"), p.stages.map { it.stage })
    }

    @Test
    fun revertedMergeIsCapturedOnlyAndDegraded() {
        val p = CaptureProvenance.forBurst("m2", burst(6, 6, OrchestrationState.COMPLETE), cleanMerge.copy(guardAction = "REVERT_TO_ORIGINAL"))
        assertTrue(p.degraded)
        assertEquals(ContentOrigin.CAPTURED, p.outputOrigin)
        assertEquals("unmerged reference frame", p.stages[1].implementation)
    }

    @Test
    fun partialUnconvergedCaptureIsRecordedAsDegradedWithCaveats() {
        val p = CaptureProvenance.forBurst("m3", burst(4, 6, OrchestrationState.PARTIAL, unconverged = true), cleanMerge.copy(framesIn = 4))
        val capture = p.stages[0]
        assertEquals(StageStatus.DEGRADED, capture.status)
        assertTrue(capture.note!!.contains("4 of 6"))
        assertTrue(p.capturedWithoutConvergence)
        assertTrue(p.toJson().contains("\"captured_without_3a_convergence\":true"))
    }

    @Test
    fun singleFrameAndGrayscaleAreDegraded() {
        val p = CaptureProvenance.forBurst("m4", burst(1, 1, OrchestrationState.COMPLETE), cleanMerge.copy(framesIn = 1, colour = false))
        assertEquals(StageStatus.DEGRADED, p.stages[1].status)
        assertEquals(StageStatus.DEGRADED, p.stages[2].status)
        assertEquals(ContentOrigin.CAPTURED, p.outputOrigin)
    }
}
