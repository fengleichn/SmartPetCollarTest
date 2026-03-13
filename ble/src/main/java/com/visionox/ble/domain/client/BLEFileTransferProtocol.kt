package com.visionox.ble.domain.client

interface BLEFileTransferProtocol {
    fun sendBytes(bytes: ByteArray, fileName: String)
    fun release()
}
