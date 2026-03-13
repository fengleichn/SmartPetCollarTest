package com.visionox.ble.infrastructure.scanner

import android.Manifest
import com.visionox.ble.domain.scanner.Scanner
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.core.app.ActivityCompat

class BLEScanner(private val context: Context) : Scanner {

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var isScanning = false
    private val handler = Handler(Looper.getMainLooper())

    private val SCAN_PERIOD: Long = 10000

    private var scanCallback: Scanner.ScanCallback? = null

    init {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner
    }

    override fun isBLESupported(): Boolean {
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
    }

    override fun isBluetoothEnabled(): Boolean {
        return bluetoothAdapter?.isEnabled == true
    }

    /**
     * 检查扫描权限
     */
    private fun hasPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+：仅需扫描/连接权限
            ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                    ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            // Android 6-11：扫描需位置权限
            ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    override fun startScan(callback: Scanner.ScanCallback, serviceUUIDs: List<String>?) {
        if (!isBLESupported()) {
            callback.onError(-1)
            return
        }

        if (!isBluetoothEnabled()) {
            callback.onError(-2)
            return
        }

        if (!hasPermissions()) {
            callback.onError(-3)
            return
        }

        if (isScanning) {
            return
        }

        this.scanCallback = callback

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
            .setReportDelay(0)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    setLegacy(false)
                    setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
                }
            }
            .build()

        // 创建扫描过滤器
        val filters = mutableListOf<ScanFilter>()
        serviceUUIDs?.forEach { uuid ->
            val filter = ScanFilter.Builder()
                .setServiceUuid(ParcelUuid.fromString(uuid))
                .build()
            filters.add(filter)
        }

        try {
            bluetoothLeScanner?.startScan(filters, scanSettings, leScanCallback)
            isScanning = true
            callback.onScanStarted()

            // 设置扫描超时
            handler.postDelayed({
                stopScan()
            }, SCAN_PERIOD)

        } catch (e: SecurityException) {
            Log.e("BLEScanner", "权限不足: ${e.message}")
            callback.onError(-3)
        }
    }

    override fun stopScan() {
        if (!isScanning) return

        try {
            bluetoothLeScanner?.stopScan(leScanCallback)
            isScanning = false
            handler.removeCallbacksAndMessages(null)
            scanCallback?.onScanStopped()

        } catch (e: SecurityException) {
            Log.e("BLEScanner", "停止扫描失败: ${e.message}")
        }
    }

    /**
     * BLE扫描结果回调
     */
    private val leScanCallback = object : android.bluetooth.le.ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val rssi = result.rssi
            val scanRecord = result.scanRecord?.bytes

            scanCallback?.onDeviceFound(device, rssi, scanRecord)
        }

        override fun onBatchScanResults(results: List<ScanResult>) {
            for (result in results) {
                onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, result)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e("BLEScanner", "扫描失败，错误代码: $errorCode")
            isScanning = false
            scanCallback?.onError(errorCode)
        }
    }

    /**
     * 释放资源
     */
    fun release() {
        stopScan()
        scanCallback = null
    }
}