package com.neuralcamera.cameracore

import com.neuralcamera.cameracore.orchestration.FramePrioritizer
import com.neuralcamera.cameracore.orchestration.FrameRef
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong

/** Frames taken out of the ring for a shot; the caller now owns them (and must close their buffers). */
data class ZslTake(
    val frames: List<CameraFrame>,
    val shutterTimestampNs: Long,
    /** Sensor timestamp of the taken frame nearest the press minus the press time; null when nothing was taken. */
    val nearestOffsetNs: Long?
)

/**
 * Zero-shutter-lag ring: the newest frames within a count and byte budget. Older frames are evicted and their buffers
 * closed; a frame larger than the whole byte budget is refused (closed) rather than allowed to exceed it. Frames handed
 * out by [takeClosestTo] leave the ring, so eviction can never close a buffer a shot is still processing.
 */
class BoundedRingFrameRepository(
    val maxCapacity: Int = 16,
    val maxSizeBytes: Long = 256 * 1024 * 1024L // 256 MB default ceiling
) : FrameRepository {

    private val deque = ConcurrentLinkedDeque<CameraFrame>()
    @Volatile private var totalBytes: Long = 0L

    private val _acquiredCount = AtomicLong(0)
    private val _closedCount = AtomicLong(0)
    private val _droppedCount = AtomicLong(0)
    private val _oversizeCount = AtomicLong(0)

    val acquiredCount: Long get() = _acquiredCount.get()
    val closedCount: Long get() = _closedCount.get()
    val droppedCount: Long get() = _droppedCount.get()
    /** Frames refused because a single frame was larger than [maxSizeBytes]. */
    val oversizeCount: Long get() = _oversizeCount.get()
    val queueDepth: Int get() = deque.size

    @Synchronized
    override fun pushFrame(frame: CameraFrame) {
        _acquiredCount.incrementAndGet()
        val frameBytes = frame.sizeBytes
        if (frameBytes > maxSizeBytes) {
            frame.bufferHandle?.close()
            _closedCount.incrementAndGet()
            _oversizeCount.incrementAndGet()
            return
        }

        // Evict if capacity or size exceeded
        while (deque.size >= maxCapacity || (totalBytes + frameBytes > maxSizeBytes && deque.isNotEmpty())) {
            val evicted = deque.pollFirst()
            if (evicted != null) {
                totalBytes -= evicted.sizeBytes
                _droppedCount.incrementAndGet()
                evicted.bufferHandle?.close()
                _closedCount.incrementAndGet()
            }
        }

        deque.addLast(frame)
        totalBytes += frameBytes
    }

    override fun getLatestFrame(): CameraFrame? = deque.peekLast()

    override fun getRecentFrames(count: Int): List<CameraFrame> {
        val list = deque.toList()
        return list.takeLast(count)
    }

    /**
     * Removes and returns up to [count] frames whose sensor timestamps are closest to [shutterTimestampNs] (the press,
     * on the sensor clock), in timestamp order. Frames not taken stay in the ring.
     */
    @Synchronized
    fun takeClosestTo(shutterTimestampNs: Long, count: Int): ZslTake {
        require(count >= 1) { "count must be at least one" }
        val all = deque.toList()
        val refs = all.mapIndexed { i, f -> FrameRef(i, f.metadata.timestampNs, f.metadata.frameSequence) }
        val chosen = FramePrioritizer.closestToShutter(refs, shutterTimestampNs, count).map { all[it.requestIndex] }
        chosen.forEach { f -> if (deque.removeFirstOccurrence(f)) totalBytes -= f.sizeBytes }
        val nearest = chosen.minByOrNull { kotlin.math.abs(it.metadata.timestampNs - shutterTimestampNs) }
        return ZslTake(chosen, shutterTimestampNs, nearest?.let { it.metadata.timestampNs - shutterTimestampNs })
    }

    @Synchronized
    override fun clear() {
        while (deque.isNotEmpty()) {
            val frame = deque.pollFirst()
            if (frame != null) {
                frame.bufferHandle?.close()
                _closedCount.incrementAndGet()
            }
        }
        totalBytes = 0L
    }

    override fun currentResidencyBytes(): Long = totalBytes
}

class InMemoryMetadataRepository : MetadataRepository {
    private val map = ConcurrentHashMap<Long, FrameMetadata>()

    override fun recordMetadata(metadata: FrameMetadata) {
        map[metadata.frameSequence] = metadata
        if (map.size > 200) {
            // Trim oldest entries
            val oldestKey = map.keys().toList().minOrNull()
            if (oldestKey != null) {
                map.remove(oldestKey)
            }
        }
    }

    override fun getMetadata(frameSequence: Long): FrameMetadata? = map[frameSequence]

    override fun clear() {
        map.clear()
    }
}
