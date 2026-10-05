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

/**
 * Future benchmark record data structure adhering to Section 20 of the Constitution.
 */
data class BenchmarkRecord(
    val testName: String,
    val device: String,
    val model: String,
    val backend: String,
    val coldLatency: Long,
    val warmLatency: Long,
    val throughput: Float,
    val memoryPeak: Long,
    val thermalStart: Int,
    val thermalEnd: Int,
    val powerEstimate: Float,
    val success: Boolean,
    val error: String? = null
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

/**
 * 13 structured logging categories mandated by Section 19 of the Constitution.
 */
enum class LogCategory {
    CAMERA,
    CAPTURE,
    FRAME,
    SENSOR,
    RUNTIME,
    MODEL,
    BACKEND,
    PIPELINE,
    QUALITY,
    THERMAL,
    MEMORY,
    STORAGE,
    UI
}

enum class LogLevel {
    VERBOSE,
    DEBUG,
    INFO,
    WARN,
    ERROR
}

data class StructuredLogEntry(
    val timestampNs: Long,
    val category: LogCategory,
    val level: LogLevel,
    val message: String,
    val attributes: Map<String, String> = emptyMap(),
    val error: String? = null
)

/**
 * Structured logging contract avoiding pixel byte data or sensitive PII (Section 19).
 */
interface StructuredLogger {
    fun log(
        category: LogCategory,
        level: LogLevel,
        message: String,
        attributes: Map<String, String> = emptyMap(),
        throwable: Throwable? = null
    )
    fun getLogs(
        category: LogCategory? = null,
        minLevel: LogLevel = LogLevel.DEBUG,
        limit: Int = 100
    ): List<StructuredLogEntry>
    fun clear()
}
