package com.neuralcamera.cameracore

import com.neuralcamera.cameracore.orchestration.FrameRef
import com.neuralcamera.cameracore.orchestration.OrchestrationResult
import com.neuralcamera.cameracore.orchestration.OrchestrationState
import com.neuralcamera.cameracore.threea.ConvergenceVerdict
import com.neuralcamera.deviceprofiles.LensFacing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BurstCaptureTest {

    private fun frame(i: Int) = CameraFrame(
        "f$i", "YUV_420_888", 4, 4, emptyList(),
        FrameMetadata(i.toLong(), 1_000L * i, 10_000_000L, 100, 6f, 1f, 1.8f, LensFacing.BACK_WIDE, "0", 90)
    )

    private fun capture(n: Int, state: OrchestrationState, unconverged: Boolean = false, locked: Int? = n, attempts: Int = 1) = BurstCapture(
        frames = (0 until n).map(::frame),
        result = OrchestrationResult(state, (0 until n).map { FrameRef(it, 1_000L * it, it.toLong()) }, 4, attempts, 4 - n, unconverged, "r"),
        convergence = if (unconverged) ConvergenceVerdict.TIMED_OUT else ConvergenceVerdict.CONVERGED,
        lockedFrames = locked,
        lockRequested = true
    )

    @Test
    fun cleanBurstHasNoCaveats() {
        val c = capture(4, OrchestrationState.COMPLETE)
        assertTrue(c.usable)
        assertTrue(c.caveats().isEmpty())
    }

    @Test
    fun partialUnconvergedUnlockedBurstReportsEachCaveat() {
        val c = capture(2, OrchestrationState.PARTIAL, unconverged = true, locked = 1, attempts = 2)
        assertTrue(c.usable)
        val caveats = c.caveats()
        assertEquals(4, caveats.size)
        assertTrue(caveats[0].contains("2 of 4"))
        assertTrue(caveats[1].contains("TIMED_OUT"))
        assertTrue(caveats[2].contains("1 of 2"))
    }

    @Test
    fun failedCaptureIsNotUsable() {
        val c = BurstCapture.failed(4, "camera is not open and configured")
        assertFalse(c.usable)
        assertEquals(OrchestrationState.FAILED, c.result.state)
        assertTrue(c.frames.isEmpty())
    }
}
