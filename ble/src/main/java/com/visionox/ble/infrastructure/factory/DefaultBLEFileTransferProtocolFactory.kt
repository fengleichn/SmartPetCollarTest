package com.visionox.ble.infrastructure.factory

import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import com.visionox.ble.domain.client.BLEFileTransferProtocol
import com.visionox.ble.domain.factory.BLEFileTransferProtocolFactory
import com.visionox.ble.domain.session.GattSession
import com.visionox.ble.infrastructure.client.DefaultBLEFileTransferProtocol

class DefaultBLEFileTransferProtocolFactory(
    private val context: Context
) : BLEFileTransferProtocolFactory {

    override fun create(
        session: GattSession,
        dataCharacteristic: BluetoothGattCharacteristic,
        onProgress: (Int, Int) -> Unit,
        onCompleted: () -> Unit,
        onError: (String, Throwable?) -> Unit
    ): BLEFileTransferProtocol {
        val listener = object : DefaultBLEFileTransferProtocol.TransferProgressListener {
            override fun onProgress(bytesSent: Int, total: Int) = onProgress(bytesSent, total)
            override fun onCompleted() = onCompleted()
            override fun onError(msg: String, t: Throwable?) = onError(msg, t)
        }

        return DefaultBLEFileTransferProtocol(context, session, dataCharacteristic, listener)
    }
}
