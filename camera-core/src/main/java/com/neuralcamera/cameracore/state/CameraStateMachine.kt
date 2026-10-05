package com.neuralcamera.cameracore.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Camera operational states specified by Section 10 of the Neural Camera Constitution.
 */
enum class CameraState {
    UNINITIALIZED,
    INITIALIZING,
    READY,
    FOCUSING,
    CAPTURING,
    PROCESSING,
    SAVING,
    ERROR,
    RECOVERING,
    CLOSED;

    /**
     * Verifies whether transitioning from `this` state to `next` is deterministically legal.
     */
    fun canTransitionTo(next: CameraState): Boolean {
        return when (this) {
            UNINITIALIZED -> next in setOf(INITIALIZING, ERROR, CLOSED)
            INITIALIZING -> next in setOf(READY, ERROR, CLOSED)
            READY -> next in setOf(FOCUSING, CAPTURING, PROCESSING, ERROR, CLOSED)
            FOCUSING -> next in setOf(READY, CAPTURING, ERROR, CLOSED)
            CAPTURING -> next in setOf(PROCESSING, READY, ERROR, CLOSED)
            PROCESSING -> next in setOf(SAVING, READY, ERROR, CLOSED)
            SAVING -> next in setOf(READY, ERROR, CLOSED)
            ERROR -> next in setOf(RECOVERING, CLOSED)
            RECOVERING -> next in setOf(INITIALIZING, READY, ERROR, CLOSED)
            CLOSED -> next in setOf(INITIALIZING, UNINITIALIZED)
        }
    }
}

/**
 * Deterministic state machine governing camera lifecycle transitions.
 */
class CameraStateMachine(initialState: CameraState = CameraState.UNINITIALIZED) {

    private val _state = MutableStateFlow(initialState)
    val state: StateFlow<CameraState> = _state.asStateFlow()

    @Synchronized
    fun transitionTo(next: CameraState): Result<CameraState> {
        val current = _state.value
        return if (current.canTransitionTo(next)) {
            _state.value = next
            Result.success(next)
        } else {
            Result.failure(
                IllegalStateException("Illegal camera state transition from $current to $next")
            )
        }
    }

    @Synchronized
    fun forceError(errorMessage: String): CameraState {
        _state.value = CameraState.ERROR
        return CameraState.ERROR
    }

    @Synchronized
    fun reset() {
        _state.value = CameraState.UNINITIALIZED
    }
}
