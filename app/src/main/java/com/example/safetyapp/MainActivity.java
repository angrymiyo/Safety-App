package com.example.safetyapp;

import android.Manifest;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import com.google.android.material.navigation.NavigationView;

import com.example.safetyapp.helper.EmergencyMessageHelper;
import com.facebook.share.model.SharePhoto;
import com.facebook.share.model.SharePhotoContent;
import com.facebook.share.widget.ShareDialog;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;

public class MainActivity extends BaseActivity implements ShakeDetector.OnShakeListener {

    private DrawerLayout drawerLayout;
    private NavigationView navigationView;
    private View pulseView;
    private Animation pulseAnimation;
    private PowerButtonReceiver powerButtonReceiver;

    private static final int REQ_NOTIFICATION_PERMISSION = 999;
    private static final int REQ_SMS_PERMISSION = 1001;
    private static final int REQ_MIC_PERMISSION = 1002;
    private Uri imageUri;

    private SharedPreferences prefs;
    private FusedLocationProviderClient locationClient;

    private String emergencyMessage = "";
    private String locationUrl = "";

    // Shake detection variables
    private SensorManager sensorManager;
    private Sensor accelerometer;
    private ShakeDetector shakeDetector;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        pulseView = findViewById(R.id.pulse_view);
        FrameLayout startShakeButton = findViewById(R.id.btn_sos);
        pulseAnimation = AnimationUtils.loadAnimation(this, R.anim.pulse);
        prefs = getSharedPreferences("AppSettingsPrefs", MODE_PRIVATE);
        locationClient = LocationServices.getFusedLocationProviderClient(this);

        requestNotificationPermission();
        requestSMSPermission();
        requestMicrophonePermission();

        // Start background shake detection service
        startShakeDetectionService();

        // Power button triple-press receiver
        powerButtonReceiver = new PowerButtonReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(powerButtonReceiver, filter);

        // SOS Button click
        startShakeButton.setOnClickListener(v -> {
            showSOSMethodDialog();
        });

        // Drawer setup
        drawerLayout = findViewById(R.id.drawer_layout);
        navigationView = findViewById(R.id.nav_view);

        // Menu button to open drawer
        ImageButton btnMenu = findViewById(R.id.btn_menu);
        btnMenu.setOnClickListener(v -> drawerLayout.openDrawer(GravityCompat.START));

        // Set user info in nav header
        View headerView = navigationView.getHeaderView(0);
        TextView navUserName = headerView.findViewById(R.id.nav_user_name);
        TextView navUserEmail = headerView.findViewById(R.id.nav_user_email);
        ImageView navProfileImage = headerView.findViewById(R.id.nav_profile_image);

        if (mAuth.getCurrentUser() != null) {
            String userId = mAuth.getCurrentUser().getUid();

            // Load user data from Firebase Database
            DatabaseReference userRef = FirebaseDatabase.getInstance().getReference("Users").child(userId);
            userRef.addListenerForSingleValueEvent(new ValueEventListener() {
                @Override
                public void onDataChange(DataSnapshot snapshot) {
                    if (snapshot.exists()) {
                        String name = snapshot.child("name").getValue(String.class);
                        String email = snapshot.child("email").getValue(String.class);
                        String photoBase64 = snapshot.child("photoBase64").getValue(String.class);

                        navUserName.setText(name != null ? name : "User");
                        navUserEmail.setText(email != null ? email : mAuth.getCurrentUser().getEmail());

                        // Load profile picture from Base64
                        if (photoBase64 != null && !photoBase64.isEmpty()) {
                            try {
                                byte[] decodedBytes = Base64.decode(photoBase64, Base64.DEFAULT);
                                Bitmap bitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.length);
                                navProfileImage.setImageBitmap(bitmap);
                            } catch (Exception e) {
                                navProfileImage.setImageResource(R.drawable.user_profile);
                            }
                        } else {
                            navProfileImage.setImageResource(R.drawable.user_profile);
                        }
                    } else {
                        navUserName.setText(mAuth.getCurrentUser().getDisplayName() != null ?
                                mAuth.getCurrentUser().getDisplayName() : "User");
                        navUserEmail.setText(mAuth.getCurrentUser().getEmail());
                    }
                }

                @Override
                public void onCancelled(DatabaseError error) {
                    navUserName.setText(mAuth.getCurrentUser().getDisplayName() != null ?
                            mAuth.getCurrentUser().getDisplayName() : "User");
                    navUserEmail.setText(mAuth.getCurrentUser().getEmail());
                }
            });
        }

        // Make profile image clickable to change picture
        navProfileImage.setOnClickListener(v -> {
            // Open image picker or profile activity
            startActivity(new Intent(this, ProfileActivity.class));
        });

        // Navigation drawer item click
        navigationView.setNavigationItemSelectedListener(item -> {
            int id = item.getItemId();

            if (id == R.id.nav_drawer_profile) {
                startActivity(new Intent(this, ProfileActivity.class));
            } else if (id == R.id.nav_drawer_settings) {
                startActivity(new Intent(this, SettingsActivity.class));
            } else if (id == R.id.nav_drawer_logout) {
                mAuth.signOut();
                Intent intent = new Intent(this, LoginActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                finish();
            }

            drawerLayout.closeDrawer(GravityCompat.START);
            return true;
        });

        // Navigation Buttons
        findViewById(R.id.btn_save_contatcs).setOnClickListener(v -> startActivity(new Intent(this, SaveSMSActivity.class)));
        findViewById(R.id.btn_ai_voice).setOnClickListener(v -> startActivity(new Intent(this, AIVoiceActivity.class)));
        findViewById(R.id.btn_share_location).setOnClickListener(v -> startActivity(new Intent(this, LiveLocation.class)));
        findViewById(R.id.btn_emergency_mode).setOnClickListener(v -> startActivity(new Intent(this, InCaseEmergencyActivity.class)));
        findViewById(R.id.btn_safe_zone).setOnClickListener(v -> startActivity(new Intent(this, SafeZoneActivity.class)));

        // Video Recording button - 60-second background recording
        findViewById(R.id.btn_alert).setOnClickListener(v -> {
            Toast.makeText(this, "Starting 60-second video recording...", Toast.LENGTH_SHORT).show();
            Intent intent = new Intent(this, VideoRecordingService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
        });
    }

    private void showSOSMethodDialog() {
        // Direct SOS - send immediately without countdown
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("SOS Alert")
                .setMessage("Choose how to send your emergency message:")
                .setPositiveButton("SMS", (dialog, which) -> {
                    sendSOSImmediately("sms");
                })
                .setNegativeButton("WhatsApp", (dialog, which) -> {
                    sendSOSImmediately("whatsapp");
                })
                .show();
    }
    private void sendSOSImmediately(String method) {
        // Start 60-second video recording in background
        Intent videoIntent = new Intent(this, VideoRecordingService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(videoIntent);
        } else {
            startService(videoIntent);
        }


        fetchEmergencyMessage(() -> {
            // Use LiveLocationManager instead of static location
            LiveLocationManager locationManager = new LiveLocationManager(this);
            LiveLocationManager.TrackingInfo trackingInfo = locationManager.startTracking();

            String fullMessage = emergencyMessage + "\n\n " + trackingInfo.getTrackingUrl();

            EmergencyMessageHelper helper = new EmergencyMessageHelper(this);
            helper.sendCustomMessage(method, fullMessage);
            Toast.makeText(this, "SOS sent with live tracking!", Toast.LENGTH_SHORT).show();
        });
    }

    //SOS Button
//    private void sendSOSImmediately(String method) {
//        // Send SOS immediately without countdown
//        fetchEmergencyMessage(() -> fetchLocation(() -> {
//            String fullMessage = emergencyMessage + "\n\nLocation: " + locationUrl;
//            EmergencyMessageHelper helper = new EmergencyMessageHelper(MainActivity.this);
//            helper.sendCustomMessage(method, fullMessage);
//            Toast.makeText(this, "Emergency alert sent!", Toast.LENGTH_SHORT).show();
//        }));
//    }


    private void initializeShakeDetection() {
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sensorManager != null) {
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            if (accelerometer != null) {
                shakeDetector = new ShakeDetector(this);
                shakeDetector.setRequiredShakeCount(3);
                Toast.makeText(this, "Shake detection enabled - shake 3 times for emergency", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "Accelerometer not available - shake detection disabled", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    public void onShake() {
        // Shake detection is now handled by background service only
        // This method is not used anymore but kept for interface implementation
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


    private void startShakeDetectionService() {
        try {
            Intent serviceIntent = new Intent(this, com.example.safetyapp.service.ShakeDetectionService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
        } catch (Exception e) {
            Toast.makeText(this, "Shake service failed, using in-app mode", Toast.LENGTH_SHORT).show();
            // Fallback to in-app shake detection if service fails
            initializeShakeDetection();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Background service handles all shake detection
        // No in-app shake detection needed

        // Reload profile picture in navigation drawer
        if (mAuth.getCurrentUser() != null && navigationView != null) {
            String userId = mAuth.getCurrentUser().getUid();
            DatabaseReference userRef = FirebaseDatabase.getInstance().getReference("Users").child(userId);

            View headerView = navigationView.getHeaderView(0);
            ImageView navProfileImage = headerView.findViewById(R.id.nav_profile_image);
            TextView navUserName = headerView.findViewById(R.id.nav_user_name);
            TextView navUserEmail = headerView.findViewById(R.id.nav_user_email);

            userRef.addListenerForSingleValueEvent(new ValueEventListener() {
                @Override
                public void onDataChange(DataSnapshot snapshot) {
                    if (snapshot.exists()) {
                        String name = snapshot.child("name").getValue(String.class);
                        String email = snapshot.child("email").getValue(String.class);
                        String photoBase64 = snapshot.child("photoBase64").getValue(String.class);

                        navUserName.setText(name != null ? name : "User");
                        navUserEmail.setText(email != null ? email : mAuth.getCurrentUser().getEmail());

                        if (photoBase64 != null && !photoBase64.isEmpty()) {
                            try {
                                byte[] decodedBytes = Base64.decode(photoBase64, Base64.DEFAULT);
                                Bitmap bitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.length);
                                navProfileImage.setImageBitmap(bitmap);
                            } catch (Exception e) {
                                navProfileImage.setImageResource(R.drawable.ic_profile);
                            }
                        } else {
                            navProfileImage.setImageResource(R.drawable.ic_profile);
                        }
                    }
                }

                @Override
                public void onCancelled(DatabaseError error) {
                    // Keep existing values
                }
            });
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Background service continues running
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (powerButtonReceiver != null) {
            unregisterReceiver(powerButtonReceiver);
        }
        // Background service continues running
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATION_PERMISSION);
        }
    }

    private void requestSMSPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.SEND_SMS}, REQ_SMS_PERMISSION);
        }
    }

    private void requestMicrophonePermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC_PERMISSION);
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIFICATION_PERMISSION && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Notification permission granted", Toast.LENGTH_SHORT).show();
        } else if (requestCode == REQ_SMS_PERMISSION && (grantResults.length == 0 || grantResults[0] != PackageManager.PERMISSION_GRANTED)) {
            Toast.makeText(this, "SMS permission denied.", Toast.LENGTH_LONG).show();
        } else if (requestCode == REQ_MIC_PERMISSION && (grantResults.length == 0 || grantResults[0] != PackageManager.PERMISSION_GRANTED)) {
            Toast.makeText(this, "Mic permission denied. Voice detection won't work.", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onBackPressed() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START);
        } else {
            super.onBackPressed();
        }
    }
}
