package com.neuralcamera.benchmarks

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.system.measureTimeMillis

class StandardBenchmarkRunner(
    private val baselinesMs: Map<String, Long> = emptyMap()
) : BenchmarkRunner {

    private val recordedMetrics = ConcurrentHashMap<String, MutableList<ExecutionMetrics>>()

    override suspend fun <T> benchmarkStage(
        stageName: String,
        iterations: Int,
        block: suspend () -> T
    ): Pair<T, ExecutionMetrics> {
        val runtime = Runtime.getRuntime()
        val memBefore = runtime.totalMemory() - runtime.freeMemory()

        var lastResult: T? = null
        val durationMs = measureTimeMillis {
            for (i in 0 until iterations) {
                lastResult = block()
            }
        }
        val memAfter = runtime.totalMemory() - runtime.freeMemory()
        val memDelta = (memAfter - memBefore).coerceAtLeast(0L)

        val metrics = ExecutionMetrics(
            stageName = stageName,
            latencyMs = durationMs / iterations.coerceAtLeast(1),
            memoryAllocatedBytes = memDelta,
            thermalStateBefore = 0,
            thermalStateAfter = 0,
            isSuccess = true
        )
        recordMetrics(metrics)
        @Suppress("UNCHECKED_CAST")
        return Pair(lastResult as T, metrics)
    }

    override fun recordMetrics(metrics: ExecutionMetrics) {
        recordedMetrics.computeIfAbsent(metrics.stageName) { CopyOnWriteArrayList() }.add(metrics)
    }

    override fun getSummary(stageName: String): BenchmarkSummary? {
        val list = recordedMetrics[stageName] ?: return null
        if (list.isEmpty()) return null

        val latencies = list.map { it.latencyMs }.sorted()
        val median = latencies[latencies.size / 2]
        val p95Index = ((latencies.size * 0.95).toInt()).coerceAtMost(latencies.size - 1)
        val p99Index = ((latencies.size * 0.99).toInt()).coerceAtMost(latencies.size - 1)
        val p95 = latencies[p95Index]
        val p99 = latencies[p99Index]
        val peakMem = list.maxOf { it.memoryAllocatedBytes }
        val baseline = baselinesMs[stageName] ?: median
        val gainPercent = if (baseline > 0) ((baseline - median).toFloat() / baseline) * 100f else 0f

        return BenchmarkSummary(
            benchmarkName = stageName,
            timestampNs = System.nanoTime(),
            iterations = list.size,
            medianLatencyMs = median,
            p95LatencyMs = p95,
            p99LatencyMs = p99,
            peakMemoryBytes = peakMem,
            baselineLatencyMs = baseline,
            gainPercent = gainPercent
        )
    }

    override fun getAllSummaries(): List<BenchmarkSummary> {
        return recordedMetrics.keys.mapNotNull { getSummary(it) }
    }

    override fun clear() {
        recordedMetrics.clear()
    }
}

class InMemoryTelemetryLogger : TelemetryLogger {
    private val events = CopyOnWriteArrayList<Map<String, Any>>()

    override fun logEvent(tag: String, eventData: Map<String, Any>) {
        val payload = HashMap(eventData)
        payload["tag"] = tag
        payload["timestamp_ms"] = System.currentTimeMillis()
        events.add(payload)
        if (events.size > 500) {
            events.removeAt(0)
        }
    }

    override fun logMetric(name: String, value: Number) {
        logEvent("METRIC", mapOf("name" to name, "value" to value))
    }

    override fun logError(tag: String, message: String, throwable: Throwable?) {
        val payload = mutableMapOf<String, Any>(
            "error_msg" to message
        )
        if (throwable != null) {
            payload["throwable"] = throwable.javaClass.simpleName
            payload["stacktrace"] = throwable.stackTraceToString().take(500)
        }
        logEvent("ERROR_$tag", payload)
    }

    override fun getRecentEvents(limit: Int): List<Map<String, Any>> {
        return events.takeLast(limit)
    }
}
