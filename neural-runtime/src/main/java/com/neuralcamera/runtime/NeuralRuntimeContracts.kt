package com.neuralcamera.runtime

import com.neuralcamera.models.HardwareBackendType
import com.neuralcamera.models.ModelDescriptor
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class TensorData(
    val shape: IntArray,
    val buffer: ByteArray
) {
    /** Interprets [buffer] as little-endian FP32. */
    fun toFloatArray(): FloatArray {
        val floats = FloatArray(buffer.size / 4)
        ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(floats)
        return floats
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TensorData) return false
        return shape.contentEquals(other.shape) && buffer.contentEquals(other.buffer)
    }

    override fun hashCode(): Int {
        var result = shape.contentHashCode()
        result = 31 * result + buffer.contentHashCode()
        return result
    }

    companion object {
        /** Packs FP32 values as little-endian bytes. */
        fun ofFloats(shape: IntArray, values: FloatArray): TensorData {
            val bytes = ByteArray(values.size * 4)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().put(values)
            return TensorData(shape, bytes)
        }
    }
}

/**
 * Handle representing a loaded or active model in the runtime engine (Section 15).
 */
data class ModelHandle(
    val modelId: String,
    val descriptor: ModelDescriptor,
    val isLoaded: Boolean = false,
    val allocatedMemoryBytes: Long = 0L
)

/**
 * Encapsulated inference execution request (Section 15).
 */
data class InferenceRequest(
    val requestId: String,
    val modelId: String,
    val inputTensor: TensorData,
    val preferredBackend: HardwareBackendType? = null,
    val timeoutMs: Long = 5000L
)

/**
 * Encapsulated inference execution result (Section 15).
 */
data class InferenceResult(
    val requestId: String,
    val outputTensor: TensorData,
    val executionLatencyMs: Long,
    val backendUsed: HardwareBackendType,
    val isFallbackUsed: Boolean = false,
    val attribution: BackendAttribution
)

/** Where a computation actually ran, as established by evidence rather than by what was requested. */
enum class ExecutionTarget {
    /** Pure-Kotlin golden reference. */
    CPU_REFERENCE,

    /** A runtime's CPU path (e.g. ONNX Runtime CPU EP). Never accelerator evidence. */
    CPU_RUNTIME,

    GPU,

    /** Hexagon HTP / NPU. */
    NPU_HTP,

    /** Evidence is missing or contradictory. Treated as not proven. */
    UNKNOWN
}

/**
 * In-process execution attribution. [wholeGraphOnTarget] must be true only when the runtime itself reported that the
 * entire measured graph ran on [target] with no CPU fallback. A backend merely being selected, or its library being
 * present, is never attribution.
 */
data class BackendAttribution(
    val target: ExecutionTarget,
    val wholeGraphOnTarget: Boolean,
    val evidence: List<String>,
    /** Raw machine-readable facts the verdict was derived from, so an independent checker can re-derive it. */
    val details: Map<String, Any?> = emptyMap()
) {
    val provesHtp: Boolean get() = target == ExecutionTarget.NPU_HTP && wholeGraphOnTarget && evidence.isNotEmpty()
    val provesGpu: Boolean get() = target == ExecutionTarget.GPU && wholeGraphOnTarget && evidence.isNotEmpty()

    companion object {
        fun cpuReference() = BackendAttribution(
            ExecutionTarget.CPU_REFERENCE, wholeGraphOnTarget = true, evidence = listOf("pure-Kotlin FP32 reference executed in-process")
        )

        fun unknown(reason: String) = BackendAttribution(ExecutionTarget.UNKNOWN, false, listOf(reason))
    }
}

/** Output of one backend execution together with its attribution. */
data class InferenceOutput(
    val tensor: TensorData,
    val attribution: BackendAttribution
)

/**
 * Thrown when no real backend executed a request. Callers must never receive an unprocessed
 * input tensor presented as an inference result.
 */
class BackendUnavailableException(message: String) : IllegalStateException(message)

/** Thrown when a backend has no way to run the requested model. */
class UnsupportedModelException(message: String) : IllegalArgumentException(message)

interface InferenceBackend {
    val backendType: HardwareBackendType
    fun isAvailable(): Boolean
    suspend fun initialize(): Boolean

    /** Loads/compiles [model] ahead of the first run. Returns false if this backend cannot run it. */
    suspend fun prepare(model: ModelDescriptor): Boolean

    /** Runs one inference. Throws if the model cannot run on this backend; never returns the input as a result. */
    suspend fun executeInference(model: ModelDescriptor, input: TensorData): InferenceOutput

    /**
     * Finalizes evidence collection after the last run (e.g. ends profiling) and returns the definitive attribution.
     * Per-run attributions from [executeInference] may be provisional; this one is the evidence of record.
     */
    suspend fun finalizeAttribution(): BackendAttribution =
        BackendAttribution.unknown("backend provides no finalized attribution")

    fun release()
}

interface ModelRegistry {
    fun registerModel(model: ModelDescriptor)
    fun getModel(modelId: String): ModelDescriptor?
    fun listModels(): List<ModelDescriptor>
    fun findFallbackFor(modelId: String): ModelDescriptor?
}

enum class ThermalState(val level: Int) {
    NONE(0),
    LIGHT(1),
    MODERATE(2),
    SEVERE(3),
    CRITICAL(4),
    EMERGENCY(5),
    SHUTDOWN(6)
}

interface ThermalManager {
    fun getCurrentThermalState(): ThermalState
    fun shouldThrottleCompute(): Boolean
    fun getMaxAllowedBurstFrames(): Int
}

interface MemoryManager {
    fun allocateBuffer(sizeBytes: Long): Boolean
    fun releaseBuffer(sizeBytes: Long)
    fun getCurrentlyAllocatedBytes(): Long
    fun isWithinBudget(requiredBytes: Long): Boolean
}

interface ComputeBudgetManager {
    fun computeAvailableLatencyBudgetMs(targetFps: Int): Long
    fun isModelWithinBudget(model: ModelDescriptor, targetFps: Int): Boolean
}

data class ScheduledPipeline(
    val selectedModel: ModelDescriptor,
    val selectedBackend: HardwareBackendType,
    val maxFramesToProcess: Int,
    val useClassicalFallback: Boolean,
    val scheduleReason: String
)

interface PipelineScheduler {
    fun scheduleExecution(
        requestedModelId: String,
        targetFps: Int
    ): ScheduledPipeline
}

/**
 * Inference runtime abstraction contract (Section 15).
 */
interface InferenceRuntime {
    suspend fun runInference(modelId: String, input: TensorData): TensorData
    suspend fun execute(request: InferenceRequest): InferenceResult
    fun getBackend(type: HardwareBackendType): InferenceBackend?
}
