package com.fenglei.smartpetcollar;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
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
import com.yalantis.ucrop.UCrop;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * BleImageFragment - mirrors iOS BLEImageView.
 *
 * Layout cards: BLE status -> image card (250dp) -> Pick / Send buttons -> progress card.
 * Picked image is cropped via UCrop to 1:1 and resized to 466x466 before BLE transfer.
 */
public class BleImageFragment extends Fragment {

    private static final int REQUEST_BLE_PERMISSIONS = 100;
    private static final int REQUEST_STORAGE_PERMISSIONS = 101;
    private static final int IMAGE_FINAL_SIZE = 466;
    private static final String TRANSFER_FILE_NAME = "/sdcard/user/0.jpg";

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
                if (uri != null) {
                    startCrop(uri);
                } else {
                    setStatusMessage("No image selected");
                }
            });

    private final ActivityResultLauncher<String> getContentLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) {
                    startCrop(uri);
                } else {
                    setStatusMessage("No image selected");
                }
            });

    private final ActivityResultLauncher<Intent> cropLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Uri croppedUri = UCrop.getOutput(result.getData());
                    if (croppedUri != null) {
                        handleCroppedImage(croppedUri);
                    } else {
                        setStatusMessage("Crop result is empty");
                    }
                } else if (result.getResultCode() == UCrop.RESULT_ERROR && result.getData() != null) {
                    Throwable err = UCrop.getError(result.getData());
                    setStatusMessage("Crop failed: " + (err != null ? err.getMessage() : "unknown"));
                }
            });

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_ble_image, container, false);
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
            if (checkAndRequestStoragePermissions()) {
                pickImage();
            }
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

    // ===== Pick / Crop =====

    private void pickImage() {
        if (ActivityResultContracts.PickVisualMedia.isPhotoPickerAvailable(requireContext())) {
            pickMediaLauncher.launch(new PickVisualMediaRequest.Builder()
                    .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                    .build());
        } else {
            getContentLauncher.launch("image/*");
        }
    }

    private void startCrop(Uri sourceUri) {
        File destFile = new File(requireContext().getExternalFilesDir(Environment.DIRECTORY_PICTURES),
                "cropped_origin.jpg");
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
                .getIntent(requireContext());

        cropLauncher.launch(intent);
    }

    private void handleCroppedImage(Uri croppedUri) {
        try {
            InputStream inputStream = requireContext().getContentResolver().openInputStream(croppedUri);
            if (inputStream == null) {
                setStatusMessage("Failed to open cropped image");
                return;
            }
            Bitmap originalBitmap = BitmapFactory.decodeStream(inputStream);
            inputStream.close();
            if (originalBitmap == null) {
                setStatusMessage("Failed to decode cropped image");
                return;
            }
            Matrix matrix = new Matrix();
            matrix.postScale((float) IMAGE_FINAL_SIZE / originalBitmap.getWidth(),
                    (float) IMAGE_FINAL_SIZE / originalBitmap.getHeight());
            Bitmap resizedBitmap = Bitmap.createBitmap(originalBitmap, 0, 0,
                    originalBitmap.getWidth(), originalBitmap.getHeight(), matrix, true);
            if (resizedBitmap != originalBitmap) originalBitmap.recycle();

            ivPreview.setImageBitmap(resizedBitmap);
            ivPreview.setVisibility(View.VISIBLE);
            imagePlaceholder.setVisibility(View.GONE);

            File destFile = new File(requireContext().getExternalFilesDir(Environment.DIRECTORY_PICTURES),
                    "cropped_466x466.jpg");
            FileOutputStream fos = new FileOutputStream(destFile);
            resizedBitmap.compress(Bitmap.CompressFormat.JPEG, 100, fos);
            fos.close();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            resizedBitmap.compress(Bitmap.CompressFormat.JPEG, 100, baos);
            selectedImageBytes = baos.toByteArray();

            tvImageMeta.setVisibility(View.VISIBLE);
            tvImageMeta.setText(getString(R.string.ble_image_ready));
            setStatusMessage("Image cropped & resized: " + IMAGE_FINAL_SIZE + "x" + IMAGE_FINAL_SIZE
                    + " (" + selectedImageBytes.length + " bytes)");

            btnSendImage.setEnabled(bleConnected);
        } catch (Exception e) {
            setStatusMessage("Error processing image: " + e.getMessage());
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
        setStatusMessage("Sending image: " + TRANSFER_FILE_NAME + " (" + selectedImageBytes.length + " bytes)");
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
                            setStatusMessage("Image sent successfully!");
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
