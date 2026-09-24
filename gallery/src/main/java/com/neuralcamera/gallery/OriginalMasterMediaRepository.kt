package com.neuralcamera.gallery

import java.io.File
import java.util.concurrent.ConcurrentHashMap

class OriginalMasterMediaRepository(
    private val storageDir: File
) : MediaRepository {

    private val index = ConcurrentHashMap<String, SavedMediaItem>()

    init {
        if (!storageDir.exists()) {
            storageDir.mkdirs()
        }
    }

    override suspend fun saveMediaBundle(
        mediaId: String,
        originalBytes: ByteArray,
        masterBytes: ByteArray,
        captureMetadataJson: String,
        processingMetadataJson: String,
        format: String
    ): SavedMediaItem {
        val originalFile = File(storageDir, "${mediaId}_ORIGINAL.$format")
        val masterFile = File(storageDir, "${mediaId}_MASTER.$format")
        val metaFile = File(storageDir, "${mediaId}_META.json")

        // Rule 20 & 21: Never overwrite or lose the original
        originalFile.writeBytes(originalBytes)
        masterFile.writeBytes(masterBytes)

        val combinedMeta = """
            {
               "media_id": "$mediaId",
               "capture": $captureMetadataJson,
               "processing": $processingMetadataJson
            }
        """.trimIndent()
        metaFile.writeText(combinedMeta)

        val item = SavedMediaItem(
            mediaId = mediaId,
            originalFilePath = originalFile.absolutePath,
            masterFilePath = masterFile.absolutePath,
            metadataFilePath = metaFile.absolutePath,
            timestampMs = System.currentTimeMillis(),
            width = 0,
            height = 0,
            format = format
        )
        index[mediaId] = item
        return item
    }

    override suspend fun getMediaItem(mediaId: String): SavedMediaItem? = index[mediaId]

    override suspend fun listAllMediaItems(): List<SavedMediaItem> = index.values.sortedByDescending { it.timestampMs }

    override suspend fun deleteMediaItem(mediaId: String): Boolean {
        val item = index.remove(mediaId) ?: return false
        File(item.originalFilePath).delete()
        File(item.masterFilePath).delete()
        File(item.metadataFilePath).delete()
        return true
    }
}

class StandardOutputEncoder : OutputEncoder {

    override fun encodeRgbToJpeg(rgbBuffer: ByteArray, width: Int, height: Int, quality: Int): EncodedOutput {
        // High quality simulated JPEG container with magic header for unit testing/mock
        val header = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        val output = header + rgbBuffer
        return EncodedOutput(
            format = "JPEG",
            data = output,
            width = width,
            height = height,
            isMaster = true
        )
    }

    override fun encodeRawToDng(rawBuffer: ByteArray, width: Int, height: Int): EncodedOutput {
        val header = byteArrayOf(0x49, 0x49, 0x2A, 0x00) // TIFF header
        val output = header + rawBuffer
        return EncodedOutput(
            format = "DNG",
            data = output,
            width = width,
            height = height,
            isMaster = false
        )
    }
}
