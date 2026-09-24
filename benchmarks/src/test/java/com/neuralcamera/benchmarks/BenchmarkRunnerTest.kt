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
}
