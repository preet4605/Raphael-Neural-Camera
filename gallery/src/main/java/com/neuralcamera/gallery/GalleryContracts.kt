package com.neuralcamera.gallery

data class EncodedOutput(
    val format: String, // "JPEG", "DNG", "HEIF", "ULTRA_HDR"
    val data: ByteArray,
    val width: Int,
    val height: Int,
    val isMaster: Boolean
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EncodedOutput) return false
        return format == other.format && isMaster == other.isMaster && data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = format.hashCode()
        result = 31 * result + data.contentHashCode()
        result = 31 * result + isMaster.hashCode()
        return result
    }
}

data class SavedMediaItem(
    val mediaId: String,
    val originalFilePath: String,
    val masterFilePath: String,
    val metadataFilePath: String,
    val timestampMs: Long,
    val width: Int,
    val height: Int,
    val format: String
)

interface OutputEncoder {
    fun encodeRgbToJpeg(rgbBuffer: ByteArray, width: Int, height: Int, quality: Int = 95): EncodedOutput
    fun encodeRawToDng(rawBuffer: ByteArray, width: Int, height: Int): EncodedOutput
}

interface MediaRepository {
    suspend fun saveMediaBundle(
        mediaId: String,
        originalBytes: ByteArray,
        masterBytes: ByteArray,
        captureMetadataJson: String,
        processingMetadataJson: String,
        format: String
    ): SavedMediaItem

    suspend fun getMediaItem(mediaId: String): SavedMediaItem?
    suspend fun listAllMediaItems(): List<SavedMediaItem>
    suspend fun deleteMediaItem(mediaId: String): Boolean
}
