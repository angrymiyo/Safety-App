package com.example.safetyapp;

import android.Manifest;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

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
        setupLayout(R.layout.activity_main, "Welcome to নির্ভয়!", false, R.id.nav_home);

        pulseView = findViewById(R.id.pulse_view);
        FrameLayout startShakeButton = findViewById(R.id.btn_sos);
        pulseAnimation = AnimationUtils.loadAnimation(this, R.anim.pulse);
        prefs = getSharedPreferences("AppSettingsPrefs", MODE_PRIVATE);
        locationClient = LocationServices.getFusedLocationProviderClient(this);

        requestNotificationPermission();
        requestSMSPermission();
        requestMicrophonePermission();

        // Initialize shake detection
        initializeShakeDetection();

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



        // Navigation Buttons
        findViewById(R.id.btn_save_contatcs).setOnClickListener(v -> startActivity(new Intent(this, SaveSMSActivity.class)));
        findViewById(R.id.btn_ai_voice).setOnClickListener(v -> startActivity(new Intent(this, AIVoiceActivity.class)));
        findViewById(R.id.btn_share_location).setOnClickListener(v -> startActivity(new Intent(this, LiveLocation.class)));
        findViewById(R.id.btn_emergency_mode).setOnClickListener(v -> startActivity(new Intent(this, InCaseEmergencyActivity.class)));
        findViewById(R.id.btn_safe_zone).setOnClickListener(v -> startActivity(new Intent(this, SafeZoneActivity.class)));
    }

    private void showSOSMethodDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("SOS Alert")
                .setMessage("Choose how to send your emergency message:")
                .setPositiveButton("SMS", (dialog, which) -> {
                    sendSOSEmergency("sms");
                })
                .setNegativeButton("WhatsApp", (dialog, which) -> {
                    sendSOSEmergency("whatsapp");
                })
                .show();
    }

    private void sendSOSEmergency(String method) {
        pulseView.startAnimation(pulseAnimation);
        fetchEmergencyMessage(() -> fetchLocation(() -> {
            String fullMessage = emergencyMessage + "\n\nLocation: " + locationUrl;

            // Use EmergencyMessageHelper with the new SOS-specific method
            EmergencyMessageHelper helper = new EmergencyMessageHelper(this);
            helper.sendSOSMessage(method, fullMessage);

            // Show confirmation toast
            String methodName = "sms".equals(method) ? "SMS" : "WhatsApp";
            Toast.makeText(this, "SOS initiated via " + methodName, Toast.LENGTH_SHORT).show();
        }));
    }

    private void initializeShakeDetection() {
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sensorManager != null) {
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            if (accelerometer != null) {
                shakeDetector = new ShakeDetector(this);
                shakeDetector.setRequiredShakeCount(3); // Require 3 shakes
                Toast.makeText(this, "Shake detection enabled - shake 3 times for emergency", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "Accelerometer not available - shake detection disabled", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    public void onShake() {
        // This method is called when 3 shakes are detected
        Toast.makeText(this, "Emergency shake detected! Sending alert...", Toast.LENGTH_LONG).show();

        // Shake detection always uses SMS + Facebook only
        sendShakeEmergency();
    }

    private void sendShakeEmergency() {
        pulseView.startAnimation(pulseAnimation);
        fetchEmergencyMessage(() -> fetchLocation(() -> {
            String fullMessage = emergencyMessage + "\n\nLocation: " + locationUrl + "\n\n[Emergency triggered by shake detection]";

            // Shake detection only uses SMS + Facebook (no WhatsApp)
            EmergencyMessageHelper helper = new EmergencyMessageHelper(this);
            helper.sendCustomMessage("sms", fullMessage); // Always SMS for shake
            helper.postToFacebookFeed(fullMessage); // Always Facebook for shake

            Toast.makeText(this, "Shake emergency sent via SMS and posted to Facebook", Toast.LENGTH_SHORT).show();
        }));
    }

    private void capturePhoto() {
        Intent cameraIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        try {
            File photoFile = createImageFile();
            imageUri = FileProvider.getUriForFile(this, getPackageName() + ".provider", photoFile);
            cameraIntent.putExtra(MediaStore.EXTRA_OUTPUT, imageUri);
            cameraLauncher.launch(cameraIntent);
        } catch (IOException e) {
            Toast.makeText(this, "Camera error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
    private File createImageFile() throws IOException {
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        File storageDir = getExternalFilesDir("Pictures");
        return File.createTempFile("SOS_" + timeStamp, ".jpg", storageDir);
    }

        private final ActivityResultLauncher<Intent> cameraLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && imageUri != null) {
                    fetchEmergencyMessage(() -> fetchLocation(() -> {
                        String fullMessage = emergencyMessage + "\n\nLocation: " + locationUrl;

                        EmergencyMessageHelper helper = new EmergencyMessageHelper(MainActivity.this);
                        helper.sendCustomMessage("sms", fullMessage);
                        postToFacebookWithImage(imageUri, fullMessage);
                    }));
                } else {
                    Toast.makeText(this, "Camera canceled or failed", Toast.LENGTH_SHORT).show();
                }
            });
    private void postToFacebookWithImage(Uri imageUri, String message) {
        try {
            Bitmap bitmap = MediaStore.Images.Media.getBitmap(this.getContentResolver(), imageUri);

            if (ShareDialog.canShow(SharePhotoContent.class)) {
                SharePhoto photo = new SharePhoto.Builder()
                        .setBitmap(bitmap)
                        .setCaption(message)
                        .build();

                SharePhotoContent content = new SharePhotoContent.Builder()
                        .addPhoto(photo)
                        .build();

                new ShareDialog(this).show(content);
            } else {
                Toast.makeText(this, "Facebook share dialog not available", Toast.LENGTH_SHORT).show();
            }
        } catch (IOException e) {
            e.printStackTrace();
            Toast.makeText(this, "Failed to load image for Facebook", Toast.LENGTH_SHORT).show();
        }
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
    protected void onResume() {
        super.onResume();
        // Register shake detection sensor listener
        if (sensorManager != null && accelerometer != null && shakeDetector != null) {
            sensorManager.registerListener(shakeDetector, accelerometer, SensorManager.SENSOR_DELAY_UI);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Unregister shake detection sensor listener to save battery
        if (sensorManager != null && shakeDetector != null) {
            sensorManager.unregisterListener(shakeDetector);
        }
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (powerButtonReceiver != null) {
            unregisterReceiver(powerButtonReceiver);
        }
        // Ensure sensor listener is unregistered
        if (sensorManager != null && shakeDetector != null) {
            sensorManager.unregisterListener(shakeDetector);
        }
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
}
