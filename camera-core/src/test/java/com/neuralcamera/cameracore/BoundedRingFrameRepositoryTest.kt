package com.neuralcamera.cameracore

import com.neuralcamera.deviceprofiles.LensFacing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedRingFrameRepositoryTest {

    private fun createDummyFrame(id: String, seq: Long, size: Int): CameraFrame {
        return CameraFrame(
            frameId = id,
            format = "YUV_420_888",
            width = 10,
            height = 10,
            planes = listOf(FramePlane(ByteArray(size), 1, 10)),
            metadata = FrameMetadata(
                frameSequence = seq,
                timestampNs = seq * 1_000_000L,
                exposureTimeNs = 10_000_000L,
                iso = 100,
                focalLengthMm = 5.0f,
                focusDistanceMeters = 1.0f,
                apertureFNumber = 1.8f,
                lensFacing = LensFacing.BACK_WIDE,
                physicalCameraId = "0",
                sensorOrientation = 90
            )
        )
    }

    @Test
    fun testBoundedCapacityEviction() {
        val repo = BoundedRingFrameRepository(maxCapacity = 3, maxSizeBytes = 1000)
        repo.pushFrame(createDummyFrame("f1", 1, 100))
        repo.pushFrame(createDummyFrame("f2", 2, 100))
        repo.pushFrame(createDummyFrame("f3", 3, 100))
        assertEquals(3, repo.getRecentFrames(10).size)

        // Adding 4th frame should evict f1
        repo.pushFrame(createDummyFrame("f4", 4, 100))
        val recent = repo.getRecentFrames(10)
        assertEquals(3, recent.size)
        assertEquals("f2", recent[0].frameId)
        assertEquals("f4", recent[2].frameId)
        assertEquals(300L, repo.currentResidencyBytes())
    }

    @Test
    fun testByteCeilingEviction() {
        // Limit to 250 bytes max
        val repo = BoundedRingFrameRepository(maxCapacity = 10, maxSizeBytes = 250)
        repo.pushFrame(createDummyFrame("f1", 1, 100))
        repo.pushFrame(createDummyFrame("f2", 2, 100))
        assertEquals(200L, repo.currentResidencyBytes())

        // Adding 100 byte frame pushes total to 300, so f1 is evicted
        repo.pushFrame(createDummyFrame("f3", 3, 100))
        assertTrue(repo.currentResidencyBytes() <= 250)
        assertEquals(2, repo.getRecentFrames(10).size)
    }
}
