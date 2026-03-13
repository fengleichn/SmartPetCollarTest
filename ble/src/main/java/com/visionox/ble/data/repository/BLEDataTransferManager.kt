package com.visionox.ble.data.repository

import android.bluetooth.BluetoothGattCharacteristic
import android.util.Log
import com.visionox.ble.domain.model.BLEError
import com.visionox.ble.domain.client.BLEFileTransferProtocol
import com.visionox.ble.domain.factory.BLEFileTransferProtocolFactory
import com.visionox.ble.domain.session.GattSession
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class BLEDataTransferManager(
    private val protocolFactory: BLEFileTransferProtocolFactory
) {

    companion object {
        private const val TAG = "BLEDataTransferManager"
    }

    private var transferClient: BLEFileTransferProtocol? = null
    private var pendingPayload: ByteArray? = null
    private var progressCallback: ((Int, Int) -> Unit)? = null
    private var transferContinuation: kotlin.coroutines.Continuation<Result<Unit>>? = null
    private var activeSession: GattSession? = null
    private var activeCharacteristic: BluetoothGattCharacteristic? = null

    suspend fun sendData(
        session: GattSession,
        dataCharacteristic: BluetoothGattCharacteristic,
        data: ByteArray,
        fileName: String,
        onProgress: (bytesSent: Int, total: Int) -> Unit,
        onCompleted: () -> Unit,
        onError: (BLEError) -> Unit
    ): Result<Unit> = suspendCancellableCoroutine { continuation ->
        synchronized(this@BLEDataTransferManager) {
            if (pendingPayload != null) {
                Log.w(TAG, "已有传输任务在进行，拒绝新的传输请求")
                onError(BLEError.ConnectionFailed("传输进行中"))
                continuation.resume(Result.failure(Exception("传输进行中")))
                return@suspendCancellableCoroutine
            }

            pendingPayload = data
            progressCallback = onProgress
            transferContinuation = continuation

            // 初始进度设为1字节，确保UI显示进度视图（0会导致视图隐藏）
            onProgress(1, data.size)

            try {
                val client = obtainClient(
                    session,
                    dataCharacteristic,
                    onCompleted = {
                        onCompleted()
                        synchronized(this@BLEDataTransferManager) {
                            pendingPayload = null
                            val cont = transferContinuation
                            transferContinuation = null
                            clearCallbacks()
                            cont?.resume(Result.success(Unit))
                        }
                    },
                    onError = { msg, t ->
                        val error = BLEError.ConnectionFailed(msg)
                        onError(error)
                        synchronized(this@BLEDataTransferManager) {
                            pendingPayload = null
                            val cont = transferContinuation
                            transferContinuation = null
                            clearCallbacks()
                            cont?.resume(Result.failure(Exception(msg, t)))
                        }
                    }
                )
                client.sendBytes(data, fileName)
            } catch (e: Exception) {
                Log.e(TAG, "发送数据失败: ${e.message}")
                onError(BLEError.ConnectionFailed(e.message ?: "发送失败"))
                pendingPayload = null
                transferContinuation = null
                clearCallbacks()
                continuation.resume(Result.failure(e))
            }
        }

        continuation.invokeOnCancellation {
            Log.d(TAG, "传输被取消，释放资源")
            synchronized(this@BLEDataTransferManager) {
                // 复用 releaseClient 保证逻辑一致
                releaseClient(transferClient, true)
                pendingPayload = null
                transferContinuation = null
                clearCallbacks()
            }
        }
    }

    fun disconnect() {
        releaseClient(transferClient, true)
        activeSession = null
        activeCharacteristic = null
        pendingPayload = null
        transferContinuation = null
        clearCallbacks()
    }

    private fun obtainClient(
        session: GattSession,
        dataCharacteristic: BluetoothGattCharacteristic,
        onCompleted: () -> Unit,
        onError: (String, Throwable?) -> Unit
    ): BLEFileTransferProtocol {
        val existingClient = transferClient

        // 每次传输都创建新的client以确保回调正确
        // 必须移除旧client的listener，否则旧listener仍会收到回调导致状态混乱
        releaseClient(existingClient, true)
        activeSession = session
        activeCharacteristic = dataCharacteristic

        return protocolFactory.create(
            session,
            dataCharacteristic,
            onProgress = { bytesSent, total ->
                Log.d(TAG, "传输进度: $bytesSent / $total")
                synchronized(this) {
                    progressCallback?.invoke(bytesSent, total)
                }
            },
            onCompleted = {
                Log.d(TAG, "文件传输完成")
                onCompleted()
            },
            onError = { msg, t ->
                Log.e(TAG, "传输出错: $msg", t)
                onError(msg, t)
            }
        ).also {
            transferClient = it
        }
    }

    private fun releaseClient(target: BLEFileTransferProtocol?, removeListener: Boolean = true) {
        target ?: return
        val isActiveClient = transferClient === target

        if (removeListener) {
            target.release()
        }
        if (isActiveClient) {
            transferClient = null
            activeSession = null
            activeCharacteristic = null
        }
    }

    private fun clearCallbacks() {
        progressCallback = null
    }
}
