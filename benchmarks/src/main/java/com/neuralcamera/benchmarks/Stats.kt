package com.neuralcamera.benchmarks

import kotlin.math.ceil

/** Latency/value summary. Percentiles use the nearest-rank definition: sorted[ceil(p/100 * n) - 1]. */
data class Summary(
    val count: Int,
    val min: Double,
    val median: Double,
    val mean: Double,
    val p95: Double,
    val p99: Double,
    val max: Double
)

object Stats {

    fun percentileNearestRank(sorted: DoubleArray, percent: Double): Double {
        require(sorted.isNotEmpty()) { "empty sample" }
        require(percent > 0.0 && percent <= 100.0) { "percent must be in (0, 100]" }
        val rank = ceil(percent / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    /** Standard median: the middle value, or the mean of the two middle values for an even count. */
    fun median(sorted: DoubleArray): Double {
        require(sorted.isNotEmpty()) { "empty sample" }
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
    }

    fun summarize(values: DoubleArray): Summary {
        require(values.isNotEmpty()) { "empty sample" }
        val sorted = values.sortedArray()
        return Summary(
            count = sorted.size,
            min = sorted.first(),
            median = median(sorted),
            mean = values.sum() / values.size,
            p95 = percentileNearestRank(sorted, 95.0),
            p99 = percentileNearestRank(sorted, 99.0),
            max = sorted.last()
        )
    }
}
