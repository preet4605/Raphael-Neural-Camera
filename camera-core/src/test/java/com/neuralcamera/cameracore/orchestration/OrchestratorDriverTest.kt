package com.neuralcamera.cameracore.orchestration

import com.neuralcamera.cameracore.threea.ConvergenceVerdict
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrchestratorDriverTest {

    /** Fake camera: scripted convergence verdict and, per burst attempt, which request indices arrive. */
    private class FakeCamera(
        val verdict: ConvergenceVerdict = ConvergenceVerdict.CONVERGED,
        val arrivals: List<Set<Int>> = listOf(setOf(0, 1, 2)),
        val failOn: Set<String> = emptySet(),
        val recoverOk: Boolean = true
    ) : CaptureCommandExecutor {
        val log = mutableListOf<String>()
        private var burst = 0

        override suspend fun execute(command: CaptureCommand): List<CaptureEvent> {
            val name = command.javaClass.simpleName
            log += name
            if (name in failOn) {
                if (name == "IssueBurst" && burst++ > 0) return deliverBurst(command as CaptureCommand.IssueBurst)
                throw IllegalStateException("camera closed")
            }
            return when (command) {
                CaptureCommand.RunPrecapture -> listOf(CaptureEvent.PrecaptureFinished)
                CaptureCommand.AwaitConvergence -> listOf(CaptureEvent.ConvergenceUpdate(verdict))
                is CaptureCommand.IssueBurst -> deliverBurst(command)
                CaptureCommand.RecreateSession ->
                    listOf(if (recoverOk) CaptureEvent.SessionRecovered else CaptureEvent.SessionError(false, "reopen failed"))
                else -> emptyList()
            }
        }

        private fun deliverBurst(c: CaptureCommand.IssueBurst): List<CaptureEvent> {
            val arrived = arrivals.getOrElse(c.attempt - 1) { arrivals.last() }
            return (0 until c.frames).map { i ->
                if (i in arrived) CaptureEvent.FrameArrived(FrameRef(i, 1_000L * (i + 1), 10L + i))
                else CaptureEvent.FrameLost(i, "buffer lost")
            }
        }
    }

    private fun run(policy: OrchestrationPolicy, cam: CaptureCommandExecutor) = runBlocking { OrchestratorDriver(policy, cam).run() }

    @Test
    fun convergedBurstCompletesWithLockBeforeBurstAndUnlockAfter() {
        val cam = FakeCamera()
        val r = run(OrchestrationPolicy(requestedFrames = 3), cam)
        assertEquals(OrchestrationState.COMPLETE, r.state)
        assertEquals(3, r.frames.size)
        assertFalse(r.capturedWithoutConvergence)
        assertEquals(listOf("RunPrecapture", "AwaitConvergence", "Lock3A", "IssueBurst", "Unlock3AAndResumePreview"), cam.log)
    }

    @Test
    fun convergenceTimeoutCapturesButFlagsFrames() {
        val r = run(OrchestrationPolicy(requestedFrames = 3), FakeCamera(verdict = ConvergenceVerdict.TIMED_OUT))
        assertEquals(OrchestrationState.COMPLETE, r.state)
        assertTrue(r.capturedWithoutConvergence)
    }

    @Test
    fun unknownConvergenceIsNeverTreatedAsConverged() {
        val r = run(OrchestrationPolicy(requestedFrames = 3), FakeCamera(verdict = ConvergenceVerdict.UNKNOWN))
        assertTrue(r.capturedWithoutConvergence)
    }

    @Test
    fun partialBurstIsReportedAsPartial() {
        val r = run(OrchestrationPolicy(requestedFrames = 3, minUsableFrames = 2), FakeCamera(arrivals = listOf(setOf(0, 2))))
        assertEquals(OrchestrationState.PARTIAL, r.state)
        assertEquals(listOf(0, 2), r.frames.map { it.requestIndex })
        assertEquals(1, r.lostFrames)
    }

    @Test
    fun tooFewFramesRetriesOnceThenSucceeds() {
        val cam = FakeCamera(arrivals = listOf(setOf(0), setOf(0, 1, 2)))
        val r = run(OrchestrationPolicy(requestedFrames = 3, minUsableFrames = 3, maxAttempts = 2), cam)
        assertEquals(OrchestrationState.COMPLETE, r.state)
        assertEquals(2, r.attempts)
        assertTrue("aborted before retrying", cam.log.contains("AbortCaptures"))
    }

    @Test
    fun executorExceptionRecoversSessionThenRetries() {
        val cam = FakeCamera(failOn = setOf("IssueBurst"))
        val r = run(OrchestrationPolicy(requestedFrames = 3), cam)
        assertEquals(OrchestrationState.COMPLETE, r.state)
        assertTrue(cam.log.contains("RecreateSession"))
    }

    @Test
    fun failedRecoveryFails() {
        val r = run(OrchestrationPolicy(requestedFrames = 3), FakeCamera(failOn = setOf("IssueBurst"), recoverOk = false))
        assertEquals(OrchestrationState.FAILED, r.state)
        assertTrue(r.frames.isEmpty())
    }

    @Test
    fun executorThatProducesNoEventFailsWithCauseInsteadOfHanging() {
        val silent = CaptureCommandExecutor { emptyList() }
        val r = run(OrchestrationPolicy(requestedFrames = 3), silent)
        assertEquals(OrchestrationState.FAILED, r.state)
        assertTrue(r.reason, r.reason.contains("no event"))
    }
}
