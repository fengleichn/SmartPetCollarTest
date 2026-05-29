package com.fenglei.smartpetcollar;

import android.app.Application;
import android.bluetooth.BluetoothDevice;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.visionox.ble.data.repository.BLERepositoryImpl;
import com.visionox.ble.domain.repository.BLERepository;
import com.visionox.ble.infrastructure.factory.DefaultBLEFileTransferProtocolFactory;
import com.visionox.ble.infrastructure.protocol.DefaultBLEDeviceProfile;
import com.visionox.ble.infrastructure.scanner.BLEScanner;

/**
 * BleSessionViewModel - shared BLE session for the Image tab.
 *
 * Scoped to ImageContainerFragment so its two child fragments
 * (Pet, Contact) reuse a single BLERepository, scan operation,
 * and connection state.
 */
public class BleSessionViewModel extends AndroidViewModel {

    public enum ConnState { IDLE, SCANNING, CONNECTED, TRANSFERRING, DISCONNECTED }

    private final BLERepository bleRepository;

    private final MutableLiveData<ConnState> connState = new MutableLiveData<>(ConnState.IDLE);
    private final MutableLiveData<String> message = new MutableLiveData<>("");
    private final MutableLiveData<String> connectedAddress = new MutableLiveData<>(null);

    public BleSessionViewModel(@NonNull Application app) {
        super(app);
        BLEScanner scanner = new BLEScanner(app);
        DefaultBLEFileTransferProtocolFactory factory =
                new DefaultBLEFileTransferProtocolFactory(app);
        bleRepository = new BLERepositoryImpl(
                app,
                scanner,
                factory,
                DefaultBLEDeviceProfile.INSTANCE
        );
    }

    public LiveData<ConnState> getConnState() { return connState; }
    public LiveData<String> getMessage() { return message; }
    public LiveData<String> getConnectedAddress() { return connectedAddress; }

    public BLERepository getBleRepository() { return bleRepository; }

    public boolean isConnected() {
        return connState.getValue() == ConnState.CONNECTED
                || connState.getValue() == ConnState.TRANSFERRING;
    }

    public void scanAndConnect() {
        ConnState s = connState.getValue();
        if (s == ConnState.SCANNING || s == ConnState.TRANSFERRING) return;

        connState.setValue(ConnState.SCANNING);
        message.setValue("Scanning for BLE devices...");

        BleCoroutineHelper.scanAndConnect(
                bleRepository,
                DefaultBLEDeviceProfile.INSTANCE,
                new BleCoroutineHelper.ScanCallback() {
                    @Override
                    public void onSuccess(@NonNull BluetoothDevice device) {
                        connState.postValue(ConnState.CONNECTED);
                        connectedAddress.postValue(device.getAddress());
                        message.postValue("Connected: " + device.getAddress());
                    }

                    @Override
                    public void onError(@NonNull String error) {
                        connState.postValue(ConnState.DISCONNECTED);
                        connectedAddress.postValue(null);
                        message.postValue("Scan/Connect failed: " + error);
                    }
                }
        );
    }

    public void disconnect() {
        bleRepository.disconnect();
        connState.setValue(ConnState.DISCONNECTED);
        connectedAddress.setValue(null);
        message.setValue("Disconnected");
    }

    /** Mark transfer start so all subscribers (incl. the other child fragment) disable send buttons. */
    public void markTransferStart(@Nullable String startMessage) {
        connState.setValue(ConnState.TRANSFERRING);
        if (startMessage != null) message.setValue(startMessage);
    }

    /** Mark transfer end. If still connected, returns to CONNECTED, else DISCONNECTED. */
    public void markTransferEnd(@Nullable String endMessage) {
        connState.setValue(bleRepository.isConnected() ? ConnState.CONNECTED : ConnState.DISCONNECTED);
        if (endMessage != null) message.setValue(endMessage);
    }

    public void postMessage(@NonNull String msg) {
        message.postValue(msg);
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        bleRepository.disconnect();
    }
}
