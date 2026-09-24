package com.neuralcamera.deviceprofiles

import com.neuralcamera.models.HardwareBackendType

interface DeviceCapabilityProvider {
    suspend fun discoverCapabilities(): DeviceProfile
    fun getRuntimeMemoryBytes(): Pair<Long, Long> // Free, Total
    fun getThermalStatus(): Int // Android BatteryManager/PowerManager thermal status
}

interface DeviceProfileRepository {
    fun getActiveProfile(): DeviceProfile
    fun setActiveProfile(profile: DeviceProfile)
    fun findProfileById(profileId: String): DeviceProfile?
    fun listKnownProfiles(): List<DeviceProfile>
}
