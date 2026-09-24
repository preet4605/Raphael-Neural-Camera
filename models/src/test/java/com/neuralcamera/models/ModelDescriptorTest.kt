package com.neuralcamera.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
        assertEquals(ModelCompatibilityState.VERIFIED, classical.compatibilityState)

        val neuralIsp = PredefinedModelCatalog.NEURAL_ISP_LITE
        assertNotNull(neuralIsp.fallbackModelId)
        assertEquals(classical.modelId, neuralIsp.fallbackModelId)
    }
}
