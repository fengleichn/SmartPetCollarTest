package com.fenglei.smartpetcollar;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.visionox.ble.data.repository.BLERepositoryImpl;
import com.visionox.ble.domain.repository.BLERepository;
import com.visionox.ble.infrastructure.factory.DefaultBLEFileTransferProtocolFactory;
import com.visionox.ble.infrastructure.protocol.DefaultBLEDeviceProfile;
import com.visionox.ble.infrastructure.scanner.BLEScanner;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * BleRawImageFragment - mirrors iOS BLERawImageView.
 *
 * Sends an image as-is (no crop, no resize) over BLE to /sdcard/user/1.jpg.
 */
public class BleRawImageFragment extends Fragment {

    private static final int REQUEST_BLE_PERMISSIONS = 200;
    private static final int REQUEST_STORAGE_PERMISSIONS = 201;
    private static final String TRANSFER_FILE_NAME = "/sdcard/user/1.jpg";

    private View statusDot;
    private TextView tvBleStatus;
    private MaterialButton btnScan;
    private MaterialButton btnPickImage;
    private MaterialButton btnSendImage;
    private View imagePlaceholder;
    private ImageView ivPreview;
    private TextView tvImageMeta;
    private ProgressBar progressBar;
    private TextView tvStatusMessage;

    private BLERepository bleRepository;
    private byte[] selectedImageBytes;
    private boolean bleConnected = false;

    private final ActivityResultLauncher<PickVisualMediaRequest> pickMediaLauncher =
            registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> {
                if (uri != null) handleSelectedImage(uri);
                else setStatusMessage("No image selected");
            });

    private final ActivityResultLauncher<String> getContentLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) handleSelectedImage(uri);
                else setStatusMessage("No image selected");
            });

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_ble_raw_image, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        statusDot = view.findViewById(R.id.statusDot);
        tvBleStatus = view.findViewById(R.id.tvBleStatus);
        btnScan = view.findViewById(R.id.btnScan);
        btnPickImage = view.findViewById(R.id.btnPickImage);
        btnSendImage = view.findViewById(R.id.btnSendImage);
        imagePlaceholder = view.findViewById(R.id.imagePlaceholder);
        ivPreview = view.findViewById(R.id.ivPreview);
        tvImageMeta = view.findViewById(R.id.tvImageMeta);
        progressBar = view.findViewById(R.id.progressBar);
        tvStatusMessage = view.findViewById(R.id.tvStatusMessage);

        initBle();

        btnScan.setOnClickListener(v -> {
            if (bleConnected) {
                bleRepository.disconnect();
                updateBleState(R.string.ble_disconnected, R.color.status_disconnected, false);
                setStatusMessage("Disconnected");
            } else if (checkAndRequestBlePermissions()) {
                startScan();
            }
        });

        btnPickImage.setOnClickListener(v -> {
            if (checkAndRequestStoragePermissions()) pickImage();
        });
        btnSendImage.setOnClickListener(v -> sendImage());

        updateBleState(R.string.ble_idle, R.color.status_disconnected, false);
    }

    private void initBle() {
        BLEScanner scanner = new BLEScanner(requireContext().getApplicationContext());
        DefaultBLEFileTransferProtocolFactory factory =
                new DefaultBLEFileTransferProtocolFactory(requireContext().getApplicationContext());
        bleRepository = new BLERepositoryImpl(
                requireContext().getApplicationContext(),
                scanner,
                factory,
                DefaultBLEDeviceProfile.INSTANCE
        );
    }

    // ===== Pick =====

    private void pickImage() {
        if (ActivityResultContracts.PickVisualMedia.isPhotoPickerAvailable(requireContext())) {
            pickMediaLauncher.launch(new PickVisualMediaRequest.Builder()
                    .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                    .build());
        } else {
            getContentLauncher.launch("image/*");
        }
    }

    private void handleSelectedImage(Uri imageUri) {
        try {
            InputStream inputStream = requireContext().getContentResolver().openInputStream(imageUri);
            if (inputStream == null) {
                setStatusMessage("Failed to open selected image");
                return;
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) baos.write(buffer, 0, bytesRead);
            inputStream.close();
            selectedImageBytes = baos.toByteArray();

            Bitmap previewBitmap = BitmapFactory.decodeByteArray(selectedImageBytes, 0, selectedImageBytes.length);
            if (previewBitmap != null) {
                ivPreview.setImageBitmap(previewBitmap);
                ivPreview.setVisibility(View.VISIBLE);
                imagePlaceholder.setVisibility(View.GONE);
                tvImageMeta.setVisibility(View.VISIBLE);
                tvImageMeta.setText(getString(R.string.ble_raw_size_format, selectedImageBytes.length));
                setStatusMessage("Raw image selected: " + previewBitmap.getWidth() + "x"
                        + previewBitmap.getHeight() + " (" + selectedImageBytes.length + " bytes)");
            } else {
                tvImageMeta.setVisibility(View.VISIBLE);
                tvImageMeta.setText(getString(R.string.ble_raw_size_format, selectedImageBytes.length));
                setStatusMessage("Image selected: " + selectedImageBytes.length + " bytes (cannot decode)");
            }
            btnSendImage.setEnabled(bleConnected);
        } catch (Exception e) {
            setStatusMessage("Error reading image: " + e.getMessage());
        }
    }

    // ===== Scan / Send =====

    private void startScan() {
        setStatusMessage("Scanning for BLE devices...");
        updateBleState(R.string.ble_scanning, R.color.status_connecting, false);
        btnScan.setEnabled(false);

        BleCoroutineHelper.scanAndConnect(
                bleRepository,
                DefaultBLEDeviceProfile.INSTANCE,
                new BleCoroutineHelper.ScanCallback() {
                    @Override
                    public void onSuccess(@NonNull android.bluetooth.BluetoothDevice device) {
                        runOnUi(() -> {
                            updateBleState(R.string.ble_connected, R.color.status_connected, true);
                            btnScan.setEnabled(true);
                            btnSendImage.setEnabled(selectedImageBytes != null);
                            setStatusMessage("Connected: " + device.getAddress());
                        });
                    }

                    @Override
                    public void onError(@NonNull String error) {
                        runOnUi(() -> {
                            updateBleState(R.string.ble_disconnected, R.color.status_disconnected, false);
                            btnScan.setEnabled(true);
                            setStatusMessage("Scan/Connect failed: " + error);
                        });
                    }
                }
        );
    }

    private void sendImage() {
        if (selectedImageBytes == null) {
            Toast.makeText(requireContext(), "Please select an image first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!bleRepository.isConnected()) {
            Toast.makeText(requireContext(), "Please connect to a BLE device first", Toast.LENGTH_SHORT).show();
            return;
        }
        setStatusMessage("Sending raw image: " + TRANSFER_FILE_NAME + " (" + selectedImageBytes.length + " bytes)");
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setProgress(0);
        btnSendImage.setEnabled(false);
        updateBleState(R.string.ble_transferring, R.color.status_transferring, true);

        startProgressObserver();

        BleCoroutineHelper.sendData(
                bleRepository,
                selectedImageBytes,
                TRANSFER_FILE_NAME,
                new BleCoroutineHelper.SendCallback() {
                    @Override
                    public void onSuccess() {
                        runOnUi(() -> {
                            progressBar.setProgress(100);
                            setStatusMessage("Raw image sent successfully!");
                            btnSendImage.setEnabled(true);
                            updateBleState(R.string.ble_connected, R.color.status_connected, true);
                        });
                    }

                    @Override
                    public void onError(@NonNull String error) {
                        runOnUi(() -> {
                            setStatusMessage("Send failed: " + error);
                            btnSendImage.setEnabled(true);
                            progressBar.setVisibility(View.GONE);
                            updateBleState(R.string.ble_connected, R.color.status_connected, true);
                        });
                    }
                }
        );
    }

    private void startProgressObserver() {
        Thread t = new Thread(() -> {
            int lastProgress = -1;
            while (true) {
                int progress = bleRepository.getTransferProgress().getValue();
                if (progress != lastProgress) {
                    lastProgress = progress;
                    int p = progress;
                    runOnUi(() -> progressBar.setProgress(p));
                }
                if (progress >= 100) break;
                try { Thread.sleep(100); } catch (InterruptedException e) { break; }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    // ===== Permissions =====

    private boolean checkAndRequestBlePermissions() {
        java.util.List<String> needed = new java.util.ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_SCAN)
                    != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_SCAN);
            }
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
        } else {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.ACCESS_FINE_LOCATION);
            }
        }
        if (!needed.isEmpty()) {
            ActivityCompat.requestPermissions(requireActivity(), needed.toArray(new String[0]),
                    REQUEST_BLE_PERMISSIONS);
            return false;
        }
        return true;
    }

    private boolean checkAndRequestStoragePermissions() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_MEDIA_IMAGES)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(requireActivity(),
                        new String[]{Manifest.permission.READ_MEDIA_IMAGES}, REQUEST_STORAGE_PERMISSIONS);
                return false;
            }
        } else {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(requireActivity(),
                        new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQUEST_STORAGE_PERMISSIONS);
                return false;
            }
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_BLE_PERMISSIONS) {
            boolean all = grantResults.length > 0;
            for (int r : grantResults) if (r != PackageManager.PERMISSION_GRANTED) { all = false; break; }
            if (all) startScan();
            else setStatusMessage("BLE permissions denied");
        } else if (requestCode == REQUEST_STORAGE_PERMISSIONS) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) pickImage();
            else setStatusMessage("Storage permission denied");
        }
    }

    // ===== UI helpers =====

    private void updateBleState(int textRes, int colorRes, boolean connected) {
        if (!isAdded()) return;
        bleConnected = connected;
        tvBleStatus.setText(textRes);
        setDotColor(statusDot, ContextCompat.getColor(requireContext(), colorRes));
        btnScan.setText(connected ? R.string.ble_btn_disconnect : R.string.ble_btn_scan);
        btnSendImage.setEnabled(connected && selectedImageBytes != null);
    }

    private void setDotColor(View dot, int color) {
        if (dot.getBackground() instanceof GradientDrawable) {
            ((GradientDrawable) dot.getBackground().mutate()).setColor(color);
        } else {
            dot.setBackgroundColor(color);
        }
    }

    private void setStatusMessage(String msg) {
        if (!isAdded()) return;
        tvStatusMessage.setText(msg);
    }

    private void runOnUi(Runnable r) {
        if (isAdded() && getActivity() != null) {
            requireActivity().runOnUiThread(r);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (bleRepository != null) bleRepository.disconnect();
    }
}
