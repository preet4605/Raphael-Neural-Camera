package com.neuralcamera.cameracore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class CaptureResultAssociatorTest {

    private class TestAssociableImage(
        override val timestampNs: Long,
        private val onClose: () -> Unit = {}
    ) : AssociableImage {
        private var _isClosed = false
        override val isClosed: Boolean get() = _isClosed

        override fun close() {
            _isClosed = true
            onClose()
        }
    }

    @Test
    fun testExactMatchingPairing() {
        val associator = CaptureResultAssociator()
        val associated = AtomicBoolean(false)

        val image = TestAssociableImage(100_000_000L)
        val dummyResult = "Result_100ms"

        // Image arrives first
        associator.onImageArrived(image) { matchedImg, matchedRes ->
            assertEquals(100_000_000L, matchedImg.timestampNs)
            assertEquals("Result_100ms", matchedRes)
            associated.set(true)
        }

        assertEquals(0, associator.associatedCount)

        // Result arrives later with same timestamp
        associator.onCaptureResultArrived(100_000_000L, dummyResult) { matchedImg, matchedRes ->
            assertEquals(100_000_000L, matchedImg.timestampNs)
            assertEquals("Result_100ms", matchedRes)
            associated.set(true)
        }

        assertTrue(associated.get())
        assertEquals(1, associator.associatedCount)
    }

    @Test
    fun testResultArrivesBeforeImage() {
        val associator = CaptureResultAssociator()
        val associated = AtomicBoolean(false)

        val image = TestAssociableImage(200_000_000L)
        val dummyResult = "Result_200ms"

        // Result arrives first
        associator.onCaptureResultArrived(200_000_000L, dummyResult) { _, _ -> }

        assertEquals(0, associator.associatedCount)

        // Image arrives later
        associator.onImageArrived(image) { matchedImg, matchedRes ->
            assertEquals(200_000_000L, matchedImg.timestampNs)
            assertEquals("Result_200ms", matchedRes)
            associated.set(true)
        }

        assertTrue(associated.get())
        assertEquals(1, associator.associatedCount)
    }

    @Test
    fun testPrunesStaleImagesWithoutLeaks() {
        val associator = CaptureResultAssociator(maxPendingWindow = 2, timeoutMs = 10L)
        val image1Closed = AtomicBoolean(false)
        val image2Closed = AtomicBoolean(false)

        val img1 = TestAssociableImage(100L) { image1Closed.set(true) }
        val img2 = TestAssociableImage(200L) { image2Closed.set(true) }

        associator.onImageArrived(img1) { _, _ -> }
        associator.onImageArrived(img2) { _, _ -> }

        // Sleep to let them become stale
        Thread.sleep(25L)

        // Arrive another image which triggers pruning
        val img3 = TestAssociableImage(300L)
        associator.onImageArrived(img3) { _, _ -> }

        assertTrue("Stale images should be closed automatically", image1Closed.get())
    }
}
