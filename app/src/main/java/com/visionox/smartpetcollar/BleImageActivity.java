package com.visionox.smartpetcollar;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
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
import com.yalantis.ucrop.UCrop;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class BleImageActivity extends AppCompatActivity {

    private static final int REQUEST_BLE_PERMISSIONS = 100;
    private static final int REQUEST_STORAGE_PERMISSIONS = 101;
    private static final int IMAGE_FINAL_SIZE = 466;
    private static final String TRANSFER_FILE_NAME = "/sdcard/user/0.jpg";

    private View bleStatusIndicator;
    private TextView tvBleStatus;
    private Button btnScan;
    private Button btnDisconnectBle;
    private Button btnPickImage;
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
                    startCrop(uri);
                } else {
                    appendLog("No image selected");
                }
            });

    // Legacy content picker (older Android)
    private final ActivityResultLauncher<String> getContentLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) {
                    startCrop(uri);
                } else {
                    appendLog("No image selected");
                }
            });

    // UCrop result handler
    private final ActivityResultLauncher<Intent> cropLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Uri croppedUri = UCrop.getOutput(result.getData());
                    if (croppedUri != null) {
                        handleCroppedImage(croppedUri);
                    } else {
                        appendLog("Crop result is empty");
                    }
                } else if (result.getResultCode() == UCrop.RESULT_ERROR && result.getData() != null) {
                    Throwable err = UCrop.getError(result.getData());
                    appendLog("Crop failed: " + (err != null ? err.getMessage() : "unknown"));
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ble_image);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.ble_image_title);
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

    // ==================== Image Picker (Modern API) ====================

    private void pickImage() {
        if (ActivityResultContracts.PickVisualMedia.isPhotoPickerAvailable(this)) {
            pickMediaLauncher.launch(new PickVisualMediaRequest.Builder()
                    .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                    .build());
        } else {
            getContentLauncher.launch("image/*");
        }
    }

    // ==================== UCrop (1:1 Square Crop) ====================

    private void startCrop(Uri sourceUri) {
        File destFile = new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "cropped_origin.jpg");
        Uri destinationUri = Uri.fromFile(destFile);

        UCrop.Options options = new UCrop.Options();
        options.setFreeStyleCropEnabled(false);
        options.setHideBottomControls(false);
        options.setToolbarTitle("Crop Image");
        options.setCompressionQuality(100);

        Intent intent = UCrop.of(sourceUri, destinationUri)
                .withAspectRatio(1f, 1f)
                .withMaxResultSize(IMAGE_FINAL_SIZE, IMAGE_FINAL_SIZE)
                .withOptions(options)
                .getIntent(this);

        cropLauncher.launch(intent);
    }

    // ==================== Process Cropped Image ====================

    private void handleCroppedImage(Uri croppedUri) {
        try {
            InputStream inputStream = getContentResolver().openInputStream(croppedUri);
            if (inputStream == null) {
                appendLog("Failed to open cropped image");
                return;
            }

            Bitmap originalBitmap = BitmapFactory.decodeStream(inputStream);
            inputStream.close();

            if (originalBitmap == null) {
                appendLog("Failed to decode cropped image");
                return;
            }

            // Resize to 466x466 using Matrix (high-quality)
            Matrix matrix = new Matrix();
            matrix.postScale((float) IMAGE_FINAL_SIZE / originalBitmap.getWidth(),
                    (float) IMAGE_FINAL_SIZE / originalBitmap.getHeight());
            Bitmap resizedBitmap = Bitmap.createBitmap(originalBitmap, 0, 0,
                    originalBitmap.getWidth(), originalBitmap.getHeight(), matrix, true);
            if (resizedBitmap != originalBitmap) {
                originalBitmap.recycle();
            }

            // Show preview
            ivPreview.setImageBitmap(resizedBitmap);

            // Save to app external storage
            File destFile = new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "cropped_466x466.jpg");
            FileOutputStream fos = new FileOutputStream(destFile);
            resizedBitmap.compress(Bitmap.CompressFormat.JPEG, 100, fos);
            fos.close();

            // Read bytes for BLE transfer
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            resizedBitmap.compress(Bitmap.CompressFormat.JPEG, 100, baos);
            selectedImageBytes = baos.toByteArray();

            appendLog("Image cropped & resized: " + IMAGE_FINAL_SIZE + "x" + IMAGE_FINAL_SIZE
                    + " (" + selectedImageBytes.length + " bytes)");
            appendLog("Transfer file name: " + TRANSFER_FILE_NAME);

            if (bleRepository.isConnected()) {
                btnSendImage.setEnabled(true);
            }

        } catch (Exception e) {
            appendLog("Error processing image: " + e.getMessage());
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

        appendLog("Sending image: " + TRANSFER_FILE_NAME + " (" + selectedImageBytes.length + " bytes)");
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
                            appendLog("Image sent successfully!");
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
