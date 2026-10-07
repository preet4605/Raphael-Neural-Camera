package com.neuralcamera.datalab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DataLabTest {

    @Test
    fun testControlledScenesCompleteness() {
        val manager = ReferenceDatasetManager()
        val scenes = manager.getReferenceScenes()
        // Section 30 specifies all 14 reference scene types
        assertEquals(14, scenes.size)
        assertEquals(SceneCategory.values().size, scenes.map { it.category }.toSet().size)
    }

    @Test
    fun testRegressionVerificationDetection() {
        val manager = ReferenceDatasetManager()
        val normalMetrics = mapOf(
            "SCENE_01_DAYLIGHT" to 40.0f,
            "SCENE_02_INDOOR" to 36.0f
        )
        val report = manager.verifyRegression(normalMetrics)
        // Two measured scenes pass; the other twelve have no measurement and must not count as passed.
        assertEquals(2, report.passedScenes)
        assertEquals(12, report.notTestedScenes.size)
        assertTrue(report.regressions.isEmpty())
        assertFalse(report.isRegressionFree)

        val all = ReferenceDatasetCatalog.CONTROLLED_SCENES.associate { it.sceneId to it.baselinePsnrMin + 1f }
        assertTrue(manager.verifyRegression(all).isRegressionFree)

        val degradedMetrics = mapOf(
            "SCENE_01_DAYLIGHT" to 32.0f // Baseline is 38f -> regression
        )
        val degradedReport = manager.verifyRegression(degradedMetrics)
        assertTrue(degradedReport.regressions.isNotEmpty())
    }
}
