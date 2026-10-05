package com.neuralcamera.cameracore

import com.neuralcamera.cameracore.state.CameraState
import com.neuralcamera.cameracore.state.CameraStateMachine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraStateMachineTest {

    @Test
    fun testValidStateTransitions() {
        val sm = CameraStateMachine(CameraState.UNINITIALIZED)
        assertEquals(CameraState.UNINITIALIZED, sm.state.value)

        assertTrue(sm.transitionTo(CameraState.INITIALIZING).isSuccess)
        assertEquals(CameraState.INITIALIZING, sm.state.value)

        assertTrue(sm.transitionTo(CameraState.READY).isSuccess)
        assertEquals(CameraState.READY, sm.state.value)

        assertTrue(sm.transitionTo(CameraState.FOCUSING).isSuccess)
        assertEquals(CameraState.FOCUSING, sm.state.value)

        assertTrue(sm.transitionTo(CameraState.CAPTURING).isSuccess)
        assertEquals(CameraState.CAPTURING, sm.state.value)

        assertTrue(sm.transitionTo(CameraState.PROCESSING).isSuccess)
        assertEquals(CameraState.PROCESSING, sm.state.value)

        assertTrue(sm.transitionTo(CameraState.SAVING).isSuccess)
        assertEquals(CameraState.SAVING, sm.state.value)

        assertTrue(sm.transitionTo(CameraState.READY).isSuccess)
        assertEquals(CameraState.READY, sm.state.value)

        assertTrue(sm.transitionTo(CameraState.CLOSED).isSuccess)
        assertEquals(CameraState.CLOSED, sm.state.value)
    }

    @Test
    fun testInvalidStateTransitionRejected() {
        val sm = CameraStateMachine(CameraState.UNINITIALIZED)

        // Cannot jump directly from UNINITIALIZED to CAPTURING
        val result = sm.transitionTo(CameraState.CAPTURING)
        assertTrue(result.isFailure)
        assertEquals(CameraState.UNINITIALIZED, sm.state.value)
    }

    @Test
    fun testErrorAndRecoveryFlow() {
        val sm = CameraStateMachine(CameraState.READY)
        sm.forceError("Sensor timeout")
        assertEquals(CameraState.ERROR, sm.state.value)

        // Error can transition to RECOVERING
        assertTrue(sm.transitionTo(CameraState.RECOVERING).isSuccess)
        assertEquals(CameraState.RECOVERING, sm.state.value)

        // Recovering can transition back to READY
        assertTrue(sm.transitionTo(CameraState.READY).isSuccess)
        assertEquals(CameraState.READY, sm.state.value)
    }
}
