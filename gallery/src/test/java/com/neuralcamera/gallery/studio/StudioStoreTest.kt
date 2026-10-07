package com.neuralcamera.gallery.studio

import com.neuralcamera.gallery.OriginalMasterMediaRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StudioStoreTest {
    @get:Rule
    val temp = TemporaryFolder()
    private val root: File get() = temp.root
    private val cameraDir: File get() = File(root, "camera")
    private val studioDir: File get() = File(root, "studio")

    private fun refuses(block: () -> Unit) = try { block(); false } catch (e: IllegalArgumentException) { true }

    @Test
    fun studioStorageMustBeSeparateFromCameraStorage() {
        assertTrue(refuses { StudioStore(cameraDir, cameraDir) })
        assertTrue(refuses { StudioStore(File(cameraDir, "studio"), cameraDir) })
        assertTrue(refuses { StudioStore(root, cameraDir) }) // camera inside studio
        StudioStore(studioDir, cameraDir)
    }

    @Test
    fun outputsAreGeneratedAndNeverTouchCameraBundles() = runBlocking {
        val camera = OriginalMasterMediaRepository(cameraDir)
        camera.saveMediaBundle("shot_1", byteArrayOf(1), byteArrayOf(2), "{}", "{}", "jpg")
        val before = cameraDir.listFiles()!!.associate { it.name to it.readBytes().toList() }

        val studio = StudioStore(studioDir, cameraDir)
        val item = studio.save("studio_1", byteArrayOf(9, 9), "jpg", "flux-klein-4b-studio-v1", "1.0.0", sourceMediaId = "shot_1")
        val meta = item.metadataFile.readText()
        assertTrue(meta, meta.contains("\"origin\": \"GENERATED\"") && meta.contains("\"pipeline\": \"AI_STUDIO\"") && meta.contains("\"source_media_id\": \"shot_1\""))
        assertEquals(studioDir.canonicalFile, item.imageFile.parentFile.canonicalFile)

        // The camera directory is byte-for-byte unchanged, and neither side accepts the other's ids.
        assertEquals(before, cameraDir.listFiles()!!.associate { it.name to it.readBytes().toList() })
        assertTrue(refuses { runBlocking { camera.saveMediaBundle("studio_2", byteArrayOf(1), byteArrayOf(2), "{}", "{}", "jpg") } })
        assertTrue(refuses { studio.save("shot_2", byteArrayOf(1), "jpg", "m", "1", null) })
    }
}
