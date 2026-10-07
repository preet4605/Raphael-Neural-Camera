package com.neuralcamera.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelDescriptorTest {

    @Test
    fun testModelVersionComparison() {
        val v100 = ModelVersion(1, 0, 0)
        val v110 = ModelVersion(1, 1, 0)
        val v101 = ModelVersion(1, 0, 1)
        val v200 = ModelVersion(2, 0, 0)

        assertTrue(v100 < v110)
        assertTrue(v100 < v101)
        assertTrue(v110 < v200)
        assertEquals("1.0.0", v100.toString())
    }

    @Test
    fun testPredefinedCatalogIntegrity() {
        val classical = PredefinedModelCatalog.CLASSICAL_BASELINE_ISP
        assertTrue(classical.isClassicalFallback)
        assertEquals(ModelCompatibilityState.UNVERIFIED, classical.compatibilityState)

        val neuralIsp = PredefinedModelCatalog.NEURAL_ISP_LITE
        assertNotNull(neuralIsp.fallbackModelId)
        assertEquals(classical.modelId, neuralIsp.fallbackModelId)
    }

    @Test
    fun testCatalogClaimsNothingUnmeasured() {
        val catalog = listOf(
            PredefinedModelCatalog.CLASSICAL_BASELINE_ISP,
            PredefinedModelCatalog.NEURAL_ISP_LITE,
            PredefinedModelCatalog.PERCEPTION_SCENE_ALIGNMENT,
            PredefinedModelCatalog.OMNI_NEURAL_4B_MOBILE,
            PredefinedModelCatalog.FLUX_KLEIN_4B_STUDIO
        )
        for (model in catalog) {
            assertEquals(model.modelId, ModelCompatibilityState.UNVERIFIED, model.compatibilityState)
            assertNull(model.modelId, model.expectedLatencyMs)
            assertNull(model.modelId, model.memoryRequirementBytes)
            assertNull(model.modelId, model.thermalCostScore)
            assertEquals(model.modelId, 0L, model.fileSizeBytes)
            assertEquals(model.modelId, "UNSPECIFIED", model.license)
        }
    }
}
