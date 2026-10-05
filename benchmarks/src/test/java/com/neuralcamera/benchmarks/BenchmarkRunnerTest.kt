package com.neuralcamera.benchmarks

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkRunnerTest {

    @Test
    fun testBenchmarkStageExecution() {
        runBlocking {
            val runner = StandardBenchmarkRunner(baselinesMs = mapOf("test_stage" to 50L))
            val (result, metrics) = runner.benchmarkStage("test_stage", iterations = 3) {
                var sum = 0L
                for (i in 0 until 10_000) sum += i
                sum
            }

            assertTrue(result > 0)
            assertEquals("test_stage", metrics.stageName)
            assertTrue(metrics.isSuccess)

            val summary = runner.getSummary("test_stage")
            assertNotNull(summary)
            assertEquals(1, summary!!.iterations)
        }
    }

    @Test
    fun testTelemetryLogging() {
        val logger = InMemoryTelemetryLogger()
        logger.logEvent("TEST_EVENT", mapOf("key" to "value"))
        logger.logMetric("fps", 60)

        val recent = logger.getRecentEvents()
        assertTrue(recent.size >= 2)
    }

    @Test
    fun testStructuredLoggingCategoriesAndFiltering() {
        val logger = InMemoryStructuredLogger()

        logger.log(
            category = LogCategory.CAMERA,
            level = LogLevel.INFO,
            message = "Camera session opened successfully",
            attributes = mapOf("cameraId" to "0")
        )

        logger.log(
            category = LogCategory.RUNTIME,
            level = LogLevel.DEBUG,
            message = "QNN backend initialized"
        )

        logger.log(
            category = LogCategory.FRAME,
            level = LogLevel.VERBOSE,
            message = "Raw FramePlane buffer received"
        )

        val cameraLogs = logger.getLogs(category = LogCategory.CAMERA)
        assertEquals(1, cameraLogs.size)
        assertEquals(LogCategory.CAMERA, cameraLogs[0].category)
        assertEquals("0", cameraLogs[0].attributes["cameraId"])

        // Test that pixel data indicator is sanitized
        val frameLogs = logger.getLogs(category = LogCategory.FRAME, minLevel = LogLevel.VERBOSE)
        assertEquals(1, frameLogs.size)
        assertEquals("[PIXEL_DATA_REDACTED]", frameLogs[0].message)
    }

    @Test
    fun testBenchmarkRecordStructure() {
        val record = BenchmarkRecord(
            testName = "neural_isp_inference",
            device = "OnePlus 15",
            model = "neural-isp-lite-v1",
            backend = "QUALCOMM_QNN_NPU",
            coldLatency = 45L,
            warmLatency = 28L,
            throughput = 35.7f,
            memoryPeak = 96 * 1024 * 1024L,
            thermalStart = 0,
            thermalEnd = 1,
            powerEstimate = 2.4f,
            success = true
        )

        assertEquals("neural_isp_inference", record.testName)
        assertEquals("OnePlus 15", record.device)
        assertTrue(record.success)
        assertEquals(28L, record.warmLatency)
    }
}
