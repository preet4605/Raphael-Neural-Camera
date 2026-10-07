package com.neuralcamera.gallery.storage

import com.neuralcamera.models.execution.FallbackAction
import com.neuralcamera.models.execution.StageOutcome
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/*
 * Crash-safe storage for capture bundles (e.g. ORIGINAL + MASTER JPEG, RAW DNG + JPEG, HEIF + metadata).
 *
 * Commit protocol for one bundle:
 *  1. Check free space for every part (+ margin); refuse before writing anything when it would not fit.
 *  2. Write the journal "<bundle>.pending" (fsynced) listing the part file names.
 *  3. Write each part to "<name>.tmp-<bundle>", flush and fsync, then rename it to its final name (atomic within one
 *     directory).
 *  4. Write the manifest "<bundle>.manifest" the same way, then delete the journal. The manifest is the commit marker:
 *     [recover] deletes every file listed by a journal that has no manifest, and every temp file.
 * The directory itself is not fsynced (java.io offers no portable way), so a power loss right after a rename may lose
 * that rename; the journal makes such a bundle incomplete rather than half-visible.
 * Any failure deletes the bundle's temp and already-renamed files, so a bundle is all-or-nothing. Existing files are
 * never overwritten (an id collision fails the save). Tested on the JVM with a temp directory; behaviour on the device's
 * filesystem under real power loss: NOT_TESTED.
 */

data class BundlePart(val role: String, val fileName: String, val bytes: ByteArray) {
    init {
        require(fileName.isNotBlank() && !fileName.contains('/') && !fileName.contains("..")) { "invalid file name $fileName" }
        require(!fileName.endsWith(MANIFEST_SUFFIX) && !fileName.endsWith(PENDING_SUFFIX) && !fileName.contains(TEMP_MARKER)) { "reserved file name $fileName" }
    }

    override fun equals(other: Any?) = other is BundlePart && role == other.role && fileName == other.fileName && bytes.contentEquals(other.bytes)
    override fun hashCode() = 31 * (31 * role.hashCode() + fileName.hashCode()) + bytes.contentHashCode()
}

data class CommittedBundle(val bundleId: String, val files: Map<String, File>, val manifest: File)

data class RecoveryReport(val removedTempFiles: List<String>, val removedOrphans: List<String>, val committedBundles: List<String>)

internal const val MANIFEST_SUFFIX = ".manifest"
internal const val TEMP_MARKER = ".tmp-"
internal const val PENDING_SUFFIX = ".pending"

class AtomicMediaStore(
    private val dir: File,
    /** Extra free space kept after a save. */
    private val reserveBytes: Long = 16L * 1024 * 1024,
    private val freeSpace: (File) -> Long = { it.usableSpace }
) {
    init {
        if (!dir.exists()) dir.mkdirs()
        require(dir.isDirectory) { "$dir is not a directory" }
    }

    fun commit(bundleId: String, parts: List<BundlePart>, manifestJson: String): StageOutcome<CommittedBundle> {
        require(bundleId.matches(Regex("[A-Za-z0-9_.-]+"))) { "invalid bundle id $bundleId" }
        require(parts.isNotEmpty()) { "a bundle needs at least one part" }
        require(parts.map { it.fileName }.toSet().size == parts.size) { "duplicate file names in bundle" }
        val manifestFile = File(dir, "$bundleId$MANIFEST_SUFFIX")
        val finals = parts.map { File(dir, it.fileName) }
        (finals + manifestFile).firstOrNull { it.exists() }?.let {
            return StageOutcome.Failed("STORAGE_COMMIT", "${it.name} already exists; refusing to overwrite", FallbackAction.ABORT)
        }
        val needed = parts.sumOf { it.bytes.size.toLong() } + manifestJson.length * 2L + reserveBytes
        val free = freeSpace(dir)
        if (free < needed) {
            return StageOutcome.Failed("STORAGE_COMMIT", "storage full: need $needed bytes, $free available", FallbackAction.ABORT)
        }
        val created = ArrayList<File>()
        val journal = File(dir, "$bundleId$PENDING_SUFFIX")
        return try {
            created.add(journal)
            writeSynced(journal, parts.joinToString("\n") { it.fileName }.toByteArray())
            val temps = parts.map { p -> File(dir, p.fileName + TEMP_MARKER + bundleId).also { created.add(it); writeSynced(it, p.bytes) } }
            temps.zip(finals).forEach { (t, f) -> move(t, f); created.remove(t); created.add(f) }
            val manifestTemp = File(dir, "$bundleId$MANIFEST_SUFFIX$TEMP_MARKER$bundleId").also { created.add(it) }
            writeSynced(manifestTemp, manifestBody(bundleId, parts, manifestJson).toByteArray())
            move(manifestTemp, manifestFile)
            journal.delete()
            StageOutcome.Success("STORAGE_COMMIT", CommittedBundle(bundleId, parts.associate { it.role to File(dir, it.fileName) }, manifestFile), "atomic-rename")
        } catch (e: IOException) {
            created.forEach { it.delete() }
            manifestFile.delete()
            val full = e.message?.contains("ENOSPC") == true || e.message?.contains("No space", ignoreCase = true) == true
            StageOutcome.Failed("STORAGE_COMMIT", (if (full) "storage full during write: " else "write failed: ") + e.message, FallbackAction.ABORT, e)
        }
    }

    /**
     * Startup recovery: deletes temp files; for every journal without a manifest, deletes the files it lists and the
     * journal (the bundle never committed); a journal next to a manifest is just deleted. Returns committed bundle ids.
     */
    fun recover(): RecoveryReport {
        val files = dir.listFiles()?.toList() ?: emptyList()
        val temps = files.filter { it.name.contains(TEMP_MARKER) }
        temps.forEach { it.delete() }
        val orphans = ArrayList<String>()
        files.filter { it.name.endsWith(PENDING_SUFFIX) }.forEach { j ->
            val id = j.name.removeSuffix(PENDING_SUFFIX)
            if (!File(dir, "$id$MANIFEST_SUFFIX").exists()) {
                j.readLines().filter { it.isNotBlank() }.forEach { name ->
                    val f = File(dir, name)
                    if (f.exists() && f.delete()) orphans.add(name)
                }
            }
            j.delete()
        }
        val committed = files.filter { it.name.endsWith(MANIFEST_SUFFIX) }.map { it.name.removeSuffix(MANIFEST_SUFFIX) }
        return RecoveryReport(temps.map { it.name }, orphans, committed.sorted())
    }

    /** Ids of committed bundles (those with a manifest), newest first by manifest time. */
    fun index(): List<String> = (dir.listFiles { f -> f.name.endsWith(MANIFEST_SUFFIX) } ?: emptyArray())
        .sortedByDescending { it.lastModified() }.map { it.name.removeSuffix(MANIFEST_SUFFIX) }

    fun readManifest(bundleId: String): String? = File(dir, "$bundleId$MANIFEST_SUFFIX").takeIf { it.exists() }?.readText()

    /** Deletes a committed bundle: manifest first (uncommits it), then its parts. */
    fun delete(bundleId: String, fileNames: List<String>): Boolean {
        val m = File(dir, "$bundleId$MANIFEST_SUFFIX")
        if (!m.exists()) return false
        m.delete()
        fileNames.forEach { File(dir, it).delete() }
        return true
    }

    private fun manifestBody(id: String, parts: List<BundlePart>, json: String) = buildString {
        append("{\"bundle\":\"").append(id).append("\",\"parts\":[")
        parts.forEachIndexed { i, p ->
            if (i > 0) append(',')
            append("{\"role\":\"").append(p.role).append("\",\"file\":\"").append(p.fileName).append("\",\"bytes\":").append(p.bytes.size).append('}')
        }
        append("],\"metadata\":").append(json).append('}')
    }

    private fun writeSynced(f: File, bytes: ByteArray) {
        FileOutputStream(f).use { out -> out.write(bytes); out.flush(); out.fd.sync() }
    }

    private fun move(from: File, to: File) {
        try {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            throw IOException("atomic rename not supported in ${dir.path}", e)
        }
    }
}
