package com.neuralcamera.cameracore.orchestration

import com.neuralcamera.cameracore.threea.ConvergenceVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureOrchestratorTest {

    private fun frame(i: Int) = FrameRef(i, 1_000_000L * (i + 1), 100L + i)

    private fun result(cmds: List<CaptureCommand>) = (cmds.last() as CaptureCommand.Deliver).result

    private fun converged(o: CaptureOrchestrator): List<CaptureCommand> {
        assertEquals(listOf(CaptureCommand.RunPrecapture), o.handle(CaptureEvent.ShutterPressed))
        assertEquals(listOf(CaptureCommand.AwaitConvergence), o.handle(CaptureEvent.PrecaptureFinished))
        return o.handle(CaptureEvent.ConvergenceUpdate(ConvergenceVerdict.CONVERGED))
    }

    @Test
    fun fullBurstCompletes() {
        val o = CaptureOrchestrator(OrchestrationPolicy(requestedFrames = 3))
        assertEquals(listOf(CaptureCommand.Lock3A, CaptureCommand.IssueBurst(1, 3)), converged(o))
        assertTrue(o.handle(CaptureEvent.FrameArrived(frame(2))).isEmpty())
        o.handle(CaptureEvent.FrameArrived(frame(0)))
        val r = result(o.handle(CaptureEvent.FrameArrived(frame(1))))
        assertEquals(OrchestrationState.COMPLETE, r.state)
        assertEquals(listOf(0, 1, 2), r.frames.map { it.requestIndex })
        assertFalse(r.capturedWithoutConvergence)
    }

    @Test
    fun duplicateFrameIsIgnored() {
        val o = CaptureOrchestrator(OrchestrationPolicy(requestedFrames = 2))
        converged(o)
        o.handle(CaptureEvent.FrameArrived(frame(0)))
        assertTrue(o.handle(CaptureEvent.FrameArrived(frame(0))).isEmpty())
        assertEquals(OrchestrationState.CAPTURING, o.state)
    }

    @Test
    fun partialBurstAboveMinimumIsDeliveredAsPartial() {
        val o = CaptureOrchestrator(OrchestrationPolicy(requestedFrames = 4, minUsableFrames = 2))
        converged(o)
        o.handle(CaptureEvent.FrameArrived(frame(0)))
        o.handle(CaptureEvent.FrameLost(1, "buffer lost"))
        o.handle(CaptureEvent.FrameArrived(frame(2)))
        val r = result(o.handle(CaptureEvent.BurstTimeout))
        assertEquals(OrchestrationState.PARTIAL, r.state)
        assertEquals(2, r.frames.size)
        assertEquals(2, r.lostFrames)
        assertTrue(r.reason.contains("timed out"))
    }

    @Test
    fun tooFewFramesRetriesThenFails() {
        val o = CaptureOrchestrator(OrchestrationPolicy(requestedFrames = 2, minUsableFrames = 2, maxAttempts = 2))
        converged(o)
        o.handle(CaptureEvent.FrameArrived(frame(0)))
        val retry = o.handle(CaptureEvent.FrameLost(1, "failed"))
        assertEquals(listOf(CaptureCommand.AbortCaptures, CaptureCommand.Lock3A, CaptureCommand.IssueBurst(2, 2)), retry)
        o.handle(CaptureEvent.FrameLost(0, "failed"))
        val r = result(o.handle(CaptureEvent.FrameLost(1, "failed")))
        assertEquals(OrchestrationState.FAILED, r.state)
        assertTrue(r.frames.isEmpty())
        assertEquals(2, r.attempts)
    }

    @Test
    fun convergenceTimeoutCapturesFlaggedOrFailsByPolicy() {
        val lenient = CaptureOrchestrator(OrchestrationPolicy(requestedFrames = 1))
        lenient.handle(CaptureEvent.ShutterPressed); lenient.handle(CaptureEvent.PrecaptureFinished)
        lenient.handle(CaptureEvent.ConvergenceUpdate(ConvergenceVerdict.TIMED_OUT))
        assertTrue(result(lenient.handle(CaptureEvent.FrameArrived(frame(0)))).capturedWithoutConvergence)

        val strict = CaptureOrchestrator(OrchestrationPolicy(requestedFrames = 1, captureOnConvergenceTimeout = false))
        strict.handle(CaptureEvent.ShutterPressed); strict.handle(CaptureEvent.PrecaptureFinished)
        val r = result(strict.handle(CaptureEvent.ConvergenceUpdate(ConvergenceVerdict.UNKNOWN)))
        assertEquals(OrchestrationState.FAILED, r.state)
    }

    @Test
    fun cancelAbortsAndIgnoresLateFrames() {
        val o = CaptureOrchestrator(OrchestrationPolicy(requestedFrames = 3))
        converged(o)
        o.handle(CaptureEvent.FrameArrived(frame(0)))
        val cmds = o.handle(CaptureEvent.Cancel)
        assertEquals(CaptureCommand.AbortCaptures, cmds.first())
        assertEquals(OrchestrationState.CANCELLED, result(cmds).state)
        assertTrue(result(cmds).frames.isEmpty())
        assertTrue(o.handle(CaptureEvent.FrameArrived(frame(1))).isEmpty())
        assertTrue(o.handle(CaptureEvent.Cancel).isEmpty())
    }

    @Test
    fun recoverableSessionErrorRecreatesSessionOnce() {
        val o = CaptureOrchestrator(OrchestrationPolicy(requestedFrames = 1, maxSessionRecoveries = 1))
        converged(o)
        assertEquals(
            listOf(CaptureCommand.AbortCaptures, CaptureCommand.RecreateSession),
            o.handle(CaptureEvent.SessionError(recoverable = true, reason = "disconnected"))
        )
        assertEquals(OrchestrationState.RECOVERING, o.state)
        assertEquals(CaptureCommand.IssueBurst(2, 1), o.handle(CaptureEvent.SessionRecovered).last())
        val r = result(o.handle(CaptureEvent.SessionError(recoverable = true, reason = "again")))
        assertEquals(OrchestrationState.FAILED, r.state)
    }

    @Test
    fun prioritizerKeepsFramesNearestTheShutter() {
        val frames = (0 until 6).map { FrameRef(it, it * 10L, it.toLong()) }
        val kept = FramePrioritizer.closestToShutter(frames, shutterTimestampNs = 32L, budget = 3)
        assertEquals(listOf(2, 3, 4), kept.map { it.requestIndex })
    }

    @Test(expected = IllegalArgumentException::class)
    fun policyRejectsImpossibleMinimum() { OrchestrationPolicy(requestedFrames = 2, minUsableFrames = 3) }
}
