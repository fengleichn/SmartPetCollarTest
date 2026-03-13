package com.visionox.smartpetcollar;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Location Test Activity - dedicated to receiving and displaying
 * real-time location data from the pet collar device via MQTT.
 */
public class LocationTestActivity extends AppCompatActivity implements MqttManager.MqttCallback {

    private View statusIndicator;
    private TextView tvConnectionStatus;
    private Button btnReconnect;
    private EditText etInterval;
    private Button btnScreenOn;
    private Button btnScreenOff;
    private TextView tvLocationType;
    private TextView tvLatitude;
    private TextView tvLongitude;
    private TextView tvSatellites;
    private TextView tvFixStatus;
    private TextView tvLastUpdate;
    private TextView tvRecordCount;
    private Button btnClearLog;
    private ScrollView scrollView;
    private TextView tvLogData;

    private MqttManager mqttManager;
    private final StringBuilder logBuffer = new StringBuilder();
    private final SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
    private int recordCount = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_location_test);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.location_test_title);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        initViews();
        setupListeners();

        mqttManager = new MqttManager();
        autoConnect();
    }

    private void initViews() {
        statusIndicator = findViewById(R.id.statusIndicator);
        tvConnectionStatus = findViewById(R.id.tvConnectionStatus);
        btnReconnect = findViewById(R.id.btnReconnect);
        etInterval = findViewById(R.id.etInterval);
        btnScreenOn = findViewById(R.id.btnScreenOn);
        btnScreenOff = findViewById(R.id.btnScreenOff);
        tvLocationType = findViewById(R.id.tvLocationType);
        tvLatitude = findViewById(R.id.tvLatitude);
        tvLongitude = findViewById(R.id.tvLongitude);
        tvSatellites = findViewById(R.id.tvSatellites);
        tvFixStatus = findViewById(R.id.tvFixStatus);
        tvLastUpdate = findViewById(R.id.tvLastUpdate);
        tvRecordCount = findViewById(R.id.tvRecordCount);
        btnClearLog = findViewById(R.id.btnClearLog);
        scrollView = findViewById(R.id.scrollView);
        tvLogData = findViewById(R.id.tvLogData);
    }

    private void setupListeners() {
        btnReconnect.setOnClickListener(v -> {
            btnReconnect.setVisibility(View.GONE);
            autoConnect();
        });

        btnScreenOn.setOnClickListener(v -> sendCommand("so"));
        btnScreenOff.setOnClickListener(v -> sendCommand("st"));

        btnClearLog.setOnClickListener(v -> {
            logBuffer.setLength(0);
            recordCount = 0;
            tvLogData.setText(R.string.hint_no_data);
            updateRecordCount();
        });
    }

    /**
     * Auto-connect to MQTT broker and subscribe to device topic.
     */
    private void autoConnect() {
        updateConnectionStatus(getString(R.string.connection_status_connecting), R.color.status_connecting);
        appendLog("[SYSTEM] Connecting to MQTT broker...");
        mqttManager.connect(this);
    }

    /**
     * Send a control command to the device.
     * @param act "so" for screen on, "st" for screen off
     */
    private void sendCommand(String act) {
        String intervalStr = etInterval.getText().toString().trim();
        int dur = 30;
        if (!intervalStr.isEmpty()) {
            try {
                dur = Integer.parseInt(intervalStr);
            } catch (NumberFormatException e) {
                Toast.makeText(this, R.string.toast_invalid_interval, Toast.LENGTH_SHORT).show();
                return;
            }
        }
        mqttManager.publishCommand(act, dur);
        String actName = "so".equals(act) ? "Screen ON" : "Screen OFF";
        appendLog("[COMMAND] " + actName + ", Interval: " + dur + "s");
    }

    private void updateConnectionStatus(String status, int colorResId) {
        tvConnectionStatus.setText(status);
        statusIndicator.setBackgroundColor(ContextCompat.getColor(this, colorResId));
    }

    private void setControlEnabled(boolean enabled) {
        btnScreenOn.setEnabled(enabled);
        btnScreenOff.setEnabled(enabled);
    }

    private void updateRecordCount() {
        tvRecordCount.setText(recordCount > 0 ? recordCount + " records" : "");
    }

    private void appendLog(String message) {
        String timestamp = sdf.format(new Date());
        String logLine = "[" + timestamp + "] " + message + "\n";
        logBuffer.append(logLine);
        tvLogData.setText(logBuffer.toString());
        scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
    }

    // ===== MqttManager.MqttCallback =====

    @Override
    public void onConnected() {
        updateConnectionStatus(getString(R.string.connection_status_connected), R.color.status_connected);
        setControlEnabled(true);
        btnReconnect.setVisibility(View.GONE);
        appendLog("[SYSTEM] Connected");

        // Step 1: Subscribe to device topic first (to be ready to receive)
        appendLog("[SYSTEM] Subscribing to device topic...");
        mqttManager.subscribeDeviceTopic();

        // Step 2: Publish command to wake device and start reporting
        int dur = 30;
        String intervalStr = etInterval.getText().toString().trim();
        if (!intervalStr.isEmpty()) {
            try {
                dur = Integer.parseInt(intervalStr);
            } catch (NumberFormatException ignored) {
            }
        }
        appendLog("[SYSTEM] Sending Screen ON command to device (interval: " + dur + "s)...");
        mqttManager.publishCommand("so", dur);
    }

    @Override
    public void onDisconnected() {
        updateConnectionStatus(getString(R.string.connection_status_disconnected), R.color.status_disconnected);
        setControlEnabled(false);
        btnReconnect.setVisibility(View.VISIBLE);
        appendLog("[SYSTEM] Disconnected");
    }

    @Override
    public void onConnectionFailed(String error) {
        updateConnectionStatus("Connection failed", R.color.status_disconnected);
        setControlEnabled(false);
        btnReconnect.setVisibility(View.VISIBLE);
        appendLog("[ERROR] Connection failed: " + error);
    }

    @Override
    public void onMessageReceived(String topic, LocationData data, String rawJson) {
        recordCount++;
        updateRecordCount();

        // Update real-time location display
        if (data.getData() != null) {
            LocationData.GnssData gnss = data.getData();
            tvLatitude.setText(String.valueOf(gnss.getLat()));
            tvLongitude.setText(String.valueOf(gnss.getLon()));
            tvSatellites.setText(String.valueOf(gnss.getSat()));

            // Location type
            tvLocationType.setText(data.getLocationTypeDisplay());

            // Fix validity status
            if ("gnss".equals(data.getType())) {
                if (gnss.isValidGnssFix()) {
                    tvFixStatus.setText(R.string.fix_valid);
                    tvFixStatus.setTextColor(ContextCompat.getColor(this, R.color.status_connected));
                } else {
                    tvFixStatus.setText(R.string.fix_invalid);
                    tvFixStatus.setTextColor(ContextCompat.getColor(this, R.color.status_disconnected));
                }
            } else {
                tvFixStatus.setText(R.string.fix_cell_approx);
                tvFixStatus.setTextColor(ContextCompat.getColor(this, R.color.status_connecting));
            }

            // Last update time
            String updateTime = getString(R.string.label_last_update, sdf.format(new Date()));
            tvLastUpdate.setText(updateTime);
        }

        // Append to log
        StringBuilder msg = new StringBuilder();
        msg.append("[#").append(recordCount).append("] ").append(data.getLocationTypeDisplay());
        if (data.getData() != null) {
            msg.append(" | Lat: ").append(data.getData().getLat());
            msg.append(", Lon: ").append(data.getData().getLon());
            msg.append(", Sat: ").append(data.getData().getSat());
        }
        appendLog(msg.toString());
    }

    @Override
    public void onPublishSuccess() {
        appendLog("[COMMAND] Sent successfully");
    }

    @Override
    public void onPublishFailed(String error) {
        appendLog("[COMMAND] Send failed: " + error);
    }

    @Override
    public void onSubscribeSuccess() {
        appendLog("[SYSTEM] Subscribed, waiting for location data...");
    }

    @Override
    public void onSubscribeFailed(String error) {
        appendLog("[ERROR] Subscribe failed: " + error);
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mqttManager.disconnect();
    }
}
