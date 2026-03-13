package com.visionox.ble.domain.protocol

interface BLEDeviceProfileProvider {
    fun getDefaultProfile(): BLEDeviceProfile
}
