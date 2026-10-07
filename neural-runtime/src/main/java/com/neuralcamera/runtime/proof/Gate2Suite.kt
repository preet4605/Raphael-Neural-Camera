package com.neuralcamera.runtime.proof

import com.neuralcamera.benchmarks.Json
import com.neuralcamera.models.PredefinedModelCatalog
import com.neuralcamera.runtime.ort.OrtBackend
import com.neuralcamera.runtime.ort.OrtBackendConfig
import com.neuralcamera.runtime.ort.OrtExecution
import com.neuralcamera.runtime.reference.DenoiseTinyV1
import java.io.File
import java.util.UUID

/** One backend/model combination measured by Gate 2. */
data class Gate2Variant(
    val name: String,
    val description: String,
    val modelResource: String,
    val execution: OrtExecution
) {
    companion object {
        /** The control runs on the CPU EP and can never prove HTP; the htp-* variants are the gate candidates. */
        val ALL = listOf(
            Gate2Variant("ort-cpu-fp32", "Control: ONNX Runtime CPU EP, FP32 graph", DenoiseTinyV1.FP32_ONNX, OrtExecution.CPU),
            Gate2Variant("htp-fp32", "HTP via QNN EP, FP32 graph (FP16 precision on HTP)", DenoiseTinyV1.FP32_ONNX, OrtExecution.QNN_HTP),
            Gate2Variant("htp-qdq-a16w8", "HTP via QNN EP, QDQ graph, uint16 activations / uint8 weights", DenoiseTinyV1.QDQ_A16W8_ONNX, OrtExecution.QNN_HTP),
            Gate2Variant("htp-qdq-a8w8", "HTP via QNN EP, QDQ graph, uint8 activations / uint8 weights", DenoiseTinyV1.QDQ_A8W8_ONNX, OrtExecution.QNN_HTP)
        )
    }
}

data class Gate2SuiteResult(val reportFiles: List<File>, val summaryFile: File, val lines: List<String>)

/**
 * Runs every Gate 2 variant sequentially in this process and writes one raw-evidence JSON per variant plus a summary.
 * The summary only repeats what each run measured; the authority is tools/proof/check_gate2.py, which recomputes every
 * verdict from the raw reports and requires three independent process runs.
 */
class Gate2Suite(
    private val sampler: SystemSampler,
    private val device: Map<String, String>,
    private val app: Map<String, String>,
    private val processStartElapsedRealtimeMs: Long?,
    private val nativeLibraryDir: String?
) {

    suspend fun run(
        outputDir: File,
        variants: List<Gate2Variant> = Gate2Variant.ALL,
        sustainedIterations: Int = 300,
        log: (String) -> Unit = {}
    ): Gate2SuiteResult {
        outputDir.mkdirs()
        val reportFiles = mutableListOf<File>()
        val lines = mutableListOf<String>()
        val summary = mutableListOf<Map<String, Any?>>()
        val model = PredefinedModelCatalog.DENOISE_TINY_V1

        for (variant in variants) {
            val runId = UUID.randomUUID().toString()
            log("Gate 2 variant ${variant.name}: starting")
            val backend = OrtBackend(
                OrtBackendConfig(
                    execution = variant.execution,
                    modelId = DenoiseTinyV1.MODEL_ID,
                    modelResource = variant.modelResource,
                    enableProfiling = true,
                    profilePathPrefix = File(outputDir, "ortprofile_${variant.name}_$runId").path,
                    nativeLibraryDir = nativeLibraryDir
                )
            )
            val runner = Gate2Runner(sampler = sampler)
            val config = Gate2Config(variantName = variant.name, sustainedIterations = sustainedIterations)
            val context = Gate2Context(
                runId = runId,
                processStartElapsedRealtimeMs = processStartElapsedRealtimeMs,
                device = device,
                app = app,
                modelResource = variant.modelResource,
                backendDescription = mapOf("implementation" to "OrtBackend", "execution" to variant.execution.name, "description" to variant.description)
            )
            try {
                val report = runner.run(backend, model, config, context)
                val file = File(outputDir, "gate2_${variant.name}_$runId.json")
                file.writeText(report.toJson())
                reportFiles.add(file)
                val v = report.verdict
                val line = "${variant.name}: numerics+latency=${v.numericsAndLatencyPass} htpAttributed=${v.htpAttributed} " +
                    "htpProvenThisRun=${v.htpInferenceProvenThisRun}" +
                    (report.json["failure"]?.let { " failure=$it" } ?: "")
                lines.add(line)
                log(line)
                summary.add(
                    mapOf(
                        "variant" to variant.name, "runId" to runId, "file" to file.name,
                        "numericsAndLatencyPass" to v.numericsAndLatencyPass, "htpAttributed" to v.htpAttributed,
                        "htpInferenceProvenThisRun" to v.htpInferenceProvenThisRun,
                        "failedCriteria" to v.criteria.filter { !it.passed }.map { it.name }
                    )
                )
            } finally {
                backend.release()
            }
        }
        val summaryFile = File(outputDir, "gate2_suite_summary.json")
        summaryFile.writeText(
            Json.stringify(
                mapOf(
                    "schema" to "raphael.gate2.suite/1",
                    "note" to "Informational only. Run tools/proof/check_gate2.py on the raw reports for the verdict.",
                    "runs" to summary
                )
            )
        )
        return Gate2SuiteResult(reportFiles, summaryFile, lines)
    }
}
