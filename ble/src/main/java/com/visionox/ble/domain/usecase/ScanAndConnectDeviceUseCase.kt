package com.visionox.ble.domain.usecase

import android.bluetooth.BluetoothDevice
import com.visionox.ble.domain.protocol.BLEDeviceProfile
import com.visionox.ble.domain.repository.BLERepository

class ScanAndConnectDeviceUseCase(
    private val repository: BLERepository
) {
    suspend operator fun invoke(
        profile: BLEDeviceProfile,
        autoRetry: Boolean = true
    ): Result<BluetoothDevice> = repository.scanAndConnect(profile, autoRetry)
}
