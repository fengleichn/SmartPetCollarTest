package com.visionox.smartpetcollar;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity implements MqttManager.MqttCallback {

    private View statusIndicator;
    private TextView tvConnectionStatus;
    private Button btnConnect;
    private Button btnDisconnect;
    private Button btnSubscribe;
    private TextView tvSubscribedLocation;
    private TextView tvReceivedData;
    private ScrollView scrollView;
    private Button btnClearLog;

    private MqttManager mqttManager;
    private final StringBuilder logBuffer = new StringBuilder();
    private final SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        setupListeners();

        mqttManager = new MqttManager();
    }

    private void initViews() {
        statusIndicator = findViewById(R.id.statusIndicator);
        tvConnectionStatus = findViewById(R.id.tvConnectionStatus);
        btnConnect = findViewById(R.id.btnConnect);
        btnDisconnect = findViewById(R.id.btnDisconnect);
        btnSubscribe = findViewById(R.id.btnSubscribe);
        tvSubscribedLocation = findViewById(R.id.tvSubscribedLocation);
        tvReceivedData = findViewById(R.id.tvReceivedData);
        scrollView = findViewById(R.id.scrollView);
        btnClearLog = findViewById(R.id.btnClearLog);
    }

    private void setupListeners() {
        findViewById(R.id.btnLocationTest).setOnClickListener(v -> {
            startActivity(new Intent(this, LocationTestActivity.class));
        });

        findViewById(R.id.btnBleImage).setOnClickListener(v -> {
            startActivity(new Intent(this, BleImageActivity.class));
        });

        findViewById(R.id.btnBleRawImage).setOnClickListener(v -> {
            startActivity(new Intent(this, BleRawImageActivity.class));
        });

        btnConnect.setOnClickListener(v -> {
            updateConnectionStatus("Connecting...", R.color.status_connecting);
            btnConnect.setEnabled(false);
            mqttManager.connect(this);
        });

        btnDisconnect.setOnClickListener(v -> {
            mqttManager.disconnect();
        });

        btnSubscribe.setOnClickListener(v -> {
            mqttManager.subscribe();
        });

        btnClearLog.setOnClickListener(v -> {
            logBuffer.setLength(0);
            tvReceivedData.setText(R.string.hint_no_data);
        });
    }

    private void updateConnectionStatus(String status, int colorResId) {
        tvConnectionStatus.setText(status);
        statusIndicator.setBackgroundColor(ContextCompat.getColor(this, colorResId));
    }

    private void setConnectedUI(boolean connected) {
        btnConnect.setEnabled(!connected);
        btnDisconnect.setEnabled(connected);
        btnSubscribe.setEnabled(connected);
    }

    private void appendLog(String message) {
        String timestamp = sdf.format(new Date());
        String logLine = "[" + timestamp + "] " + message + "\n";
        logBuffer.append(logLine);
        tvReceivedData.setText(logBuffer.toString());
        scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
    }

    // MqttManager.MqttCallback implementations

    @Override
    public void onConnected() {
        updateConnectionStatus("Connected", R.color.status_connected);
        setConnectedUI(true);
        appendLog("Connected to MQTT broker");
    }

    @Override
    public void onDisconnected() {
        updateConnectionStatus("Disconnected", R.color.status_disconnected);
        setConnectedUI(false);
        tvSubscribedLocation.setText(R.string.hint_location_waiting);
        appendLog("Disconnected from MQTT broker");
    }

    @Override
    public void onConnectionFailed(String error) {
        updateConnectionStatus("Connection failed", R.color.status_disconnected);
        setConnectedUI(false);
        appendLog("Connection failed: " + error);
        Toast.makeText(this, "Connection failed: " + error, Toast.LENGTH_LONG).show();
    }

    @Override
    public void onMessageReceived(String topic, LocationData data, String rawJson) {
        if (data.getData() != null) {
            String locationText = "Lat: " + data.getData().getLat()
                    + "  Lon: " + data.getData().getLon()
                    + "  Sat: " + data.getData().getSat();
            tvSubscribedLocation.setText(locationText);
        }
        StringBuilder msg = new StringBuilder();
        msg.append("[RECEIVED] ").append(data.getLocationTypeDisplay());
        if (data.getData() != null) {
            msg.append("\n  ").append(data.getData().toString());
        }
        msg.append("\n  Raw: ").append(rawJson);
        appendLog(msg.toString());
    }

    @Override
    public void onPublishSuccess() {
        appendLog("[PUBLISH] Success");
    }

    @Override
    public void onPublishFailed(String error) {
        appendLog("[PUBLISH] Failed: " + error);
    }

    @Override
    public void onSubscribeSuccess() {
        appendLog("[SUBSCRIBE] Subscribed to device topic");
        Toast.makeText(this, "Subscribed successfully", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onSubscribeFailed(String error) {
        appendLog("[SUBSCRIBE] Failed: " + error);
        Toast.makeText(this, "Subscribe failed: " + error, Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mqttManager.disconnect();
    }
}
