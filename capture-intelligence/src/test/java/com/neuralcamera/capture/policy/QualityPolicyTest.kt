package com.neuralcamera.capture.policy

import com.neuralcamera.models.execution.ContentOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QualityPolicyTest {

    @Test
    fun everyPolicyPreservesImageSemantics() {
        QualityPolicyId.entries.forEach { id ->
            val p = QualityPolicies.of(id)
            assertEquals(id, p.id)
            assertEquals("$id must stay captured -> reconstructed", SchedulingPolicy.REALITY_PRESERVING, p.permittedOrigins)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun aPolicyCannotPermitGeneratedContent() {
        QualityPolicies.of(QualityPolicyId.MAXIMUM).copy(permittedOrigins = setOf(ContentOrigin.CAPTURED, ContentOrigin.GENERATED))
    }

    @Test
    fun budgetsOrderAsExpected() {
        val instant = QualityPolicies.of(QualityPolicyId.INSTANT)
        val balanced = QualityPolicies.of(QualityPolicyId.BALANCED)
        val max = QualityPolicies.of(QualityPolicyId.MAXIMUM)
        assertTrue(instant.maxFrames < balanced.maxFrames && balanced.maxFrames < max.maxFrames)
        assertEquals(BackendPreference.CPU_ONLY, QualityPolicies.of(QualityPolicyId.THERMAL).backend)
        assertEquals(BackendPreference.CPU_ONLY, QualityPolicies.of(QualityPolicyId.BATTERY_SAVER).backend)
    }

    @Test
    fun hotDeviceOverridesToThermalWithReason() {
        val (p, why) = QualityPolicies.resolve(QualityPolicyId.MAXIMUM, DeviceConditions(thermalStatus = 3, batteryPercent = 80, powerSaveMode = false))
        assertEquals(QualityPolicyId.THERMAL, p.id)
        assertNotNull(why)
    }

    @Test
    fun lowBatteryOverridesToBatterySaver() {
        val (p, _) = QualityPolicies.resolve(QualityPolicyId.NIGHT, DeviceConditions(thermalStatus = 0, batteryPercent = 10, powerSaveMode = null))
        assertEquals(QualityPolicyId.BATTERY_SAVER, p.id)
    }

    @Test
    fun normalConditionsKeepRequestedPolicy() {
        val (p, why) = QualityPolicies.resolve(QualityPolicyId.BALANCED, DeviceConditions(null, null, null))
        assertEquals(QualityPolicyId.BALANCED, p.id)
        assertNull(why)
        assertEquals(QualityPolicyId.INSTANT, QualityPolicies.resolve(QualityPolicyId.INSTANT, DeviceConditions(5, 5, true)).first.id)
    }

    @Test
    fun decisionKeepsPlannedFramesUnlessAnOverrideShrinksThem() {
        val cool = DeviceConditions(thermalStatus = 0, batteryPercent = 80, powerSaveMode = false)
        val hot = DeviceConditions(thermalStatus = 3, batteryPercent = 80, powerSaveMode = false)
        val unknown = DeviceConditions(thermalStatus = null, batteryPercent = null, powerSaveMode = null)
        val m = com.neuralcamera.capture.CameraShootingMode.MASTER

        val normal = QualityPolicies.decide(12, m, cool)
        assertEquals(QualityPolicyId.MAXIMUM, normal.policy.id)
        assertEquals(12, normal.frames)
        assertNull(normal.override)

        val throttled = QualityPolicies.decide(12, m, hot)
        assertEquals(QualityPolicyId.THERMAL, throttled.policy.id)
        assertEquals(3, throttled.frames)
        assertTrue(throttled.override!!.contains("thermal"))

        // Fewer planned frames than the cap stay as planned; unmeasured conditions never trigger an override.
        assertEquals(2, QualityPolicies.decide(2, m, hot).frames)
        assertNull(QualityPolicies.decide(8, com.neuralcamera.capture.CameraShootingMode.AUTO, unknown).override)
    }
}
