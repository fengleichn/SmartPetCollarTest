package com.visionox.ble.domain.usecase

import com.visionox.ble.domain.repository.BLERepository

class ClearErrorUseCase(
    private val repository: BLERepository
) {
    operator fun invoke() = repository.clearError()
}
