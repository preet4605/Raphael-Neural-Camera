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

/**
 * Original sensor capture preserving untampered raw bytes (Section 18).
 */
data class OriginalCapture(
    val captureId: String,
    val format: String, // "DNG", "RAW_SENSOR", "UNPROCESSED_JPEG"
    val rawBytes: ByteArray,
    val width: Int,
    val height: Int,
    val timestampNs: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OriginalCapture) return false
        return captureId == other.captureId && format == other.format && rawBytes.contentEquals(other.rawBytes)
    }

    override fun hashCode(): Int {
        var result = captureId.hashCode()
        result = 31 * result + format.hashCode()
        result = 31 * result + rawBytes.contentHashCode()
        return result
    }
}

/**
 * Standard processed image output prior to neural master post-grading (Section 18).
 */
data class ProcessedImage(
    val imageId: String,
    val rgbBytes: ByteArray,
    val width: Int,
    val height: Int,
    val appliedPipeline: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ProcessedImage) return false
        return imageId == other.imageId && rgbBytes.contentEquals(other.rgbBytes)
    }

    override fun hashCode(): Int {
        var result = imageId.hashCode()
        result = 31 * result + rgbBytes.contentHashCode()
        return result
    }
}

/**
 * High-fidelity neural master image output (Section 18).
 */
data class NeuralMaster(
    val masterId: String,
    val format: String, // "JPEG", "HEIF", "ULTRA_HDR"
    val encodedBytes: ByteArray,
    val width: Int,
    val height: Int,
    val confidenceScore: Float
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NeuralMaster) return false
        return masterId == other.masterId && format == other.format && encodedBytes.contentEquals(other.encodedBytes)
    }

    override fun hashCode(): Int {
        var result = masterId.hashCode()
        result = 31 * result + format.hashCode()
        result = 31 * result + encodedBytes.contentHashCode()
        return result
    }
}

/**
 * Rich capture and processing metadata (Section 18).
 */
data class CaptureStorageMetadata(
    val mediaId: String,
    val iso: Int,
    val exposureTimeNs: Long,
    val focalLengthMm: Float,
    val lensFacing: String,
    val neuralAccelerationUsed: Boolean,
    val appliedModelId: String?,
    val realityGuardPassed: Boolean
)

/**
 * Temporary scratch data allocated during multi-frame pipeline fusion (Section 18).
 */
data class TemporaryProcessingData(
    val sessionId: String,
    val intermediateTensorBytes: Long,
    val tempFilePaths: List<String> = emptyList(),
    val isCleanedUp: Boolean = false
)

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

/**
 * Contract for real image encoders. No implementation exists yet; an implementation must emit
 * decodable JPEG / valid DNG, never a header prepended to raw bytes.
 */
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
