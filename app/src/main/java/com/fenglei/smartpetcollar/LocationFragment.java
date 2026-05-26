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

import com.amap.api.maps.AMap;
import com.amap.api.maps.CameraUpdateFactory;
import com.amap.api.maps.CoordinateConverter;
import com.amap.api.maps.MapView;
import com.amap.api.maps.model.BitmapDescriptorFactory;
import com.amap.api.maps.model.LatLng;
import com.amap.api.maps.model.Marker;
import com.amap.api.maps.model.MarkerOptions;
import com.google.android.material.button.MaterialButton;

/**
 * LocationFragment - mirrors iOS LocationView.
 *
 * Layout: nav bar -> AMap MapView (top fill) -> bottom info panel with
 * status row, location row, last message row, button row (Connect / Screen On / Screen Off).
 *
 * The pet collar reports WGS-84 GPS coordinates over MQTT; AMap uses GCJ-02 ("火星坐标"),
 * so each incoming point is converted via AMap's CoordinateConverter before being drawn.
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
    private AMap aMap;
    private Marker petMarker;
    private boolean firstFix = true;

    private MqttManager mqttManager;
    private boolean isConnected = false;

    private static final int DEFAULT_INTERVAL_SEC = 30;
    private static final float MARKER_ZOOM = 16f;

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

        mapView = view.findViewById(R.id.mapView);
        mapView.onCreate(savedInstanceState);
        aMap = mapView.getMap();
        aMap.getUiSettings().setZoomControlsEnabled(false);
        aMap.moveCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(39.9042, 116.4074), 10f));

        mqttManager = new MqttManager();

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
        });
        btnScreenOff.setOnClickListener(v -> {
            mqttManager.publishCommand("st", DEFAULT_INTERVAL_SEC);
            appendLastMessage("[CMD] Screen OFF sent");
        });

        btnSettings.setOnClickListener(v -> showDeviceSwitcherDialog());

        updateConnectionUI(getString(R.string.connection_status_disconnected),
                R.color.status_disconnected, false);
    }

    /**
     * Pops a single-choice dialog over MqttManager.PRESET_DEVICE_MACS and, on selection,
     * delegates to switchDeviceAndReconnect — which transparently disconnects, reconnects,
     * and re-subscribes under the new device's topic if a session is active.
     */
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
                        mqttManager.switchDeviceAndReconnect(selected);
                        appendLastMessage(getString(R.string.device_switch_toast_format, selected));
                        Toast.makeText(requireContext(),
                                getString(R.string.device_switch_toast_format, selected),
                                Toast.LENGTH_SHORT).show();
                        // Reset map state so the new device's first fix re-centers the camera.
                        if (petMarker != null) {
                            petMarker.remove();
                            petMarker = null;
                        }
                        firstFix = true;
                    }
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.device_switch_cancel, null)
                .show();
    }

    // ===== MapView lifecycle wiring (required by AMap SDK) =====

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
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (mapView != null) mapView.onSaveInstanceState(outState);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (mapView != null) mapView.onDestroy();
        if (mqttManager != null) mqttManager.disconnect();
    }

    // ===== UI helpers =====

    private void updateConnectionUI(String text, int colorRes, boolean connected) {
        if (!isAdded()) return;
        isConnected = connected;
        tvConnectionStatus.setText(text);
        setDotColor(statusDot, ContextCompat.getColor(requireContext(), colorRes));
        btnConnect.setText(connected ? R.string.btn_disconnect : R.string.btn_connect);
        btnScreenOn.setEnabled(connected);
        btnScreenOff.setEnabled(connected);
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

    /**
     * Converts a WGS-84 point (raw GPS from collar) to GCJ-02 (AMap's expected coord system)
     * and drops/updates a marker, animating the camera to follow it on the first fix.
     */
    private void updateMapMarker(double wgsLat, double wgsLon) {
        if (aMap == null) return;
        CoordinateConverter converter = new CoordinateConverter(requireContext());
        converter.from(CoordinateConverter.CoordType.GPS);
        converter.coord(new LatLng(wgsLat, wgsLon));
        LatLng gcj = converter.convert();

        if (petMarker == null) {
            petMarker = aMap.addMarker(new MarkerOptions()
                    .position(gcj)
                    .title("Pet")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)));
        } else {
            petMarker.setPosition(gcj);
        }

        if (firstFix) {
            aMap.animateCamera(CameraUpdateFactory.newLatLngZoom(gcj, MARKER_ZOOM));
            firstFix = false;
        } else {
            aMap.animateCamera(CameraUpdateFactory.newLatLng(gcj));
        }
    }

    // ===== MqttManager.MqttCallback =====

    @Override
    public void onConnected() {
        updateConnectionUI(getString(R.string.connection_status_connected),
                R.color.status_connected, true);
        appendLastMessage("Connected to MQTT broker");
        // Auto-subscribe so location data can flow in immediately (mirrors iOS connect flow).
        mqttManager.subscribeDeviceTopic();
    }

    @Override
    public void onDisconnected() {
        updateConnectionUI(getString(R.string.connection_status_disconnected),
                R.color.status_disconnected, false);
        appendLastMessage("Disconnected");
    }

    @Override
    public void onConnectionFailed(String error) {
        updateConnectionUI(getString(R.string.connection_status_disconnected),
                R.color.status_disconnected, false);
        appendLastMessage("Connection failed: " + error);
        if (isAdded()) {
            Toast.makeText(requireContext(), "Connection failed: " + error, Toast.LENGTH_LONG).show();
        }
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

            boolean valid = "gnss".equals(data.getType()) ? gnss.isValidGnssFix() : true;
            String fixSymbol = valid ? getString(R.string.fix_check) : getString(R.string.fix_cross);
            tvSatFix.setText(getString(R.string.loc_sat_fix_format, gnss.getSat(), fixSymbol));
            tvSatFix.setTextColor(ContextCompat.getColor(requireContext(),
                    valid ? R.color.status_connected : R.color.status_connecting));
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
