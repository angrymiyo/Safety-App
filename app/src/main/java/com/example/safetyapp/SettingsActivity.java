package com.example.safetyapp;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.switchmaterial.SwitchMaterial;

public class SettingsActivity extends BaseActivity {

    private SwitchMaterial switchSound, switchVibration, switchContacts, switchVoiceDetection;
    private Spinner spinnerPressCount, spinnerShakeCount;
    private SharedPreferences prefs;

    private static final int REQ_CONTACTS_PERMISSION = 1001;
    private static final int REQ_RECORD_AUDIO_PERMISSION = 1002;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Setup layout with toolbar, back button, and no bottom nav
        setupLayout(R.layout.activity_settings, "Settings", true, R.id.nav_home, false);

        prefs = getSharedPreferences("AppSettingsPrefs", MODE_PRIVATE);

        switchSound = findViewById(R.id.switch_sound);
        switchVibration = findViewById(R.id.switch_vibration);
        switchContacts = findViewById(R.id.switch_contacts);
        switchVoiceDetection = findViewById(R.id.switch_voice_detection);
        spinnerPressCount = findViewById(R.id.spinner_press_count);
        spinnerShakeCount = findViewById(R.id.spinner_shake_count);
        // Load switch preferences

        switchSound.setChecked(prefs.getBoolean("sound", true));
        switchVibration.setChecked(prefs.getBoolean("vibration", true));
        switchContacts.setChecked(prefs.getBoolean("contacts", false));
        switchVoiceDetection.setChecked(prefs.getBoolean("voice_detection", false));

        switchSound.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean("sound", isChecked).apply();
            Toast.makeText(this, "Sound " + (isChecked ? "enabled" : "disabled"), Toast.LENGTH_SHORT).show();
        });

        switchVibration.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean("vibration", isChecked).apply();
            Toast.makeText(this, "Vibration " + (isChecked ? "enabled" : "disabled"), Toast.LENGTH_SHORT).show();
        });

        switchContacts.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked && ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.READ_CONTACTS},
                        REQ_CONTACTS_PERMISSION);
            }
            prefs.edit().putBoolean("contacts", isChecked).apply();
        });

        switchVoiceDetection.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean("voice_detection", isChecked).apply();

            if (isChecked) {
                // Check RECORD_AUDIO permission first (required for Android 14+)
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                        != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(this,
                            new String[]{Manifest.permission.RECORD_AUDIO},
                            REQ_RECORD_AUDIO_PERMISSION);
                    return; // Will continue after permission granted
                }

                // Check and request all other required permissions for 24/7 operation
                if (!checkAllRequiredPermissions()) {
                    requestAllRequiredPermissions();
                    return; // Will enable after permissions granted
                }

                // All permissions granted - start the voice detection service
                startVoiceDetectionService();
            } else {
                // Stop the service if the switch is turned off
                stopVoiceDetectionService();
            }
        });


        // Power button press count spinner (existing)
        int savedCount = prefs.getInt("power_press_count", 3);
        spinnerPressCount.setSelection(savedCount - 2);
        spinnerPressCount.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                int selectedCount = position + 2;
                prefs.edit().putInt("power_press_count", selectedCount).apply();
                Toast.makeText(SettingsActivity.this, "Trigger set to " + selectedCount + " presses", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        // Shake count spinner (new)
        int savedShakeCount = prefs.getInt("shake_count_threshold", 3);
        spinnerShakeCount.setSelection(savedShakeCount - 1);
        spinnerShakeCount.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                int selectedShakeCount = position + 1;
                prefs.edit().putInt("shake_count_threshold", selectedShakeCount).apply();
                Toast.makeText(SettingsActivity.this, "Shake count set to " + selectedShakeCount, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQ_CONTACTS_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Permission denied. You can enable it from settings.", Toast.LENGTH_LONG).show();
                switchContacts.setChecked(false);
                prefs.edit().putBoolean("contacts", false).apply();
            }
        } else if (requestCode == REQ_RECORD_AUDIO_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // RECORD_AUDIO permission granted
                Toast.makeText(this, "✅ Microphone permission granted", Toast.LENGTH_SHORT).show();

                // Now check other required permissions
                if (!checkAllRequiredPermissions()) {
                    requestAllRequiredPermissions();
                } else {
                    // All permissions granted - start service
                    startVoiceDetectionService();
                }
            } else {
                // Permission denied
                Toast.makeText(this, "❌ Microphone permission denied - Voice detection requires this!", Toast.LENGTH_LONG).show();
                switchVoiceDetection.setChecked(false);
                prefs.edit().putBoolean("voice_detection", false).apply();
            }
        }
    }

    /**
     * Start the voice detection foreground service
     */
    private void startVoiceDetectionService() {
        try {
            Intent serviceIntent = new Intent(this, VoiceDetectionService.class);
            ContextCompat.startForegroundService(this, serviceIntent);
            Toast.makeText(this, "✅ Voice detection enabled - Works 24/7 (even when locked)", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "❌ Failed to start voice detection: " + e.getMessage(), Toast.LENGTH_LONG).show();
            e.printStackTrace();
            switchVoiceDetection.setChecked(false);
            prefs.edit().putBoolean("voice_detection", false).apply();
        }
    }

    /**
     * Stop the voice detection service
     */
    private void stopVoiceDetectionService() {
        Intent serviceIntent = new Intent(this, VoiceDetectionService.class);
        stopService(serviceIntent);
        Toast.makeText(this, "Voice detection disabled", Toast.LENGTH_SHORT).show();
    }

    /**
     * Check if all required permissions for 24/7 operation are granted
     */
    private boolean checkAllRequiredPermissions() {
        boolean hasBatteryExemption = true;
        boolean hasOverlayPermission = true;

        // Check battery optimization exemption
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
            hasBatteryExemption = powerManager != null && powerManager.isIgnoringBatteryOptimizations(getPackageName());
        }

        // Check overlay permission (for lock screen display)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            hasOverlayPermission = Settings.canDrawOverlays(this);
        }

        return hasBatteryExemption && hasOverlayPermission;
    }

    /**
     * Request all required permissions with explanation dialog
     */
    private void requestAllRequiredPermissions() {
        new AlertDialog.Builder(this)
            .setTitle("Required Permissions")
            .setMessage("For 24/7 voice detection to work when phone is locked:\n\n" +
                    "1. Battery Optimization: Allow app to run in background\n" +
                    "2. Display over other apps: Show emergency popup on lock screen\n\n" +
                    "These are critical for your safety!")
            .setPositiveButton("Grant Permissions", (dialog, which) -> {
                requestBatteryOptimizationExemption();
                requestOverlayPermission();
            })
            .setNegativeButton("Cancel", (dialog, which) -> {
                switchVoiceDetection.setChecked(false);
                prefs.edit().putBoolean("voice_detection", false).apply();
            })
            .setCancelable(false)
            .show();
    }

    private void requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
            String packageName = getPackageName();

            if (powerManager != null && !powerManager.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    intent.setData(Uri.parse("package:" + packageName));
                    startActivity(intent);

                    Toast.makeText(this, "Step 1: Please allow battery optimization exemption", Toast.LENGTH_LONG).show();
                } catch (Exception e) {
                    Toast.makeText(this, "Please disable battery optimization manually in settings", Toast.LENGTH_LONG).show();
                }
            }
        }
    }

    private void requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);

                    Toast.makeText(this, "Step 2: Please allow 'Display over other apps'", Toast.LENGTH_LONG).show();
                } catch (Exception e) {
                    Toast.makeText(this, "Please enable 'Display over other apps' manually in settings", Toast.LENGTH_LONG).show();
                }
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Check if permissions were granted and start service if needed
        SharedPreferences prefs = getSharedPreferences("AppSettingsPrefs", MODE_PRIVATE);
        boolean voiceDetectionEnabled = prefs.getBoolean("voice_detection", false);

        if (voiceDetectionEnabled) {
            // Check if RECORD_AUDIO permission is granted
            boolean hasRecordAudio = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED;

            if (hasRecordAudio && checkAllRequiredPermissions()) {
                // All permissions granted, start service
                startVoiceDetectionService();
            } else if (!hasRecordAudio) {
                // Missing microphone permission
                Toast.makeText(this, "⚠️ Please grant microphone permission for voice detection", Toast.LENGTH_SHORT).show();
            }
        }
    }
}
