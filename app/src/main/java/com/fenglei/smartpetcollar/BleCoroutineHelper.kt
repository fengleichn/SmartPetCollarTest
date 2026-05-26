package com.fenglei.smartpetcollar

import android.bluetooth.BluetoothDevice
import com.visionox.ble.domain.protocol.BLEDeviceProfile
import com.visionox.ble.domain.repository.BLERepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Helper to call BLE suspend functions from Java code.
 */
object BleCoroutineHelper {

    interface ScanCallback {
        fun onSuccess(device: BluetoothDevice)
        fun onError(error: String)
    }

    interface SendCallback {
        fun onSuccess()
        fun onError(error: String)
    }

    @JvmStatic
    fun scanAndConnect(
        repository: BLERepository,
        profile: BLEDeviceProfile,
        callback: ScanCallback
    ): Job {
        return CoroutineScope(Dispatchers.IO).launch {
            val result = repository.scanAndConnect(profile, true)
            result.fold(
                onSuccess = { device -> callback.onSuccess(device) },
                onFailure = { e -> callback.onError(e.message ?: "Unknown error") }
            )
        }
    }

    @JvmStatic
    fun sendData(
        repository: BLERepository,
        data: ByteArray,
        fileName: String,
        callback: SendCallback
    ): Job {
        return CoroutineScope(Dispatchers.IO).launch {
            val result = repository.sendData(data, fileName)
            result.fold(
                onSuccess = { callback.onSuccess() },
                onFailure = { e -> callback.onError(e.message ?: "Unknown error") }
            )
        }
    }
}
