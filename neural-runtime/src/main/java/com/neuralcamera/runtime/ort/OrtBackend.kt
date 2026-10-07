package com.neuralcamera.runtime.ort

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtProvider
import ai.onnxruntime.OrtSession
import com.neuralcamera.models.HardwareBackendType
import com.neuralcamera.models.ModelDescriptor
import com.neuralcamera.runtime.BackendAttribution
import com.neuralcamera.runtime.BackendUnavailableException
import com.neuralcamera.runtime.ExecutionTarget
import com.neuralcamera.runtime.InferenceBackend
import com.neuralcamera.runtime.InferenceOutput
import com.neuralcamera.runtime.ModelArtifacts
import com.neuralcamera.runtime.TensorData
import com.neuralcamera.runtime.UnsupportedModelException
import java.io.File
import java.nio.FloatBuffer

enum class OrtExecution { CPU, QNN_HTP }

/**
 * @property modelResource committed artifact name under `/models/` (e.g. FP32, QDQ).
 * @property enableProfiling records an ONNX Runtime profile whose node-event providers are the attribution evidence.
 * @property profilePathPrefix directory + file prefix for the profile; required when profiling (Android has no writable cwd).
 * @property nativeLibraryDir only used to list bundled QNN libraries in the bring-up log.
 */
data class OrtBackendConfig(
    val execution: OrtExecution,
    val modelId: String,
    val modelResource: String,
    val qnnProviderOptions: Map<String, String> = defaultHtpOptions(),
    val disableCpuEpFallback: Boolean = execution == OrtExecution.QNN_HTP,
    val enableProfiling: Boolean = false,
    val profilePathPrefix: String? = null,
    val intraOpThreads: Int? = null,
    val nativeLibraryDir: String? = null
) {
    companion object {
        /**
         * HTP defaults. `offload_graph_io_quantization=0` keeps graph-boundary Quantize/Dequantize on the QNN EP so that
         * disabling CPU EP fallback really means "no node ran on the CPU". `enable_htp_fp16_precision=1` (ORT default)
         * runs FP32 graphs in FP16 on the HTP.
         */
        fun defaultHtpOptions(): Map<String, String> = linkedMapOf(
            "backend_type" to "htp",
            "htp_performance_mode" to "default",
            "enable_htp_fp16_precision" to "1",
            "offload_graph_io_quantization" to "0",
            "profiling_level" to "off"
        )
    }
}

/**
 * ONNX Runtime backend (CPU EP or QNN EP on the Hexagon HTP). For QNN the attribution is only proven by
 * [finalizeAttribution], from three independent facts: the session was created with CPU EP fallback disabled, every
 * profiled node ran under the QNN EP (none under CPU), and the HTP stub library is mapped into this process.
 */
class OrtBackend(
    private val config: OrtBackendConfig,
    private val artifacts: (String) -> ByteArray = ModelArtifacts::readResource,
    private val readProcMaps: () -> String? = ProcMaps::readSelf
) : InferenceBackend, BringUpReporting {

    override val backendType: HardwareBackendType = when (config.execution) {
        OrtExecution.CPU -> HardwareBackendType.ORT_CPU
        OrtExecution.QNN_HTP -> HardwareBackendType.QUALCOMM_QNN_NPU
    }

    private val steps = mutableListOf<BringUpStep>()
    private var environment: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var inputName: String? = null
    private var modelSha256: String? = null
    private var profileSummary: OrtProfileSummary? = null
    private var profilePath: String? = null
    private var profileError: String? = null
    private var profileEnded = false

    override fun bringUpSteps(): List<BringUpStep> = steps.toList()

    private fun step(name: String, ok: Boolean, detail: String, startNs: Long? = null) {
        steps.add(BringUpStep(name, ok, detail, startNs?.let { (System.nanoTime() - it) / 1e6 }))
    }

    private fun env(): OrtEnvironment =
        environment ?: OrtEnvironment.getEnvironment().also { environment = it }

    private var availability: Boolean? = null

    override fun isAvailable(): Boolean = availability ?: try {
        val providers = OrtEnvironment.getAvailableProviders()
        val environment = env()
        step("ort.environment", true, "version=${environment.version} providers=${providers.joinToString { it.name }}")
        disableOrtTelemetry(environment)
        config.execution == OrtExecution.CPU || providers.contains(OrtProvider.QNN)
    } catch (t: Throwable) {
        step("ort.environment", false, "${t.javaClass.simpleName}: ${t.message}")
        false
    }.also { availability = it }

    /** The project is offline-only: switch ONNX Runtime's telemetry off explicitly (the app also strips INTERNET). */
    private fun disableOrtTelemetry(environment: OrtEnvironment) {
        try {
            environment.setTelemetry(false)
            step("ort.telemetry", true, "disabled")
        } catch (t: Throwable) {
            step("ort.telemetry", false, "setTelemetry(false) failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    override suspend fun initialize(): Boolean = isAvailable()

    override suspend fun prepare(model: ModelDescriptor): Boolean {
        if (model.modelId != config.modelId) {
            step("model.match", false, "backend configured for ${config.modelId}, asked for ${model.modelId}")
            return false
        }
        listBundledQnnLibraries()
        step("env.ADSP_LIBRARY_PATH", true, System.getenv("ADSP_LIBRARY_PATH") ?: "<unset>")
        val started = System.nanoTime()
        return try {
            val bytes = artifacts(config.modelResource)
            modelSha256 = ModelArtifacts.sha256Hex(bytes)
            OrtSession.SessionOptions().use { opts ->
                opts.setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_WARNING)
                config.intraOpThreads?.let { opts.setIntraOpNumThreads(it) }
                if (config.enableProfiling) {
                    val prefix = config.profilePathPrefix
                        ?: throw IllegalStateException("profilePathPrefix is required when profiling")
                    opts.enableProfiling(prefix)
                }
                if (config.execution == OrtExecution.QNN_HTP) {
                    if (config.disableCpuEpFallback) opts.addConfigEntry("session.disable_cpu_ep_fallback", "1")
                    opts.addQnn(config.qnnProviderOptions)
                }
                session = env().createSession(bytes, opts)
            }
            inputName = session!!.inputNames.first()
            step("session.create", true, "model=${config.modelResource} sha256=$modelSha256", started)
            true
        } catch (t: Throwable) {
            step("session.create", false, "${t.javaClass.simpleName}: ${t.message}", started)
            session = null
            false
        }
    }

    private fun listBundledQnnLibraries() {
        val dir = config.nativeLibraryDir ?: return
        val libs = File(dir).listFiles { f -> f.name.startsWith("libQnn") || f.name == "libonnxruntime.so" }
            ?.sortedBy { it.name }
            ?.joinToString { "${it.name}(${it.length()})" }
        step("native.libs", !libs.isNullOrEmpty(), "dir=$dir libs=${libs ?: "<none>"}")
    }

    override suspend fun executeInference(model: ModelDescriptor, input: TensorData): InferenceOutput {
        if (model.modelId != config.modelId) {
            throw UnsupportedModelException("OrtBackend is configured for ${config.modelId}, not ${model.modelId}")
        }
        val s = session ?: throw BackendUnavailableException("prepare() has not produced a session")
        val shape = LongArray(input.shape.size) { input.shape[it].toLong() }
        OnnxTensor.createTensor(env(), FloatBuffer.wrap(input.toFloatArray()), shape).use { tensor ->
            s.run(mapOf(inputName!! to tensor)).use { result ->
                val out = result[0] as OnnxTensor
                val buffer = out.floatBuffer
                val values = FloatArray(buffer.remaining())
                buffer.get(values)
                val outShape = IntArray(out.info.shape.size) { out.info.shape[it].toInt() }
                return InferenceOutput(TensorData.ofFloats(outShape, values), provisionalAttribution())
            }
        }
    }

    private fun provisionalAttribution(): BackendAttribution = when (config.execution) {
        OrtExecution.CPU -> BackendAttribution(
            ExecutionTarget.CPU_RUNTIME, true, listOf("ONNX Runtime CPU EP session executed in-process")
        )
        OrtExecution.QNN_HTP -> BackendAttribution(
            ExecutionTarget.NPU_HTP, false, listOf("QNN EP session executed; attribution pending finalizeAttribution()")
        )
    }

    override suspend fun finalizeAttribution(): BackendAttribution {
        val s = session ?: return BackendAttribution.unknown("no ONNX Runtime session was created")
        if (config.enableProfiling && !profileEnded) {
            profileEnded = true
            try {
                val path = s.endProfiling()
                profilePath = path
                profileSummary = OrtProfile.summarizeFile(path)
            } catch (t: Throwable) {
                profileError = "${t.javaClass.simpleName}: ${t.message}"
            }
        }
        val maps = readProcMaps()
        val libs = maps?.let { ProcMaps.mappedLibraries(it) } ?: emptyList()
        val libPaths = maps?.let { ProcMaps.mappedLibraryPaths(it) } ?: emptyList()
        val counts = profileSummary?.providerCounts ?: emptyMap()

        val details = linkedMapOf<String, Any?>(
            "execution" to config.execution.name,
            "ortVersion" to env().version,
            // Which QNN copy is loaded (app-bundled 2.42.0 vs vendor 2.37.4) is read from these paths; informational,
            // the checker's verdict does not depend on it.
            "mappedLibraryPaths" to libPaths,
            "modelResource" to config.modelResource,
            "modelSha256" to modelSha256,
            "sessionCreated" to true,
            "cpuEpFallbackDisabled" to config.disableCpuEpFallback,
            "providerOptions" to if (config.execution == OrtExecution.QNN_HTP) config.qnnProviderOptions else emptyMap<String, String>(),
            "ortProfile" to mapOf(
                "enabled" to config.enableProfiling,
                "path" to profilePath,
                "nodeEvents" to profileSummary?.nodeEvents,
                "providerCounts" to counts,
                "error" to profileError
            ),
            "mappedLibraries" to libs,
            "adspLibraryPath" to System.getenv("ADSP_LIBRARY_PATH"),
            "rule" to RULE
        )

        return when (config.execution) {
            OrtExecution.CPU -> {
                val onlyCpu = counts.isNotEmpty() && counts.keys.all { it == OrtProfile.CPU_PROVIDER }
                BackendAttribution(
                    ExecutionTarget.CPU_RUNTIME,
                    wholeGraphOnTarget = onlyCpu || !config.enableProfiling,
                    evidence = listOf("ONNX Runtime CPU EP; profile providers=$counts"),
                    details = details
                )
            }
            OrtExecution.QNN_HTP -> attributeHtp(counts, libs, details)
        }
    }

    private fun attributeHtp(counts: Map<String, Int>, libs: List<String>, details: Map<String, Any?>): BackendAttribution {
        val failures = HtpAttributionRule.failures(
            cpuEpFallbackDisabled = config.disableCpuEpFallback,
            backendType = config.qnnProviderOptions["backend_type"],
            profilingEnabled = config.enableProfiling,
            providerCounts = counts,
            mappedLibraries = libs
        )
        val proven = failures.isEmpty()
        val evidence = buildList {
            add("QNN EP session created, CPU EP fallback disabled=${config.disableCpuEpFallback}, backend_type=${config.qnnProviderOptions["backend_type"]}")
            add("ORT profile node providers=$counts")
            add("mapped QNN/FastRPC libraries=$libs")
            failures.forEach { add("NOT PROVEN: $it") }
        }
        return BackendAttribution(
            target = if (proven) ExecutionTarget.NPU_HTP else ExecutionTarget.UNKNOWN,
            wholeGraphOnTarget = proven,
            evidence = evidence,
            details = details
        )
    }

    override fun release() {
        try {
            session?.close()
        } catch (_: Throwable) {
        }
        session = null
    }

    companion object {
        const val RULE = "wholeGraphOnHtp = sessionCreated && cpuEpFallbackDisabled && providerOptions.backend_type == 'htp' && " +
            "ortProfile.enabled && providerCounts['QNNExecutionProvider'] > 0 && no other provider counted && " +
            "mappedLibraries contains a name starting with 'libQnnHtp'"
    }
}

/** The rule that decides whether an ONNX Runtime QNN session is attributed to the HTP. Mirrored in tools/proof/check_gate2.py. */
internal object HtpAttributionRule {
    /** Returns the reasons attribution is NOT proven; empty means proven. */
    fun failures(
        cpuEpFallbackDisabled: Boolean,
        backendType: String?,
        profilingEnabled: Boolean,
        providerCounts: Map<String, Int>,
        mappedLibraries: List<String>
    ): List<String> = buildList {
        val qnnNodes = providerCounts[OrtProfile.QNN_PROVIDER] ?: 0
        val otherNodes = providerCounts.filterKeys { it != OrtProfile.QNN_PROVIDER }.values.sum()
        if (!cpuEpFallbackDisabled) add("CPU EP fallback was not disabled")
        if (backendType != "htp") add("QNN backend_type is not htp")
        if (!profilingEnabled) add("profiling disabled: no per-node provider evidence")
        else if (qnnNodes == 0) add("no profiled node ran under ${OrtProfile.QNN_PROVIDER}")
        if (otherNodes > 0) add("$otherNodes profiled node(s) ran under another provider: $providerCounts")
        if (mappedLibraries.none { it.startsWith("libQnnHtp") }) add("no libQnnHtp* library is mapped into this process")
    }
}
