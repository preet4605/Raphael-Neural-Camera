package com.neuralcamera.app

import com.neuralcamera.deviceprofiles.PredefinedDeviceProfiles
import com.neuralcamera.models.HardwareBackendType
import com.neuralcamera.models.PredefinedModelCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApplicationArchitectureTest {

    @Test
    fun testDependencyGraphAndTargetProfileIntegrity() {
        val profile = PredefinedDeviceProfiles.ONEPLUS_15
        assertEquals("OnePlus", profile.manufacturer)
        assertEquals("OnePlus 15", profile.deviceModel)
        assertEquals(36, profile.osVersionSdk)
        assertTrue(profile.backends.containsKey(HardwareBackendType.QUALCOMM_QNN_NPU))

        val classicalModel = PredefinedModelCatalog.CLASSICAL_BASELINE_ISP
        assertNotNull(classicalModel)
        assertTrue(classicalModel.isClassicalFallback)
    }
}
