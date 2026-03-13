package com.visionox.ble.domain.model

/**
 * 扫描状态
 */
sealed class ScanningState {
    object Idle : ScanningState()
    object Scanning : ScanningState()
}