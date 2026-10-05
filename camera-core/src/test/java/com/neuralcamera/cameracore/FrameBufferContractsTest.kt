package com.neuralcamera.cameracore

import com.neuralcamera.cameracore.buffers.BufferOwnershipState
import com.neuralcamera.cameracore.buffers.BufferType
import com.neuralcamera.cameracore.buffers.ReferenceCountedBufferHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameBufferContractsTest {

    @Test
    fun testBufferLifecycleAndLeasing() {
        val handle = ReferenceCountedBufferHandle(
            handleId = "buf_001",
            bufferType = BufferType.HARDWARE_BUFFER,
            sizeBytes = 8_294_400L // ~8MB 4K RGBA
        )

        assertEquals(BufferOwnershipState.ACQUIRED, handle.ownershipState)

        val lease1 = handle.acquireLease("NeuralInferenceEngine")
        assertEquals(BufferOwnershipState.PROCESSING, handle.ownershipState)
        assertFalse(lease1.isReleased)

        val lease2 = handle.acquireLease("PreviewRenderer")
        assertEquals(BufferOwnershipState.PROCESSING, handle.ownershipState)

        // Releasing lease 1 does not free the buffer while lease 2 remains active
        lease1.close()
        assertTrue(lease1.isReleased)
        assertEquals(BufferOwnershipState.PROCESSING, handle.ownershipState)

        // Releasing lease 2 transitions buffer to RELEASED
        lease2.close()
        assertTrue(lease2.isReleased)
        assertEquals(BufferOwnershipState.RELEASED, handle.ownershipState)
    }

    @Test(expected = IllegalStateException::class)
    fun testAcquiringLeaseOnReleasedBufferFails() {
        val handle = ReferenceCountedBufferHandle(
            handleId = "buf_002",
            bufferType = BufferType.BYTE_BUFFER,
            sizeBytes = 1024L
        )
        handle.close()
        assertEquals(BufferOwnershipState.RELEASED, handle.ownershipState)
        handle.acquireLease("ShouldFail")
    }
}
