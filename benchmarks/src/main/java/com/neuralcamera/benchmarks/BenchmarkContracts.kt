package com.neuralcamera.benchmarks

data class ExecutionMetrics(
    val stageName: String,
    val latencyMs: Long,
    val memoryAllocatedBytes: Long,
    val thermalStateBefore: Int,
    val thermalStateAfter: Int,
    val isSuccess: Boolean,
    val errorMessage: String? = null
)

data class QualityMetrics(
    val psnrDb: Float,
    val ssimScore: Float,
    val noiseVariance: Float,
    val sharpnessGradientMean: Float,
    val hallucinationRiskScore: Float // 0.0 = safe/authentic, 1.0 = highly synthetic/hallucinated
)

data class BenchmarkSummary(
    val benchmarkName: String,
    val timestampNs: Long,
    val iterations: Int,
    val medianLatencyMs: Long,
    val p95LatencyMs: Long,
    val p99LatencyMs: Long,
    val peakMemoryBytes: Long,
    val baselineLatencyMs: Long,
    val gainPercent: Float
)

interface BenchmarkRunner {
    suspend fun <T> benchmarkStage(
        stageName: String,
        iterations: Int = 1,
        block: suspend () -> T
    ): Pair<T, ExecutionMetrics>

    fun recordMetrics(metrics: ExecutionMetrics)
    fun getSummary(stageName: String): BenchmarkSummary?
    fun getAllSummaries(): List<BenchmarkSummary>
    fun clear()
}

interface TelemetryLogger {
    fun logEvent(tag: String, eventData: Map<String, Any>)
    fun logMetric(name: String, value: Number)
    fun logError(tag: String, message: String, throwable: Throwable? = null)
    fun getRecentEvents(limit: Int = 100): List<Map<String, Any>>
}
