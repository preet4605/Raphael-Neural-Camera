package com.neuralcamera.cameracore.orchestration

import kotlinx.coroutines.CancellationException

/**
 * Runs one [CaptureCommand] against the camera and returns the events it produced, in order. Implementations block (by
 * suspending) until the command's outcome is known: RunPrecapture returns PrecaptureFinished, AwaitConvergence returns a
 * final ConvergenceUpdate (never PENDING), IssueBurst returns one FrameArrived/FrameLost per request (or BurstTimeout),
 * RecreateSession returns SessionRecovered or SessionError. Commands with no outcome (locks, abort) return nothing.
 */
fun interface CaptureCommandExecutor {
    suspend fun execute(command: CaptureCommand): List<CaptureEvent>
}

/**
 * Drives a [CaptureOrchestrator] with a [CaptureCommandExecutor] until it delivers a result. An executor exception
 * becomes a recoverable [CaptureEvent.SessionError], so the orchestrator's bounded recovery decides what happens next;
 * the driver never invents frames or success. Plain Kotlin: testable on the JVM with a fake executor.
 */
class OrchestratorDriver(
    private val policy: OrchestrationPolicy,
    private val executor: CaptureCommandExecutor,
    /** Guard against an executor that never lets the orchestrator reach a terminal state. */
    private val maxEvents: Int = 1_000
) {
    suspend fun run(): OrchestrationResult {
        val orchestrator = CaptureOrchestrator(policy)
        val queue = ArrayDeque<CaptureEvent>()
        queue.addLast(CaptureEvent.ShutterPressed)
        var handled = 0
        var stall: String? = null
        while (true) {
            val event = when {
                handled >= maxEvents -> { stall = "aborted after $maxEvents events without a result"; CaptureEvent.Cancel }
                queue.isEmpty() -> { stall = "executor produced no event while ${orchestrator.state}"; CaptureEvent.Cancel }
                else -> queue.removeFirst()
            }
            handled++
            for (command in orchestrator.handle(event)) {
                if (command is CaptureCommand.Deliver) {
                    // A forced stop is reported as FAILED with its cause, never as a user cancel.
                    return stall?.let { command.result.copy(state = OrchestrationState.FAILED, reason = it) } ?: command.result
                }
                queue.addAll(executeSafely(command))
            }
        }
    }

    private suspend fun executeSafely(command: CaptureCommand): List<CaptureEvent> = try {
        executor.execute(command)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        listOf(CaptureEvent.SessionError(recoverable = true, reason = "${command.javaClass.simpleName} failed: ${e.message ?: e.javaClass.simpleName}"))
    }
}
