package com.visionox.ble.domain.repository

import android.bluetooth.BluetoothDevice
import com.visionox.ble.domain.model.BLEConnectionState
import com.visionox.ble.domain.model.BLEError
import com.visionox.ble.domain.model.ScanningState
import com.visionox.ble.domain.protocol.BLEDeviceProfile
import kotlinx.coroutines.flow.StateFlow

interface BLERepository {
    val connectionState: StateFlow<BLEConnectionState>
    val scanningState: StateFlow<ScanningState>
    val errorState: StateFlow<BLEError?>
    val transferProgress: StateFlow<Int>

    suspend fun scanAndConnect(
        profile: BLEDeviceProfile,
        autoRetry: Boolean = true
    ): Result<BluetoothDevice>

    suspend fun sendData(data: ByteArray, fileName: String): Result<Unit>
    fun disconnect()
    fun stopScan()
    fun isConnected(): Boolean
    fun getConnectedDevice(): BluetoothDevice?
    fun clearError()
}
