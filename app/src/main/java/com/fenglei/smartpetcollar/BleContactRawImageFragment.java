package com.fenglei.smartpetcollar;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.button.MaterialButton;
import com.visionox.ble.domain.repository.BLERepository;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * BleContactRawImageFragment - Contact sub-tab. Sends an image as-is
 * over BLE to /sdcard/user/1.jpg, sharing the BLE session owned by
 * the parent ImageContainerFragment (BleSessionViewModel).
 */
public class BleContactRawImageFragment extends Fragment {

    private static final int REQUEST_STORAGE_PERMISSIONS = 201;
    private static final String TRANSFER_FILE_NAME = "/sdcard/user/1.jpg";

    private MaterialButton btnPickImage;
    private MaterialButton btnSendImage;
    private View imagePlaceholder;
    private ImageView ivPreview;
    private TextView tvImageMeta;
    private ProgressBar progressBar;
    private TextView tvStatusMessage;

    private BleSessionViewModel vm;
    private byte[] selectedImageBytes;

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
        vm = new ViewModelProvider(requireParentFragment()).get(BleSessionViewModel.class);

        btnPickImage = view.findViewById(R.id.btnPickImage);
        btnSendImage = view.findViewById(R.id.btnSendImage);
        imagePlaceholder = view.findViewById(R.id.imagePlaceholder);
        ivPreview = view.findViewById(R.id.ivPreview);
        tvImageMeta = view.findViewById(R.id.tvImageMeta);
        progressBar = view.findViewById(R.id.progressBar);
        tvStatusMessage = view.findViewById(R.id.tvStatusMessage);

        btnPickImage.setOnClickListener(v -> {
            if (checkAndRequestStoragePermissions()) pickImage();
        });
        btnSendImage.setOnClickListener(v -> sendImage());

        vm.getConnState().observe(getViewLifecycleOwner(), state -> updateSendButtonEnabled());
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
            updateSendButtonEnabled();
        } catch (Exception e) {
            setStatusMessage("Error reading image: " + e.getMessage());
        }
    }

    // ===== Send =====

    private void sendImage() {
        if (selectedImageBytes == null) {
            Toast.makeText(requireContext(), "Please select an image first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!vm.isConnected()) {
            Toast.makeText(requireContext(), "Please connect to a BLE device first", Toast.LENGTH_SHORT).show();
            return;
        }
        BLERepository ble = vm.getBleRepository();
        vm.markTransferStart("Sending raw image: " + TRANSFER_FILE_NAME
                + " (" + selectedImageBytes.length + " bytes)");
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setProgress(0);

        startProgressObserver(ble);

        BleCoroutineHelper.sendData(
                ble,
                selectedImageBytes,
                TRANSFER_FILE_NAME,
                new BleCoroutineHelper.SendCallback() {
                    @Override
                    public void onSuccess() {
                        runOnUi(() -> {
                            progressBar.setProgress(100);
                            vm.markTransferEnd("Raw image sent successfully!");
                        });
                    }

                    @Override
                    public void onError(@NonNull String error) {
                        runOnUi(() -> {
                            progressBar.setVisibility(View.GONE);
                            vm.markTransferEnd("Send failed: " + error);
                        });
                    }
                }
        );
    }

    private void updateSendButtonEnabled() {
        if (!isAdded()) return;
        boolean canSend = selectedImageBytes != null && vm != null
                && vm.getConnState().getValue() == BleSessionViewModel.ConnState.CONNECTED;
        btnSendImage.setEnabled(canSend);
    }

    private void startProgressObserver(BLERepository ble) {
        Thread t = new Thread(() -> {
            int lastProgress = -1;
            while (true) {
                int progress = ble.getTransferProgress().getValue();
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

    // ===== Storage permission =====

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
        if (requestCode == REQUEST_STORAGE_PERMISSIONS) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) pickImage();
            else setStatusMessage("Storage permission denied");
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
}
