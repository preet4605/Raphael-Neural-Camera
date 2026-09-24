package com.neuralcamera.cameracore

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque

class BoundedRingFrameRepository(
    private val maxCapacity: Int = 16,
    private val maxSizeBytes: Long = 256 * 1024 * 1024L // 256 MB default ceiling
) : FrameRepository {

    private val deque = ConcurrentLinkedDeque<CameraFrame>()
    private var totalBytes: Long = 0L

    @Synchronized
    override fun pushFrame(frame: CameraFrame) {
        val frameBytes = frame.sizeBytes

        // Evict if capacity or size exceeded
        while (deque.size >= maxCapacity || (totalBytes + frameBytes > maxSizeBytes && deque.isNotEmpty())) {
            val evicted = deque.pollFirst()
            if (evicted != null) {
                totalBytes -= evicted.sizeBytes
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

    @Synchronized
    override fun clear() {
        deque.clear()
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
