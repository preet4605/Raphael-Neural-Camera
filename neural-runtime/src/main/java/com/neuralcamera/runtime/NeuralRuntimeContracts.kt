package com.neuralcamera.runtime

import com.neuralcamera.models.HardwareBackendType
import com.neuralcamera.models.ModelDescriptor

data class TensorData(
    val shape: IntArray,
    val buffer: ByteArray
) {
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
    val isFallbackUsed: Boolean = false
)

interface InferenceBackend {
    val backendType: HardwareBackendType
    fun isAvailable(): Boolean
    suspend fun initialize(): Boolean
    suspend fun executeInference(model: ModelDescriptor, input: TensorData): TensorData
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
