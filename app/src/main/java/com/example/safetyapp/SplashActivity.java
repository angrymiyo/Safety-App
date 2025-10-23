package com.example.safetyapp;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;

public class SplashActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final int BACKGROUND_LOCATION_REQUEST_CODE = 101;
    private static final int OVERLAY_PERMISSION_REQUEST_CODE = 102;
    private static final int BATTERY_OPTIMIZATION_REQUEST_CODE = 103;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        ImageView logo = findViewById(R.id.logoImageView);
        Animation fadeIn = AnimationUtils.loadAnimation(this, R.anim.fade_in);
        logo.startAnimation(fadeIn);

        new Handler().postDelayed(this::checkAndRequestPermissions, 2000);
    }

    private void checkAndRequestPermissions() {
        ArrayList<String> permissionsToRequest = new ArrayList<>();

        // Basic permissions that need runtime request
        String[] permissions = {
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.SEND_SMS,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.CAMERA
        };

        // Add version-specific permissions
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) { // API 32 and below
            permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // API 33+
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS);
            permissionsToRequest.add(Manifest.permission.READ_MEDIA_IMAGES);
        }

        // Check basic permissions
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(permission);
            }
        }

        if (!permissionsToRequest.isEmpty()) {
            ActivityCompat.requestPermissions(this,
                permissionsToRequest.toArray(new String[0]),
                PERMISSION_REQUEST_CODE);
        } else {
            // All basic permissions granted, check special permissions
            checkSpecialPermissions();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }

            if (allGranted) {
                // All permissions granted, check special permissions
                checkSpecialPermissions();
            } else {
                // Some permissions denied, show dialog
                showPermissionDeniedDialog();
            }
        } else if (requestCode == BACKGROUND_LOCATION_REQUEST_CODE) {
            // Background location permission result handled
            checkOverlayPermission();
        }
    }

    private void checkSpecialPermissions() {
        // Request background location permission (Android 10+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                // Show explanation dialog for background location
                new AlertDialog.Builder(this)
                    .setTitle("Background Location Permission")
                    .setMessage("This app needs background location access to provide safety features even when the app is not in use. Please select 'Allow all the time' in the next screen.")
                    .setPositiveButton("Continue", (dialog, which) -> {
                        ActivityCompat.requestPermissions(SplashActivity.this,
                            new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION},
                            BACKGROUND_LOCATION_REQUEST_CODE);
                    })
                    .setCancelable(false)
                    .show();
                return;
            }
        }
        checkOverlayPermission();
    }

    private void checkOverlayPermission() {
        // Check overlay permission for emergency alerts
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                new AlertDialog.Builder(this)
                    .setTitle("Display Over Other Apps Permission")
                    .setMessage("This app needs permission to display emergency alerts over other apps.")
                    .setPositiveButton("Grant", (dialog, which) -> {
                        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName()));
                        startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST_CODE);
                    })
                    .setNegativeButton("Skip", (dialog, which) -> checkBatteryOptimization())
                    .setCancelable(false)
                    .show();
                return;
            }
        }
        checkBatteryOptimization();
    }

    private void checkBatteryOptimization() {
        // Request to ignore battery optimization
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent intent = new Intent();
            String packageName = getPackageName();
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);

            if (pm != null && !pm.isIgnoringBatteryOptimizations(packageName)) {
                new AlertDialog.Builder(this)
                    .setTitle("Battery Optimization")
                    .setMessage("To ensure the app works properly in the background, please disable battery optimization.")
                    .setPositiveButton("Disable", (dialog, which) -> {
                        intent.setAction(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                        intent.setData(Uri.parse("package:" + packageName));
                        startActivityForResult(intent, BATTERY_OPTIMIZATION_REQUEST_CODE);
                    })
                    .setNegativeButton("Skip", (dialog, which) -> proceedToMainActivity())
                    .setCancelable(false)
                    .show();
                return;
            }
        }
        proceedToMainActivity();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == OVERLAY_PERMISSION_REQUEST_CODE) {
            checkBatteryOptimization();
        } else if (requestCode == BATTERY_OPTIMIZATION_REQUEST_CODE) {
            proceedToMainActivity();
        }
    }

    private void showPermissionDeniedDialog() {
        new AlertDialog.Builder(this)
            .setTitle("Permissions Required")
            .setMessage("This app requires all permissions to function properly for your safety. Please grant all permissions.")
            .setPositiveButton("Grant Permissions", (dialog, which) -> checkAndRequestPermissions())
            .setNegativeButton("Exit", (dialog, which) -> finish())
            .setCancelable(false)
            .show();
    }

    private void proceedToMainActivity() {
        startActivity(new Intent(SplashActivity.this, MainActivity.class));
        finish();
    }
}
