package com.neuralcamera.runtime

import com.neuralcamera.deviceprofiles.PredefinedDeviceProfiles
import com.neuralcamera.models.HardwareBackendType
import com.neuralcamera.models.PredefinedModelCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NeuralRuntimeEngineTest {

    private val profile = PredefinedDeviceProfiles.ONEPLUS_15
    private val registry = StandardModelRegistry()
    private val thermal = StandardThermalManager(profile)
    private val memory = StandardMemoryManager(profile.memoryLimits.maxModelResidencyBytes)
    private val budget = StandardComputeBudgetManager()

    private val scheduler = AdaptivePipelineScheduler(
        modelRegistry = registry,
        thermalManager = thermal,
        memoryManager = memory,
        computeBudgetManager = budget,
        deviceProfile = profile
    )

    @Test
    fun testNormalScheduleSelectsNpu() {
        val scheduled = scheduler.scheduleExecution(
            PredefinedModelCatalog.NEURAL_ISP_LITE.modelId,
            targetFps = 30
        )

        assertEquals(HardwareBackendType.QUALCOMM_QNN_NPU, scheduled.selectedBackend)
        assertFalse(scheduled.useClassicalFallback)
        assertEquals(profile.thermalLimits.maxBurstFramesNormal, scheduled.maxFramesToProcess)
    }

    @Test
    fun testSevereThermalForcesClassicalFallback() {
        thermal.updateThermalState(ThermalState.SEVERE)
        val scheduled = scheduler.scheduleExecution(
            PredefinedModelCatalog.NEURAL_ISP_LITE.modelId,
            targetFps = 30
        )

        assertTrue(scheduled.useClassicalFallback)
        assertEquals(HardwareBackendType.XNNPACK_CPU, scheduled.selectedBackend)
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

        // NEURAL_ISP_LITE requires 96MB, which exceeds 50MB
        val scheduled = constrainedScheduler.scheduleExecution(
            PredefinedModelCatalog.NEURAL_ISP_LITE.modelId,
            targetFps = 30
        )

        assertTrue(scheduled.useClassicalFallback)
        assertTrue(scheduled.scheduleReason.contains("Memory pressure"))
    }
}
