package com.fenglei.smartpetcollar;

import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;

import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;

/**
 * LocationFragment - mirrors iOS LocationView.
 *
 * Layout: nav bar -> OSMDroid MapView (top fill) -> bottom info panel with
 * status row, location row, last message row, button row (Connect / Screen On / Screen Off).
 *
 * The pet collar reports WGS-84 GPS coordinates over MQTT; OSMDroid also uses WGS-84,
 * so no coordinate conversion is needed.
 */
public class LocationFragment extends Fragment implements MqttManager.MqttCallback {

    private View statusDot;
    private TextView tvConnectionStatus;
    private TextView tvLatitude;
    private TextView tvLongitude;
    private TextView tvSatFix;
    private TextView tvLastMessage;
    private MaterialButton btnConnect;
    private MaterialButton btnScreenOn;
    private MaterialButton btnScreenOff;
    private ImageButton btnSettings;

    private MapView mapView;
    private Marker petMarker;
    private boolean firstFix = true;

    private MqttManager mqttManager;
    private DevicePreferences devicePreferences;
    private boolean isConnected = false;

    private enum ScreenState { NONE, ON, OFF }
    private ScreenState screenState = ScreenState.NONE;

    private static final int DEFAULT_INTERVAL_SEC = 10;
    private static final double MARKER_ZOOM = 16.0;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_location, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        statusDot = view.findViewById(R.id.statusDot);
        tvConnectionStatus = view.findViewById(R.id.tvConnectionStatus);
        tvLatitude = view.findViewById(R.id.tvLatitude);
        tvLongitude = view.findViewById(R.id.tvLongitude);
        tvSatFix = view.findViewById(R.id.tvSatFix);
        tvLastMessage = view.findViewById(R.id.tvLastMessage);
        btnConnect = view.findViewById(R.id.btnConnect);
        btnScreenOn = view.findViewById(R.id.btnScreenOn);
        btnScreenOff = view.findViewById(R.id.btnScreenOff);
        btnSettings = view.findViewById(R.id.btnSettings);

        Configuration.getInstance().setUserAgentValue(requireContext().getPackageName());

        mapView = view.findViewById(R.id.mapView);
        mapView.setTileSource(TileSourceFactory.MAPNIK);
        mapView.setMultiTouchControls(true);
        mapView.getController().setZoom(10.0);
        mapView.getController().setCenter(new GeoPoint(39.9042, 116.4074));

        mqttManager = new MqttManager();
        devicePreferences = DevicePreferences.getInstance(requireContext());
        devicePreferences.getSelectedMac().observe(getViewLifecycleOwner(), mac ->
                mqttManager.setDeviceMac(mac));

        btnConnect.setOnClickListener(v -> {
            if (isConnected) {
                mqttManager.disconnect();
            } else {
                updateConnectionUI(getString(R.string.connection_status_connecting),
                        R.color.status_connecting, false);
                mqttManager.connect(this);
            }
        });

        btnScreenOn.setOnClickListener(v -> {
            mqttManager.publishCommand("so", DEFAULT_INTERVAL_SEC);
            appendLastMessage("[CMD] Screen ON sent (interval " + DEFAULT_INTERVAL_SEC + "s)");
            setScreenState(ScreenState.ON);
        });
        btnScreenOff.setOnClickListener(v -> {
            mqttManager.publishCommand("st", DEFAULT_INTERVAL_SEC);
            appendLastMessage("[CMD] Screen OFF sent");
            setScreenState(ScreenState.OFF);
        });

        btnSettings.setOnClickListener(v -> showDeviceSwitcherDialog());

        updateConnectionUI(getString(R.string.connection_status_disconnected),
                R.color.status_idle, false);
    }

    private void showDeviceSwitcherDialog() {
        if (!isAdded()) return;
        String[] macs = MqttManager.PRESET_DEVICE_MACS;
        String currentMac = mqttManager.getDeviceMac();
        int checkedIndex = 0;
        for (int i = 0; i < macs.length; i++) {
            if (macs[i].equals(currentMac)) {
                checkedIndex = i;
                break;
            }
        }

        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.device_switch_title)
                .setSingleChoiceItems(macs, checkedIndex, (dialog, which) -> {
                    String selected = macs[which];
                    if (!selected.equals(mqttManager.getDeviceMac())) {
                        devicePreferences.saveSelectedMac(selected);
                        mqttManager.switchDeviceAndReconnect(selected);
                        appendLastMessage(getString(R.string.device_switch_toast_format, selected));
                        Toast.makeText(requireContext(),
                                getString(R.string.device_switch_toast_format, selected),
                                Toast.LENGTH_SHORT).show();
                        if (petMarker != null) {
                            mapView.getOverlays().remove(petMarker);
                            petMarker = null;
                        }
                        firstFix = true;
                    }
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.device_switch_cancel, null)
                .show();
    }

    // ===== MapView lifecycle =====

    @Override
    public void onResume() {
        super.onResume();
        if (mapView != null) mapView.onResume();
    }

    @Override
    public void onPause() {
        super.onPause();
        if (mapView != null) mapView.onPause();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (mapView != null) mapView.onDetach();
        if (mqttManager != null) mqttManager.disconnect();
    }

    // ===== UI helpers =====

    private void setScreenState(ScreenState state) {
        if (!isAdded()) return;
        screenState = state;
        applyScreenButtonStyles();
    }

    private void applyScreenButtonStyles() {
        setButtonStyle(btnScreenOn, screenState == ScreenState.ON);
        setButtonStyle(btnScreenOff, screenState == ScreenState.OFF);
    }

    private void setButtonStyle(com.google.android.material.button.MaterialButton btn, boolean active) {
        if (active) {
            btn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                    getResources().getColor(R.color.ios_blue, null)));
            btn.setTextColor(getResources().getColor(android.R.color.white, null));
        } else {
            btn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0x1A007AFF));
            btn.setTextColor(getResources().getColor(R.color.ios_blue, null));
        }
    }

    private void updateConnectionUI(String text, int colorRes, boolean connected) {
        if (!isAdded()) return;
        isConnected = connected;
        tvConnectionStatus.setText(text);
        setDotColor(statusDot, ContextCompat.getColor(requireContext(), colorRes));
        btnConnect.setText(connected ? R.string.btn_disconnect : R.string.btn_connect);
        btnConnect.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                connected ? 0x1A007AFF : getResources().getColor(R.color.ios_blue, null)));
        btnConnect.setTextColor(connected
                ? getResources().getColor(R.color.ios_blue, null)
                : getResources().getColor(android.R.color.white, null));
        btnScreenOn.setEnabled(connected);
        btnScreenOff.setEnabled(connected);
        if (!connected) {
            screenState = ScreenState.NONE;
            applyScreenButtonStyles();
        }
    }

    private void setDotColor(View dot, int color) {
        if (dot.getBackground() instanceof GradientDrawable) {
            ((GradientDrawable) dot.getBackground().mutate()).setColor(color);
        } else {
            dot.setBackgroundColor(color);
        }
    }

    private void appendLastMessage(String text) {
        if (!isAdded()) return;
        tvLastMessage.setText(text);
    }

    // Collar reports WGS-84 coordinates; OSMDroid uses WGS-84 natively — no conversion needed.
    private void updateMapMarker(double lat, double lon) {
        if (mapView == null) return;
        GeoPoint point = new GeoPoint(lat, lon);

        if (petMarker == null) {
            petMarker = new Marker(mapView);
            petMarker.setTitle("Pet");
            petMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
            mapView.getOverlays().add(petMarker);
        }
        petMarker.setPosition(point);

        if (firstFix) {
            mapView.getController().animateTo(point, MARKER_ZOOM, null);
            firstFix = false;
        } else {
            mapView.getController().animateTo(point);
        }
        mapView.invalidate();
    }

    // ===== MqttManager.MqttCallback =====

    @Override
    public void onConnected() {
        if (!isAdded()) return;
        updateConnectionUI(getString(R.string.connection_status_connected),
                R.color.status_connected, true);
        appendLastMessage("Connected to MQTT broker");
        mqttManager.subscribeDeviceTopic();
    }

    @Override
    public void onDisconnected() {
        if (!isAdded()) return;
        updateConnectionUI(getString(R.string.connection_status_disconnected),
                R.color.status_idle, false);
        appendLastMessage("Disconnected");
    }

    @Override
    public void onConnectionFailed(String error) {
        if (!isAdded()) return;
        updateConnectionUI(getString(R.string.connection_status_disconnected),
                R.color.status_disconnected, false);
        appendLastMessage("Connection failed: " + error);
        Toast.makeText(requireContext(), "Connection failed: " + error, Toast.LENGTH_LONG).show();
    }

    @Override
    public void onMessageReceived(String topic, LocationData data, String rawJson) {
        if (!isAdded()) return;
        if (data.getData() != null) {
            LocationData.GnssData gnss = data.getData();
            double lat = gnss.getLat();
            double lon = gnss.getLon();

            tvLatitude.setText(getString(R.string.loc_lat_format, lat));
            tvLongitude.setText(getString(R.string.loc_lon_format, lon));
            tvLongitude.setVisibility(View.VISIBLE);

            if ("gnss".equals(data.getType())) {
                boolean valid = gnss.isValidGnssFix();
                String fixSymbol = valid ? getString(R.string.fix_check) : getString(R.string.fix_cross);
                tvSatFix.setText(getString(R.string.loc_gnss_format, gnss.getSat(), fixSymbol));
                tvSatFix.setTextColor(ContextCompat.getColor(requireContext(),
                        valid ? R.color.status_connected : R.color.status_connecting));
            } else {
                tvSatFix.setText(getString(R.string.loc_type_cell));
                tvSatFix.setTextColor(ContextCompat.getColor(requireContext(),
                        R.color.ios_secondary_label));
            }
            tvSatFix.setVisibility(View.VISIBLE);

            if (lat != 0 || lon != 0) {
                updateMapMarker(lat, lon);
            }
        }
        appendLastMessage(rawJson);
    }

    @Override public void onPublishSuccess() {}
    @Override public void onPublishFailed(String error) { appendLastMessage("Publish failed: " + error); }
    @Override public void onSubscribeSuccess() { appendLastMessage("Subscribed to device topic"); }
    @Override public void onSubscribeFailed(String error) { appendLastMessage("Subscribe failed: " + error); }
}
