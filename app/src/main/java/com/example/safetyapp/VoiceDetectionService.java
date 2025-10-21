package com.example.safetyapp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.example.safetyapp.helper.BackgroundKeywordDetector;

public class VoiceDetectionService extends Service {

    private static final String CHANNEL_ID = "VoiceDetectionChannel";
    private static final int NOTIFICATION_ID = 1001;
    private static final long COOLDOWN_PERIOD = 30000; // 30 seconds

    public static final String ACTION_STATUS_UPDATE = "com.example.safetyapp.VOICE_STATUS_UPDATE";
    public static final String EXTRA_STATUS = "status";
    public static final String EXTRA_KEYWORD = "keyword";

    private BackgroundKeywordDetector keywordDetector;
    private PowerManager.WakeLock wakeLock;
    private Handler mainHandler;
    private boolean isInCooldown = false;
    private Runnable healthCheckRunnable;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i("VoiceService", "=====================================");
        Log.i("VoiceService", "=== Voice Detection Service Created ===");
        Log.i("VoiceService", "=====================================");

        mainHandler = new Handler(Looper.getMainLooper());

        // Acquire wake lock to keep CPU and audio system running
        // Using PARTIAL_WAKE_LOCK to keep CPU running while screen is off
        // This allows microphone to work in background
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK | PowerManager.ON_AFTER_RELEASE,
                "SafetyApp::VoiceDetectionService"
        );
        wakeLock.acquire(10*60*60*1000L /*10 hours*/); // Acquire for 10 hours at a time
        Log.i("VoiceService", "Wake lock acquired for background operation");

        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i("VoiceService", "=====================================");
        Log.i("VoiceService", "=== Voice Detection Service Started ===");
        Log.i("VoiceService", "StartId: " + startId + ", Flags: " + flags);
        Log.i("VoiceService", "=====================================");

        // Verify RECORD_AUDIO permission before starting foreground service (required for Android 14+)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.e("VoiceService", "❌ RECORD_AUDIO permission not granted - cannot start foreground service with microphone type");
                Log.e("VoiceService", "Please grant microphone permission first!");
                stopSelf();
                return START_NOT_STICKY;
            }
        }

        // Start foreground service with notification
        try {
            startForeground(NOTIFICATION_ID, createNotification("Initializing voice detection..."));
            Log.i("VoiceService", "✅ Foreground service started with notification");
        } catch (SecurityException e) {
            Log.e("VoiceService", "❌ SecurityException when starting foreground service", e);
            Log.e("VoiceService", "Make sure RECORD_AUDIO permission is granted!");
            stopSelf();
            return START_NOT_STICKY;
        }

        // Check if voice detection is enabled in settings
        SharedPreferences prefs = getSharedPreferences("AppSettingsPrefs", MODE_PRIVATE);
        boolean voiceDetectionEnabled = prefs.getBoolean("voice_detection", false);
        Log.i("VoiceService", "Voice detection setting: " + (voiceDetectionEnabled ? "ENABLED" : "DISABLED"));

        if (!voiceDetectionEnabled) {
            Log.w("VoiceService", "⚠️ Voice detection disabled in settings - stopping service");
            stopSelf();
            return START_NOT_STICKY;
        }

        // Check if speech recognition is available
        boolean speechRecAvailable = android.speech.SpeechRecognizer.isRecognitionAvailable(this);
        Log.i("VoiceService", "Speech recognition available: " + (speechRecAvailable ? "YES" : "NO"));

        if (!speechRecAvailable) {
            Log.e("VoiceService", "❌ Speech recognition NOT available - stopping service");
            stopSelf();
            return START_NOT_STICKY;
        }

        // Initialize keyword detection
        Log.i("VoiceService", "📝 Initializing keyword detection...");
        startKeywordDetection();

        // Return START_STICKY to restart service if killed
        Log.i("VoiceService", "✅ Service started successfully - returning START_STICKY");
        return START_STICKY;
    }

    private void startKeywordDetection() {
        Log.i("VoiceService", "Starting advanced background keyword detection...");

        keywordDetector = new BackgroundKeywordDetector(this, new BackgroundKeywordDetector.KeywordDetectionListener() {
            @Override
            public void onKeywordDetected(String keyword, float confidence) {
                if (isInCooldown) {
                    Log.w("VoiceService", "In cooldown - ignoring keyword: \"" + keyword + "\"");
                    return;
                }

                Log.i("VoiceService", "🚨 EMERGENCY KEYWORD DETECTED: \"" + keyword + "\" (confidence: " + confidence + ") 🚨");

                // Start cooldown
                isInCooldown = true;
                updateNotification("⏳ Cooldown active (30s)...");

                mainHandler.postDelayed(() -> {
                    isInCooldown = false;
                    updateNotification("🎙️ Monitoring audio (works 24/7)...");
                    Log.i("VoiceService", "Cooldown ended - ready for next detection");
                }, COOLDOWN_PERIOD);

                // Trigger emergency
                triggerEmergency(keyword);
            }

            @Override
            public void onStatusChanged(boolean isListening) {
                if (isListening && !isInCooldown) {
                    updateNotification("🎙️ Monitoring audio (works 24/7)...");
                    broadcastStatus("listening");
                    Log.d("VoiceService", "Background audio monitoring active");
                } else if (!isListening) {
                    updateNotification("⚠️ Audio monitoring paused");
                    broadcastStatus("paused");
                }
            }
        });

        keywordDetector.startListening();
        Log.i("VoiceService", "✅ Advanced background keyword detection ACTIVE (works when screen off)");

        // Start periodic health check to ensure detection keeps running
        startHealthCheck();
    }

    private void startHealthCheck() {
        // Periodically check if detection is still running and restart if needed
        healthCheckRunnable = new Runnable() {
            @Override
            public void run() {
                if (keywordDetector != null && !isInCooldown) {
                    if (!keywordDetector.isListening()) {
                        Log.w("VoiceService", "⚠️ Detection stopped - restarting...");
                        keywordDetector.startListening();
                    } else {
                        Log.d("VoiceService", "✓ Health check: Background audio monitoring running");
                    }
                }

                // Re-acquire wake lock if needed
                if (wakeLock != null && !wakeLock.isHeld()) {
                    Log.w("VoiceService", "⚠️ Wake lock released - re-acquiring...");
                    wakeLock.acquire(10*60*60*1000L);
                }

                // Check again in 30 seconds
                mainHandler.postDelayed(this, 30000);
            }
        };
        mainHandler.postDelayed(healthCheckRunnable, 30000);
        Log.i("VoiceService", "Health check monitoring started (checking every 30 seconds)");
    }

    private void broadcastStatus(String status) {
        Intent intent = new Intent(ACTION_STATUS_UPDATE);
        intent.putExtra(EXTRA_STATUS, status);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    private void broadcastKeywordDetection(String keyword) {
        Intent intent = new Intent(ACTION_STATUS_UPDATE);
        intent.putExtra(EXTRA_STATUS, "keyword_detected");
        intent.putExtra(EXTRA_KEYWORD, keyword);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    private void triggerEmergency(String keyword) {
        Log.i("VoiceService", "========================================");
        Log.i("VoiceService", "🚨🚨🚨 EMERGENCY TRIGGERED 🚨🚨🚨");
        Log.i("VoiceService", "Keyword detected: \"" + keyword + "\"");
        Log.i("VoiceService", "========================================");

        // Wake up screen IMMEDIATELY with bright wake lock
        wakeUpScreen();

        // Broadcast keyword detection
        broadcastKeywordDetection(keyword);

        // Send high-priority full-screen notification to wake up screen
        sendFullScreenNotification(keyword);

        // Update service notification
        updateNotification("🚨 Emergency detected! Starting SOS...");

        // Small delay to ensure screen is on
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        // Start Evidence Recording with flags to show on lock screen
        try {
            Log.i("VoiceService", "Starting evidence recording activity...");
            Intent evidenceIntent = new Intent(this, EvidenceRecordingActivity.class);
            evidenceIntent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK |
                Intent.FLAG_ACTIVITY_CLEAR_TOP |
                Intent.FLAG_ACTIVITY_NO_USER_ACTION
            );
            startActivity(evidenceIntent);
            Log.i("VoiceService", "✅ Evidence recording activity started");
        } catch (Exception e) {
            Log.e("VoiceService", "❌ Failed to start evidence recording", e);
            e.printStackTrace();
        }

        // Start SOS countdown with flags to show on lock screen
        try {
            Log.i("VoiceService", "Starting SOS countdown popup...");
            Intent sosIntent = new Intent(this, PopupCountdownActivity.class);
            sosIntent.putExtra("method", "sms");
            sosIntent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK |
                Intent.FLAG_ACTIVITY_CLEAR_TOP |
                Intent.FLAG_ACTIVITY_NO_USER_ACTION
            );
            startActivity(sosIntent);
            Log.i("VoiceService", "✅ SOS countdown activity started");
        } catch (Exception e) {
            Log.e("VoiceService", "❌ Failed to start PopupCountdownActivity", e);
            e.printStackTrace();
        }

        Log.i("VoiceService", "========================================");
        Log.i("VoiceService", "Emergency sequence completed");
        Log.i("VoiceService", "========================================");
    }

    /**
     * Wake up the screen immediately using a bright wake lock
     */
    private void wakeUpScreen() {
        try {
            Log.i("VoiceService", "Attempting to wake up screen...");
            PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);

            if (powerManager != null) {
                // Check if screen is already on
                boolean isScreenOn = powerManager.isInteractive();
                Log.i("VoiceService", "Screen state: " + (isScreenOn ? "ON" : "OFF"));

                if (!isScreenOn) {
                    Log.i("VoiceService", "Screen is OFF - acquiring bright wake lock");

                    // Acquire a bright wake lock to turn screen on
                    PowerManager.WakeLock screenWakeLock = powerManager.newWakeLock(
                        PowerManager.SCREEN_BRIGHT_WAKE_LOCK |
                        PowerManager.ACQUIRE_CAUSES_WAKEUP |
                        PowerManager.ON_AFTER_RELEASE,
                        "SafetyApp::EmergencyScreenWake"
                    );
                    screenWakeLock.acquire(10000); // Keep screen on for 10 seconds

                    Log.i("VoiceService", "✅ Bright wake lock acquired - screen should turn ON");

                    // Release after 10 seconds
                    mainHandler.postDelayed(() -> {
                        if (screenWakeLock.isHeld()) {
                            screenWakeLock.release();
                            Log.i("VoiceService", "Screen wake lock released");
                        }
                    }, 10000);
                } else {
                    Log.i("VoiceService", "Screen is already ON - no wake-up needed");
                }
            }
        } catch (Exception e) {
            Log.e("VoiceService", "❌ Failed to wake up screen", e);
            e.printStackTrace();
        }
    }

    /**
     * Send a full-screen high-priority notification to wake up the device
     */
    private void sendFullScreenNotification(String keyword) {
        try {
            // Create intent for full-screen notification
            Intent fullScreenIntent = new Intent(this, PopupCountdownActivity.class);
            fullScreenIntent.putExtra("method", "sms");
            fullScreenIntent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK |
                Intent.FLAG_ACTIVITY_CLEAR_TOP |
                Intent.FLAG_ACTIVITY_NO_USER_ACTION
            );

            PendingIntent fullScreenPendingIntent = PendingIntent.getActivity(
                this,
                0,
                fullScreenIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );

            // Create high-priority notification with full-screen intent
            NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID + "_emergency")
                .setSmallIcon(R.drawable.ic_microphone)
                .setContentTitle("🚨 EMERGENCY DETECTED!")
                .setContentText("Keyword: \"" + keyword + "\" - Starting SOS")
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setFullScreenIntent(fullScreenPendingIntent, true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setVibrate(new long[]{0, 500, 250, 500})
                .setSound(android.provider.Settings.System.DEFAULT_NOTIFICATION_URI);

            NotificationManager notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            notificationManager.notify(9999, builder.build());

            Log.i("VoiceService", "Full-screen notification sent");
        } catch (Exception e) {
            Log.e("VoiceService", "Failed to send full-screen notification", e);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = getSystemService(NotificationManager.class);

            // Channel for ongoing monitoring (low priority)
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Voice Detection Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Monitors for emergency keywords 24/7");
            channel.setShowBadge(false);
            manager.createNotificationChannel(channel);

            // High-priority channel for emergency alerts with full-screen capability
            NotificationChannel emergencyChannel = new NotificationChannel(
                    CHANNEL_ID + "_emergency",
                    "Emergency Alerts",
                    NotificationManager.IMPORTANCE_HIGH
            );
            emergencyChannel.setDescription("Critical emergency notifications");
            emergencyChannel.setShowBadge(true);
            emergencyChannel.enableLights(true);
            emergencyChannel.enableVibration(true);
            emergencyChannel.setBypassDnd(true); // Bypass Do Not Disturb
            manager.createNotificationChannel(emergencyChannel);

            Log.i("VoiceService", "Notification channels created");
        }
    }

    private Notification createNotification(String contentText) {
        Intent notificationIntent = new Intent(this, AIVoiceActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                notificationIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("🎙️ AI Voice Guardian Active")
                .setContentText(contentText)
                .setSmallIcon(R.drawable.ic_microphone)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void updateNotification(String contentText) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION_ID, createNotification(contentText));
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i("VoiceService", "=== Voice Detection Service Destroyed ===");

        // Stop health check
        if (mainHandler != null && healthCheckRunnable != null) {
            mainHandler.removeCallbacks(healthCheckRunnable);
        }

        if (keywordDetector != null) {
            keywordDetector.stopListening();
            keywordDetector.destroy();
            Log.i("VoiceService", "Background keyword detector stopped and destroyed");
        }

        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            Log.i("VoiceService", "Wake lock released");
        }

        if (mainHandler != null) {
            mainHandler.removeCallbacksAndMessages(null);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
