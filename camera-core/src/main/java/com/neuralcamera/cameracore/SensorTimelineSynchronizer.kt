package com.neuralcamera.cameracore

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import java.util.concurrent.ConcurrentSkipListMap

/**
 * Individual IMU telemetry sample recorded at hardware timestamp.
 */
data class ImuSample(
    val timestampNs: Long,
    val sensorType: Int,
    val values: FloatArray,
    val accuracy: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ImuSample) return false
        return timestampNs == other.timestampNs &&
                sensorType == other.sensorType &&
                values.contentEquals(other.values) &&
                accuracy == other.accuracy
    }

    override fun hashCode(): Int {
        var result = timestampNs.hashCode()
        result = 31 * result + sensorType
        result = 31 * result + values.contentHashCode()
        result = 31 * result + accuracy
        return result
    }
}

/**
 * Metric summary validating camera and sensor timestamp clock domain synchronization (Section 18).
 */
data class TimestampValidationReport(
    val isMonotonic: Boolean,
    val averageOffsetNs: Long,
    val maxJitterNs: Long,
    val missingIntervalsCount: Int,
    val sampleCount: Int,
    val clockDomainVerified: Boolean
)

/**
 * Connects device IMU sensors and synchronizes telemetry against Camera2 hardware timestamps.
 * Supports linear interpolation, nearest-neighbor query, and timestamp clock domain validation.
 */
class SensorTimelineSynchronizer(
    private val sensorManager: SensorManager?
) : SensorEventListener {

    // Sorted map by timestampNs for O(log N) temporal lookups
    private val gyroSamples = ConcurrentSkipListMap<Long, ImuSample>()
    private val accelSamples = ConcurrentSkipListMap<Long, ImuSample>()

    private val maxRetentionWindowNs = 2_000_000_000L // 2 seconds retention window

    fun startListening() {
        if (sensorManager == null) return
        val gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        // SENSOR_DELAY_FASTEST (0) or SENSOR_DELAY_GAME (20000 microseconds / 50Hz)
        if (gyro != null) {
            sensorManager.registerListener(this, gyro, SensorManager.SENSOR_DELAY_FASTEST)
        }
        if (accel != null) {
            sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_FASTEST)
        }
    }

    fun stopListening() {
        sensorManager?.unregisterListener(this)
    }

    fun injectSample(sample: ImuSample) {
        when (sample.sensorType) {
            Sensor.TYPE_GYROSCOPE -> gyroSamples[sample.timestampNs] = sample
            Sensor.TYPE_ACCELEROMETER -> accelSamples[sample.timestampNs] = sample
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        val sample = ImuSample(
            timestampNs = event.timestamp,
            sensorType = event.sensor.type,
            values = event.values.clone(),
            accuracy = event.accuracy
        )

        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> {
                gyroSamples[event.timestamp] = sample
                pruneOldSamples(gyroSamples, event.timestamp)
            }
            Sensor.TYPE_ACCELEROMETER -> {
                accelSamples[event.timestamp] = sample
                pruneOldSamples(accelSamples, event.timestamp)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No-op
    }

    private fun pruneOldSamples(map: ConcurrentSkipListMap<Long, ImuSample>, currentNs: Long) {
        val cutoff = currentNs - maxRetentionWindowNs
        val headMap = map.headMap(cutoff)
        val iterator = headMap.keys.iterator()
        while (iterator.hasNext()) {
            iterator.next()
            iterator.remove()
        }
    }

    /**
     * Synchronizes and associates sensor telemetry at a specific camera timestamp.
     * Uses linear interpolation between surrounding samples when available.
     */
    fun synchronizeAtTimestamp(cameraTimestampNs: Long): SensorSampleAssociation {
        val gyroValues = interpolateOrNearest(gyroSamples, cameraTimestampNs)
        val accelValues = interpolateOrNearest(accelSamples, cameraTimestampNs)

        return SensorSampleAssociation(
            timestampNs = cameraTimestampNs,
            gyroRadS = gyroValues.first,
            accelMps2 = accelValues.first,
            isInterpolated = gyroValues.second || accelValues.second
        )
    }

    private fun interpolateOrNearest(
        map: ConcurrentSkipListMap<Long, ImuSample>,
        targetNs: Long
    ): Pair<FloatArray?, Boolean> {
        if (map.isEmpty()) return Pair(null, false)

        val floor = map.floorEntry(targetNs)
        val ceiling = map.ceilingEntry(targetNs)

        if (floor == null && ceiling == null) return Pair(null, false)
        if (floor == null) return Pair(ceiling!!.value.values.clone(), false)
        if (ceiling == null) return Pair(floor!!.value.values.clone(), false)

        if (floor.key == ceiling.key) {
            return Pair(floor.value.values.clone(), false)
        }

        // Linear interpolation: v = v0 + (v1 - v0) * alpha
        val t0 = floor.key
        val t1 = ceiling.key
        val alpha = (targetNs - t0).toDouble() / (t1 - t0).toDouble()

        val v0 = floor.value.values
        val v1 = ceiling.value.values
        val dims = minOf(v0.size, v1.size)
        val interpolated = FloatArray(dims)
        for (i in 0 until dims) {
            interpolated[i] = (v0[i] + (v1[i] - v0[i]) * alpha).toFloat()
        }

        return Pair(interpolated, true)
    }

    /**
     * Validates whether camera timestamps and sensor timestamps share the same monotonic clock domain (Section 18).
     */
    fun validateTimestamps(cameraTimestamps: List<Long>): TimestampValidationReport {
        if (cameraTimestamps.isEmpty() || gyroSamples.isEmpty()) {
            return TimestampValidationReport(
                isMonotonic = true,
                averageOffsetNs = 0L,
                maxJitterNs = 0L,
                missingIntervalsCount = 0,
                sampleCount = 0,
                clockDomainVerified = false
            )
        }

        var isMonotonic = true
        for (i in 1 until cameraTimestamps.size) {
            if (cameraTimestamps[i] <= cameraTimestamps[i - 1]) {
                isMonotonic = false
                break
            }
        }

        val offsets = mutableListOf<Long>()
        var missingCount = 0
        for (camTs in cameraTimestamps) {
            val nearest = gyroSamples.floorKey(camTs) ?: gyroSamples.ceilingKey(camTs)
            if (nearest != null) {
                val offset = Math.abs(camTs - nearest)
                offsets.add(offset)
                if (offset > 50_000_000L) { // > 50ms gap
                    missingCount++
                }
            }
        }

        val avgOffset = if (offsets.isNotEmpty()) offsets.average().toLong() else 0L
        val maxJitter = if (offsets.isNotEmpty()) offsets.maxOrNull() ?: 0L else 0L

        // On modern Linux/Android, Camera2 SENSOR_TIMESTAMP uses CLOCK_BOOTTIME,
        // which matches SensorEvent.timestamp (also CLOCK_BOOTTIME).
        val clockDomainVerified = isMonotonic && avgOffset < 50_000_000L

        return TimestampValidationReport(
            isMonotonic = isMonotonic,
            averageOffsetNs = avgOffset,
            maxJitterNs = maxJitter,
            missingIntervalsCount = missingCount,
            sampleCount = offsets.size,
            clockDomainVerified = clockDomainVerified
        )
    }

    fun clear() {
        gyroSamples.clear()
        accelSamples.clear()
    }
}
