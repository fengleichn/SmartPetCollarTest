package com.visionox.ble.domain.usecase

import com.visionox.ble.domain.repository.BLERepository

class SendDataUseCase(
    private val repository: BLERepository
) {
    suspend operator fun invoke(data: ByteArray, fileName: String): Result<Unit> = repository.sendData(data, fileName)
}
