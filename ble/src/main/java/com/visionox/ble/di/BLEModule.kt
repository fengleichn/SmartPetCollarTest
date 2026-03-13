package com.visionox.ble.di

import android.content.Context
import com.visionox.ble.domain.scanner.Scanner
import com.visionox.ble.domain.factory.BLEFileTransferProtocolFactory
import com.visionox.ble.domain.repository.BLERepository
import com.visionox.ble.domain.usecase.ClearErrorUseCase
import com.visionox.ble.domain.usecase.DisconnectUseCase
import com.visionox.ble.domain.usecase.GetConnectionInfoUseCase
import com.visionox.ble.domain.usecase.ObserveBLEStateUseCase
import com.visionox.ble.domain.usecase.ScanAndConnectDeviceUseCase
import com.visionox.ble.domain.usecase.SendDataUseCase
import com.visionox.ble.domain.usecase.StopScanUseCase
import com.visionox.ble.data.repository.BLERepositoryImpl
import com.visionox.ble.infrastructure.scanner.BLEScanner
import com.visionox.ble.infrastructure.factory.DefaultBLEFileTransferProtocolFactory
import com.visionox.ble.domain.protocol.BLEDeviceProfileProvider
import com.visionox.ble.infrastructure.protocol.DefaultBLEDeviceProfileProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object BLEModule {

    @Provides
    @Singleton
    fun provideScanner(@ApplicationContext context: Context): Scanner = BLEScanner(context)

    @Provides
    @Singleton
    fun provideBLEFileTransferProtocolFactory(@ApplicationContext context: Context): BLEFileTransferProtocolFactory =
        DefaultBLEFileTransferProtocolFactory(context)

    @Provides
    @Singleton
    fun provideBLEDeviceProfileProvider(): BLEDeviceProfileProvider =
        DefaultBLEDeviceProfileProvider()

    @Provides
    @Singleton
    fun provideBLERepository(
        @ApplicationContext context: Context,
        scanner: Scanner,
        protocolFactory: BLEFileTransferProtocolFactory,
        profileProvider: BLEDeviceProfileProvider
    ): BLERepository = BLERepositoryImpl(context, scanner, protocolFactory, profileProvider.getDefaultProfile())

    @Provides
    fun provideScanAndConnectDeviceUseCase(repository: BLERepository): ScanAndConnectDeviceUseCase =
        ScanAndConnectDeviceUseCase(repository)

    @Provides
    fun provideSendDataUseCase(repository: BLERepository): SendDataUseCase =
        SendDataUseCase(repository)

    @Provides
    fun provideDisconnectUseCase(repository: BLERepository): DisconnectUseCase =
        DisconnectUseCase(repository)

    @Provides
    fun provideStopScanUseCase(repository: BLERepository): StopScanUseCase =
        StopScanUseCase(repository)

    @Provides
    fun provideClearErrorUseCase(repository: BLERepository): ClearErrorUseCase =
        ClearErrorUseCase(repository)

    @Provides
    fun provideGetConnectionInfoUseCase(repository: BLERepository): GetConnectionInfoUseCase =
        GetConnectionInfoUseCase(repository)

    @Provides
    fun provideObserveBLEStateUseCase(repository: BLERepository): ObserveBLEStateUseCase =
        ObserveBLEStateUseCase(repository)
}
