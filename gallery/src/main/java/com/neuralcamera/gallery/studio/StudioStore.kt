package com.neuralcamera.gallery.studio

import com.neuralcamera.gallery.provenance.ProvenanceRecord
import com.neuralcamera.gallery.storage.AtomicMediaStore
import com.neuralcamera.gallery.storage.BundlePart
import com.neuralcamera.models.execution.ContentOrigin
import com.neuralcamera.models.execution.PipelinePath
import com.neuralcamera.models.execution.StageOutcome
import java.io.File
import java.io.IOException

data class StudioItem(val studioId: String, val imageFile: File, val metadataFile: File, val sourceMediaId: String?)

/**
 * AI Studio outputs, kept apart from camera captures: their own directory (never the camera directory or inside it),
 * their own id namespace ([ID_PREFIX], which the camera repository refuses), and metadata that marks every output
 * GENERATED on the AI_STUDIO path with the model that produced it and the capture it started from. Studio only reads
 * camera media; nothing here can write into a camera bundle.
 */
class StudioStore(private val studioDir: File, cameraDir: File) {
    private val store: AtomicMediaStore

    init {
        val s = studioDir.canonicalFile
        val c = cameraDir.canonicalFile
        require(s != c && !s.path.startsWith(c.path + File.separator) && !c.path.startsWith(s.path + File.separator)) {
            "Studio storage $s must be separate from camera storage $c"
        }
        store = AtomicMediaStore(studioDir)
        store.recover()
    }

    fun save(
        studioId: String,
        image: ByteArray,
        format: String,
        modelId: String,
        modelVersion: String,
        sourceMediaId: String?,
        parametersJson: String = "{}"
    ): StudioItem {
        require(studioId.startsWith(ID_PREFIX)) { "Studio ids start with $ID_PREFIX" }
        val imageName = "${studioId}_GENERATED.$format"
        val metaName = "${studioId}_META.json"
        val meta = """{"studio_id": ${ProvenanceRecord.q(studioId)}, "pipeline": "${PipelinePath.AI_STUDIO}", """ +
            """"origin": "${ContentOrigin.GENERATED}", "model": ${ProvenanceRecord.q(modelId)}, "model_version": ${ProvenanceRecord.q(modelVersion)}, """ +
            """"source_media_id": ${sourceMediaId?.let { ProvenanceRecord.q(it) } ?: "null"}, "parameters": $parametersJson}"""
        val outcome = store.commit(studioId, listOf(BundlePart("generated", imageName, image), BundlePart("metadata", metaName, meta.toByteArray())), "{}")
        if (outcome is StageOutcome.Failed) throw IOException("saving $studioId failed: ${outcome.reason}", outcome.cause)
        return StudioItem(studioId, File(studioDir, imageName), File(studioDir, metaName), sourceMediaId)
    }

    companion object {
        const val ID_PREFIX = "studio_"
    }
}
