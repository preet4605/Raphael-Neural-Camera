package com.neuralcamera.cameracore.orchestration

import com.neuralcamera.cameracore.threea.ConvergenceVerdict
import kotlin.math.abs

/*
 * Capture orchestration as a deterministic state machine: pre-capture, 3A convergence, burst scheduling, cancellation,
 * timeout/retry, partial-burst recovery and session recovery. It issues [CaptureCommand]s and consumes
 * [CaptureEvent]s. [OrchestratorDriver] runs it against a [CaptureCommandExecutor]; RealCamera2Controller.captureBurst
 * is the Camera2 executor. On-device behaviour is NOT_TESTED.
 */

enum class OrchestrationState { IDLE, PRECAPTURE, CONVERGING, CAPTURING, RECOVERING, COMPLETE, PARTIAL, FAILED, CANCELLED }

data class OrchestrationPolicy(
    /** Frames requested per burst. */
    val requestedFrames: Int,
    /** Fewer valid frames than this fails the attempt (a merge needs at least this many). */
    val minUsableFrames: Int = 1,
    /** Burst attempts before giving up (first attempt included). */
    val maxAttempts: Int = 2,
    /** Session re-creations allowed after a recoverable session error. */
    val maxSessionRecoveries: Int = 1,
    /** When 3A times out: capture anyway (frames flagged) or fail. */
    val captureOnConvergenceTimeout: Boolean = true,
    val runPrecapture: Boolean = true
) {
    init {
        require(requestedFrames >= 1) { "at least one frame" }
        require(minUsableFrames in 1..requestedFrames) { "minUsableFrames must be within 1..requestedFrames" }
        require(maxAttempts >= 1) { "at least one attempt" }
    }
}

sealed class CaptureEvent {
    object ShutterPressed : CaptureEvent()
    object PrecaptureFinished : CaptureEvent()
    data class ConvergenceUpdate(val verdict: ConvergenceVerdict) : CaptureEvent()
    data class FrameArrived(val frame: FrameRef) : CaptureEvent()
    data class FrameLost(val requestIndex: Int, val reason: String) : CaptureEvent()
    /** The burst deadline passed; frames still outstanding count as lost. */
    object BurstTimeout : CaptureEvent()
    data class SessionError(val recoverable: Boolean, val reason: String) : CaptureEvent()
    object SessionRecovered : CaptureEvent()
    object Cancel : CaptureEvent()
}

sealed class CaptureCommand {
    object RunPrecapture : CaptureCommand()
    object AwaitConvergence : CaptureCommand()
    /** Lock AE/AWB (and AF) for the burst so exposure does not drift between frames. */
    object Lock3A : CaptureCommand()
    data class IssueBurst(val attempt: Int, val frames: Int) : CaptureCommand()
    object AbortCaptures : CaptureCommand()
    object RecreateSession : CaptureCommand()
    object Unlock3AAndResumePreview : CaptureCommand()
    data class Deliver(val result: OrchestrationResult) : CaptureCommand()
}

/** A received frame, by reference; the pixel buffer stays with its owner. */
data class FrameRef(val requestIndex: Int, val sensorTimestampNs: Long, val frameNumber: Long)

data class OrchestrationResult(
    val state: OrchestrationState,
    /** Valid frames of the delivering attempt, ordered by sensor timestamp. */
    val frames: List<FrameRef>,
    val requested: Int,
    val attempts: Int,
    val lostFrames: Int,
    /** True when capture proceeded without 3A convergence; downstream must record this in processing metadata. */
    val capturedWithoutConvergence: Boolean,
    val reason: String
)

class CaptureOrchestrator(private val policy: OrchestrationPolicy) {
    var state: OrchestrationState = OrchestrationState.IDLE
        private set
    private var attempt = 0
    private var recoveries = 0
    private var notConverged = false
    private val frames = LinkedHashMap<Int, FrameRef>()
    private val lost = HashSet<Int>()
    private var totalLost = 0

    val isTerminal: Boolean
        get() = state in setOf(OrchestrationState.COMPLETE, OrchestrationState.PARTIAL, OrchestrationState.FAILED, OrchestrationState.CANCELLED)

    fun handle(event: CaptureEvent): List<CaptureCommand> {
        if (event is CaptureEvent.Cancel) return cancel()
        return when (state) {
            OrchestrationState.IDLE -> when (event) {
                CaptureEvent.ShutterPressed -> if (policy.runPrecapture) {
                    state = OrchestrationState.PRECAPTURE; listOf(CaptureCommand.RunPrecapture)
                } else {
                    state = OrchestrationState.CONVERGING; listOf(CaptureCommand.AwaitConvergence)
                }
                else -> emptyList()
            }
            OrchestrationState.PRECAPTURE -> when (event) {
                CaptureEvent.PrecaptureFinished -> { state = OrchestrationState.CONVERGING; listOf(CaptureCommand.AwaitConvergence) }
                is CaptureEvent.SessionError -> onSessionError(event)
                else -> emptyList()
            }
            OrchestrationState.CONVERGING -> when (event) {
                is CaptureEvent.ConvergenceUpdate -> onConvergence(event.verdict)
                is CaptureEvent.SessionError -> onSessionError(event)
                else -> emptyList()
            }
            OrchestrationState.CAPTURING -> when (event) {
                is CaptureEvent.FrameArrived -> onFrame(event.frame)
                is CaptureEvent.FrameLost -> onLost(event.requestIndex)
                CaptureEvent.BurstTimeout -> {
                    for (i in 0 until policy.requestedFrames) if (i !in frames && i !in lost) onLostInternal(i)
                    finishAttempt(timedOut = true)
                }
                is CaptureEvent.SessionError -> onSessionError(event)
                else -> emptyList()
            }
            OrchestrationState.RECOVERING -> when (event) {
                CaptureEvent.SessionRecovered -> startBurst()
                is CaptureEvent.SessionError -> fail("session recovery failed: ${event.reason}")
                else -> emptyList()
            }
            else -> emptyList() // terminal: late events (e.g. a frame after cancel) are ignored; the owner releases them
        }
    }

    private fun onConvergence(verdict: ConvergenceVerdict): List<CaptureCommand> = when (verdict) {
        ConvergenceVerdict.CONVERGED -> startBurst()
        ConvergenceVerdict.PENDING -> emptyList()
        ConvergenceVerdict.TIMED_OUT, ConvergenceVerdict.FOCUS_FAILED, ConvergenceVerdict.UNKNOWN ->
            if (policy.captureOnConvergenceTimeout) { notConverged = true; startBurst() }
            else fail("3A did not converge ($verdict)")
    }

    private fun startBurst(): List<CaptureCommand> {
        attempt++
        frames.clear(); lost.clear()
        state = OrchestrationState.CAPTURING
        return listOf(CaptureCommand.Lock3A, CaptureCommand.IssueBurst(attempt, policy.requestedFrames))
    }

    private fun onFrame(frame: FrameRef): List<CaptureCommand> {
        if (frame.requestIndex !in 0 until policy.requestedFrames || frame.requestIndex in lost) return emptyList()
        // A second frame for the same request is a duplicate, never a new frame.
        if (frames.containsKey(frame.requestIndex)) return emptyList()
        frames[frame.requestIndex] = frame
        return if (frames.size + lost.size == policy.requestedFrames) finishAttempt(timedOut = false) else emptyList()
    }

    private fun onLost(index: Int): List<CaptureCommand> {
        if (index !in 0 until policy.requestedFrames || index in frames || index in lost) return emptyList()
        onLostInternal(index)
        return if (frames.size + lost.size == policy.requestedFrames) finishAttempt(timedOut = false) else emptyList()
    }

    private fun onLostInternal(index: Int) { lost.add(index); totalLost++ }

    private fun finishAttempt(timedOut: Boolean): List<CaptureCommand> {
        val valid = frames.values.sortedBy { it.sensorTimestampNs }
        return when {
            valid.size == policy.requestedFrames -> deliver(OrchestrationState.COMPLETE, valid, "all ${valid.size} frames received")
            valid.size >= policy.minUsableFrames -> deliver(
                OrchestrationState.PARTIAL, valid,
                "${valid.size} of ${policy.requestedFrames} frames received${if (timedOut) " (burst timed out)" else ""}"
            )
            attempt < policy.maxAttempts -> listOf(CaptureCommand.AbortCaptures) + startBurst()
            else -> fail("only ${valid.size} of ${policy.requestedFrames} frames after $attempt attempts (need ${policy.minUsableFrames})")
        }
    }

    private fun onSessionError(e: CaptureEvent.SessionError): List<CaptureCommand> =
        if (e.recoverable && recoveries < policy.maxSessionRecoveries) {
            recoveries++
            state = OrchestrationState.RECOVERING
            listOf(CaptureCommand.AbortCaptures, CaptureCommand.RecreateSession)
        } else fail("session error: ${e.reason}")

    private fun cancel(): List<CaptureCommand> {
        if (isTerminal) return emptyList()
        val wasCapturing = state == OrchestrationState.CAPTURING
        return deliverTerminal(OrchestrationState.CANCELLED, emptyList(), "cancelled by caller", abort = wasCapturing)
    }

    private fun fail(reason: String) = deliverTerminal(OrchestrationState.FAILED, emptyList(), reason, abort = true)

    private fun deliver(s: OrchestrationState, valid: List<FrameRef>, reason: String) = deliverTerminal(s, valid, reason, abort = false)

    private fun deliverTerminal(s: OrchestrationState, valid: List<FrameRef>, reason: String, abort: Boolean): List<CaptureCommand> {
        state = s
        val result = OrchestrationResult(s, valid, policy.requestedFrames, attempt, totalLost, notConverged, reason)
        return (if (abort) listOf(CaptureCommand.AbortCaptures) else emptyList()) +
            listOf(CaptureCommand.Unlock3AAndResumePreview, CaptureCommand.Deliver(result))
    }
}

/**
 * Frame prioritization when memory allows only [budget] frames: keep the frames closest to the shutter press (what the
 * user saw), returned in timestamp order.
 */
object FramePrioritizer {
    fun closestToShutter(frames: List<FrameRef>, shutterTimestampNs: Long, budget: Int): List<FrameRef> {
        require(budget >= 1) { "budget must be at least one frame" }
        return frames.sortedBy { abs(it.sensorTimestampNs - shutterTimestampNs) }.take(budget).sortedBy { it.sensorTimestampNs }
    }
}
