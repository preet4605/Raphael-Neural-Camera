package com.neuralcamera.runtime

import com.neuralcamera.deviceprofiles.PredefinedDeviceProfiles
import com.neuralcamera.models.HardwareBackendType
import com.neuralcamera.models.ModelCompatibilityState
import com.neuralcamera.models.ModelDescriptor
import com.neuralcamera.models.PredefinedModelCatalog
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NeuralRuntimeEngineTest {

    private val profile = PredefinedDeviceProfiles.ONEPLUS_15
    private val registry = StandardModelRegistry()
    private val thermal = StandardThermalManager(profile)
    private val memory = StandardMemoryManager(profile.memoryLimits.maxModelResidencyBytes)
    private val budget = StandardComputeBudgetManager()

    /** Test fixture only: the real catalog contains no verified model. */
    private val verifiedModel = PredefinedModelCatalog.NEURAL_ISP_LITE.copy(
        modelId = "test-verified-model",
        fileSizeBytes = 1024L,
        memoryRequirementBytes = 96 * 1024 * 1024L,
        expectedLatencyMs = 10L,
        compatibilityState = ModelCompatibilityState.VERIFIED
    )

    init {
        registry.registerModel(verifiedModel)
    }

    private val scheduler = AdaptivePipelineScheduler(
        modelRegistry = registry,
        thermalManager = thermal,
        memoryManager = memory,
        computeBudgetManager = budget,
        deviceProfile = profile
    )

    @Test
    fun testShippedProfileDoesNotAssumeAcceleratorsUsable() {
        assertFalse(profile.backends.getValue(HardwareBackendType.QUALCOMM_QNN_NPU).isUsable)
        assertFalse(profile.backends.getValue(HardwareBackendType.VULKAN_GPU).isUsable)

        val scheduled = scheduler.scheduleExecution(verifiedModel.modelId, targetFps = 30)

        assertEquals(HardwareBackendType.CPU_REFERENCE, scheduled.selectedBackend)
    }

    @Test
    fun testUnverifiedModelFallsBackToClassicalCpu() {
        val scheduled = scheduler.scheduleExecution(
            PredefinedModelCatalog.NEURAL_ISP_LITE.modelId,
            targetFps = 30
        )

        assertTrue(scheduled.useClassicalFallback)
        assertEquals(PredefinedModelCatalog.CLASSICAL_BASELINE_ISP, scheduled.selectedModel)
        assertEquals(HardwareBackendType.CPU_REFERENCE, scheduled.selectedBackend)
        assertTrue(scheduled.scheduleReason.contains("not VERIFIED"))
    }

    @Test
    fun testVerifiedModelOnProvenNpuSelectsNpu() {
        val qnn = profile.backends.getValue(HardwareBackendType.QUALCOMM_QNN_NPU)
        val provenProfile = profile.copy(
            backends = profile.backends + (HardwareBackendType.QUALCOMM_QNN_NPU to qnn.copy(isUsable = true))
        )
        val provenScheduler = AdaptivePipelineScheduler(registry, thermal, memory, budget, provenProfile)

        val scheduled = provenScheduler.scheduleExecution(verifiedModel.modelId, targetFps = 30)

        assertEquals(HardwareBackendType.QUALCOMM_QNN_NPU, scheduled.selectedBackend)
        assertFalse(scheduled.useClassicalFallback)
        assertEquals(profile.thermalLimits.maxBurstFramesNormal, scheduled.maxFramesToProcess)
    }

    @Test
    fun testSevereThermalForcesClassicalFallback() {
        thermal.updateThermalState(ThermalState.SEVERE)
        val scheduled = scheduler.scheduleExecution(verifiedModel.modelId, targetFps = 30)

        assertTrue(scheduled.useClassicalFallback)
        assertEquals(HardwareBackendType.CPU_REFERENCE, scheduled.selectedBackend)
        assertEquals(profile.thermalLimits.maxBurstFramesThrottled, scheduled.maxFramesToProcess)
    }

    @Test
    fun testMemoryBudgetEnforcement() {
        val smallMemory = StandardMemoryManager(maxBudgetBytes = 50 * 1024 * 1024L) // 50MB
        val constrainedScheduler = AdaptivePipelineScheduler(
            modelRegistry = registry,
            thermalManager = thermal,
            memoryManager = smallMemory,
            computeBudgetManager = budget,
            deviceProfile = profile
        )

        // The fixture model requires 96MB, which exceeds 50MB
        val scheduled = constrainedScheduler.scheduleExecution(verifiedModel.modelId, targetFps = 30)

        assertTrue(scheduled.useClassicalFallback)
        assertTrue(scheduled.scheduleReason.contains("Memory pressure"))
    }

    @Test
    fun testUnmeasuredModelIsNeverWithinLatencyBudget() {
        assertFalse(budget.isModelWithinBudget(PredefinedModelCatalog.NEURAL_ISP_LITE, targetFps = 30))
        assertTrue(budget.isModelWithinBudget(verifiedModel, targetFps = 30))
    }

    @Test
    fun testInferenceWithoutBackendThrowsInsteadOfPassingInputThrough() {
        val runtime = StandardInferenceRuntime(registry, scheduler, backends = emptyMap())
        val input = TensorData(intArrayOf(1, 4), byteArrayOf(1, 2, 3, 4))

        assertThrows(BackendUnavailableException::class.java) {
            runBlocking { runtime.runInference(verifiedModel.modelId, input) }
        }
    }

    @Test
    fun testInferenceAttributesResultToExecutingBackend() {
        val executed = byteArrayOf(9, 9, 9, 9)
        val backend = object : InferenceBackend {
            override val backendType = HardwareBackendType.CPU_REFERENCE
            override fun isAvailable() = true
            override suspend fun initialize() = true
            override suspend fun prepare(model: ModelDescriptor) = true
            override suspend fun executeInference(model: ModelDescriptor, input: TensorData) =
                InferenceOutput(TensorData(input.shape, executed), BackendAttribution.cpuReference())
            override fun release() {}
        }
        val runtime = StandardInferenceRuntime(
            registry, scheduler, backends = mapOf(HardwareBackendType.CPU_REFERENCE to backend)
        )

        val result = runBlocking {
            runtime.execute(
                InferenceRequest("r1", verifiedModel.modelId, TensorData(intArrayOf(1, 4), byteArrayOf(1, 2, 3, 4)))
            )
        }

        assertEquals(HardwareBackendType.CPU_REFERENCE, result.backendUsed)
        assertEquals(ExecutionTarget.CPU_REFERENCE, result.attribution.target)
        assertFalse(result.attribution.provesHtp)
        assertTrue(result.outputTensor.buffer.contentEquals(executed))
    }
}
