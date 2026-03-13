package com.visionox.ble.domain.model

import android.bluetooth.BluetoothDevice

data class ScannedDevice(
    val name: String?,
    val address: String,
    val rssi: Int,
    val device: BluetoothDevice
)