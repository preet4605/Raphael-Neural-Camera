package com.neuralcamera.gallery.storage

import com.neuralcamera.models.execution.StageOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AtomicMediaStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val parts = listOf(
        BundlePart("raw", "shot1.dng", ByteArray(100) { 1 }),
        BundlePart("jpeg", "shot1.jpg", ByteArray(50) { 2 })
    )

    @Test
    fun commitWritesAllPartsAndManifestAndNoLeftovers() {
        val dir = tmp.newFolder()
        val o = AtomicMediaStore(dir).commit("shot1", parts, "{\"iso\":100}")
        assertTrue(o is StageOutcome.Success)
        assertEquals(setOf("shot1.dng", "shot1.jpg", "shot1.manifest"), dir.list()!!.toSet())
        assertEquals(100L, File(dir, "shot1.dng").length())
        val m = AtomicMediaStore(dir).readManifest("shot1")!!
        assertTrue(m.contains("\"role\":\"raw\"") && m.contains("\"metadata\":{\"iso\":100}"))
        assertEquals(listOf("shot1"), AtomicMediaStore(dir).index())
    }

    @Test
    fun refusesToOverwrite() {
        val dir = tmp.newFolder()
        val s = AtomicMediaStore(dir)
        s.commit("shot1", parts, "{}")
        val again = s.commit("shot1", parts, "{}")
        assertTrue(again is StageOutcome.Failed && again.reason.contains("already exists"))
    }

    @Test
    fun storageFullFailsBeforeWritingAnything() {
        val dir = tmp.newFolder()
        val o = AtomicMediaStore(dir, reserveBytes = 0, freeSpace = { 120L }).commit("shot1", parts, "{}")
        assertTrue(o is StageOutcome.Failed && o.reason.startsWith("storage full"))
        assertTrue(dir.list()!!.isEmpty())
    }

    @Test
    fun recoveryRemovesUncommittedBundleAndKeepsCommittedOnes() {
        val dir = tmp.newFolder()
        val s = AtomicMediaStore(dir)
        s.commit("good", listOf(BundlePart("jpeg", "good.jpg", byteArrayOf(1))), "{}")
        // Simulate a crash mid-commit of "bad": journal written, one part renamed, one temp left, no manifest.
        File(dir, "bad.pending").writeText("bad.dng\nbad.jpg")
        File(dir, "bad.dng").writeBytes(byteArrayOf(9))
        File(dir, "bad.jpg.tmp-bad").writeBytes(byteArrayOf(9))
        val report = AtomicMediaStore(dir).recover()
        assertEquals(listOf("good"), report.committedBundles)
        assertEquals(listOf("bad.dng"), report.removedOrphans)
        assertEquals(listOf("bad.jpg.tmp-bad"), report.removedTempFiles)
        assertEquals(setOf("good.jpg", "good.manifest"), dir.list()!!.toSet())
    }

    @Test
    fun deleteUncommitsFirst() {
        val dir = tmp.newFolder()
        val s = AtomicMediaStore(dir)
        s.commit("shot1", parts, "{}")
        assertTrue(s.delete("shot1", parts.map { it.fileName }))
        assertTrue(dir.list()!!.isEmpty())
        assertFalse(s.delete("shot1", emptyList()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun reservedNamesAreRejected() { BundlePart("x", "a.manifest", byteArrayOf()) }

    @Test(expected = IllegalArgumentException::class)
    fun pathTraversalIsRejected() { BundlePart("x", "../a.jpg", byteArrayOf()) }
}
