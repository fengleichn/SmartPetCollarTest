package com.visionox.ble.infrastructure.protocol

import com.visionox.ble.domain.protocol.BLEDeviceProfile

/**
 * Profile registry for managing multiple device profiles
 */
object BLEDeviceProfileRegistry {
    private val profiles = mutableListOf<BLEDeviceProfile>(
        DefaultBLEDeviceProfile
    )

    fun register(profile: BLEDeviceProfile) {
        profiles.add(profile)
    }

    fun findProfile(deviceName: String?, deviceAddress: String?): BLEDeviceProfile? {
        return profiles.firstOrNull { it.matches(deviceName, deviceAddress) }
    }

    fun getDefaultProfile(): BLEDeviceProfile = DefaultBLEDeviceProfile
}
