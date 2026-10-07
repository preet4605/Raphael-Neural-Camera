package com.neuralcamera.ui

import com.neuralcamera.capture.CameraShootingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraUIStateTest {

    @Test
    fun testDefaultStateValues() {
        val state = CameraUIState()
        assertEquals(CameraShootingMode.AUTO, state.activeMode)
        assertEquals(1.0f, state.activeZoomFactor, 0.001f)
        assertFalse(state.isNeuralActive)
        assertFalse(state.showDiagnostics)
    }

    @Test
    fun testDefaultStateReportsNothingUnmeasured() {
        val state = CameraUIState()
        assertNull(state.latencyMs)
        assertNull(state.memoryUsageMb)
        assertNull(state.statusMessage)
        assertNull(state.diagnosticsData.fps)
        assertNull(state.diagnosticsData.iso)
        assertNull(state.diagnosticsData.frameNumber)
    }

    @Test
    fun testStateMutation() {
        val state = CameraUIState(
            activeMode = CameraShootingMode.MASTER,
            activeZoomFactor = 3.0f,
            showDiagnostics = true
        )
        assertEquals(CameraShootingMode.MASTER, state.activeMode)
        assertEquals(3.0f, state.activeZoomFactor, 0.001f)
        assertTrue(state.showDiagnostics)
    }
}
