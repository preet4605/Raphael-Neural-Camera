package com.neuralcamera.runtime.ort

import com.neuralcamera.benchmarks.Json
import java.io.File

/** Node-event provider counts extracted from an ONNX Runtime profiling file. */
data class OrtProfileSummary(
    val nodeEvents: Int,
    val providerCounts: Map<String, Int>
)

object OrtProfile {
    const val QNN_PROVIDER = "QNNExecutionProvider"
    const val CPU_PROVIDER = "CPUExecutionProvider"

    /**
     * Counts `cat == "Node"` events per `args.provider`. Events with no provider are counted under "UNKNOWN", so a
     * profile of an unexpected shape can never be mistaken for accelerator execution.
     */
    fun summarize(json: String): OrtProfileSummary {
        val root = Json.parse(json) as? List<*> ?: throw IllegalArgumentException("ORT profile is not a JSON array")
        val counts = LinkedHashMap<String, Int>()
        var nodes = 0
        for (event in root) {
            val map = event as? Map<*, *> ?: continue
            if (map["cat"] != "Node") continue
            nodes++
            val provider = (map["args"] as? Map<*, *>)?.get("provider") as? String ?: "UNKNOWN"
            counts[provider] = (counts[provider] ?: 0) + 1
        }
        return OrtProfileSummary(nodes, counts)
    }

    fun summarizeFile(path: String): OrtProfileSummary = summarize(File(path).readText())
}

/** In-process library evidence from /proc/self/maps. */
object ProcMaps {
    val DEFAULT_PATTERNS = listOf("libQnn", "libcdsprpc", "libonnxruntime")

    fun readSelf(): String? = try {
        File("/proc/self/maps").readText()
    } catch (e: Exception) {
        null
    }

    /** Distinct basenames of mapped files whose name contains any of [patterns], sorted. */
    fun mappedLibraries(mapsText: String, patterns: List<String> = DEFAULT_PATTERNS): List<String> =
        mapsText.lineSequence()
            .map { it.substringAfterLast(' ').trim() }
            .filter { it.contains('/') }
            .map { it.substringAfterLast('/') }
            .filter { name -> patterns.any { name.contains(it) } }
            .distinct()
            .sorted()
            .toList()

    /**
     * Distinct full paths of matching mapped files, sorted. Paths show which copy was loaded: the app's own
     * (Maven-bundled QNN 2.42.0, under the app's lib directory) or a vendor one (device QNN 2.37.4, under /vendor).
     */
    fun mappedLibraryPaths(mapsText: String, patterns: List<String> = DEFAULT_PATTERNS): List<String> =
        mapsText.lineSequence()
            .map { it.substringAfterLast(' ').trim() }
            .filter { it.startsWith('/') && patterns.any { p -> it.substringAfterLast('/').contains(p) } }
            .distinct()
            .sorted()
            .toList()
}

/** One step of backend bring-up, kept verbatim in the evidence report (including failures). */
data class BringUpStep(val name: String, val ok: Boolean, val detail: String, val elapsedMs: Double? = null)

interface BringUpReporting {
    fun bringUpSteps(): List<BringUpStep>
}
