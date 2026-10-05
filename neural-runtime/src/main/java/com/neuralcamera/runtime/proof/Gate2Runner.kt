package com.neuralcamera.runtime.proof

import com.neuralcamera.benchmarks.Json
import com.neuralcamera.benchmarks.Stats
import com.neuralcamera.models.ModelDescriptor
import com.neuralcamera.runtime.BackendAttribution
import com.neuralcamera.runtime.InferenceBackend
import com.neuralcamera.runtime.ModelArtifacts
import com.neuralcamera.runtime.TensorData
import com.neuralcamera.runtime.ort.BringUpReporting
import com.neuralcamera.runtime.reference.CpuReferenceBackend
import com.neuralcamera.runtime.reference.DenoiseTinyV1
import kotlin.math.abs

/** Thresholds and sizes of a Gate 2 run. Defaults are the gate's stated targets. */
data class Gate2Config(
    val variantName: String,
    val inputSeed: Long = 20260925L,
    val warmupIterations: Int = 10,
    val sustainedIterations: Int = 300,
    val sampleEvery: Int = 10,
    val psnrThresholdDb: Double = 35.0,
    val p95ThresholdMs: Double = 100.0,
    /** Largest tolerated per-element error vs the FP32 reference (full scale = 1.0): catches catastrophic clipping. */
    val maxAbsErrorLimit: Double = 0.1,
    /** PSNR(input, reference) must be at most this, so a pass-through cannot satisfy the PSNR criterion. */
    val maxInputVsReferencePsnrDb: Double = 30.0,
    /** Last-third median latency may be at most this multiple of the first-third median (sustained stability). */
    val maxLatencyDriftRatio: Double = 1.5,
    /** Thermal status at or above this (PowerManager.THERMAL_STATUS_SEVERE) fails the run. */
    val thermalFailStatus: Int = 3
)

/** Facts about the process/device that the runner cannot know. All values must be real measurements or properties. */
data class Gate2Context(
    val runId: String,
    val processStartElapsedRealtimeMs: Long?,
    val device: Map<String, String>,
    val app: Map<String, String>,
    val modelResource: String,
    val backendDescription: Map<String, String>
)

data class Criterion(val name: String, val passed: Boolean, val detail: String)

data class Gate2Verdict(
    val criteria: List<Criterion>,
    val htpAttributed: Boolean,
    /** Every non-attribution criterion passed. True for a valid CPU control run too; says nothing about HTP. */
    val numericsAndLatencyPass: Boolean,
    /** True only when numerics/latency/stability pass AND the backend's finalized attribution proves HTP. */
    val htpInferenceProvenThisRun: Boolean
)

class Gate2RunReport(val json: Map<String, Any?>, val verdict: Gate2Verdict) {
    fun toJson(pretty: Boolean = true): String = Json.stringify(json, pretty)
}

/**
 * Runs the Gate 2 measurement against one backend/variant and produces a raw-data evidence report. The report keeps
 * every per-iteration value so an independent checker can recompute all statistics and verdicts.
 */
class Gate2Runner(
    private val reference: InferenceBackend = CpuReferenceBackend(),
    private val sampler: SystemSampler = SystemSampler.None,
    private val nanoClock: () -> Long = System::nanoTime,
    private val wallClockMs: () -> Long = System::currentTimeMillis
) {

    suspend fun run(
        backend: InferenceBackend,
        model: ModelDescriptor,
        config: Gate2Config,
        context: Gate2Context
    ): Gate2RunReport {
        val report = linkedMapOf<String, Any?>(
            "schema" to SCHEMA,
            "runId" to context.runId,
            "startedAtEpochMs" to wallClockMs(),
            "processStartElapsedRealtimeMs" to context.processStartElapsedRealtimeMs,
            "variant" to config.variantName,
            "device" to context.device,
            "app" to context.app,
            "backend" to context.backendDescription + mapOf("type" to backend.backendType.name),
            "model" to mapOf("id" to model.modelId, "resource" to context.modelResource),
            "thresholds" to thresholds(config),
            "gpuUse" to mapOf("available" to false, "reason" to "GPU utilization counters are not accessible to an unprivileged app")
        )
        val timings = linkedMapOf<String, Any?>()
        val numerics = linkedMapOf<String, Any?>()
        val samples = mutableListOf<SystemSample>()
        var attribution: BackendAttribution? = null
        var failure: String? = null
        val iterationsMs = DoubleArray(config.sustainedIterations)
        val psnrDb = DoubleArray(config.sustainedIterations)
        val maxAbsError = DoubleArray(config.sustainedIterations)
        var nonFinite = 0L
        var outOfRange = 0L
        var inputVsRef = Double.NaN
        var completed = 0

        try {
            val shape = DenoiseTinyV1.INPUT_SHAPE
            val inputValues = DeterministicInput.noisyScene(shape[2], shape[3], config.inputSeed)
            val input = TensorData.ofFloats(shape, inputValues)
            val referenceOut = reference.executeInference(model, input).tensor
            val refValues = referenceOut.toFloatArray()
            inputVsRef = psnr(inputValues, refValues)
            report["input"] = mapOf(
                "seed" to config.inputSeed, "shape" to shape.toList(),
                "sha256" to ModelArtifacts.sha256Hex(input.buffer),
                "referenceSha256" to ModelArtifacts.sha256Hex(referenceOut.buffer),
                "psnrInputVsReferenceDb" to inputVsRef
            )

            check(backend.isAvailable()) { "backend reports unavailable" }
            check(backend.initialize()) { "backend initialize() returned false" }

            val prepareStart = nanoClock()
            val prepared = backend.prepare(model)
            timings["sessionCreateMs"] = (nanoClock() - prepareStart) / 1e6
            check(prepared) { "backend.prepare() returned false" }

            samples.add(sampler.sample(-1))
            val coldStart = nanoClock()
            val cold = backend.executeInference(model, input)
            timings["coldMs"] = (nanoClock() - coldStart) / 1e6
            check(cold.tensor.toFloatArray().size == refValues.size) { "cold output has wrong element count" }

            val warmup = DoubleArray(config.warmupIterations)
            for (i in 0 until config.warmupIterations) {
                val t0 = nanoClock()
                backend.executeInference(model, input)
                warmup[i] = (nanoClock() - t0) / 1e6
            }
            timings["warmupMs"] = warmup

            val loopStart = nanoClock()
            for (i in 0 until config.sustainedIterations) {
                if (i % config.sampleEvery == 0) samples.add(sampler.sample(i))
                val t0 = nanoClock()
                val out = backend.executeInference(model, input)
                iterationsMs[i] = (nanoClock() - t0) / 1e6
                val got = out.tensor.toFloatArray()
                if (got.size != refValues.size) throw IllegalStateException("iteration $i: wrong element count ${got.size}")
                var maxAbs = 0.0
                for (k in got.indices) {
                    val g = got[k]
                    if (!g.isFinite()) {
                        nonFinite++
                        maxAbs = Double.POSITIVE_INFINITY
                        continue
                    }
                    if (g < 0f || g > 1f) outOfRange++
                    maxAbs = maxOf(maxAbs, abs(g.toDouble() - refValues[k].toDouble()))
                }
                maxAbsError[i] = maxAbs
                psnrDb[i] = psnr(got, refValues)
                completed++
            }
            timings["loopWallMs"] = (nanoClock() - loopStart) / 1e6
            samples.add(sampler.sample(config.sustainedIterations))
            attribution = backend.finalizeAttribution()
        } catch (t: Throwable) {
            failure = "${t.javaClass.simpleName}: ${t.message}"
            if (attribution == null) {
                attribution = try {
                    backend.finalizeAttribution()
                } catch (e: Throwable) {
                    BackendAttribution.unknown("finalizeAttribution failed: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }

        timings["iterationsMs"] = iterationsMs.copyOf(completed)
        numerics["psnrDb"] = psnrDb.copyOf(completed)
        numerics["maxAbsError"] = maxAbsError.copyOf(completed)
        numerics["nonFiniteCount"] = nonFinite
        numerics["outOfRangeCount"] = outOfRange
        report["timing"] = timings
        report["numerics"] = numerics
        report["samples"] = samples.map { sampleToMap(it) }
        report["bringUp"] = (backend as? BringUpReporting)?.bringUpSteps()?.map {
            mapOf("step" to it.name, "ok" to it.ok, "detail" to it.detail, "elapsedMs" to it.elapsedMs)
        } ?: emptyList<Any>()
        val finalAttribution = attribution ?: BackendAttribution.unknown("run aborted before attribution")
        report["attribution"] = mapOf(
            "target" to finalAttribution.target.name,
            "wholeGraphOnTarget" to finalAttribution.wholeGraphOnTarget,
            "provesHtp" to finalAttribution.provesHtp,
            "evidence" to finalAttribution.evidence,
            "details" to finalAttribution.details
        )
        report["failure"] = failure

        val verdict = evaluate(config, completed, iterationsMs, psnrDb, maxAbsError, nonFinite, inputVsRef, samples, finalAttribution, failure)
        report["verdict"] = mapOf(
            "criteria" to verdict.criteria.map { mapOf("name" to it.name, "passed" to it.passed, "detail" to it.detail) },
            "htpAttributed" to verdict.htpAttributed,
            "numericsAndLatencyPass" to verdict.numericsAndLatencyPass,
            "htpInferenceProvenThisRun" to verdict.htpInferenceProvenThisRun
        )
        return Gate2RunReport(report, verdict)
    }

    private fun evaluate(
        config: Gate2Config,
        completed: Int,
        iterationsMs: DoubleArray,
        psnrDb: DoubleArray,
        maxAbsError: DoubleArray,
        nonFinite: Long,
        inputVsRef: Double,
        samples: List<SystemSample>,
        attribution: BackendAttribution,
        failure: String?
    ): Gate2Verdict {
        val criteria = mutableListOf<Criterion>()
        fun add(name: String, passed: Boolean, detail: String) = criteria.add(Criterion(name, passed, detail))

        add("run_completed_without_error", failure == null, failure ?: "no exception")
        add("all_iterations_completed", completed == config.sustainedIterations, "$completed of ${config.sustainedIterations}")
        add("no_nan_or_inf", failure == null && nonFinite == 0L, "$nonFinite non-finite output values")
        val minPsnr = if (completed > 0) psnrDb.copyOf(completed).min() else Double.NaN
        add("psnr_vs_fp32_reference", completed > 0 && minPsnr >= config.psnrThresholdDb, "min PSNR $minPsnr dB, threshold ${config.psnrThresholdDb} dB")
        val worst = if (completed > 0) maxAbsError.copyOf(completed).max() else Double.NaN
        add("no_gross_error_or_clipping", completed > 0 && worst <= config.maxAbsErrorLimit, "max abs error $worst, limit ${config.maxAbsErrorLimit}")
        add("model_is_discriminating", inputVsRef <= config.maxInputVsReferencePsnrDb, "PSNR(input, reference) $inputVsRef dB, must be <= ${config.maxInputVsReferencePsnrDb} dB")
        if (completed > 0) {
            val lat = Stats.summarize(iterationsMs.copyOf(completed))
            add("p95_latency", lat.p95 <= config.p95ThresholdMs, "p95 ${lat.p95} ms, threshold ${config.p95ThresholdMs} ms")
            val third = (completed / 3).coerceAtLeast(1)
            val first = Stats.median(iterationsMs.copyOfRange(0, third).sortedArray())
            val last = Stats.median(iterationsMs.copyOfRange(completed - third, completed).sortedArray())
            add("latency_stable", first > 0 && last <= config.maxLatencyDriftRatio * first, "first-third median $first ms, last-third median $last ms, max ratio ${config.maxLatencyDriftRatio}")
        } else {
            add("p95_latency", false, "no iterations")
            add("latency_stable", false, "no iterations")
        }
        val thermal = samples.mapNotNull { it.thermalStatus }
        add(
            "thermal_stable",
            thermal.size >= 2 && thermal.max() < config.thermalFailStatus,
            if (thermal.size >= 2) "max thermal status ${thermal.max()}, fail at ${config.thermalFailStatus}" else "thermal status was not sampled"
        )
        val pss = samples.mapNotNull { it.pssKb }
        add("memory_sampled", pss.size >= 2, if (pss.size >= 2) "PSS ${pss.first()} kB -> ${pss.last()} kB" else "memory was not sampled")

        val numericsAndLatencyPass = criteria.all { it.passed }
        val htp = attribution.provesHtp
        add("htp_attribution", htp, if (htp) "ORT/QNN evidence proves whole graph on HTP" else "not proven: target=${attribution.target}, whole=${attribution.wholeGraphOnTarget}")
        return Gate2Verdict(criteria, htp, numericsAndLatencyPass, numericsAndLatencyPass && htp)
    }

    private fun thresholds(c: Gate2Config) = mapOf(
        "psnrThresholdDb" to c.psnrThresholdDb, "p95ThresholdMs" to c.p95ThresholdMs,
        "sustainedIterations" to c.sustainedIterations, "warmupIterations" to c.warmupIterations,
        "maxAbsErrorLimit" to c.maxAbsErrorLimit, "maxInputVsReferencePsnrDb" to c.maxInputVsReferencePsnrDb,
        "maxLatencyDriftRatio" to c.maxLatencyDriftRatio, "thermalFailStatus" to c.thermalFailStatus,
        "sampleEvery" to c.sampleEvery
    )

    private fun sampleToMap(s: SystemSample) = linkedMapOf<String, Any?>(
        "iteration" to s.iteration, "elapsedRealtimeNanos" to s.elapsedRealtimeNanos,
        "thermalStatus" to s.thermalStatus, "thermalHeadroom" to s.thermalHeadroom, "batteryTempC" to s.batteryTempC,
        "processCpuTimeMs" to s.processCpuTimeMs, "pssKb" to s.pssKb, "nativeHeapKb" to s.nativeHeapKb,
        "javaHeapKb" to s.javaHeapKb, "availMemKb" to s.availMemKb, "lowMemory" to s.lowMemory
    )

    companion object {
        const val SCHEMA = "raphael.gate2.run/1"
        const val IDENTICAL_PSNR_DB = 200.0

        /** PSNR with peak 1.0; identical signals are reported as [IDENTICAL_PSNR_DB] so JSON never carries Infinity. */
        fun psnr(a: FloatArray, b: FloatArray): Double {
            var mse = 0.0
            for (i in a.indices) {
                val d = a[i].toDouble() - b[i].toDouble()
                mse += d * d
            }
            mse /= a.size
            if (mse.isNaN()) return Double.NEGATIVE_INFINITY
            return if (mse == 0.0) IDENTICAL_PSNR_DB else minOf(IDENTICAL_PSNR_DB, 10 * Math.log10(1.0 / mse))
        }
    }
}
