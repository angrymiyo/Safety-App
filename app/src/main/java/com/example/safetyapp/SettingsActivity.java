package com.example.safetyapp;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
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
            if (isChecked && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.RECORD_AUDIO},
                        REQ_RECORD_AUDIO_PERMISSION);
                return;
            }

            prefs.edit().putBoolean("voice_detection", isChecked).apply();

            Intent serviceIntent = new Intent(this, com.example.safetyapp.service.VoiceDetectionService.class);


            if (isChecked) {
                // Start the voice monitor service in foreground
                ContextCompat.startForegroundService(this, serviceIntent);
                Toast.makeText(this, "Voice detection enabled", Toast.LENGTH_SHORT).show();
            } else {
                // Stop the service if the switch is turned off
                stopService(serviceIntent);
                Toast.makeText(this, "Voice detection disabled", Toast.LENGTH_SHORT).show();
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

        if ((requestCode == REQ_CONTACTS_PERMISSION || requestCode == REQ_RECORD_AUDIO_PERMISSION)
                && grantResults.length > 0 && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Permission denied. You can enable it from settings.", Toast.LENGTH_LONG).show();

            if (requestCode == REQ_CONTACTS_PERMISSION) {
                switchContacts.setChecked(false);
                prefs.edit().putBoolean("contacts", false).apply();
            } else if (requestCode == REQ_RECORD_AUDIO_PERMISSION) {
                switchVoiceDetection.setChecked(false);
                prefs.edit().putBoolean("voice_detection", false).apply();
            }
        }
    }
}
