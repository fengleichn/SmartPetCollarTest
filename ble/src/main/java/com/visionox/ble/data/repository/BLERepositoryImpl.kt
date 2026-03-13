package com.visionox.ble.data.repository

import android.bluetooth.BluetoothDevice
import android.content.Context
import com.visionox.ble.domain.model.BLEConnectionState
import com.visionox.ble.domain.model.BLEError
import com.visionox.ble.domain.model.ScanningState
import com.visionox.ble.domain.protocol.BLEDeviceProfile
import com.visionox.ble.domain.repository.BLERepository
import com.visionox.ble.domain.scanner.Scanner
import com.visionox.ble.domain.factory.BLEFileTransferProtocolFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class BLERepositoryImpl(
    context: Context,
    scanner: Scanner,
    protocolFactory: BLEFileTransferProtocolFactory,
    private val deviceProfile: BLEDeviceProfile
) : BLERepository {

    private val _connectionState = MutableStateFlow<BLEConnectionState>(BLEConnectionState.Disconnected)
    override val connectionState: StateFlow<BLEConnectionState> = _connectionState.asStateFlow()

    private val _scanningState = MutableStateFlow<ScanningState>(ScanningState.Idle)
    override val scanningState: StateFlow<ScanningState> = _scanningState.asStateFlow()

    private val _errorState = MutableStateFlow<BLEError?>(null)
    override val errorState: StateFlow<BLEError?> = _errorState.asStateFlow()

    private val _transferProgress = MutableStateFlow(0)
    override val transferProgress: StateFlow<Int> = _transferProgress.asStateFlow()

    private val connectionManager = BLEConnectionManager(context, scanner, object : BLEConnectionManager.Listener {
        override fun onConnectionState(state: BLEConnectionState) {
            _connectionState.value = state
        }

        override fun onScanningState(state: ScanningState) {
            _scanningState.value = state
        }

        override fun onError(error: BLEError) {
            _errorState.value = error
        }
    })

    private val dataTransferManager = BLEDataTransferManager(protocolFactory)

    override suspend fun scanAndConnect(
        profile: BLEDeviceProfile,
        autoRetry: Boolean
    ): Result<BluetoothDevice> = connectionManager.scanAndConnectDevice(profile, autoRetry)

    override suspend fun sendData(data: ByteArray, fileName: String): Result<Unit> {
        if (!connectionManager.isConnected()) {
            return Result.failure(Exception("设备未连接"))
        }

        if (!connectionManager.isServicesReady()) {
            return Result.failure(Exception("服务未就绪"))
        }

        val session = connectionManager.getActiveSession()
            ?: return Result.failure(Exception("无法获取GATT会话"))

        val dataChar = session.getCharacteristic(deviceProfile.serviceUuid, deviceProfile.dataCharacteristicUuid)
            ?: return Result.failure(Exception("无法找到数据特征值"))

        // 设置初始进度为1，确保UI显示进度视图（0会导致视图隐藏）
        _transferProgress.value = 1

        return dataTransferManager.sendData(
            session,
            dataChar,
            data,
            fileName,
            onProgress = { bytesSent, total ->
                val percent = if (total > 0) ((bytesSent.toFloat() / total) * 100).toInt().coerceIn(0, 100) else 0
                _transferProgress.value = percent
            },
            onCompleted = {
                _transferProgress.value = 100
            },
            onError = { error ->
                _transferProgress.value = 0
                _errorState.value = error
            }
        )
    }

    override fun disconnect() = connectionManager.disconnect()
    override fun stopScan() = connectionManager.stopScan()
    override fun isConnected() = connectionManager.isConnected()
    override fun getConnectedDevice() = connectionManager.getConnectedDevice()
    override fun clearError() {
        _errorState.value = null
    }
}
