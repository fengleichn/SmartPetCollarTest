package com.fenglei.smartpetcollar;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

/**
 * ImageContainerFragment - hosts the two BLE image-transfer flows
 * (Pet / Contact) and owns the single shared BLE scan/connect UI.
 */
public class ImageContainerFragment extends Fragment {

    private static final int PAGE_PET = 0;
    private static final int PAGE_CONTACT = 1;
    private static final int REQUEST_BLE_PERMISSIONS = 400;

    private BleSessionViewModel vm;

    private View statusDot;
    private TextView tvBleStatus;
    private MaterialButton btnScan;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_image_container, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        vm = new ViewModelProvider(this).get(BleSessionViewModel.class);

        statusDot = view.findViewById(R.id.statusDot);
        tvBleStatus = view.findViewById(R.id.tvBleStatus);
        btnScan = view.findViewById(R.id.btnScan);

        TabLayout tabLayout = view.findViewById(R.id.imageTabLayout);
        ViewPager2 viewPager = view.findViewById(R.id.imageViewPager);
        viewPager.setAdapter(new ImagePagerAdapter(this));
        viewPager.setOffscreenPageLimit(1);

        new TabLayoutMediator(tabLayout, viewPager, (tab, position) -> {
            switch (position) {
                case PAGE_PET:
                    tab.setText(R.string.image_subtab_pet);
                    break;
                case PAGE_CONTACT:
                    tab.setText(R.string.image_subtab_contact);
                    break;
            }
        }).attach();

        btnScan.setOnClickListener(v -> onScanClicked());

        vm.getConnState().observe(getViewLifecycleOwner(), this::applyConnState);
    }

    private void onScanClicked() {
        BleSessionViewModel.ConnState state = vm.getConnState().getValue();
        if (state == BleSessionViewModel.ConnState.CONNECTED
                || state == BleSessionViewModel.ConnState.TRANSFERRING) {
            vm.disconnect();
        } else if (checkAndRequestBlePermissions()) {
            vm.scanAndConnect();
        }
    }

    private void applyConnState(BleSessionViewModel.ConnState state) {
        if (!isAdded() || state == null) return;
        int textRes;
        int colorRes;
        boolean scanEnabled;
        boolean asDisconnect;
        switch (state) {
            case SCANNING:
                textRes = R.string.ble_scanning;
                colorRes = R.color.status_connecting;
                scanEnabled = false;
                asDisconnect = false;
                break;
            case CONNECTED:
                textRes = R.string.ble_connected;
                colorRes = R.color.status_connected;
                scanEnabled = true;
                asDisconnect = true;
                break;
            case TRANSFERRING:
                textRes = R.string.ble_transferring;
                colorRes = R.color.status_transferring;
                scanEnabled = false;
                asDisconnect = true;
                break;
            case DISCONNECTED:
                textRes = R.string.ble_disconnected;
                colorRes = R.color.status_idle;
                scanEnabled = true;
                asDisconnect = false;
                break;
            case IDLE:
            default:
                textRes = R.string.ble_idle;
                colorRes = R.color.status_idle;
                scanEnabled = true;
                asDisconnect = false;
                break;
        }
        tvBleStatus.setText(textRes);
        setDotColor(statusDot, ContextCompat.getColor(requireContext(), colorRes));
        btnScan.setEnabled(scanEnabled);
        btnScan.setText(asDisconnect ? R.string.ble_btn_disconnect : R.string.ble_btn_scan);
    }

    private void setDotColor(View dot, int color) {
        if (dot.getBackground() instanceof GradientDrawable) {
            ((GradientDrawable) dot.getBackground().mutate()).setColor(color);
        } else {
            dot.setBackgroundColor(color);
        }
    }

    // ===== BLE permissions =====

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

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_BLE_PERMISSIONS) {
            boolean all = grantResults.length > 0;
            for (int r : grantResults) if (r != PackageManager.PERMISSION_GRANTED) { all = false; break; }
            if (all) vm.scanAndConnect();
            else vm.postMessage("BLE permissions denied");
        }
    }

    // ===== Adapter =====

    private static class ImagePagerAdapter extends FragmentStateAdapter {
        ImagePagerAdapter(@NonNull Fragment fragment) {
            super(fragment);
        }

        @Override
        public int getItemCount() {
            return 2;
        }

        @NonNull
        @Override
        public Fragment createFragment(int position) {
            switch (position) {
                case PAGE_CONTACT:
                    return new BleContactRawImageFragment();
                case PAGE_PET:
                default:
                    return new BlePetImageFragment();
            }
        }
    }
}
