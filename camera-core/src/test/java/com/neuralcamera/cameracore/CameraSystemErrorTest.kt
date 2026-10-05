package com.neuralcamera.cameracore

import com.neuralcamera.cameracore.errors.CameraSystemError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraSystemErrorTest {

    @Test
    fun testExpectedUnsupportedCapability() {
        val err = CameraSystemError.ExpectedUnsupportedCapability.RawFormatUnsupported("0")
        assertEquals("ERR_CAP_RAW_UNSUPPORTED", err.code)
        assertTrue(err.isRecoverable)
        assertTrue(err.message.contains("Camera 0 does not support RAW_SENSOR"))
    }

    @Test
    fun testRecoverableRuntimeFailure() {
        val err = CameraSystemError.RecoverableRuntimeFailure.BackendInitFailed("QNN_NPU", "Missing library")
        assertEquals("ERR_RUN_BACKEND_INIT", err.code)
        assertTrue(err.isRecoverable)
    }

    @Test
    fun testCameraHardwareFailure() {
        val err = CameraSystemError.CameraHardwareFailure.CameraDisconnected("1")
        assertEquals("ERR_CAM_DISCONNECTED", err.code)
        assertTrue(err.isRecoverable)
    }

    @Test
    fun testResourceFailure() {
        val memErr = CameraSystemError.ResourceFailure.InsufficientMemory(500_000_000L, 200_000_000L)
        assertEquals("ERR_RES_MEMORY", memErr.code)
        assertFalse(memErr.isRecoverable)

        val storageErr = CameraSystemError.ResourceFailure.InsufficientStorage(10_000_000L, 1_000_000L)
        assertEquals("ERR_RES_STORAGE", storageErr.code)
        assertTrue(storageErr.isRecoverable)
    }

    @Test
    fun testFatalApplicationFailure() {
        val fatalErr = CameraSystemError.FatalApplicationFailure.UnrecoverableInitFailure("HAL", "Kernel panic")
        assertEquals("ERR_FATAL_INIT", fatalErr.code)
        assertFalse(fatalErr.isRecoverable)
    }
}
