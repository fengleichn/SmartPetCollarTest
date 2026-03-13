package com.visionox.ble.domain.model

sealed class BLEConnectionState {
    object Disconnected : BLEConnectionState()
    data class Connecting(val deviceName: String) : BLEConnectionState()
    data class Connected(val deviceName: String) : BLEConnectionState()
    data class ConnectionFailed(val deviceName: String, val reason: String? = null) : BLEConnectionState()
    object Scanning : BLEConnectionState()
}