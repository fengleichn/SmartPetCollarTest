package com.visionox.smartpetcollar;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.gson.Gson;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

public class MqttManager {

    private static final String TAG = "MqttManager";
    private static final String BROKER_URL = "tcp://47.100.220.100:1883";
    private static final String DEVICE_MAC = "E006F4";
    private static final String TOPIC_PUBLISH = "v175/" + DEVICE_MAC + "/app";
    private static final String TOPIC_SUBSCRIBE = "v175/" + DEVICE_MAC + "/dev";
    private static final String CLIENT_ID_PREFIX = "SmartPetCollar_";

    private MqttAsyncClient mqttClient;
    private MqttCallback callback;
    private final Gson gson = new Gson();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface MqttCallback {
        void onConnected();
        void onDisconnected();
        void onConnectionFailed(String error);
        void onMessageReceived(String topic, LocationData data, String rawJson);
        void onPublishSuccess();
        void onPublishFailed(String error);
        void onSubscribeSuccess();
        void onSubscribeFailed(String error);
    }

    public void connect(MqttCallback callback) {
        this.callback = callback;

        String clientId = CLIENT_ID_PREFIX + System.currentTimeMillis();
        try {
            mqttClient = new MqttAsyncClient(BROKER_URL, clientId, new MemoryPersistence());
        } catch (MqttException e) {
            Log.e(TAG, "Failed to create MQTT client", e);
            mainHandler.post(() -> {
                if (this.callback != null) {
                    this.callback.onConnectionFailed(e.getMessage());
                }
            });
            return;
        }

        mqttClient.setCallback(new MqttCallbackExtended() {
            @Override
            public void connectComplete(boolean reconnect, String serverURI) {
                Log.d(TAG, "Connected to: " + serverURI + " (reconnect=" + reconnect + ")");
                mainHandler.post(() -> {
                    if (MqttManager.this.callback != null) {
                        MqttManager.this.callback.onConnected();
                    }
                });
            }

            @Override
            public void connectionLost(Throwable cause) {
                Log.w(TAG, "Connection lost", cause);
                mainHandler.post(() -> {
                    if (MqttManager.this.callback != null) {
                        MqttManager.this.callback.onDisconnected();
                    }
                });
            }

            @Override
            public void messageArrived(String topic, MqttMessage message) {
                String payload = new String(message.getPayload());
                Log.d(TAG, "=== Message Arrived ===");
                Log.d(TAG, "  Topic: " + topic);
                Log.d(TAG, "  QoS: " + message.getQos());
                Log.d(TAG, "  Retained: " + message.isRetained());
                Log.d(TAG, "  Payload: " + payload);
                try {
                    LocationData locationData = gson.fromJson(payload, LocationData.class);
                    if (locationData.getData() != null) {
                        Log.d(TAG, "  Parsed -> type: " + locationData.getType()
                                + ", lat: " + locationData.getData().getLat()
                                + ", lon: " + locationData.getData().getLon()
                                + ", sat: " + locationData.getData().getSat()
                                + ", valid: " + locationData.getData().isValidGnssFix());
                    }
                    mainHandler.post(() -> {
                        if (MqttManager.this.callback != null) {
                            MqttManager.this.callback.onMessageReceived(topic, locationData, payload);
                        }
                    });
                } catch (Exception e) {
                    Log.e(TAG, "Failed to parse message: " + payload, e);
                }
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {
                Log.d(TAG, "Delivery complete");
            }
        });

        MqttConnectOptions options = new MqttConnectOptions();
        options.setCleanSession(true);
        options.setConnectionTimeout(10);
        options.setKeepAliveInterval(60);
        options.setAutomaticReconnect(true);

        try {
            mqttClient.connect(options);
        } catch (MqttException e) {
            Log.e(TAG, "Connect exception", e);
            mainHandler.post(() -> {
                if (this.callback != null) {
                    this.callback.onConnectionFailed(e.getMessage());
                }
            });
        }
    }

    public void subscribe() {
        if (mqttClient == null || !mqttClient.isConnected()) {
            mainHandler.post(() -> {
                if (callback != null) {
                    callback.onSubscribeFailed("Not connected to MQTT broker");
                }
            });
            return;
        }
        Log.d(TAG, "Subscribing to topic: " + TOPIC_PUBLISH + " with QoS=1 (read retained location)");
        try {
            mqttClient.subscribe(TOPIC_PUBLISH, 1);
            Log.d(TAG, "Subscribe request sent successfully, topic: " + TOPIC_PUBLISH);
            mainHandler.post(() -> {
                if (callback != null) {
                    callback.onSubscribeSuccess();
                }
            });
        } catch (MqttException e) {
            Log.e(TAG, "Subscribe failed for topic: " + TOPIC_PUBLISH + ", error: " + e.getMessage(), e);
            mainHandler.post(() -> {
                if (callback != null) {
                    callback.onSubscribeFailed(e.getMessage());
                }
            });
        }
    }

    public void subscribeDeviceTopic() {
        if (mqttClient == null || !mqttClient.isConnected()) {
            mainHandler.post(() -> {
                if (callback != null) {
                    callback.onSubscribeFailed("Not connected to MQTT broker");
                }
            });
            return;
        }
        Log.d(TAG, "Subscribing to device topic: " + TOPIC_SUBSCRIBE + " with QoS=1");
        try {
            mqttClient.subscribe(TOPIC_SUBSCRIBE, 1);
            Log.d(TAG, "Subscribe request sent successfully, topic: " + TOPIC_SUBSCRIBE);
            mainHandler.post(() -> {
                if (callback != null) {
                    callback.onSubscribeSuccess();
                }
            });
        } catch (MqttException e) {
            Log.e(TAG, "Subscribe failed for topic: " + TOPIC_SUBSCRIBE + ", error: " + e.getMessage(), e);
            mainHandler.post(() -> {
                if (callback != null) {
                    callback.onSubscribeFailed(e.getMessage());
                }
            });
        }
    }

    public void publishCommand(String act, int dur) {
        if (mqttClient == null || !mqttClient.isConnected()) {
            mainHandler.post(() -> {
                if (callback != null) {
                    callback.onPublishFailed("Not connected to MQTT broker");
                }
            });
            return;
        }

        String id = String.format("%03d", (int) (Math.random() * 999) + 1);
        String json = "{\"id\":\"" + id + "\",\"act\":\"" + act + "\",\"dur\":" + dur + "}";
        Log.d(TAG, "Publishing command to " + TOPIC_PUBLISH + ": " + json);

        try {
            MqttMessage message = new MqttMessage(json.getBytes());
            message.setQos(1);
            message.setRetained(true);
            mqttClient.publish(TOPIC_PUBLISH, message);
            Log.d(TAG, "Command publish initiated");
            mainHandler.post(() -> {
                if (callback != null) {
                    callback.onPublishSuccess();
                }
            });
        } catch (MqttException e) {
            Log.e(TAG, "Command publish exception", e);
            mainHandler.post(() -> {
                if (callback != null) {
                    callback.onPublishFailed(e.getMessage());
                }
            });
        }
    }

    public void disconnect() {
        if (mqttClient != null && mqttClient.isConnected()) {
            try {
                mqttClient.disconnect();
                Log.d(TAG, "Disconnected");
                mainHandler.post(() -> {
                    if (callback != null) {
                        callback.onDisconnected();
                    }
                });
            } catch (MqttException e) {
                Log.e(TAG, "Disconnect exception", e);
            }
        }
    }

    public boolean isConnected() {
        return mqttClient != null && mqttClient.isConnected();
    }
}
