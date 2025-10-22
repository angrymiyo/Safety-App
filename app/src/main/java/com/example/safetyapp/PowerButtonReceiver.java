package com.example.safetyapp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

public class PowerButtonReceiver extends BroadcastReceiver {

    private static int pressCount = 0;
    private static long lastPressTime = 0;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_SCREEN_ON.equals(intent.getAction()) || Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {

            long currentTime = System.currentTimeMillis();

            if (currentTime - lastPressTime < 1000) {
                pressCount++;
            } else {
                pressCount = 1;
            }

            lastPressTime = currentTime;

            SharedPreferences prefs = context.getSharedPreferences("AppSettingsPrefs", Context.MODE_PRIVATE);
            int requiredCount = prefs.getInt("power_press_count", 3);

            if (pressCount == requiredCount) {
                pressCount = 0;

                // Start 60-second video recording in background
                Intent videoIntent = new Intent(context, VideoRecordingService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(videoIntent);
                } else {
                    context.startService(videoIntent);
                }

                // Show SOS popup
                Intent sosIntent = new Intent(context, PopupCountdownActivity.class);
                sosIntent.putExtra("method", "sms");
                sosIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(sosIntent);
            }
        }
    }
}
