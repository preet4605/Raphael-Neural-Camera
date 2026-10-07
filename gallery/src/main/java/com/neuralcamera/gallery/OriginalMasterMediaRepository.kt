package com.neuralcamera.gallery

import com.neuralcamera.gallery.storage.AtomicMediaStore
import com.neuralcamera.gallery.storage.BundlePart
import com.neuralcamera.models.execution.StageOutcome
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

class OriginalMasterMediaRepository(
    private val storageDir: File
) : MediaRepository {

    private val index = ConcurrentHashMap<String, SavedMediaItem>()

    /** Original, master and metadata are committed together or not at all (see AtomicMediaStore). */
    private val store = AtomicMediaStore(storageDir)

    init {
        store.recover() // removes temp files and bundles a crash left uncommitted
    }

    override suspend fun saveMediaBundle(
        mediaId: String,
        originalBytes: ByteArray,
        masterBytes: ByteArray,
        captureMetadataJson: String,
        processingMetadataJson: String,
        format: String
    ): SavedMediaItem {
        // AI Studio outputs live in StudioStore; a Studio id can never become a camera bundle.
        require(!mediaId.startsWith(com.neuralcamera.gallery.studio.StudioStore.ID_PREFIX)) { "$mediaId is a Studio id; camera storage refuses it" }
        val originalFile = File(storageDir, "${mediaId}_ORIGINAL.$format")
        val masterFile = File(storageDir, "${mediaId}_MASTER.$format")
        val metaFile = File(storageDir, "${mediaId}_META.json")

        val combinedMeta = """
            {
               "media_id": "$mediaId",
               "capture": $captureMetadataJson,
               "processing": $processingMetadataJson
            }
        """.trimIndent()
        // Never overwrite or lose the original: the store refuses existing names and commits all parts atomically.
        val outcome = store.commit(
            mediaId,
            listOf(
                BundlePart("original", originalFile.name, originalBytes),
                BundlePart("master", masterFile.name, masterBytes),
                BundlePart("metadata", metaFile.name, combinedMeta.toByteArray())
            ),
            manifestJson = "{}"
        )
        if (outcome is StageOutcome.Failed) throw IOException("saving $mediaId failed: ${outcome.reason}", outcome.cause)

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
        store.delete(mediaId, listOf(item.originalFilePath, item.masterFilePath, item.metadataFilePath).map { File(it).name })
        return true
    }
}
