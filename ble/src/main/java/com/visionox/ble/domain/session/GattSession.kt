package com.visionox.ble.domain.session

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import java.util.UUID

/**
 * Thin abstraction over a single BluetoothGatt connection so upper layers
 * can share one GATT session instead of opening new connections.
 */
interface GattSession {
    val device: BluetoothDevice
    val mtu: Int

    fun requestMtu(size: Int): Boolean
    fun getService(uuid: UUID): BluetoothGattService?
    fun getCharacteristic(serviceUuid: UUID, characteristicUuid: UUID): BluetoothGattCharacteristic?
    fun writeCharacteristic(
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        writeType: Int = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
    ): Boolean

    fun addListener(listener: Listener)
    fun removeListener(listener: Listener)

    interface Listener {
        fun onMtuChanged(mtu: Int)
        fun onCharacteristicWrite(
            characteristic: BluetoothGattCharacteristic,
            status: Int,
            value: ByteArray
        )

        fun onDisconnected()
    }
}
