package com.neuralcamera.runtime.proof

import com.neuralcamera.benchmarks.Json
import com.neuralcamera.models.HardwareBackendType
import com.neuralcamera.models.ModelDescriptor
import com.neuralcamera.models.PredefinedModelCatalog
import com.neuralcamera.runtime.BackendAttribution
import com.neuralcamera.runtime.ExecutionTarget
import com.neuralcamera.runtime.InferenceBackend
import com.neuralcamera.runtime.InferenceOutput
import com.neuralcamera.runtime.TensorData
import com.neuralcamera.runtime.ort.BringUpReporting
import com.neuralcamera.runtime.ort.BringUpStep
import com.neuralcamera.runtime.reference.CpuReferenceBackend
import com.neuralcamera.runtime.reference.DenoiseTinyV1
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Logic tests for the Gate 2 runner. The "scripted" backends are test doubles that exercise the verdict rules; none of
 * these runs is evidence of anything on a device.
 */
class Gate2RunnerTest {

    private val model = PredefinedModelCatalog.DENOISE_TINY_V1
    private val reference = CpuReferenceBackend()

    private val config = Gate2Config(variantName = "test", warmupIterations = 2, sustainedIterations = 12, sampleEvery = 4)
    private val context = Gate2Context(
        runId = "run-1", processStartElapsedRealtimeMs = 1_000L,
        device = mapOf("model" to "host"), app = mapOf("version" to "test"),
        modelResource = DenoiseTinyV1.FP32_ONNX, backendDescription = mapOf("implementation" to "scripted")
    )

    private class FixedSampler(private val thermal: Int? = 0, private val pss: Long? = 100_000L) : SystemSampler {
        override fun sample(iteration: Int) = SystemSample(
            iteration = iteration, elapsedRealtimeNanos = iteration * 1_000L,
            thermalStatus = thermal, pssKb = pss, processCpuTimeMs = iteration * 10L
        )
    }

    /** Computes the true reference output, then lets a script alter it. */
    private inner class ScriptedBackend(
        private val attribution: BackendAttribution,
        private val advanceClockNs: Long = 5_000_000L,
        private val clock: FakeClock = sharedClock,
        private val transform: (FloatArray, FloatArray) -> FloatArray = { _, ref -> ref },
        private val available: Boolean = true,
        private val prepared: Boolean = true
    ) : InferenceBackend, BringUpReporting {
        private var cachedRef: FloatArray? = null
        override val backendType = HardwareBackendType.QUALCOMM_QNN_NPU
        override fun isAvailable() = available
        override suspend fun initialize() = true
        override suspend fun prepare(model: ModelDescriptor) = prepared
        override suspend fun executeInference(model: ModelDescriptor, input: TensorData): InferenceOutput {
            clock.advance(advanceClockNs)
            val ref = (cachedRef ?: reference.executeInference(model, input).tensor.toFloatArray().also { cachedRef = it }).copyOf()
            val out = transform(input.toFloatArray(), ref)
            return InferenceOutput(TensorData.ofFloats(input.shape, out), attribution)
        }
        override suspend fun finalizeAttribution() = attribution
        override fun release() {}
        override fun bringUpSteps() = listOf(BringUpStep("session.create", prepared, "scripted"))
    }

    private class FakeClock {
        var now = 0L
        fun advance(ns: Long) { now += ns }
        fun read() = now
    }

    private val sharedClock = FakeClock()

    /** Delegates to a real backend but advances the fake clock, so latency criteria are deterministic. */
    private inner class ClockedBackend(private val inner: InferenceBackend, private val advanceNs: Long = 5_000_000L) : InferenceBackend {
        override val backendType get() = inner.backendType
        override fun isAvailable() = inner.isAvailable()
        override suspend fun initialize() = inner.initialize()
        override suspend fun prepare(model: ModelDescriptor) = inner.prepare(model)
        override suspend fun executeInference(model: ModelDescriptor, input: TensorData): InferenceOutput {
            sharedClock.advance(advanceNs)
            return inner.executeInference(model, input)
        }
        override suspend fun finalizeAttribution() = inner.finalizeAttribution()
        override fun release() = inner.release()
    }

    private val htpProof = BackendAttribution(ExecutionTarget.NPU_HTP, true, listOf("scripted evidence"))

    private fun run(
        backend: InferenceBackend,
        sampler: SystemSampler = FixedSampler()
    ): Gate2RunReport = runBlocking {
        Gate2Runner(reference, sampler, sharedClock::read).run(backend, model, config, context)
    }

    private fun passed(report: Gate2RunReport, name: String) = report.verdict.criteria.first { it.name == name }.passed

    @Test
    fun cpuReferenceControlRunPassesNumericsButIsNeverHtp() {
        val report = run(ClockedBackend(CpuReferenceBackend()))

        assertTrue(report.verdict.criteria.filter { it.name != "htp_attribution" }.all { it.passed })
        assertTrue(report.verdict.numericsAndLatencyPass)
        assertFalse(report.verdict.htpAttributed)
        assertFalse(report.verdict.htpInferenceProvenThisRun)
    }

    @Test
    fun faithfulBackendWithHtpEvidenceIsProvenForTheRun() {
        val report = run(ScriptedBackend(htpProof))

        assertTrue(report.verdict.criteria.joinToString { "${it.name}=${it.passed}" }, report.verdict.htpInferenceProvenThisRun)
    }

    @Test
    fun passThroughCannotPassEvenWhenItClaimsHtp() {
        val report = run(ScriptedBackend(htpProof, transform = { input, _ -> input }))

        assertFalse(passed(report, "psnr_vs_fp32_reference"))
        assertFalse(report.verdict.numericsAndLatencyPass)
        assertFalse(report.verdict.htpInferenceProvenThisRun)
    }

    @Test
    fun nonFiniteOutputFailsTheRun() {
        val report = run(ScriptedBackend(htpProof, transform = { _, ref -> ref.also { it[5] = Float.NaN } }))

        assertFalse(passed(report, "no_nan_or_inf"))
        assertFalse(report.verdict.htpInferenceProvenThisRun)
    }

    @Test
    fun saturatedOutputIsCaughtAsGrossError() {
        val report = run(ScriptedBackend(htpProof, transform = { _, ref -> FloatArray(ref.size) { i -> if (i == 3) 5f else ref[i] } }))

        assertFalse(passed(report, "no_gross_error_or_clipping"))
    }

    @Test
    fun slowBackendFailsP95() {
        val report = run(ScriptedBackend(htpProof, advanceClockNs = 150_000_000L))

        assertFalse(passed(report, "p95_latency"))
        assertTrue(passed(report, "latency_stable"))
        assertFalse(report.verdict.htpInferenceProvenThisRun)
    }

    @Test
    fun missingThermalOrMemoryTelemetryBlocksTheVerdict() {
        assertFalse(passed(run(ScriptedBackend(htpProof), sampler = FixedSampler(thermal = null)), "thermal_stable"))
        assertFalse(passed(run(ScriptedBackend(htpProof), sampler = FixedSampler(pss = null)), "memory_sampled"))
        assertFalse(run(ScriptedBackend(htpProof), sampler = SystemSampler.None).verdict.htpInferenceProvenThisRun)
    }

    @Test
    fun severeThermalStatusFailsTheRun() {
        val report = run(ScriptedBackend(htpProof), sampler = FixedSampler(thermal = 3))

        assertFalse(passed(report, "thermal_stable"))
        assertFalse(report.verdict.htpInferenceProvenThisRun)
    }

    @Test
    fun unprovenAttributionBlocksHtpEvenWithPerfectNumerics() {
        val report = run(ScriptedBackend(BackendAttribution(ExecutionTarget.NPU_HTP, false, listOf("claimed only"))))

        assertTrue(report.verdict.numericsAndLatencyPass)
        assertFalse(report.verdict.htpAttributed)
        assertFalse(report.verdict.htpInferenceProvenThisRun)
    }

    @Test
    fun unavailableBackendStillProducesAFailureReportWithBringUpLog() {
        val report = run(ScriptedBackend(htpProof, available = false))

        assertFalse(passed(report, "run_completed_without_error"))
        assertFalse(report.verdict.htpInferenceProvenThisRun)
        val parsed = Json.parse(report.toJson()) as Map<*, *>
        assertEquals("backend reports unavailable", (parsed["failure"] as String).substringAfter(": "))
        assertEquals(1, (parsed["bringUp"] as List<*>).size)
    }

    @Test
    fun failedPrepareIsRecorded() {
        val report = run(ScriptedBackend(htpProof, prepared = false))

        assertFalse(passed(report, "run_completed_without_error"))
        assertTrue(report.toJson().contains("prepare() returned false"))
    }

    @Test
    fun reportKeepsEveryRawValueForIndependentRecomputation() {
        val report = run(ScriptedBackend(htpProof))
        val parsed = Json.parse(report.toJson()) as Map<*, *>

        assertEquals(Gate2Runner.SCHEMA, parsed["schema"])
        val timing = parsed["timing"] as Map<*, *>
        assertEquals(config.sustainedIterations, (timing["iterationsMs"] as List<*>).size)
        assertEquals(config.warmupIterations, (timing["warmupMs"] as List<*>).size)
        assertNotNull(timing["coldMs"])
        assertNotNull(timing["sessionCreateMs"])
        val numerics = parsed["numerics"] as Map<*, *>
        assertEquals(config.sustainedIterations, (numerics["psnrDb"] as List<*>).size)
        assertEquals(config.sustainedIterations, (numerics["maxAbsError"] as List<*>).size)
        val samples = parsed["samples"] as List<*>
        assertEquals(config.sustainedIterations / config.sampleEvery + 2, samples.size) // start + every 4th + end
        assertEquals("NPU_HTP", (parsed["attribution"] as Map<*, *>)["target"])
        assertNotNull((parsed["input"] as Map<*, *>)["sha256"])
        assertEquals(false, (parsed["gpuUse"] as Map<*, *>)["available"])
    }

    @Test
    fun psnrReportsIdenticalSignalsAsAFiniteCap() {
        val a = floatArrayOf(0.1f, 0.2f)
        assertEquals(Gate2Runner.IDENTICAL_PSNR_DB, Gate2Runner.psnr(a, a), 0.0)
        assertEquals(20.0, Gate2Runner.psnr(floatArrayOf(0f), floatArrayOf(0.1f)), 1e-6)
    }
}
