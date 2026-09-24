package com.neuralcamera.capture

import com.neuralcamera.deviceprofiles.PredefinedDeviceProfiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UniversalCapturePlannerTest {

    private val profile = PredefinedDeviceProfiles.ONEPLUS_15
    private val planner = UniversalCapturePlanner()

    @Test
    fun testAutoModeInLowLightPlansMultiFrameFusion() {
        val stillMotion = MotionVector(0.1f, 0.1f, 0.01f, true)
        val plan = planner.planCapture(
            mode = CameraShootingMode.AUTO,
            sceneLuminanceLux = 10f, // Low light
            motion = stillMotion,
            deviceProfile = profile
        )

        assertTrue(plan.temporalFrameCount >= 6)
        assertTrue(plan.enableTemporalFusion)
        assertTrue(plan.useRawStream)
    }

    @Test
    fun testMasterModePlansDeepFusionAndRaw() {
        val stillMotion = MotionVector(0f, 0f, 0f, true)
        val plan = planner.planCapture(
            mode = CameraShootingMode.MASTER,
            sceneLuminanceLux = 5f,
            motion = stillMotion,
            deviceProfile = profile
        )

        assertEquals(CameraShootingMode.MASTER, plan.mode)
        assertTrue(plan.temporalFrameCount >= 10)
        assertTrue(plan.useRawStream)
        assertTrue(plan.bracketStepsEv.isNotEmpty())
    }

    @Test
    fun testAuthenticModeIsConservative() {
        val motion = MotionVector(0.1f, 0.1f, 0.05f, false)
        val plan = planner.planCapture(
            mode = CameraShootingMode.AUTHENTIC,
            sceneLuminanceLux = 150f,
            motion = motion,
            deviceProfile = profile
        )

        assertEquals(2, plan.temporalFrameCount)
        assertTrue(plan.bracketStepsEv.isEmpty())
    }
}
