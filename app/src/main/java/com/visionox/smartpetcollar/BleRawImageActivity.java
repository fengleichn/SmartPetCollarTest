package com.visionox.smartpetcollar;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.visionox.ble.data.repository.BLERepositoryImpl;
import com.visionox.ble.domain.repository.BLERepository;
import com.visionox.ble.infrastructure.factory.DefaultBLEFileTransferProtocolFactory;
import com.visionox.ble.infrastructure.protocol.DefaultBLEDeviceProfile;
import com.visionox.ble.infrastructure.scanner.BLEScanner;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class BleRawImageActivity extends AppCompatActivity {

    private static final int REQUEST_BLE_PERMISSIONS = 100;
    private static final int REQUEST_STORAGE_PERMISSIONS = 101;
    private static final String TRANSFER_FILE_NAME = "/sdcard/user/1.jpg";

    private View bleStatusIndicator;
    private TextView tvBleStatus;
    private Button btnScan;
    private Button btnDisconnectBle;
    private Button btnPickImage;
    private TextView tvImageInfo;
    private ImageView ivPreview;
    private Button btnSendImage;
    private ProgressBar progressBar;
    private TextView tvProgress;
    private ScrollView scrollViewLog;
    private TextView tvLog;
    private Button btnClearBleLog;

    private BLERepository bleRepository;
    private byte[] selectedImageBytes;

    private final StringBuilder logBuffer = new StringBuilder();
    private final SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    // Modern photo picker (Android 13+)
    private final ActivityResultLauncher<PickVisualMediaRequest> pickMediaLauncher =
            registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> {
                if (uri != null) {
                    handleSelectedImage(uri);
                } else {
                    appendLog("No image selected");
                }
            });

    // Legacy content picker (older Android)
    private final ActivityResultLauncher<String> getContentLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) {
                    handleSelectedImage(uri);
                } else {
                    appendLog("No image selected");
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ble_raw_image);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.ble_raw_image_title);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        initViews();
        initBle();
        setupListeners();
    }

    private void initViews() {
        bleStatusIndicator = findViewById(R.id.bleStatusIndicator);
        tvBleStatus = findViewById(R.id.tvBleStatus);
        btnScan = findViewById(R.id.btnScan);
        btnDisconnectBle = findViewById(R.id.btnDisconnectBle);
        btnPickImage = findViewById(R.id.btnPickImage);
        tvImageInfo = findViewById(R.id.tvImageInfo);
        ivPreview = findViewById(R.id.ivPreview);
        btnSendImage = findViewById(R.id.btnSendImage);
        progressBar = findViewById(R.id.progressBar);
        tvProgress = findViewById(R.id.tvProgress);
        scrollViewLog = findViewById(R.id.scrollViewLog);
        tvLog = findViewById(R.id.tvLog);
        btnClearBleLog = findViewById(R.id.btnClearBleLog);
    }

    private void initBle() {
        BLEScanner scanner = new BLEScanner(getApplicationContext());
        DefaultBLEFileTransferProtocolFactory factory = new DefaultBLEFileTransferProtocolFactory(getApplicationContext());
        bleRepository = new BLERepositoryImpl(
                getApplicationContext(),
                scanner,
                factory,
                DefaultBLEDeviceProfile.INSTANCE
        );
        appendLog("BLE initialized");
    }

    private void setupListeners() {
        btnScan.setOnClickListener(v -> {
            if (checkAndRequestBlePermissions()) {
                startScan();
            }
        });

        btnDisconnectBle.setOnClickListener(v -> {
            bleRepository.disconnect();
            updateBleStatus(getString(R.string.ble_status_disconnected), R.color.status_disconnected);
            btnDisconnectBle.setEnabled(false);
            appendLog("Disconnected");
        });

        btnPickImage.setOnClickListener(v -> {
            if (checkAndRequestStoragePermissions()) {
                pickImage();
            }
        });

        btnSendImage.setOnClickListener(v -> sendImage());

        btnClearBleLog.setOnClickListener(v -> {
            logBuffer.setLength(0);
            tvLog.setText(R.string.ble_log_empty);
        });
    }

    // ==================== Image Picker ====================

    private void pickImage() {
        if (ActivityResultContracts.PickVisualMedia.isPhotoPickerAvailable(this)) {
            pickMediaLauncher.launch(new PickVisualMediaRequest.Builder()
                    .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                    .build());
        } else {
            getContentLauncher.launch("image/*");
        }
    }

    // ==================== Handle Raw Image (No Crop, No Compress) ====================

    private void handleSelectedImage(Uri imageUri) {
        try {
            // Read raw bytes directly from the selected image
            InputStream inputStream = getContentResolver().openInputStream(imageUri);
            if (inputStream == null) {
                appendLog("Failed to open selected image");
                return;
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                baos.write(buffer, 0, bytesRead);
            }
            inputStream.close();
            selectedImageBytes = baos.toByteArray();

            // Show preview (decode for display only)
            Bitmap previewBitmap = BitmapFactory.decodeByteArray(selectedImageBytes, 0, selectedImageBytes.length);
            if (previewBitmap != null) {
                ivPreview.setImageBitmap(previewBitmap);
                tvImageInfo.setText(getString(R.string.ble_raw_image_info,
                        previewBitmap.getWidth(), previewBitmap.getHeight(), selectedImageBytes.length));
                appendLog("Raw image selected: " + previewBitmap.getWidth() + "x" + previewBitmap.getHeight()
                        + " (" + selectedImageBytes.length + " bytes)");
            } else {
                tvImageInfo.setText(getString(R.string.ble_raw_image_info_no_decode, selectedImageBytes.length));
                appendLog("Image selected: " + selectedImageBytes.length + " bytes (cannot decode for preview)");
            }

            appendLog("Transfer file name: " + TRANSFER_FILE_NAME);

            if (bleRepository.isConnected()) {
                btnSendImage.setEnabled(true);
            }

        } catch (Exception e) {
            appendLog("Error reading image: " + e.getMessage());
        }
    }

    // ==================== BLE Scan & Send ====================

    private void startScan() {
        appendLog("Scanning for BLE devices...");
        updateBleStatus("Scanning...", R.color.status_connecting);
        btnScan.setEnabled(false);

        BleCoroutineHelper.scanAndConnect(
                bleRepository,
                DefaultBLEDeviceProfile.INSTANCE,
                new BleCoroutineHelper.ScanCallback() {
                    @Override
                    public void onSuccess(@NonNull android.bluetooth.BluetoothDevice device) {
                        runOnUiThread(() -> {
                            updateBleStatus("Connected", R.color.status_connected);
                            btnScan.setEnabled(true);
                            btnDisconnectBle.setEnabled(true);
                            btnSendImage.setEnabled(selectedImageBytes != null);
                            appendLog("Connected to device: " + device.getAddress());
                        });
                    }

                    @Override
                    public void onError(@NonNull String error) {
                        runOnUiThread(() -> {
                            updateBleStatus("Connection failed", R.color.status_disconnected);
                            btnScan.setEnabled(true);
                            btnDisconnectBle.setEnabled(false);
                            appendLog("Scan/Connect failed: " + error);
                        });
                    }
                }
        );
    }

    private void sendImage() {
        if (selectedImageBytes == null) {
            Toast.makeText(this, "Please select an image first", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!bleRepository.isConnected()) {
            Toast.makeText(this, "Please connect to a BLE device first", Toast.LENGTH_SHORT).show();
            return;
        }

        appendLog("Sending raw image: " + TRANSFER_FILE_NAME + " (" + selectedImageBytes.length + " bytes)");
        progressBar.setVisibility(View.VISIBLE);
        tvProgress.setVisibility(View.VISIBLE);
        progressBar.setProgress(0);
        btnSendImage.setEnabled(false);

        startProgressObserver();

        BleCoroutineHelper.sendData(
                bleRepository,
                selectedImageBytes,
                TRANSFER_FILE_NAME,
                new BleCoroutineHelper.SendCallback() {
                    @Override
                    public void onSuccess() {
                        runOnUiThread(() -> {
                            progressBar.setProgress(100);
                            tvProgress.setText("100%");
                            appendLog("Raw image sent successfully!");
                            btnSendImage.setEnabled(true);
                        });
                    }

                    @Override
                    public void onError(@NonNull String error) {
                        runOnUiThread(() -> {
                            appendLog("Send failed: " + error);
                            btnSendImage.setEnabled(true);
                            progressBar.setVisibility(View.GONE);
                            tvProgress.setVisibility(View.GONE);
                        });
                    }
                }
        );
    }

    private void startProgressObserver() {
        Thread progressThread = new Thread(() -> {
            int lastProgress = -1;
            while (true) {
                int progress = bleRepository.getTransferProgress().getValue();
                if (progress != lastProgress) {
                    lastProgress = progress;
                    final int p = progress;
                    runOnUiThread(() -> {
                        progressBar.setProgress(p);
                        tvProgress.setText(p + "%");
                    });
                }
                if (progress >= 100) break;
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    break;
                }
            }
        });
        progressThread.setDaemon(true);
        progressThread.start();
    }

    // ==================== Permissions ====================

    private boolean checkAndRequestBlePermissions() {
        List<String> permissionsNeeded = new ArrayList<>();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.BLUETOOTH_SCAN);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.ACCESS_FINE_LOCATION);
            }
        }

        if (!permissionsNeeded.isEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsNeeded.toArray(new String[0]), REQUEST_BLE_PERMISSIONS);
            return false;
        }
        return true;
    }

    private boolean checkAndRequestStoragePermissions() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.READ_MEDIA_IMAGES}, REQUEST_STORAGE_PERMISSIONS);
                return false;
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQUEST_STORAGE_PERMISSIONS);
                return false;
            }
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_BLE_PERMISSIONS) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                startScan();
            } else {
                appendLog("BLE permissions denied");
                Toast.makeText(this, "BLE permissions are required", Toast.LENGTH_SHORT).show();
            }
        } else if (requestCode == REQUEST_STORAGE_PERMISSIONS) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                pickImage();
            } else {
                appendLog("Storage permission denied");
                Toast.makeText(this, "Storage permission is required to pick images", Toast.LENGTH_SHORT).show();
            }
        }
    }

    // ==================== UI Helpers ====================

    private void updateBleStatus(String status, int colorResId) {
        tvBleStatus.setText(status);
        bleStatusIndicator.setBackgroundColor(ContextCompat.getColor(this, colorResId));
    }

    private void appendLog(String message) {
        String timestamp = sdf.format(new Date());
        String logLine = "[" + timestamp + "] " + message + "\n";
        logBuffer.append(logLine);
        tvLog.setText(logBuffer.toString());
        scrollViewLog.post(() -> scrollViewLog.fullScroll(View.FOCUS_DOWN));
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (bleRepository != null) {
            bleRepository.disconnect();
        }
    }
}
