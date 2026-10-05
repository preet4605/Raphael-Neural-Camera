package com.neuralcamera.cameracore

import android.hardware.Sensor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorTimelineSynchronizerTest {

    @Test
    fun testExactTimestampLookup() {
        val synchronizer = SensorTimelineSynchronizer(null)

        synchronizer.injectSample(
            ImuSample(
                timestampNs = 1_000_000_000L,
                sensorType = Sensor.TYPE_GYROSCOPE,
                values = floatArrayOf(0.1f, 0.2f, 0.3f),
                accuracy = 3
            )
        )
        synchronizer.injectSample(
            ImuSample(
                timestampNs = 1_000_000_000L,
                sensorType = Sensor.TYPE_ACCELEROMETER,
                values = floatArrayOf(0.0f, 9.8f, 0.0f),
                accuracy = 3
            )
        )

        val association = synchronizer.synchronizeAtTimestamp(1_000_000_000L)

        assertNotNull(association.gyroRadS)
        assertNotNull(association.accelMps2)
        assertEquals(0.1f, association.gyroRadS!![0], 0.001f)
        assertEquals(9.8f, association.accelMps2!![1], 0.001f)
    }

    @Test
    fun testLinearInterpolationBetweenSamples() {
        val synchronizer = SensorTimelineSynchronizer(null)

        // Sample at t = 100ns
        synchronizer.injectSample(
            ImuSample(
                timestampNs = 100L,
                sensorType = Sensor.TYPE_GYROSCOPE,
                values = floatArrayOf(1.0f, 0.0f, 0.0f),
                accuracy = 3
            )
        )
        // Sample at t = 200ns
        synchronizer.injectSample(
            ImuSample(
                timestampNs = 200L,
                sensorType = Sensor.TYPE_GYROSCOPE,
                values = floatArrayOf(3.0f, 0.0f, 0.0f),
                accuracy = 3
            )
        )

        // Query at midpoint t = 150ns: interpolated value should be (1.0 + 3.0)/2 = 2.0
        val association = synchronizer.synchronizeAtTimestamp(150L)

        assertNotNull(association.gyroRadS)
        assertTrue(association.isInterpolated)
        assertEquals(2.0f, association.gyroRadS!![0], 0.001f)
    }

    @Test
    fun testTimestampMonotonicityValidation() {
        val synchronizer = SensorTimelineSynchronizer(null)

        synchronizer.injectSample(
            ImuSample(100L, Sensor.TYPE_GYROSCOPE, floatArrayOf(0f, 0f, 0f), 3)
        )
        synchronizer.injectSample(
            ImuSample(200L, Sensor.TYPE_GYROSCOPE, floatArrayOf(0f, 0f, 0f), 3)
        )

        val cameraTimestamps = listOf(105L, 150L, 195L)
        val report = synchronizer.validateTimestamps(cameraTimestamps)

        assertTrue(report.isMonotonic)
        assertTrue(report.clockDomainVerified)
        assertEquals(3, report.sampleCount)
    }
}
