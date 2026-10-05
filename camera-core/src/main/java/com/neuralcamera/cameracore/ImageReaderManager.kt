package com.neuralcamera.cameracore

import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.view.Surface
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Interface representing an image handle that can be associated with capture metadata.
 */
interface AssociableImage : AutoCloseable {
    val timestampNs: Long
    val isClosed: Boolean
    override fun close()
}

/**
 * Manages production-safe ImageReader lifecycle and backpressure adhering to Section 14.
 * Enforces strict reference accounting and guaranteed image closure to prevent camera HAL deadlock.
 */
class ImageReaderManager(
    val width: Int,
    val height: Int,
    val format: Int,
    val maxImages: Int = 4,
    private val handler: Handler? = null
) : AutoCloseable {

    private val reader: ImageReader = ImageReader.newInstance(width, height, format, maxImages)
    val surface: Surface get() = reader.surface

    private val _acquiredCount = AtomicLong(0)
    private val _closedCount = AtomicLong(0)
    private val _droppedCount = AtomicLong(0)
    private val _outstandingCount = AtomicInteger(0)

    val acquiredCount: Long get() = _acquiredCount.get()
    val closedCount: Long get() = _closedCount.get()
    val droppedCount: Long get() = _droppedCount.get()
    val outstandingCount: Int get() = _outstandingCount.get()
    val queueDepth: Int get() = maxImages

    @Volatile
    private var isClosed = false

    /**
     * Sets listener for frame arrivals with backpressure and immediate closure fallback.
     */
    fun setOnImageAvailableListener(
        onImageAvailable: (SafeImageHandle) -> Unit
    ) {
        reader.setOnImageAvailableListener({ ir ->
            if (isClosed) return@setOnImageAvailableListener

            // If outstanding images approach max capacity, drop oldest/latest to prevent HAL buffer starvation
            if (_outstandingCount.get() >= maxImages - 1) {
                try {
                    val dropped = ir.acquireLatestImage()
                    dropped?.close()
                    _droppedCount.incrementAndGet()
                } catch (e: Exception) {
                    // Ignore dropped frame acquisition errors
                }
                return@setOnImageAvailableListener
            }

            val image: Image? = try {
                ir.acquireLatestImage()
            } catch (e: Exception) {
                null
            }

            if (image != null) {
                _acquiredCount.incrementAndGet()
                _outstandingCount.incrementAndGet()
                val safeHandle = SafeImageHandle(image) {
                    _closedCount.incrementAndGet()
                    _outstandingCount.decrementAndGet()
                }
                try {
                    onImageAvailable(safeHandle)
                } catch (e: Exception) {
                    // Always close image if consumer throws
                    safeHandle.close()
                }
            }
        }, handler)
    }

    override fun close() {
        isClosed = true
        try {
            reader.close()
        } catch (e: Exception) {
            // Safe close
        }
    }
}

/**
 * AutoCloseable wrapper for android.media.Image ensuring single close invocation.
 */
class SafeImageHandle(
    val image: Image,
    private val onCloseCallback: () -> Unit
) : AssociableImage {

    @Volatile
    private var closed = false

    override val timestampNs: Long
        get() = try { image.timestamp } catch (e: Throwable) { 0L }

    override val isClosed: Boolean get() = closed

    override fun close() {
        if (!closed) {
            synchronized(this) {
                if (!closed) {
                    closed = true
                    try {
                        image.close()
                    } catch (e: Exception) {
                        // Suppress already closed exception
                    }
                    onCloseCallback()
                }
            }
        }
    }
}
