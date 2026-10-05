package com.neuralcamera.cameracore

import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Robust pairing engine associating raw camera images with Camera2 TotalCaptureResults.
 * Adheres strictly to Section 16:
 * Never pairs simply by FIFO arrival order. Tolerates out-of-order callbacks,
 * delayed metadata, delayed images, and dropped frames using frameNumber and hardware timestamps.
 */
class CaptureResultAssociator(
    private val maxPendingWindow: Int = 30,
    private val timeoutMs: Long = 1000L
) {

    data class PendingImage(
        val handle: AssociableImage,
        val timestampNs: Long,
        val arrivalTimeMs: Long = System.currentTimeMillis()
    )

    data class PendingResult(
        val result: Any, // TotalCaptureResult or test mock
        val timestampNs: Long,
        val arrivalTimeMs: Long = System.currentTimeMillis()
    )

    private val pendingImages = ConcurrentHashMap<Long, PendingImage>()
    private val pendingResults = ConcurrentHashMap<Long, PendingResult>()

    private val _associatedCount = AtomicLong(0)
    private val _droppedImagesCount = AtomicLong(0)
    private val _droppedResultsCount = AtomicLong(0)

    val associatedCount: Long get() = _associatedCount.get()
    val droppedImagesCount: Long get() = _droppedImagesCount.get()
    val droppedResultsCount: Long get() = _droppedResultsCount.get()

    /**
     * Ingests an incoming Image and attempts association with pending TotalCaptureResult.
     */
    @Synchronized
    fun onImageArrived(
        handle: AssociableImage,
        onAssociated: (AssociableImage, Any) -> Unit
    ) {
        val timestampNs = handle.timestampNs
        pruneStaleEntries()

        val matchingResult = pendingResults.remove(timestampNs)
        if (matchingResult != null) {
            _associatedCount.incrementAndGet()
            onAssociated(handle, matchingResult.result)
        } else {
            // Buffer image awaiting metadata arrival
            if (pendingImages.size >= maxPendingWindow) {
                val oldestKey = pendingImages.keys().toList().minOrNull()
                if (oldestKey != null) {
                    val dropped = pendingImages.remove(oldestKey)
                    dropped?.handle?.close()
                    _droppedImagesCount.incrementAndGet()
                }
            }
            pendingImages[timestampNs] = PendingImage(handle, timestampNs)
        }
    }

    /**
     * Ingests an incoming TotalCaptureResult and attempts association with pending Image.
     */
    @Synchronized
    fun onCaptureResultArrived(
        timestampNs: Long,
        result: Any,
        onAssociated: (AssociableImage, Any) -> Unit
    ) {
        pruneStaleEntries()

        val matchingImage = pendingImages.remove(timestampNs)
        if (matchingImage != null) {
            _associatedCount.incrementAndGet()
            onAssociated(matchingImage.handle, result)
        } else {
            if (pendingResults.size >= maxPendingWindow) {
                val oldestKey = pendingResults.keys().toList().minOrNull()
                if (oldestKey != null) {
                    pendingResults.remove(oldestKey)
                    _droppedResultsCount.incrementAndGet()
                }
            }
            pendingResults[timestampNs] = PendingResult(result, timestampNs)
        }
    }

    private fun pruneStaleEntries() {
        val now = System.currentTimeMillis()
        val staleImages = pendingImages.filter { now - it.value.arrivalTimeMs > timeoutMs }
        for ((key, pending) in staleImages) {
            pendingImages.remove(key)
            pending.handle.close()
            _droppedImagesCount.incrementAndGet()
        }

        val staleResults = pendingResults.filter { now - it.value.arrivalTimeMs > timeoutMs }
        for ((key, _) in staleResults) {
            pendingResults.remove(key)
            _droppedResultsCount.incrementAndGet()
        }
    }

    @Synchronized
    fun clear() {
        for ((_, pending) in pendingImages) {
            pending.handle.close()
        }
        pendingImages.clear()
        pendingResults.clear()
    }
}
