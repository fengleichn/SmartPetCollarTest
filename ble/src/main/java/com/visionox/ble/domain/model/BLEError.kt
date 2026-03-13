package com.visionox.ble.domain.model

sealed class BLEError {
    object BleNotSupported : BLEError()
    object BluetoothDisabled : BLEError()
    object PermissionDenied : BLEError()
    data class ScanFailed(val errorCode: Int) : BLEError()
    data class ConnectionFailed(val reason: String) : BLEError()
}