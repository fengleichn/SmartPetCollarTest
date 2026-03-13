package com.visionox.ble.infrastructure.protocol

import com.visionox.ble.domain.protocol.BLEDeviceProfile
import com.visionox.ble.domain.protocol.BLEDeviceProfile.NameMatchMode
import java.util.UUID

object DefaultBLEDeviceProfile : BLEDeviceProfile {

    private const val SERVICE_UUID_16BIT: Int = 0x00FF
    private const val DATA_CHARACTERISTIC_UUID_16BIT: Int = 0xFF01

    private fun to128BitUuid(u16: Int): UUID =
        UUID.fromString(String.format("0000%04X-0000-1000-8000-00805F9B34FB", u16))

    override val serviceUuid: UUID = to128BitUuid(SERVICE_UUID_16BIT)
    override val dataCharacteristicUuid: UUID = to128BitUuid(DATA_CHARACTERISTIC_UUID_16BIT)
    override val deviceNameFilter: String? = "8G"
    override val nameMatchMode: NameMatchMode = NameMatchMode.PREFIX
    override val deviceAddress: String? = null
}
