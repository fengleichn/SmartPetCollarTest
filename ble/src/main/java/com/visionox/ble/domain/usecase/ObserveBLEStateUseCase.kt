package com.visionox.ble.domain.usecase

import com.visionox.ble.domain.model.BLEConnectionState
import com.visionox.ble.domain.model.BLEError
import com.visionox.ble.domain.model.ScanningState
import com.visionox.ble.domain.repository.BLERepository
import kotlinx.coroutines.flow.StateFlow

/**
 * UseCase for observing BLE state flows
 */
class ObserveBLEStateUseCase(
    private val repository: BLERepository
) {
    val connectionState: StateFlow<BLEConnectionState> = repository.connectionState
    val scanningState: StateFlow<ScanningState> = repository.scanningState
    val errorState: StateFlow<BLEError?> = repository.errorState
    val transferProgress: StateFlow<Int> = repository.transferProgress
}
