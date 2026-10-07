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

    @Test
    fun aFrameLargerThanTheWholeBudgetIsRefusedAndTheRingKept() {
        val repo = BoundedRingFrameRepository(maxCapacity = 10, maxSizeBytes = 250)
        repo.pushFrame(createDummyFrame("f1", 1, 100))
        repo.pushFrame(createDummyFrame("huge", 2, 300))
        assertEquals(listOf("f1"), repo.getRecentFrames(10).map { it.frameId })
        assertEquals(1L, repo.oversizeCount)
        assertEquals(100L, repo.currentResidencyBytes())
    }

    @Test
    fun takingFramesForAShotRemovesTheOnesClosestToThePress() {
        val repo = BoundedRingFrameRepository(maxCapacity = 8, maxSizeBytes = 10_000)
        for (s in 1L..8L) repo.pushFrame(createDummyFrame("f$s", s, 100)) // timestamps 1..8 ms
        val take = repo.takeClosestTo(shutterTimestampNs = 5_400_000L, count = 3)
        assertEquals(listOf("f4", "f5", "f6"), take.frames.map { it.frameId })
        assertEquals(-400_000L, take.nearestOffsetNs)
        // Taken frames leave the ring; later eviction cannot close them.
        assertEquals(listOf("f1", "f2", "f3", "f7", "f8"), repo.getRecentFrames(10).map { it.frameId })
        assertEquals(500L, repo.currentResidencyBytes())
        val rest = repo.takeClosestTo(shutterTimestampNs = 100_000_000L, count = 10)
        assertEquals(5, rest.frames.size)
        assertEquals(0L, repo.currentResidencyBytes())
    }
}
