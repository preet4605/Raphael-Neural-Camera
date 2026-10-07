package com.neuralcamera.models.execution

/**
 * Failure-aware execution contract shared by every expensive stage (capture, merge, RAW processing, inference,
 * encoding, storage).
 *
 * A stage reports exactly one of:
 * - [StageOutcome.Success]: the intended implementation ran and produced the value.
 * - [StageOutcome.Degraded]: a value was produced, but not by the intended implementation (a fallback ran, an input was
 *   partial, a step was skipped). [Degraded.intended] and [Degraded.actual] name both, so nothing downstream can report
 *   the fallback as though the intended backend ran.
 * - [StageOutcome.Failed]: no usable value. [Failed.fallback] names what the caller should do next.
 *
 * Plain Kotlin, no Android types, so it is testable on the JVM.
 */
sealed class StageOutcome<out T> {
    abstract val stage: String

    data class Success<T>(
        override val stage: String,
        val value: T,
        /** The implementation that actually ran, e.g. "cpu-reference", "ort-qnn-htp", "kotlin-bayer-merge". */
        val implementation: String
    ) : StageOutcome<T>()

    data class Degraded<T>(
        override val stage: String,
        val value: T,
        val intended: String,
        val actual: String,
        val reason: String
    ) : StageOutcome<T>() {
        init {
            require(intended != actual) { "a degraded outcome must name a different actual implementation than intended" }
            require(reason.isNotBlank()) { "a degraded outcome must say why" }
        }
    }

    data class Failed(
        override val stage: String,
        val reason: String,
        val fallback: FallbackAction,
        val cause: Throwable? = null
    ) : StageOutcome<Nothing>() {
        init {
            require(reason.isNotBlank()) { "a failed outcome must say why" }
        }
    }

    val status: StageStatus
        get() = when (this) {
            is Success -> StageStatus.SUCCESS
            is Degraded -> StageStatus.DEGRADED
            is Failed -> StageStatus.FAILED
        }

    /** The produced value for SUCCESS or DEGRADED, null for FAILED. */
    fun valueOrNull(): T? = when (this) {
        is Success -> value
        is Degraded -> value
        is Failed -> null
    }

    /** True only when the intended implementation ran. A fallback is never "intended". */
    val ranAsIntended: Boolean get() = this is Success

    /** What actually executed, or null when nothing produced a value. */
    val executedImplementation: String?
        get() = when (this) {
            is Success -> implementation
            is Degraded -> actual
            is Failed -> null
        }
}

enum class StageStatus { SUCCESS, DEGRADED, FAILED }

/** What the caller does after a stage fails. */
enum class FallbackAction {
    /** Retry the same stage (bounded by the caller's retry policy). */
    RETRY,

    /** Run the classical/CPU path instead. Its result must be reported as DEGRADED, not SUCCESS. */
    USE_CLASSICAL_FALLBACK,

    /** Continue without this stage; the output carries less processing and says so. */
    SKIP_STAGE,

    /** Use the single best captured frame instead of a merged result. */
    USE_SINGLE_FRAME,

    /** Nothing can be produced; report the failure to the user. Never substitute synthetic data. */
    ABORT
}

/**
 * Runs [intended]; when it throws, runs [fallback] and reports DEGRADED with both names. When both throw the result is
 * FAILED with [onBothFailed]. Cancellation-style errors are not swallowed: [Error]s propagate.
 */
inline fun <T> runWithFallback(
    stage: String,
    intendedName: String,
    fallbackName: String,
    onBothFailed: FallbackAction = FallbackAction.ABORT,
    intended: () -> T,
    fallback: () -> T
): StageOutcome<T> {
    val firstError = try {
        return StageOutcome.Success(stage, intended(), intendedName)
    } catch (e: Exception) {
        e
    }
    return try {
        StageOutcome.Degraded(
            stage, fallback(), intended = intendedName, actual = fallbackName,
            reason = "$intendedName failed: ${firstError.message ?: firstError.javaClass.simpleName}"
        )
    } catch (e: Exception) {
        StageOutcome.Failed(
            stage,
            "$intendedName failed (${firstError.message ?: firstError.javaClass.simpleName}); " +
                "$fallbackName failed (${e.message ?: e.javaClass.simpleName})",
            onBothFailed,
            e
        )
    }
}
