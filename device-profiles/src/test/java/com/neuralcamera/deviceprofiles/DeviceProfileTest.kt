package com.neuralcamera.deviceprofiles

import com.neuralcamera.models.HardwareBackendType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceProfileTest {

    @Test
    fun testOnePlus15ProfileProperties() {
        val profile = PredefinedDeviceProfiles.ONEPLUS_15
        assertEquals("OnePlus", profile.manufacturer)
        assertEquals("OnePlus 15", profile.deviceModel)
        assertEquals(36, profile.osVersionSdk)
        assertEquals(12L * 1024 * 1024 * 1024, profile.totalRamBytes)

        val wideCamera = profile.cameras["0"]
        assertNotNull(wideCamera)
        assertTrue(wideCamera!!.hasRawSupport)
        assertTrue(wideCamera.supportedFormats.contains("RAW_SENSOR"))

        val qnnBackend = profile.backends[HardwareBackendType.QUALCOMM_QNN_NPU]
        assertNotNull(qnnBackend)
        assertTrue(qnnBackend!!.isSupported)
        // Library presence is not execution proof: usable stays false until a backend-attributed run.
        assertFalse(qnnBackend.isUsable)
        assertNull(qnnBackend.latencyMultiplier)
        assertFalse(profile.backends.getValue(HardwareBackendType.VULKAN_GPU).isUsable)
        assertFalse(profile.isProfileVerifiedAtRuntime)
    }

    @Test
    fun testRepositorySwitching() {
        val repo = InMemoryDeviceProfileRepository()
        assertEquals(PredefinedDeviceProfiles.ONEPLUS_15.profileId, repo.getActiveProfile().profileId)

        repo.setActiveProfile(PredefinedDeviceProfiles.GENERIC_FALLBACK)
        assertEquals(PredefinedDeviceProfiles.GENERIC_FALLBACK.profileId, repo.getActiveProfile().profileId)
        assertEquals(3, repo.listKnownProfiles().size)
    }
}
