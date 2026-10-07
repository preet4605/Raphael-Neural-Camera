package com.neuralcamera.gallery

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MediaRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testNonDestructiveOriginalAndMasterSave() {
        runBlocking {
            val repo = OriginalMasterMediaRepository(tempFolder.newFolder("camera_media"))
            val origBytes = byteArrayOf(1, 2, 3, 4)
            val masterBytes = byteArrayOf(5, 6, 7, 8)

            val item = repo.saveMediaBundle(
                mediaId = "test_shot_001",
                originalBytes = origBytes,
                masterBytes = masterBytes,
                captureMetadataJson = """{"iso": 100}""",
                processingMetadataJson = """{"pipeline": "baseline"}""",
                format = "JPG"
            )

            assertNotNull(item)
            val origFile = File(item.originalFilePath)
            val masterFile = File(item.masterFilePath)
            val metaFile = File(item.metadataFilePath)

            assertTrue(origFile.exists())
            assertTrue(masterFile.exists())
            assertTrue(metaFile.exists())

            assertEquals(4, origFile.length())
            assertEquals(4, masterFile.length())
        }
    }

    @Test
    fun savingTheSameIdTwiceFailsAndKeepsTheOriginal() {
        runBlocking {
            val dir = tempFolder.newFolder("dup")
            val repo = OriginalMasterMediaRepository(dir)
            val item = repo.saveMediaBundle("shot", byteArrayOf(1), byteArrayOf(2), "{}", "{}", "jpg")
            val second = runCatching { repo.saveMediaBundle("shot", byteArrayOf(9, 9), byteArrayOf(9), "{}", "{}", "jpg") }
            assertTrue(second.exceptionOrNull() is java.io.IOException)
            assertEquals(1, File(item.originalFilePath).length())
            assertTrue(File(dir, "shot.manifest").exists())
        }
    }

    @Test
    fun testStorageSeparationModelsIntegrity() {
        val original = OriginalCapture(
            captureId = "cap_01",
            format = "DNG",
            rawBytes = byteArrayOf(10, 20),
            width = 4096,
            height = 3072,
            timestampNs = 123456789L
        )

        val master = NeuralMaster(
            masterId = "master_01",
            format = "JPEG",
            encodedBytes = byteArrayOf(30, 40),
            width = 4096,
            height = 3072,
            confidenceScore = 0.98f
        )

        val metadata = CaptureStorageMetadata(
            mediaId = "media_01",
            iso = 100,
            exposureTimeNs = 10_000_000L,
            focalLengthMm = 5.59f,
            lensFacing = "BACK_WIDE",
            neuralAccelerationUsed = true,
            appliedModelId = "neural-isp-lite-v1",
            realityGuardPassed = true
        )

        assertEquals("cap_01", original.captureId)
        assertEquals("master_01", master.masterId)
        assertTrue(metadata.neuralAccelerationUsed)
        assertTrue(metadata.realityGuardPassed)
    }
}
