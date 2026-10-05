package com.neuralcamera.runtime

import com.neuralcamera.deviceprofiles.DeviceProfile
import com.neuralcamera.models.HardwareBackendType
import com.neuralcamera.models.ModelDescriptor
import com.neuralcamera.models.PredefinedModelCatalog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class StandardModelRegistry : ModelRegistry {

    private val models = ConcurrentHashMap<String, ModelDescriptor>()

    init {
        registerModel(PredefinedModelCatalog.CLASSICAL_BASELINE_ISP)
        registerModel(PredefinedModelCatalog.NEURAL_ISP_LITE)
        registerModel(PredefinedModelCatalog.PERCEPTION_SCENE_ALIGNMENT)
        registerModel(PredefinedModelCatalog.OMNI_NEURAL_4B_MOBILE)
        registerModel(PredefinedModelCatalog.FLUX_KLEIN_4B_STUDIO)
    }

    override fun registerModel(model: ModelDescriptor) {
        models[model.modelId] = model
    }

    override fun getModel(modelId: String): ModelDescriptor? = models[modelId]

    override fun listModels(): List<ModelDescriptor> = models.values.toList()

    override fun findFallbackFor(modelId: String): ModelDescriptor? {
        val model = models[modelId] ?: return PredefinedModelCatalog.CLASSICAL_BASELINE_ISP
        val fallbackId = model.fallbackModelId ?: return if (model.isClassicalFallback) null else PredefinedModelCatalog.CLASSICAL_BASELINE_ISP
        return models[fallbackId] ?: PredefinedModelCatalog.CLASSICAL_BASELINE_ISP
    }
}

class StandardThermalManager(
    private val deviceProfile: DeviceProfile
) : ThermalManager {

    @Volatile
    private var currentState: ThermalState = ThermalState.NONE

    fun updateThermalState(state: ThermalState) {
        currentState = state
    }

    override fun getCurrentThermalState(): ThermalState = currentState

    override fun shouldThrottleCompute(): Boolean {
        return currentState.level >= ThermalState.MODERATE.level
    }

    override fun getMaxAllowedBurstFrames(): Int {
        return when (currentState) {
            ThermalState.NONE, ThermalState.LIGHT -> deviceProfile.thermalLimits.maxBurstFramesNormal
            ThermalState.MODERATE -> (deviceProfile.thermalLimits.maxBurstFramesNormal / 2).coerceAtLeast(3)
            ThermalState.SEVERE, ThermalState.CRITICAL, ThermalState.EMERGENCY, ThermalState.SHUTDOWN -> {
                deviceProfile.thermalLimits.maxBurstFramesThrottled
            }
        }
    }
}

class StandardMemoryManager(
    private val maxBudgetBytes: Long
) : MemoryManager {

    private val allocatedBytes = AtomicLong(0L)

    override fun allocateBuffer(sizeBytes: Long): Boolean {
        while (true) {
            val current = allocatedBytes.get()
            if (current + sizeBytes > maxBudgetBytes) {
                return false
            }
            if (allocatedBytes.compareAndSet(current, current + sizeBytes)) {
                return true
            }
        }
    }

    override fun releaseBuffer(sizeBytes: Long) {
        while (true) {
            val current = allocatedBytes.get()
            val next = (current - sizeBytes).coerceAtLeast(0L)
            if (allocatedBytes.compareAndSet(current, next)) {
                break
            }
        }
    }

    override fun getCurrentlyAllocatedBytes(): Long = allocatedBytes.get()

    override fun isWithinBudget(requiredBytes: Long): Boolean {
        return (allocatedBytes.get() + requiredBytes) <= maxBudgetBytes
    }
}

class StandardComputeBudgetManager : ComputeBudgetManager {

    override fun computeAvailableLatencyBudgetMs(targetFps: Int): Long {
        if (targetFps <= 0) return 33L // Default ~30fps
        return (1000L / targetFps)
    }

    override fun isModelWithinBudget(model: ModelDescriptor, targetFps: Int): Boolean {
        val maxBudgetMs = computeAvailableLatencyBudgetMs(targetFps)
        return model.expectedLatencyMs <= maxBudgetMs
    }
}

class AdaptivePipelineScheduler(
    private val modelRegistry: ModelRegistry,
    private val thermalManager: ThermalManager,
    private val memoryManager: MemoryManager,
    private val computeBudgetManager: ComputeBudgetManager,
    private val deviceProfile: DeviceProfile
) : PipelineScheduler {

    override fun scheduleExecution(requestedModelId: String, targetFps: Int): ScheduledPipeline {
        val requestedModel = modelRegistry.getModel(requestedModelId)
            ?: return ScheduledPipeline(
                selectedModel = PredefinedModelCatalog.CLASSICAL_BASELINE_ISP,
                selectedBackend = HardwareBackendType.XNNPACK_CPU,
                maxFramesToProcess = 1,
                useClassicalFallback = true,
                scheduleReason = "Requested model $requestedModelId not found; fell back to classical ISP."
            )

        // Rule 37 & 26: If thermal throttling is severe, fallback to classical ISP
        if (thermalManager.getCurrentThermalState().level >= ThermalState.SEVERE.level) {
            val fallback = modelRegistry.findFallbackFor(requestedModelId) ?: PredefinedModelCatalog.CLASSICAL_BASELINE_ISP
            return ScheduledPipeline(
                selectedModel = fallback,
                selectedBackend = HardwareBackendType.XNNPACK_CPU,
                maxFramesToProcess = thermalManager.getMaxAllowedBurstFrames(),
                useClassicalFallback = true,
                scheduleReason = "Thermal state ${thermalManager.getCurrentThermalState()} is severe; fallback to classical ISP."
            )
        }

        // Rule 36: Check memory budget
        if (!memoryManager.isWithinBudget(requestedModel.memoryRequirementBytes)) {
            val fallback = modelRegistry.findFallbackFor(requestedModelId) ?: PredefinedModelCatalog.CLASSICAL_BASELINE_ISP
            return ScheduledPipeline(
                selectedModel = fallback,
                selectedBackend = HardwareBackendType.XNNPACK_CPU,
                maxFramesToProcess = 2,
                useClassicalFallback = true,
                scheduleReason = "Memory pressure: cannot allocate ${requestedModel.memoryRequirementBytes / (1024 * 1024)}MB for model."
            )
        }

        // Rule 17: Prefer Qualcomm QNN NPU, then Vulkan, then XNNPACK CPU
        val preferredBackend = when {
            deviceProfile.backends[HardwareBackendType.QUALCOMM_QNN_NPU]?.isUsable == true &&
                    requestedModel.supportedBackends.contains(HardwareBackendType.QUALCOMM_QNN_NPU) -> {
                HardwareBackendType.QUALCOMM_QNN_NPU
            }
            deviceProfile.backends[HardwareBackendType.VULKAN_GPU]?.isUsable == true &&
                    requestedModel.supportedBackends.contains(HardwareBackendType.VULKAN_GPU) -> {
                HardwareBackendType.VULKAN_GPU
            }
            else -> HardwareBackendType.XNNPACK_CPU
        }

        val maxFrames = thermalManager.getMaxAllowedBurstFrames()

        return ScheduledPipeline(
            selectedModel = requestedModel,
            selectedBackend = preferredBackend,
            maxFramesToProcess = maxFrames,
            useClassicalFallback = requestedModel.isClassicalFallback,
            scheduleReason = "Scheduled on $preferredBackend with $maxFrames frames."
        )
    }
}

/**
 * Standard implementation of InferenceRuntime adhering to Section 15.
 */
class StandardInferenceRuntime(
    private val modelRegistry: ModelRegistry,
    private val scheduler: PipelineScheduler,
    private val backends: Map<HardwareBackendType, InferenceBackend> = emptyMap()
) : InferenceRuntime {

    override suspend fun runInference(modelId: String, input: TensorData): TensorData {
        val request = InferenceRequest(
            requestId = "req_${System.currentTimeMillis()}",
            modelId = modelId,
            inputTensor = input
        )
        return execute(request).outputTensor
    }

    override suspend fun execute(request: InferenceRequest): InferenceResult {
        val scheduled = scheduler.scheduleExecution(request.modelId, targetFps = 30)
        val startTime = System.currentTimeMillis()
        val backend = backends[scheduled.selectedBackend]

        val output = if (backend != null && backend.isAvailable()) {
            backend.executeInference(scheduled.selectedModel, request.inputTensor)
        } else {
            // Classical/safe fallback passthrough
            request.inputTensor
        }
        val latency = System.currentTimeMillis() - startTime

        return InferenceResult(
            requestId = request.requestId,
            outputTensor = output,
            executionLatencyMs = latency,
            backendUsed = scheduled.selectedBackend,
            isFallbackUsed = scheduled.useClassicalFallback
        )
    }

    override fun getBackend(type: HardwareBackendType): InferenceBackend? = backends[type]
}
