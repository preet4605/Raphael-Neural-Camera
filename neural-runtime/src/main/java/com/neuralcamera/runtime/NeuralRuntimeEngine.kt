package com.neuralcamera.runtime

import com.neuralcamera.deviceprofiles.DeviceProfile
import com.neuralcamera.models.HardwareBackendType
import com.neuralcamera.models.ModelCompatibilityState
import com.neuralcamera.models.ModelDescriptor
import com.neuralcamera.models.PredefinedModelCatalog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class StandardModelRegistry : ModelRegistry {

    private val models = ConcurrentHashMap<String, ModelDescriptor>()

    init {
        registerModel(PredefinedModelCatalog.CLASSICAL_BASELINE_ISP)
        registerModel(PredefinedModelCatalog.DENOISE_TINY_V1)
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
        val latencyMs = model.expectedLatencyMs ?: return false // unmeasured is never "within budget"
        return latencyMs <= maxBudgetMs
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
                selectedBackend = HardwareBackendType.CPU_REFERENCE,
                maxFramesToProcess = 1,
                useClassicalFallback = true,
                scheduleReason = "Requested model $requestedModelId not found; fell back to classical ISP."
            )

        // Rule 37 & 26: If thermal throttling is severe, fallback to classical ISP
        if (thermalManager.getCurrentThermalState().level >= ThermalState.SEVERE.level) {
            val fallback = modelRegistry.findFallbackFor(requestedModelId) ?: PredefinedModelCatalog.CLASSICAL_BASELINE_ISP
            return ScheduledPipeline(
                selectedModel = fallback,
                selectedBackend = HardwareBackendType.CPU_REFERENCE,
                maxFramesToProcess = thermalManager.getMaxAllowedBurstFrames(),
                useClassicalFallback = true,
                scheduleReason = "Thermal state ${thermalManager.getCurrentThermalState()} is severe; fallback to classical ISP."
            )
        }

        // Never run an unverified model on an accelerator: a catalog entry or a present library is not execution proof.
        if (!requestedModel.isClassicalFallback && requestedModel.compatibilityState != ModelCompatibilityState.VERIFIED) {
            val fallback = modelRegistry.findFallbackFor(requestedModelId) ?: PredefinedModelCatalog.CLASSICAL_BASELINE_ISP
            return ScheduledPipeline(
                selectedModel = fallback,
                selectedBackend = HardwareBackendType.CPU_REFERENCE,
                maxFramesToProcess = thermalManager.getMaxAllowedBurstFrames(),
                useClassicalFallback = true,
                scheduleReason = "Model $requestedModelId is ${requestedModel.compatibilityState}, not VERIFIED; fell back to classical ISP."
            )
        }

        // Rule 36: Check memory budget
        val requiredBytes = requestedModel.memoryRequirementBytes
        if (requiredBytes != null && !memoryManager.isWithinBudget(requiredBytes)) {
            val fallback = modelRegistry.findFallbackFor(requestedModelId) ?: PredefinedModelCatalog.CLASSICAL_BASELINE_ISP
            return ScheduledPipeline(
                selectedModel = fallback,
                selectedBackend = HardwareBackendType.CPU_REFERENCE,
                maxFramesToProcess = 2,
                useClassicalFallback = true,
                scheduleReason = "Memory pressure: cannot allocate ${requiredBytes / (1024 * 1024)}MB for model."
            )
        }

        // Rule 17: Prefer Qualcomm QNN NPU, then Vulkan, then the best proven CPU path, else the CPU reference
        val preferredBackend = when {
            deviceProfile.backends[HardwareBackendType.QUALCOMM_QNN_NPU]?.isUsable == true &&
                    requestedModel.supportedBackends.contains(HardwareBackendType.QUALCOMM_QNN_NPU) -> {
                HardwareBackendType.QUALCOMM_QNN_NPU
            }
            deviceProfile.backends[HardwareBackendType.VULKAN_GPU]?.isUsable == true &&
                    requestedModel.supportedBackends.contains(HardwareBackendType.VULKAN_GPU) -> {
                HardwareBackendType.VULKAN_GPU
            }
            deviceProfile.backends[HardwareBackendType.ORT_CPU]?.isUsable == true &&
                    requestedModel.supportedBackends.contains(HardwareBackendType.ORT_CPU) -> {
                HardwareBackendType.ORT_CPU
            }
            deviceProfile.backends[HardwareBackendType.XNNPACK_CPU]?.isUsable == true &&
                    requestedModel.supportedBackends.contains(HardwareBackendType.XNNPACK_CPU) -> {
                HardwareBackendType.XNNPACK_CPU
            }
            else -> HardwareBackendType.CPU_REFERENCE
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

        if (backend == null || !backend.isAvailable()) {
            throw BackendUnavailableException(
                "No available backend for ${scheduled.selectedBackend} (model ${scheduled.selectedModel.modelId}); " +
                    "refusing to return the input tensor as an inference result."
            )
        }
        val output = backend.executeInference(scheduled.selectedModel, request.inputTensor)
        val latency = System.currentTimeMillis() - startTime

        return InferenceResult(
            requestId = request.requestId,
            outputTensor = output.tensor,
            executionLatencyMs = latency,
            backendUsed = backend.backendType,
            isFallbackUsed = scheduled.useClassicalFallback,
            attribution = output.attribution
        )
    }

    override fun getBackend(type: HardwareBackendType): InferenceBackend? = backends[type]
}
