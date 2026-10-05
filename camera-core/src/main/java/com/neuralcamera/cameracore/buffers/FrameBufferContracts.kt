package com.neuralcamera.cameracore.buffers

/**
 * Supported underlying memory buffer representations (Section 8).
 */
enum class BufferType {
    IMAGE,                  // android.media.Image (Camera2 ImageReader)
    HARDWARE_BUFFER,        // android.hardware.HardwareBuffer (zero-copy GPU/NPU interop)
    SURFACE,                // android.view.Surface
    NATIVE_DIRECT_BUFFER,   // JNI direct native memory allocation
    BYTE_BUFFER             // JVM heap or direct byte array fallback
}

/**
 * Lifecycle state of a memory buffer.
 */
enum class BufferOwnershipState {
    ACQUIRED,
    PROCESSING,
    RELEASED
}

/**
 * FrameLease represents temporary borrowed ownership of an image buffer.
 * When closed, the reference count is decremented.
 */
interface FrameBufferLease : AutoCloseable {
    val handleId: String
    val consumerTag: String
    val isReleased: Boolean
    override fun close()
}

/**
 * Handle representing a low-level memory frame buffer with explicit ownership contracts.
 */
interface FrameBufferHandle : AutoCloseable {
    val handleId: String
    val bufferType: BufferType
    val sizeBytes: Long
    val ownershipState: BufferOwnershipState
    val isReusable: Boolean
    val canCrossThreadBoundaries: Boolean

    /**
     * Borrows the buffer with a lease. Buffer cannot be freed or reused until all leases are closed.
     */
    fun acquireLease(consumerTag: String): FrameBufferLease

    /**
     * Releases underlying memory immediately if no active leases remain.
     */
    override fun close()
}

/**
 * In-memory reference counted frame buffer implementation for safe testing and runtime validation.
 */
class ReferenceCountedBufferHandle(
    override val handleId: String,
    override val bufferType: BufferType,
    override val sizeBytes: Long,
    override val isReusable: Boolean = false,
    override val canCrossThreadBoundaries: Boolean = true
) : FrameBufferHandle {

    private val activeLeases = mutableSetOf<String>()
    private var _ownershipState = BufferOwnershipState.ACQUIRED
    override val ownershipState: BufferOwnershipState
        get() = _ownershipState

    @Synchronized
    override fun acquireLease(consumerTag: String): FrameBufferLease {
        check(_ownershipState != BufferOwnershipState.RELEASED) {
            "Cannot acquire lease on already released buffer $handleId"
        }
        _ownershipState = BufferOwnershipState.PROCESSING
        activeLeases.add(consumerTag)

        return object : FrameBufferLease {
            private var released = false
            override val handleId: String = this@ReferenceCountedBufferHandle.handleId
            override val consumerTag: String = consumerTag
            override val isReleased: Boolean get() = released

            override fun close() {
                synchronized(this@ReferenceCountedBufferHandle) {
                    if (!released) {
                        released = true
                        activeLeases.remove(consumerTag)
                        if (activeLeases.isEmpty()) {
                            _ownershipState = BufferOwnershipState.RELEASED
                        }
                    }
                }
            }
        }
    }

    @Synchronized
    override fun close() {
        activeLeases.clear()
        _ownershipState = BufferOwnershipState.RELEASED
    }
}
