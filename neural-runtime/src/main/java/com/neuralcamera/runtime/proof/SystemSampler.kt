package com.neuralcamera.runtime.proof

/**
 * One snapshot of device state taken during a proof run. Any field the platform cannot provide is null, never a
 * placeholder value.
 */
data class SystemSample(
    val iteration: Int,
    val elapsedRealtimeNanos: Long,
    /** android.os.PowerManager THERMAL_STATUS_* (0 none .. 6 shutdown). */
    val thermalStatus: Int? = null,
    val thermalHeadroom: Float? = null,
    val batteryTempC: Float? = null,
    /** Process CPU time (android.os.Process.getElapsedCpuTime). */
    val processCpuTimeMs: Long? = null,
    val pssKb: Long? = null,
    val nativeHeapKb: Long? = null,
    val javaHeapKb: Long? = null,
    val availMemKb: Long? = null,
    val lowMemory: Boolean? = null
)

interface SystemSampler {
    fun sample(iteration: Int): SystemSample

    /** Sampler for hosts with no device telemetry: every field is null. */
    object None : SystemSampler {
        override fun sample(iteration: Int) = SystemSample(iteration, System.nanoTime())
    }
}
