package com.visionox.ble.infrastructure.protocol

import com.visionox.ble.domain.protocol.BLEDeviceProfile
import com.visionox.ble.domain.protocol.BLEDeviceProfileProvider

class DefaultBLEDeviceProfileProvider : BLEDeviceProfileProvider {
    override fun getDefaultProfile(): BLEDeviceProfile = DefaultBLEDeviceProfile
}
