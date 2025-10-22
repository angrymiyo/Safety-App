package com.example.safetyapp;

import android.Manifest;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.PowerManager;
import android.os.Vibrator;
import android.util.Log;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.safetyapp.helper.EmergencyMessageHelper;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;

public class PopupCountdownActivity extends AppCompatActivity {

    private static final String TAG = "PopupCountdown";
    private static final int REQUEST_PERMISSIONS = 200;

    private TextView tvCountdown, tvMessage;
    private Button btnCancel;
    private CountDownTimer countDownTimer;
    private int countdownSeconds = 0;
    private String method;
    private EmergencyMessageHelper messageHelper;
    private boolean permissionsGranted = false;

    private FusedLocationProviderClient locationClient;
    private String emergencyMessage = "";
    private String locationUrl = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Wake up screen and show on lock screen
        setupLockScreenDisplay();

        setContentView(R.layout.activity_popup_countdown);

        tvCountdown = findViewById(R.id.tv_countdown);
        tvMessage = findViewById(R.id.tv_message);
        btnCancel = findViewById(R.id.btn_cancel);

        method = getIntent().getStringExtra("method");
        messageHelper = new EmergencyMessageHelper(this);
        locationClient = LocationServices.getFusedLocationProviderClient(this);

        btnCancel.setOnClickListener(v -> {
            if (countDownTimer != null) countDownTimer.cancel();
            Toast.makeText(this, "Emergency message cancelled", Toast.LENGTH_SHORT).show();
            finish();
        });

        // Check and request all required permissions before starting countdown
        Log.i(TAG, "Checking permissions before starting countdown...");
        checkAndRequestPermissions();
    }

    /**
     * Check if all required permissions are granted
     */
    private boolean hasAllPermissions() {
        boolean hasLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;

        boolean hasBackgroundLocation = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            hasBackgroundLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    == PackageManager.PERMISSION_GRANTED;
        }

        boolean hasSms = ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
                == PackageManager.PERMISSION_GRANTED;

        Log.i(TAG, "Permission status - Location: " + hasLocation +
                  ", Background Location: " + hasBackgroundLocation +
                  ", SMS: " + hasSms);

        return hasLocation && hasBackgroundLocation && hasSms;
    }

    /**
     * Request all required permissions at once
     */
    private void checkAndRequestPermissions() {
        if (hasAllPermissions()) {
            Log.i(TAG, "✅ All permissions already granted - starting countdown");
            permissionsGranted = true;
            fetchCountdownTimerFromFirebase();
        } else {
            Log.w(TAG, "❌ Missing permissions - requesting now...");
            tvMessage.setText("Requesting permissions for emergency...");

            // Build list of permissions to request
            java.util.ArrayList<String> permissionsToRequest = new java.util.ArrayList<>();

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION);
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                        != PackageManager.PERMISSION_GRANTED) {
                    permissionsToRequest.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION);
                }
            }

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.SEND_SMS);
            }

            // Request all missing permissions
            if (!permissionsToRequest.isEmpty()) {
                Log.i(TAG, "Requesting " + permissionsToRequest.size() + " permissions...");
                ActivityCompat.requestPermissions(this,
                    permissionsToRequest.toArray(new String[0]),
                    REQUEST_PERMISSIONS);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_PERMISSIONS) {
            Log.i(TAG, "Permission request result received");

            // Check if all permissions were granted
            boolean allGranted = true;
            for (int i = 0; i < permissions.length; i++) {
                if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    Log.w(TAG, "❌ Permission denied: " + permissions[i]);
                    allGranted = false;
                }
            }

            if (allGranted) {
                Log.i(TAG, "✅ All permissions granted - starting countdown");
                permissionsGranted = true;
                fetchCountdownTimerFromFirebase();
            } else {
                Log.e(TAG, "❌ Not all permissions granted - cannot send emergency SMS with location");
                Toast.makeText(this, "⚠️ Permissions required for emergency SMS with location tracking", Toast.LENGTH_LONG).show();
                tvMessage.setText("Missing permissions - cannot send emergency");

                // Still allow user to cancel or wait
                // If user really wants to send without permissions, they can wait for countdown
                // But sendMessage() will handle the permission check
                fetchCountdownTimerFromFirebase();
            }
        }
    }

    /**
     * Setup activity to show on lock screen and wake up the device
     */
    private void setupLockScreenDisplay() {
        // For Android 8.1 (API 27) and above
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);

            // Dismiss keyguard for Android 10 and below
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                KeyguardManager keyguardManager = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
                if (keyguardManager != null) {
                    keyguardManager.requestDismissKeyguard(this, null);
                }
            }
        } else {
            // For older Android versions
            getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD |
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            );
        }

        // Additional flags to ensure visibility
        getWindow().addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
            WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON
        );

        // Wake up the device if screen is off
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager != null && !powerManager.isInteractive()) {
            PowerManager.WakeLock wakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK |
                PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "SafetyApp::EmergencyPopup"
            );
            wakeLock.acquire(30000); // Keep screen on for 30 seconds
            wakeLock.release();
        }
    }

    private void fetchCountdownTimerFromFirebase() {
        String uid = FirebaseAuth.getInstance().getCurrentUser().getUid();
        DatabaseReference ref = FirebaseDatabase.getInstance().getReference("Users")
                .child(uid)
                .child("countdown_timer_seconds");

        ref.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                countdownSeconds = snapshot.exists() ? snapshot.getValue(Integer.class) : 0;
                startCountdown(countdownSeconds);
            }

            @Override
            public void onCancelled(DatabaseError error) {
                Toast.makeText(PopupCountdownActivity.this, "Failed to fetch timer", Toast.LENGTH_SHORT).show();
                finish();
            }
        });
    }

    private void startCountdown(int seconds) {
        tvMessage.setText("Sending emergency message in...");

        Vibrator vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);

        countDownTimer = new CountDownTimer(seconds * 1000L, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                int sec = (int) (millisUntilFinished / 1000);
                tvCountdown.setText(sec + "s");

                // Bounce animation
                Animation anim = AnimationUtils.loadAnimation(PopupCountdownActivity.this, R.anim.scale_bounce);
                tvCountdown.startAnimation(anim);

                // 📳 Vibrate
                if (vibrator != null && vibrator.hasVibrator()) {
                    vibrator.vibrate(100);
                }
            }

            @Override
            public void onFinish() {
                tvCountdown.setText("Sending...");
                // Send emergency message like SOS button - fetch message, location, then send
                fetchEmergencyMessage(() -> fetchLocation(() -> {
                    String fullMessage = emergencyMessage + "\n\nLocation: " + locationUrl;
                    messageHelper.sendCustomMessage(method, fullMessage);
                    Toast.makeText(PopupCountdownActivity.this, "Emergency alert sent!", Toast.LENGTH_SHORT).show();
                    finish();
                }));
            }
        };

        countDownTimer.start();
    }

    private void fetchEmergencyMessage(Runnable callback) {
        DatabaseReference userRef = FirebaseDatabase.getInstance()
                .getReference("Users")
                .child(FirebaseAuth.getInstance().getCurrentUser().getUid());

        userRef.child("emergency_message_template").addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                emergencyMessage = snapshot.exists() ? snapshot.getValue(String.class) : "Help me!";
                callback.run();
            }

            @Override
            public void onCancelled(DatabaseError error) {
                emergencyMessage = "Help me!";
                callback.run();
            }
        });
    }

    private void fetchLocation(Runnable callback) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            locationUrl = "Location not available";
            callback.run();
            return;
        }

        locationClient.getLastLocation()
                .addOnSuccessListener(location -> {
                    if (location != null) {
                        locationUrl = "https://maps.google.com/?q=" + location.getLatitude() + "," + location.getLongitude();
                    } else {
                        locationUrl = "Location not available";
                    }
                    callback.run();
                })
                .addOnFailureListener(e -> {
                    locationUrl = "Location not available";
                    callback.run();
                });
    }

    @Override
    protected void onDestroy() {
        if (countDownTimer != null) countDownTimer.cancel();
        super.onDestroy();
    }
}
