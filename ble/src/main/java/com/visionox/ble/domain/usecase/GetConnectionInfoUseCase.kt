package com.visionox.ble.domain.usecase

import android.bluetooth.BluetoothDevice
import com.visionox.ble.domain.repository.BLERepository

class GetConnectionInfoUseCase(
    private val repository: BLERepository
) {
    fun isConnected(): Boolean = repository.isConnected()

    fun getConnectedDevice(): BluetoothDevice? = repository.getConnectedDevice()
}
