package com.visionox.ble.domain.protocol

import com.visionox.ble.domain.protocol.BLEDeviceProfile.NameMatchMode
import java.util.UUID

/**
 * Generic BLE device profile for other manufacturers
 */
data class GenericBLEDeviceProfile(
    override val serviceUuid: UUID,
    override val dataCharacteristicUuid: UUID,
    override val deviceNameFilter: String? = null,
    override val nameMatchMode: NameMatchMode = NameMatchMode.EXACT,
    override val deviceAddress: String? = null
) : BLEDeviceProfile
