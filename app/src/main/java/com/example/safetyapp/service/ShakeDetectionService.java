package com.example.safetyapp.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;

import com.example.safetyapp.PopupCountdownActivity;
import com.example.safetyapp.R;
import com.example.safetyapp.ShakeDetector;

public class ShakeDetectionService extends Service implements ShakeDetector.OnShakeListener {

    private static final String CHANNEL_ID = "ShakeDetectionChannel";
    private static final int NOTIFICATION_ID = 1001;

    private SensorManager sensorManager;
    private Sensor accelerometer;
    private ShakeDetector shakeDetector;
    private PowerManager.WakeLock wakeLock;

    @Override
    public void onCreate() {
        super.onCreate();

        // Create notification channel
        createNotificationChannel();

        // Start as foreground service
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Shake Detection Active")
                .setContentText("Shake your phone 3 times for emergency")
                .setSmallIcon(R.drawable.ic_sos)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        // Initialize shake detection
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sensorManager != null) {
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            if (accelerometer != null) {
                shakeDetector = new ShakeDetector(this);
                shakeDetector.setRequiredShakeCount(3);
                sensorManager.registerListener(shakeDetector, accelerometer, SensorManager.SENSOR_DELAY_UI);
            }
        }

        // Acquire wake lock to detect shakes even when screen is off
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SafetyApp::ShakeDetection");
        wakeLock.acquire();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onShake() {
        // Show popup countdown (exactly like power button trigger)
        Intent sosIntent = new Intent(this, PopupCountdownActivity.class);
        sosIntent.putExtra("method", "sms");
        sosIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(sosIntent);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        if (sensorManager != null && shakeDetector != null) {
            sensorManager.unregisterListener(shakeDetector);
        }

        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Shake Detection Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Keeps shake detection running in background");

            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }
}