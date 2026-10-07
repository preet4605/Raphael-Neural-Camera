package com.neuralcamera.cameracore

/**
 * Record documenting an actual or potential memory copy in the camera pipeline. [measured] is true only for records
 * built from counted bytes ([ZeroCopyAuditor.measuredBurstCopy]); [ZeroCopyAuditor.auditPipeline] records are design
 * expectations.
 */
data class BufferCopyRecord(
    val stage: String,
    val source: String,
    val destination: String,
    val bytesMoved: Long,
    val frequency: String, // e.g. "Per Frame", "Per Still Capture", "Zero"
    val isAvoidable: Boolean,
    val reason: String,
    val latencyImpactMs: Float,
    val measured: Boolean = false
)

/**
 * Audit result evaluating the zero-copy pipeline architecture and physical implementation.
 */
data class ZeroCopyAuditReport(
    /** Whether the *design* avoids per-frame CPU copies: "NO_COPIES_EXPECTED" or "COPIES_EXPECTED". Not evidence. */
    val zeroCopyDesignStatus: String,
    /** Always "NOT_MEASURED" until a device trace measures buffer movement. Zero-copy is NOT_PROVEN. */
    val actualHardwarePathStatus: String,
    val copies: List<BufferCopyRecord>,
    val totalPerFrameCopiedBytes: Long,
    val summary: String
)

/**
 * Zero-copy design audit. It lists the buffer movements the design expects for a configuration; it measures nothing.
 * The real burst path copies every YUV frame into the JVM heap (CopiedYuv), and no HardwareBuffer/Vulkan/QNN path
 * exists, so the zero-copy claim stays NOT_PROVEN regardless of what this report lists.
 */
object ZeroCopyAuditor {

    /**
     * A measured record of the YUV burst path: [bytes] actually copied from camera Image planes into JVM arrays for
     * [images] images (all attempts, including frames later dropped). This is the one copy the app can count today;
     * it proves copies exist on this path, not the absence of others.
     */
    fun measuredBurstCopy(bytes: Long, images: Int): BufferCopyRecord? {
        require(bytes >= 0 && images >= 0) { "counts cannot be negative" }
        if (images == 0) return null
        return BufferCopyRecord(
            stage = "Camera ImageReader -> JVM heap (CopiedYuv)",
            source = "android.media.Image planes",
            destination = "JVM ByteArray (FramePlane)",
            bytesMoved = bytes,
            frequency = "Per burst ($images images)",
            isAvoidable = true,
            reason = "The burst path copies every YUV image so the camera buffer can be returned at once.",
            latencyImpactMs = Float.NaN, // not timed
            measured = true
        )
    }

    fun auditPipeline(
        previewWidth: Int = 1920,
        previewHeight: Int = 1080,
        stillWidth: Int = 4000,
        stillHeight: Int = 3000,
        useHardwareBuffer: Boolean = true
    ): ZeroCopyAuditReport {
        val copies = mutableListOf<BufferCopyRecord>()

        // 1. Camera HAL -> SurfaceView (Preview Path)
        // Direct HardwareComposer overlay: zero-copy
        copies.add(
            BufferCopyRecord(
                stage = "Camera HAL -> SurfaceView Preview",
                source = "Camera HAL3 BufferQueue",
                destination = "SurfaceFlinger Hardware Composer Plane",
                bytesMoved = 0L,
                frequency = "Per Frame (60 FPS)",
                isAvoidable = false,
                reason = "Hardware direct scanout via Surface. Zero CPU/GPU copy required.",
                latencyImpactMs = 0.0f
            )
        )

        // 2. Camera HAL -> ImageReader HardwareBuffer (Zero-Copy GPU/NPU Path)
        if (useHardwareBuffer) {
            copies.add(
                BufferCopyRecord(
                    stage = "Camera HAL -> HardwareBuffer",
                    source = "Camera HAL GraphicBuffer",
                    destination = "android.hardware.HardwareBuffer (AHardwareBuffer)",
                    bytesMoved = 0L,
                    frequency = "Per Frame (30 FPS)",
                    isAvoidable = false,
                    reason = "HardwareBuffer directly mapped into Vulkan VkDeviceMemory or Qualcomm QNN RPC memory. Zero CPU copy.",
                    latencyImpactMs = 0.0f
                )
            )
        } else {
            val yuvBytes = (previewWidth * previewHeight * 1.5).toLong()
            copies.add(
                BufferCopyRecord(
                    stage = "Camera HAL -> JVM Heap Planes",
                    source = "android.media.Image.Plane ByteBuffer",
                    destination = "JVM ByteArray (FramePlane)",
                    bytesMoved = yuvBytes,
                    frequency = "Per Frame (30 FPS)",
                    isAvoidable = true,
                    reason = "JVM array allocation for testing and fallback inspection. Forced by non-JNI callers.",
                    latencyImpactMs = 2.1f
                )
            )
        }

        // 3. Still Capture: Camera HAL -> JPEG / RAW ImageReader
        // Compressed JPEG is written directly by camera ISP into ImageReader byte buffer:
        val jpegEstBytes = 3_500_000L // ~3.5 MB
        copies.add(
            BufferCopyRecord(
                stage = "Camera ISP -> JPEG ImageReader",
                source = "Qualcomm Spectra ISP Hardware Encoder",
                destination = "ImageReader ByteBuffer",
                bytesMoved = 0L,
                frequency = "Per Still Capture",
                isAvoidable = false,
                reason = "ISP hardware JPEG encoder DMA transfer directly into gralloc buffer. Zero CPU copy.",
                latencyImpactMs = 0.0f
            )
        )

        // 4. Disk IO: Memory -> MediaStore FileDescriptor
        // Must write bytes to disk storage:
        copies.add(
            BufferCopyRecord(
                stage = "Memory -> Disk Storage (MediaStore)",
                source = "Direct ByteBuffer / Memory Mapped File",
                destination = "Linux VFS Page Cache / UFS 4.0 Storage",
                bytesMoved = jpegEstBytes,
                frequency = "Per Saved Photo",
                isAvoidable = false,
                reason = "Persistence requires transferring memory pages to OS file system and NAND flash.",
                latencyImpactMs = 4.5f
            )
        )

        val totalCopied = copies.filter { it.frequency.contains("Per Frame") }.sumOf { it.bytesMoved }
        val design = if (totalCopied == 0L) "NO_COPIES_EXPECTED" else "COPIES_EXPECTED"

        val summary = if (totalCopied == 0L) {
            "Design expectation only: no per-frame CPU copy is expected in this configuration. Not measured; zero-copy NOT_PROVEN."
        } else {
            "Design expectation only: about ${totalCopied / 1024} KB per frame is expected to be copied into JVM FramePlanes. Not measured."
        }

        return ZeroCopyAuditReport(
            zeroCopyDesignStatus = design,
            actualHardwarePathStatus = "NOT_MEASURED",
            copies = copies,
            totalPerFrameCopiedBytes = totalCopied,
            summary = summary
        )
    }
}
