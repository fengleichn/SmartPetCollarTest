package com.visionox.ble.domain.usecase

import com.visionox.ble.domain.repository.BLERepository

class DisconnectUseCase(
    private val repository: BLERepository
) {
    operator fun invoke() = repository.disconnect()
}
