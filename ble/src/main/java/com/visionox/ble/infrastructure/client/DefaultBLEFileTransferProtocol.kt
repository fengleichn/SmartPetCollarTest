package com.visionox.ble.infrastructure.client

import android.bluetooth.BluetoothDevice
import com.visionox.ble.domain.client.BLEFileTransferProtocol
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.visionox.ble.domain.session.GattSession
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/**
 * Default implementation of BLE file transfer protocol that reuses an existing GATT session.
 */
class DefaultBLEFileTransferProtocol(
    private val context: Context,
    private val session: GattSession,
    private val dataChar: BluetoothGattCharacteristic,
    private val listener: TransferProgressListener,
    private val sessionId: Byte = 0x01
) : BLEFileTransferProtocol {

    companion object {
        private const val TAG = "DefaultBLEFileTransferProtocol"
    }

    interface TransferProgressListener {
        fun onProgress(bytesSent: Int, total: Int)
        fun onCompleted()
        fun onError(msg: String, t: Throwable? = null)
    }

    @Volatile private var mtu: Int = session.mtu
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var sending = false
    @Volatile private var fileBytes: ByteArray = byteArrayOf()
    @Volatile private var totalLen = 0
    @Volatile private var sent = 0
    @Volatile private var seq = 0
    @Volatile private var totalPackets = 0
    @Volatile private var transferStartTime: Long = 0L
    @Volatile private var currentFileName: String = ""

    // 协议常量
    private val MAX_DATA_PER_PACKET = 500
    private val HEADER_LENGTH = 6
    private val CHECKSUM_LENGTH = 1
    private val PACKET_LENGTH_SIZE = 2
    private val FRAME_TYPE_START: Byte = 0x03
    private val FRAME_TYPE_DATA: Byte = 0x00
    private val START_FRAME_DELAY_MS = 10L
    private val DATA_FRAME_DELAY_MS = 2L

    private data class ProtocolHeader(
        val sessionId: Byte,
        val type: Byte,
        val totalPackets: Int,
        val seq: Int
    )

    private fun parseHeader(value: ByteArray): ProtocolHeader? {
        if (value.size < 6) return null
        val sessionId = value[0]
        val type = value[1]
        val totalPacketsHigh = value[2].toInt() and 0xFF
        val totalPacketsLow = value[3].toInt() and 0xFF
        val totalPackets = (totalPacketsHigh shl 8) or totalPacketsLow
        val seqHigh = value[4].toInt() and 0xFF
        val seqLow = value[5].toInt() and 0xFF
        val seq = (seqHigh shl 8) or seqLow
        return ProtocolHeader(sessionId, type, totalPackets, seq)
    }

    private val sessionListener = object : GattSession.Listener {
        override fun onMtuChanged(mtu: Int) {
            Log.d(TAG, "MTU改变: $mtu")
            this@DefaultBLEFileTransferProtocol.mtu = mtu
        }

        override fun onCharacteristicWrite(
            characteristic: BluetoothGattCharacteristic,
            status: Int,
            value: ByteArray
        ) {
            if (characteristic.uuid != dataChar.uuid) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                mainHandler.post { fail("onCharacteristicWrite failed: $status") }
                return
            }

            if (value.size >= 6) {
                val frameType = value[1]
                when (frameType) {
                    FRAME_TYPE_START -> handleStartWriteAck(value)
                    FRAME_TYPE_DATA -> handleDataWriteAck(value)
                    else -> Log.w(TAG, "未知帧类型：0x${frameType.toString(16).uppercase()}, 总长度：${value.size} 字节")
                }
            } else {
                Log.d(TAG, "写入成功，但数据包过短：${value.size} 字节")
            }
        }

        override fun onDisconnected() {
            mainHandler.post {
                sending = false
            }
        }
    }

    init {
        session.addListener(sessionListener)
    }

    override fun release() {
        sending = false
        session.removeListener(sessionListener)
    }


    override fun sendBytes(bytes: ByteArray, fileName: String) {
        if (sending) {
            listener.onError("Busy: previous transfer is in progress")
            return
        }

        fileBytes = bytes
        totalLen = bytes.size
        sent = 0
        seq = 0
        sending = true
        transferStartTime = System.currentTimeMillis()
        currentFileName = fileName

        val payloadMax = maxPayloadForWrite()
        totalPackets = if (payloadMax <= 0) 1 else (totalLen + payloadMax - 1) / payloadMax

        Log.d(TAG, "========== 文件传输开始 ==========")
        Log.d(TAG, "文件名：$fileName")
        Log.d(TAG, "文件大小：${totalLen}字节 (${String.format("%.2f", totalLen / 1024.0)} KB)")
        Log.d(TAG, "预计数据包数：${totalPackets + 1}个，每包最大${payloadMax}字节")
        Log.d(TAG, "开始时间：${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.getDefault()).format(java.util.Date(transferStartTime))}")

        val startFrameOk = writeStartFrame(fileName, bytes.size)
        if (!startFrameOk) {
            listener.onError("Failed to send start frame")
            sending = false
        }
    }

    private fun pumpWithoutAck() {
        if (!sending) {
            Log.d(TAG, "传输已停止，退出pumpWithoutAck")
            return
        }
        if (sent >= totalLen) {
            Log.d(TAG, "数据发送完成，调用succeed()")
            mainHandler.post { succeed() }
            return
        }

        Log.d(TAG, "发送数据包 ${seq + 1}/${totalPackets}，已发送：${sent}/${totalLen}")
        val ok = writeNextChunk()
        if (!ok) {
            fail("Failed to write DATA chunk")
            return
        }
    }

    private fun writeStartFrame(fileName: String, fileLength: Int): Boolean {
        val nameBytes = fileName.toByteArray(Charsets.UTF_8)

        var fileNameBytes: Int = nameBytes.size
        val paddedNameBytes = if (nameBytes.size < 32) {
            nameBytes.copyOf(32)
        } else {
            fileNameBytes = 32
            nameBytes.take(32).toByteArray()
        }

        val fileInfoPart = ByteBuffer.allocate(1 + paddedNameBytes.size + 4).order(ByteOrder.BIG_ENDIAN)
            .put(fileNameBytes.toByte())
            .put(paddedNameBytes)
            .putInt(fileLength)
            .array()

        val totalPackets = (fileLength + maxPayloadForWrite() - 1) / maxPayloadForWrite()

        val header = ByteBuffer.allocate(HEADER_LENGTH).order(ByteOrder.BIG_ENDIAN)
            .put(sessionId)
            .put(FRAME_TYPE_START)
            .putShort((totalPackets + 1).toShort())
            .putShort(0)
            .array()

        val packetLength = ByteBuffer.allocate(PACKET_LENGTH_SIZE).order(ByteOrder.BIG_ENDIAN)
            .putShort(fileInfoPart.size.toShort())
            .array()

        val frameWithoutChecksum = header + packetLength + fileInfoPart
        val checksum = calculateChecksum(0, frameWithoutChecksum.size, frameWithoutChecksum)
        val payload = frameWithoutChecksum + byteArrayOf(checksum)

        Log.d(
            TAG,
            "发送START帧，文件名：${fileName}，文件大小：${fileLength}字节，总包数：${totalPackets}，START帧大小：${payload.size}字节"
        )

        return session.writeCharacteristic(
            dataChar,
            payload,
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        )
    }

    private fun writeNextChunk(): Boolean {
        val payloadMax = maxPayloadForWrite()
        if (payloadMax <= 0) {
            listener.onError("MTU too small for data transfer")
            return false
        }

        val remain = totalLen - sent
        val dataLength = min(remain, payloadMax)

        val header = ByteBuffer.allocate(HEADER_LENGTH).order(ByteOrder.BIG_ENDIAN)
            .put(sessionId)
            .put(FRAME_TYPE_DATA)
            .putShort((totalPackets + 1).toShort())
            .putShort((seq + 1).toShort())
            .array()

        val packetLength = ByteBuffer.allocate(PACKET_LENGTH_SIZE).order(ByteOrder.BIG_ENDIAN)
            .putShort(dataLength.toShort())
            .array()

        val dataPart = ByteArray(dataLength)
        System.arraycopy(fileBytes, sent, dataPart, 0, dataLength)

        val packetWithoutChecksum = header + packetLength + dataPart
        val checksum = calculateChecksum(0, packetWithoutChecksum.size, packetWithoutChecksum)
        val packet = packetWithoutChecksum + byteArrayOf(checksum)

        Log.d(
            TAG,
            "数据包构成 - Header:${header.size}, PacketLength:${packetLength.size}, " +
                "DataPart:${dataPart.size}, Checksum:1, 总计:${packet.size}字节"
        )

        val ok = session.writeCharacteristic(
            dataChar,
            packet,
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        )

        if (ok) {
            sent += dataLength
            seq += 1
            mainHandler.post { listener.onProgress(sent, totalLen) }
        }

        return ok
    }

    fun calculateChecksum(start: Int, end: Int, buf: ByteArray): Byte {
        if (buf.isEmpty() || end <= start) return 0
        var checksum = 0

        for (i in start until end) {
            checksum += buf[i].toInt() and 0xFF
        }

        return (256 - (checksum % 256)).toByte()
    }

    private fun succeed() {
        val transferEndTime = System.currentTimeMillis()
        val transferDuration = transferEndTime - transferStartTime
        val transferSpeed = if (transferDuration > 0) (totalLen * 1000.0 / transferDuration) else 0.0

        Log.d(TAG, "========== 文件传输完成 ==========")
        Log.d(TAG, "文件名：$currentFileName")
        Log.d(TAG, "文件大小：${totalLen}字节 (${String.format("%.2f", totalLen / 1024.0)} KB)")
        Log.d(TAG, "实际发送数据包数：${seq}个")
        Log.d(TAG, "结束时间：${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.getDefault()).format(java.util.Date(transferEndTime))}")
        Log.d(TAG, "传输耗时：${transferDuration}ms (${String.format("%.2f", transferDuration / 1000.0)}秒)")
        Log.d(TAG, "平均传输速度：${String.format("%.2f", transferSpeed / 1024.0)} KB/s")
        Log.d(TAG, "==================================")

        sending = false
        listener.onCompleted()
    }

    private fun fail(msg: String) {
        fail(msg, null)
    }

    private fun fail(msg: String, t: Throwable?) {
        val transferEndTime = System.currentTimeMillis()
        val transferDuration = transferEndTime - transferStartTime

        Log.e(TAG, "========== 文件传输失败 ==========")
        Log.e(TAG, "文件名：$currentFileName")
        Log.e(TAG, "文件大小：${totalLen}字节")
        Log.e(TAG, "已发送：${sent}字节 (${String.format("%.1f", if (totalLen > 0) sent * 100.0 / totalLen else 0.0)}%)")
        Log.e(TAG, "失败时间：${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.getDefault()).format(java.util.Date(transferEndTime))}")
        Log.e(TAG, "已耗时：${transferDuration}ms (${String.format("%.2f", transferDuration / 1000.0)}秒)")
        Log.e(TAG, "失败原因：$msg", t)
        Log.e(TAG, "==================================")

        sending = false
        listener.onError(msg, t)
    }

    private fun handleStartWriteAck(value: ByteArray) {
        val header = parseHeader(value) ?: return
        val fileInfoSize = value.size - 6
        Log.d(TAG, "START帧写入成功，总长度：${value.size} 字节 (包头6字节 + 文件信息${fileInfoSize}字节 + 校验和1字节)")

        val packetLengthHigh = value[6].toInt() and 0xFF
        val packetLengthLow = value[7].toInt() and 0xFF
        val packetLength = (packetLengthHigh shl 8) or packetLengthLow

        val fileNameLength = value[8].toInt() and 0xFF
        val fileNameBytes = value.slice(9 until 9 + fileNameLength)
        val fileName = String(fileNameBytes.toByteArray(), Charsets.UTF_8)
        val fileLength =
            ByteBuffer.wrap(value.slice(9 + fileNameLength until 9 + fileNameLength + 4).toByteArray())
                .int

        Log.d(TAG, "Header 输出：")
        Log.d(TAG, "sessionId: 0x${header.sessionId.toString(16)}")
        Log.d(TAG, "type: 0x${header.type.toString(16)}")
        Log.d(TAG, "totalPackets: 0x${header.totalPackets.toString(16)}")
        Log.d(TAG, "seq: 0x${header.seq.toString(16)}")
        Log.d(TAG, "packetLength: 0x${packetLength.toString(16)}")

        Log.d(TAG, "文件名长度: $fileNameLength 字节")
        Log.d(TAG, "文件名: $fileName")
        Log.d(TAG, "文件大小: $fileLength 字节")

        Log.d(TAG, "开始发送文件数据，文件总大小：${totalLen}字节，预计${header.totalPackets - 1}个数据包")
        mainHandler.postDelayed({ pumpWithoutAck() }, START_FRAME_DELAY_MS)
    }

    private fun handleDataWriteAck(value: ByteArray) {
        val header = parseHeader(value)
        if (header != null && value.size >= 9) {
            val dataSize = value.size - 6 - 2 - 1
            Log.d(
                TAG,
                "DATA帧写入成功，总长度：${value.size} 字节 " +
                    "(包头6字节 + 长度字段2字节 + 数据${dataSize}字节 + 校验和1字节)，" +
                    "进度：${sent}/${totalLen}"
            )

            Log.d(TAG, "Header 输出：")
            Log.d(TAG, "sessionId: 0x${header.sessionId.toString(16)}")
            Log.d(TAG, "type: 0x${header.type.toString(16)}")
            Log.d(TAG, "totalPackets: 0x${header.totalPackets.toString(16)}")
            Log.d(TAG, "seq: 0x${header.seq.toString(16)}")
        } else {
            Log.d(TAG, "DATA帧写入成功，总长度：${value.size} 字节，进度：${sent}/${totalLen}")
        }

        if (sent >= totalLen) {
            Log.d(TAG, "所有数据传输完成！")
            mainHandler.post { succeed() }
        } else {
            mainHandler.postDelayed({ pumpWithoutAck() }, DATA_FRAME_DELAY_MS)
        }
    }

    /** 计算可用数据部分大小（限制为500字节有效数据） */
    private fun maxPayloadForWrite(): Int {
        val mtuSupported = (mtu - 3 - HEADER_LENGTH - PACKET_LENGTH_SIZE - CHECKSUM_LENGTH)
            .coerceAtLeast(20 - 3 - HEADER_LENGTH - PACKET_LENGTH_SIZE - CHECKSUM_LENGTH)
        return min(mtuSupported, MAX_DATA_PER_PACKET)
    }
}
