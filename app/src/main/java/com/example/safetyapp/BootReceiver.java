package com.example.safetyapp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Log.d(TAG, "Device booted - checking if voice detection is enabled");

            // Check if voice detection is enabled in settings
            SharedPreferences prefs = context.getSharedPreferences("AppSettingsPrefs", Context.MODE_PRIVATE);
            boolean voiceDetectionEnabled = prefs.getBoolean("voice_detection", false);

            if (voiceDetectionEnabled) {
                // Check if RECORD_AUDIO permission is granted (required for Android 14+)
                if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    Log.w(TAG, "Voice detection enabled but RECORD_AUDIO permission not granted - cannot start service");
                    return;
                }

                Log.d(TAG, "Voice detection enabled - starting VoiceDetectionService");

                try {
                    Intent serviceIntent = new Intent(context, VoiceDetectionService.class);

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent);
                    } else {
                        context.startService(serviceIntent);
                    }
                    Log.d(TAG, "VoiceDetectionService started successfully on boot");
                } catch (Exception e) {
                    Log.e(TAG, "Failed to start VoiceDetectionService on boot", e);
                }
            } else {
                Log.d(TAG, "Voice detection disabled - not starting service");
            }
        }
    }
}
