package com.visionox.ble.domain.scanner

import android.bluetooth.BluetoothDevice

interface Scanner {
    fun isBLESupported(): Boolean
    fun isBluetoothEnabled(): Boolean
    fun startScan(callback: ScanCallback, serviceUUIDs: List<String>? = null)
    fun stopScan()

    interface ScanCallback {
        fun onDeviceFound(device: BluetoothDevice, rssi: Int, scanRecord: ByteArray?)
        fun onScanStarted()
        fun onScanStopped()
        fun onError(errorCode: Int)
    }
}
