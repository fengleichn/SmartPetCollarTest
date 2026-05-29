package com.fenglei.smartpetcollar;

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
import com.yalantis.ucrop.UCrop;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * BlePetImageFragment - Pet sub-tab. One pick, two send actions
 * (Crop & Send / Send Raw) both targeting /sdcard/user/0.jpg.
 *
 * BLE scan/connect lives in the parent ImageContainerFragment via
 * BleSessionViewModel; this fragment only handles image picking,
 * cropping, and dispatching bytes through the shared session.
 */
public class BlePetImageFragment extends Fragment {

    private static final int REQUEST_STORAGE_PERMISSIONS = 101;
    private static final int IMAGE_FINAL_SIZE = 466;
    private static final String TRANSFER_FILE_NAME = "/sdcard/user/0.jpg";

    private MaterialButton btnPickImage;
    private MaterialButton btnSendCropped;
    private MaterialButton btnSendRaw;
    private View imagePlaceholder;
    private ImageView ivPreview;
    private TextView tvImageMeta;
    private ProgressBar progressBar;
    private TextView tvStatusMessage;

    private BleSessionViewModel vm;
    private Uri pickedSourceUri;
    private byte[] rawImageBytes;

    private final ActivityResultLauncher<PickVisualMediaRequest> pickMediaLauncher =
            registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> {
                if (uri != null) handlePickedImage(uri);
                else setStatusMessage("No image selected");
            });

    private final ActivityResultLauncher<String> getContentLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) handlePickedImage(uri);
                else setStatusMessage("No image selected");
            });

    private final ActivityResultLauncher<Intent> cropLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Uri croppedUri = UCrop.getOutput(result.getData());
                    if (croppedUri != null) {
                        sendCroppedImage(croppedUri);
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
        vm = new ViewModelProvider(requireParentFragment()).get(BleSessionViewModel.class);

        btnPickImage = view.findViewById(R.id.btnPickImage);
        btnSendCropped = view.findViewById(R.id.btnSendCropped);
        btnSendRaw = view.findViewById(R.id.btnSendRaw);
        imagePlaceholder = view.findViewById(R.id.imagePlaceholder);
        ivPreview = view.findViewById(R.id.ivPreview);
        tvImageMeta = view.findViewById(R.id.tvImageMeta);
        progressBar = view.findViewById(R.id.progressBar);
        tvStatusMessage = view.findViewById(R.id.tvStatusMessage);

        btnPickImage.setOnClickListener(v -> {
            if (checkAndRequestStoragePermissions()) pickImage();
        });
        btnSendCropped.setOnClickListener(v -> startCropAndSend());
        btnSendRaw.setOnClickListener(v -> sendRawImage());

        vm.getConnState().observe(getViewLifecycleOwner(), state -> updateSendButtonsEnabled());
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

    private void handlePickedImage(Uri imageUri) {
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
            rawImageBytes = baos.toByteArray();
            pickedSourceUri = imageUri;

            Bitmap previewBitmap = BitmapFactory.decodeByteArray(rawImageBytes, 0, rawImageBytes.length);
            if (previewBitmap != null) {
                ivPreview.setImageBitmap(previewBitmap);
                ivPreview.setVisibility(View.VISIBLE);
                imagePlaceholder.setVisibility(View.GONE);
                tvImageMeta.setVisibility(View.VISIBLE);
                tvImageMeta.setText(getString(R.string.ble_raw_size_format, rawImageBytes.length));
                setStatusMessage("Image selected: " + previewBitmap.getWidth() + "x"
                        + previewBitmap.getHeight() + " (" + rawImageBytes.length + " bytes)");
            } else {
                tvImageMeta.setVisibility(View.VISIBLE);
                tvImageMeta.setText(getString(R.string.ble_raw_size_format, rawImageBytes.length));
                setStatusMessage("Image selected: " + rawImageBytes.length + " bytes (cannot decode)");
            }
            updateSendButtonsEnabled();
        } catch (Exception e) {
            setStatusMessage("Error reading image: " + e.getMessage());
        }
    }

    // ===== Crop & Send =====

    private void startCropAndSend() {
        if (pickedSourceUri == null) {
            Toast.makeText(requireContext(), "Please select an image first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!vm.isConnected()) {
            Toast.makeText(requireContext(), "Please connect to a BLE device first", Toast.LENGTH_SHORT).show();
            return;
        }
        File destFile = new File(requireContext().getExternalFilesDir(Environment.DIRECTORY_PICTURES),
                "cropped_origin.jpg");
        Uri destinationUri = Uri.fromFile(destFile);

        UCrop.Options options = new UCrop.Options();
        options.setFreeStyleCropEnabled(false);
        options.setHideBottomControls(false);
        options.setToolbarTitle("Crop Image");
        options.setCompressionQuality(100);

        Intent intent = UCrop.of(pickedSourceUri, destinationUri)
                .withAspectRatio(1f, 1f)
                .withMaxResultSize(IMAGE_FINAL_SIZE, IMAGE_FINAL_SIZE)
                .withOptions(options)
                .getIntent(requireContext());

        cropLauncher.launch(intent);
    }

    private void sendCroppedImage(Uri croppedUri) {
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
            byte[] croppedBytes = baos.toByteArray();

            tvImageMeta.setVisibility(View.VISIBLE);
            tvImageMeta.setText(getString(R.string.ble_image_ready));

            sendBytes(croppedBytes, "cropped");
        } catch (Exception e) {
            setStatusMessage("Error processing cropped image: " + e.getMessage());
        }
    }

    // ===== Raw send =====

    private void sendRawImage() {
        if (rawImageBytes == null) {
            Toast.makeText(requireContext(), "Please select an image first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!vm.isConnected()) {
            Toast.makeText(requireContext(), "Please connect to a BLE device first", Toast.LENGTH_SHORT).show();
            return;
        }
        sendBytes(rawImageBytes, "raw");
    }

    private void sendBytes(byte[] bytes, String tag) {
        BLERepository ble = vm.getBleRepository();
        vm.markTransferStart("Sending " + tag + " image: " + TRANSFER_FILE_NAME
                + " (" + bytes.length + " bytes)");
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setProgress(0);

        startProgressObserver(ble);

        BleCoroutineHelper.sendData(
                ble,
                bytes,
                TRANSFER_FILE_NAME,
                new BleCoroutineHelper.SendCallback() {
                    @Override
                    public void onSuccess() {
                        runOnUi(() -> {
                            progressBar.setProgress(100);
                            String head = tag.substring(0, 1).toUpperCase() + tag.substring(1);
                            vm.markTransferEnd(head + " image sent successfully!");
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

    private void updateSendButtonsEnabled() {
        if (!isAdded()) return;
        boolean hasImage = rawImageBytes != null && pickedSourceUri != null;
        boolean canSend = hasImage && vm != null
                && vm.getConnState().getValue() == BleSessionViewModel.ConnState.CONNECTED;
        btnSendCropped.setEnabled(canSend);
        btnSendRaw.setEnabled(canSend);
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
