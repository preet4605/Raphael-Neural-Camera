package com.neuralcamera.runtime.proof

import ai.onnxruntime.OrtEnvironment
import com.neuralcamera.benchmarks.Json
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Runs the real suite end to end on the host with the CPU control variant only (no HTP exists on a host). */
class Gate2SuiteHostTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun ortAvailable() = try {
        OrtEnvironment.getEnvironment()
        true
    } catch (t: Throwable) {
        false
    }

    @Test
    fun cpuControlVariantWritesRawEvidenceAndIsNeverHtp() {
        assumeTrue(ortAvailable())
        val suite = Gate2Suite(SystemSampler.None, mapOf("model" to "host"), mapOf("version" to "test"), 1L, null)
        val cpu = Gate2Variant.ALL.first { it.name == "ort-cpu-fp32" }

        val result = runBlocking { suite.run(tmp.root, listOf(cpu), sustainedIterations = 6) }

        assertEquals(1, result.reportFiles.size)
        val report = Json.parse(result.reportFiles.single().readText()) as Map<*, *>
        assertEquals("ort-cpu-fp32", report["variant"])
        assertEquals(6, ((report["timing"] as Map<*, *>)["iterationsMs"] as List<*>).size)
        val attribution = report["attribution"] as Map<*, *>
        assertEquals("CPU_RUNTIME", attribution["target"])
        assertEquals(false, attribution["provesHtp"])
        assertFalse((report["verdict"] as Map<*, *>)["htpInferenceProvenThisRun"] as Boolean)
        assertTrue(result.summaryFile.exists())
    }

    @Test
    fun htpVariantOnAHostWithoutQnnProducesAFailureReportNotAProof() {
        assumeTrue(ortAvailable())
        val suite = Gate2Suite(SystemSampler.None, mapOf("model" to "host"), mapOf("version" to "test"), 1L, null)
        val htp = Gate2Variant.ALL.first { it.name == "htp-fp32" }

        val result = runBlocking { suite.run(tmp.root, listOf(htp), sustainedIterations = 3) }

        val report = Json.parse(result.reportFiles.single().readText()) as Map<*, *>
        assertTrue((report["failure"] as String).isNotEmpty())
        assertEquals(false, (report["verdict"] as Map<*, *>)["htpInferenceProvenThisRun"])
        assertTrue((report["bringUp"] as List<*>).isNotEmpty())
    }
}
