package com.visionox.ble.domain.factory

import android.bluetooth.BluetoothGattCharacteristic
import com.visionox.ble.domain.client.BLEFileTransferProtocol
import com.visionox.ble.domain.session.GattSession

interface BLEFileTransferProtocolFactory {
    fun create(
        session: GattSession,
        dataCharacteristic: BluetoothGattCharacteristic,
        onProgress: (Int, Int) -> Unit,
        onCompleted: () -> Unit,
        onError: (String, Throwable?) -> Unit
    ): BLEFileTransferProtocol
}
