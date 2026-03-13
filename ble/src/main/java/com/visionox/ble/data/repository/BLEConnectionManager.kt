package com.visionox.ble.data.repository

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.visionox.ble.domain.model.BLEConnectionState
import com.visionox.ble.domain.model.BLEError
import com.visionox.ble.domain.model.ScanningState
import com.visionox.ble.domain.protocol.BLEDeviceProfile
import com.visionox.ble.domain.scanner.Scanner
import com.visionox.ble.domain.session.GattSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet

class BLEConnectionManager(
    private val context: Context,
    private val scanner: Scanner,
    private val listener: Listener
) {

    interface Listener {
        fun onConnectionState(state: BLEConnectionState)
        fun onScanningState(state: ScanningState)
        fun onError(error: BLEError)
    }

    companion object {
        private const val TAG = "BLEConnectionManager"
        private const val MAX_RETRY_COUNT = 3
    }

    private val bleScanner = scanner
    private val mainHandler = Handler(Looper.getMainLooper())

    private var connectedDevice: BluetoothDevice? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var gattSession: DefaultGattSession? = null
    @Volatile
    private var servicesReady: Boolean = false

    private var targetProfile: BLEDeviceProfile? = null
    private var retryCount = 0

    fun getActiveSession(): GattSession? = gattSession
    fun isServicesReady(): Boolean = servicesReady

    suspend fun scanAndConnectDevice(
        profile: BLEDeviceProfile,
        autoRetry: Boolean = true
    ): Result<BluetoothDevice> = withContext(Dispatchers.IO) {

        if (!bleScanner.isBLESupported()) {
            listener.onError(BLEError.BleNotSupported)
            return@withContext Result.failure(Exception("BLE不支持"))
        }

        if (!bleScanner.isBluetoothEnabled()) {
            listener.onError(BLEError.BluetoothDisabled)
            return@withContext Result.failure(Exception("蓝牙未开启"))
        }

        connectedDevice?.let {
            Log.d(TAG, "已有连接，跳过扫描: ${it.address}")
            return@withContext Result.success(it)
        }
        targetProfile = profile

        Log.d(TAG, "开始扫描设备: filter=${profile.deviceNameFilter}, mode=${profile.nameMatchMode}, address=${profile.deviceAddress}, scanWithServiceUuidFilter=${profile.scanWithServiceUuidFilter}")

        // 根据配置决定是否启用服务 UUID 过滤
        val serviceUUIDs = if (profile.scanWithServiceUuidFilter) {
            listOf(profile.serviceUuid.toString())
        } else {
            null  // 不过滤，扫描所有设备
        }
        val device = performScan(serviceUUIDs)

        return@withContext if (device != null) {
            val connectionResult = connectToDevice(device)
            if (connectionResult.isSuccess) {
                retryCount = 0
                Result.success(device)
            } else if (autoRetry && retryCount < MAX_RETRY_COUNT) {
                retryCount++
                Log.d(TAG, "连接失败，准备重试 ($retryCount/$MAX_RETRY_COUNT)")
                delay(2000)
                scanAndConnectDevice(profile, autoRetry)
            } else {
                retryCount = 0
                connectionResult
            }
        } else {
            // 扫描失败，未找到目标设备，更新连接状态为断开
            listener.onConnectionState(BLEConnectionState.Disconnected)
            Result.failure(Exception("未找到目标设备"))
        }
    }

    fun stopScan() {
        bleScanner.stopScan()
        listener.onScanningState(ScanningState.Idle)
    }

    fun getConnectedDevice(): BluetoothDevice? = connectedDevice

    fun isConnected(): Boolean = connectedDevice != null

    fun disconnect() {
        closeGattSafely()
        listener.onConnectionState(BLEConnectionState.Disconnected)
    }

    private suspend fun performScan(serviceUUIDs: List<String>?): BluetoothDevice? = suspendCancellableCoroutine { continuation ->
        var isCompleted = false
        val profile = targetProfile

        // 显式检查 profile 是否为空，便于排查问题
        if (profile == null) {
            Log.e(TAG, "targetProfile 为空，无法进行设备匹配，扫描将失败")
            continuation.resume(null) {}
            return@suspendCancellableCoroutine
        }

        val callback = object : Scanner.ScanCallback {
            @SuppressLint("MissingPermission")
            override fun onDeviceFound(device: BluetoothDevice, rssi: Int, scanRecord: ByteArray?) {
                if (isCompleted) return

                try {
                    val deviceName = device.name
                    val deviceAddress = device.address

                    Log.d(TAG, "发现设备: $deviceName ($deviceAddress), RSSI: $rssi")

                    // 使用 Profile 的 matches 方法进行匹配
                    val isTargetDevice = profile.matches(deviceName, deviceAddress)

                    if (isTargetDevice) {
                        Log.d(TAG, "找到目标设备: $deviceName ($deviceAddress)")
                        isCompleted = true
                        stopScan()
                        continuation.resume(device) {}
                    }
                } catch (e: SecurityException) {
                    Log.e(TAG, "获取设备信息失败: ${e.message}")
                    if (!isCompleted) {
                        isCompleted = true
                        listener.onError(BLEError.PermissionDenied)
                        continuation.resume(null) {}
                    }
                }
            }

            override fun onScanStarted() {
                listener.onScanningState(ScanningState.Scanning)
                listener.onConnectionState(BLEConnectionState.Scanning)
                Log.d(TAG, "开始扫描BLE设备")
            }

            override fun onScanStopped() {
                listener.onScanningState(ScanningState.Idle)
                if (!isCompleted) {
                    isCompleted = true
                    Log.d(TAG, "扫描超时，未找到目标设备")
                    continuation.resume(null) {}
                }
            }

            override fun onError(errorCode: Int) {
                if (!isCompleted) {
                    isCompleted = true
                    listener.onScanningState(ScanningState.Idle)
                    val error = when (errorCode) {
                        -1 -> BLEError.BleNotSupported
                        -2 -> BLEError.BluetoothDisabled
                        -3 -> BLEError.PermissionDenied
                        else -> BLEError.ScanFailed(errorCode)
                    }
                    listener.onError(error)
                    Log.e(TAG, "扫描失败，错误代码: $errorCode")
                    continuation.resume(null) {}
                }
            }
        }

        bleScanner.startScan(callback, serviceUUIDs)

        continuation.invokeOnCancellation {
            bleScanner.stopScan()
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun connectToDevice(device: BluetoothDevice): Result<BluetoothDevice> = suspendCancellableCoroutine { continuation ->
        val deviceName = device.name ?: "未知设备"
        listener.onConnectionState(BLEConnectionState.Connecting(deviceName))
        Log.d(TAG, "开始连接设备: $deviceName")

        var isCompleted = false

        fun resumeOnce(result: Result<BluetoothDevice>) {
            if (!isCompleted) {
                isCompleted = true
                continuation.resume(result) {}
            }
        }

        fun fail(message: String, throwable: Throwable? = null, gattToClose: BluetoothGatt? = null) {
            Log.e(TAG, "连接设备失败: $deviceName, 原因: $message", throwable)
            gattToClose?.let { targetGatt ->
                try {
                    targetGatt.disconnect()
                } catch (t: Throwable) {
                    Log.w(TAG, "fail阶段disconnect异常", t)
                }
                try {
                    targetGatt.close()
                } catch (t: Throwable) {
                    Log.w(TAG, "fail阶段close异常", t)
                }
            }
            listener.onConnectionState(BLEConnectionState.ConnectionFailed(deviceName, message))
            listener.onError(BLEError.ConnectionFailed(message))
            connectedDevice = null
            servicesReady = false
            closeGattSafely()
            gattSession?.handleDisconnected()
            gattSession?.clearListeners()
            gattSession = null
            resumeOnce(Result.failure(throwable ?: Exception(message)))
        }

        val gattCallback = object : BluetoothGattCallback() {
            @SuppressLint("MissingPermission")
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    fail("状态异常: $status", gattToClose = gatt)
                    return
                }

                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        Log.d(TAG, "GATT已连接，请求MTU")
                        bluetoothGatt = gatt
                        gattSession = DefaultGattSession(gatt, mainHandler)
                        servicesReady = false
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            if (!gatt.requestMtu(517)) {
                                fail("请求MTU失败", gattToClose = gatt)
                            }
                        } else {
                            if (!gatt.discoverServices()) {
                                fail("启动服务发现失败", gattToClose = gatt)
                            }
                        }
                    }

                    BluetoothProfile.STATE_DISCONNECTED -> {
                        if (!isCompleted) {
                            fail("连接已断开", gattToClose = gatt)
                        } else {
                            Log.d(TAG, "设备已断开: $deviceName")
                            connectedDevice = null
                            listener.onConnectionState(BLEConnectionState.Disconnected)
                            gattSession?.handleDisconnected()
                            gattSession?.clearListeners()
                            gattSession = null
                            servicesReady = false
                            closeGattSafely()
                        }
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    connectedDevice = device
                    servicesReady = true
                    listener.onConnectionState(BLEConnectionState.Connected(deviceName))
                    Log.d(TAG, "成功连接到设备并发现服务: $deviceName")
                    resumeOnce(Result.success(device))
                } else {
                    fail("服务发现失败: $status", gattToClose = gatt)
                }
            }

            @SuppressLint("MissingPermission")
            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                gattSession?.handleMtuChanged(mtu, status)
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG, "MTU已更改为: $mtu，开始发现服务")
                    if (!gatt.discoverServices()) {
                        fail("启动服务发现失败", gattToClose = gatt)
                    }
                } else {
                    fail("MTU更改失败: $status", gattToClose = gatt)
                }
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                gattSession?.handleCharacteristicWrite(characteristic, status)
            }
        }

        val connectRunnable = Runnable {
            try {
                closeGattSafely()
                bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                } else {
                    device.connectGatt(context, false, gattCallback)
                }

                if (bluetoothGatt == null) {
                    fail("connectGatt返回null")
                }
            } catch (t: SecurityException) {
                fail("缺少蓝牙权限", t)
            } catch (t: Throwable) {
                fail("connectGatt调用失败", t)
            }
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            connectRunnable.run()
        } else {
            mainHandler.post(connectRunnable)
        }

        continuation.invokeOnCancellation {
            Log.d(TAG, "连接流程被取消: $deviceName")
            mainHandler.post {
                if (!isCompleted) {
                    listener.onConnectionState(BLEConnectionState.Disconnected)
                    listener.onError(BLEError.ConnectionFailed("连接被取消"))
                    isCompleted = true
                }
                gattSession?.handleDisconnected()
                gattSession?.clearListeners()
                gattSession = null
                servicesReady = false
                closeGattSafely()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun closeGattSafely() {
        val gatt = bluetoothGatt ?: return
        val sessionToClose = gattSession
        val closeAction = Runnable {
            try {
                gatt.disconnect()
            } catch (t: Throwable) {
                Log.w(TAG, "disconnect调用失败", t)
            }
            try {
                gatt.close()
            } catch (t: Throwable) {
                Log.w(TAG, "close调用失败", t)
            }

            if (bluetoothGatt === gatt) {
                bluetoothGatt = null
            }
            connectedDevice = null
            sessionToClose?.handleDisconnected()
            sessionToClose?.clearListeners()
            if (gattSession === sessionToClose) {
                gattSession = null
            }
            servicesReady = false
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            closeAction.run()
        } else {
            mainHandler.post(closeAction)
        }
    }

}

private class DefaultGattSession(
    private val gatt: BluetoothGatt,
    private val mainHandler: Handler
) : GattSession {

    private val listeners = CopyOnWriteArraySet<GattSession.Listener>()
    @Volatile
    private var currentMtu: Int = 23

    override val device: BluetoothDevice
        get() = gatt.device

    override val mtu: Int
        get() = currentMtu

    override fun requestMtu(size: Int): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            gatt.requestMtu(size)
        } else {
            false
        }
    }

    override fun getService(uuid: UUID) = gatt.getService(uuid)

    override fun getCharacteristic(
        serviceUuid: UUID,
        characteristicUuid: UUID
    ) = getService(serviceUuid)?.getCharacteristic(characteristicUuid)

    override fun writeCharacteristic(
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        writeType: Int
    ): Boolean {
        characteristic.writeType = writeType
        characteristic.value = value
        return gatt.writeCharacteristic(characteristic)
    }

    override fun addListener(listener: GattSession.Listener) {
        listeners.add(listener)
    }

    override fun removeListener(listener: GattSession.Listener) {
        listeners.remove(listener)
    }

    fun handleMtuChanged(mtuValue: Int, status: Int) {
        if (status == BluetoothGatt.GATT_SUCCESS) {
            currentMtu = mtuValue
            notifyListeners { it.onMtuChanged(mtuValue) }
        }
    }

    fun handleCharacteristicWrite(
        characteristic: BluetoothGattCharacteristic,
        status: Int
    ) {
        val valueCopy = characteristic.value?.copyOf() ?: byteArrayOf()
        notifyListeners { it.onCharacteristicWrite(characteristic, status, valueCopy) }
    }

    fun handleDisconnected() {
        notifyListeners { it.onDisconnected() }
    }

    fun clearListeners() {
        listeners.clear()
    }

    private fun notifyListeners(block: (GattSession.Listener) -> Unit) {
        listeners.forEach { listener ->
            mainHandler.post {
                block(listener)
            }
        }
    }
}
