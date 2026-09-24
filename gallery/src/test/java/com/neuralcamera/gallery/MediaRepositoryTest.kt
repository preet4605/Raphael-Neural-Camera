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
}
