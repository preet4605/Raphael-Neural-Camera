package com.neuralcamera.deviceprofiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceProfileSerializationTest {

    @Test
    fun testSerializationDeserializationRoundTrip() {
        val original = PredefinedDeviceProfiles.ONEPLUS_15
        assertEquals(1, original.profileVersion)
        assertEquals("OnePlus", original.deviceIdentity.manufacturer)
        assertEquals("OnePlus 15", original.deviceIdentity.model)
        assertNotNull(original.hardware)
        assertEquals("3rd-generation Qualcomm Oryon", original.hardware.cpuArchitecture)

        val serialized = DeviceProfileSerializer.serializeToKeyValue(original)
        assertEquals("1", serialized["profileVersion"])
        assertEquals("OnePlus", serialized["manufacturer"])
        assertEquals("OnePlus 15", serialized["deviceModel"])

        val deserialized = DeviceProfileSerializer.deserializeFromKeyValue(serialized)
        assertEquals(original.profileId, deserialized.profileId)
        assertEquals(original.manufacturer, deserialized.manufacturer)
        assertEquals(original.deviceModel, deserialized.deviceModel)
        assertEquals(original.totalRamBytes, deserialized.totalRamBytes)
        assertEquals(original.osVersionSdk, deserialized.osVersionSdk)
        assertEquals(original.profileVersion, deserialized.profileVersion)
    }

    @Test
    fun testVersionedSchemaFieldsIntegrity() {
        val profile = PredefinedDeviceProfiles.ONEPLUS_15
        assertEquals(5, profile.cameraProfiles.size)
        assertTrue(profile.runtimeProfiles.isNotEmpty())
        assertEquals(60, profile.thermalProfile.normalMaxFps)
        assertNotNull(profile.calibrationProfile)
        assertEquals(64, profile.calibrationProfile.blackLevel)
    }
}
