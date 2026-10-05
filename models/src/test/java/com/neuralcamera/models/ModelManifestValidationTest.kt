package com.neuralcamera.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelManifestValidationTest {

    @Test
    fun testClassicalBaselineValidation() {
        val classical = PredefinedModelCatalog.CLASSICAL_BASELINE_ISP
        val result = ModelManifestValidator.validate(classical)
        assertTrue(result.isValid)
        assertTrue(result.issues.isEmpty())
        assertEquals(classical.fileSizeBytes, classical.size)
        assertEquals(classical.tensorPrecision, classical.precision)
        assertEquals(classical.memoryRequirementBytes, classical.memoryRequirement)
        assertEquals(classical.expectedLatencyMs, classical.latencyExpectation)
    }

    @Test
    fun testNeuralIspLiteValidation() {
        val neuralIsp = PredefinedModelCatalog.NEURAL_ISP_LITE
        val result = ModelManifestValidator.validate(neuralIsp)
        assertTrue(result.isValid)
        assertNotNull(neuralIsp.fallback)
        assertEquals(PredefinedModelCatalog.CLASSICAL_BASELINE_ISP.modelId, neuralIsp.fallback)
        assertTrue(neuralIsp.supportedBackends.contains(HardwareBackendType.QUALCOMM_QNN_NPU))
    }

    @Test
    fun testInvalidManifestDetected() {
        val invalidModel = ModelDescriptor(
            modelId = "",
            name = "Invalid",
            purpose = SemanticPurpose.NEURAL_ISP,
            version = ModelVersion(1, 0, 0),
            license = "",
            fileSizeBytes = -1L,
            inputFormat = "RAW",
            outputFormat = "RGB",
            inputResolution = Pair(100, 100),
            outputResolution = Pair(100, 100),
            tensorPrecision = TensorPrecision.FP32,
            supportedBackends = emptyList(),
            memoryRequirementBytes = 0L,
            expectedLatencyMs = 0L,
            thermalCostScore = 0f,
            compatibilityRequirements = emptyList()
        )

        val result = ModelManifestValidator.validate(invalidModel)
        assertTrue(!result.isValid)
        assertTrue(result.issues.any { it.contains("modelId") })
        assertTrue(result.issues.any { it.contains("license") })
        assertTrue(result.issues.any { it.contains("supportedBackends") })
    }

    @Test
    fun testVerifiedModelRequiresArtifactAndMeasuredMetrics() {
        val unmeasuredButClaimedVerified = PredefinedModelCatalog.NEURAL_ISP_LITE.copy(
            compatibilityState = ModelCompatibilityState.VERIFIED
        )

        val result = ModelManifestValidator.validate(unmeasuredButClaimedVerified)

        assertTrue(!result.isValid)
        assertTrue(result.issues.any { it.contains("artifact") })
        assertTrue(result.issues.any { it.contains("memoryRequirement") })
        assertTrue(result.issues.any { it.contains("latencyExpectation") })
    }
}
