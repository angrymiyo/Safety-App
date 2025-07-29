//package com.example.safetyapp;
//
//import android.Manifest;
//import android.content.Intent;
//import android.content.IntentFilter;
//import android.content.SharedPreferences;
//import android.content.pm.PackageManager;
//import android.graphics.Bitmap;
//import android.hardware.Sensor;
//import android.hardware.SensorManager;
//import android.location.Location;
//import android.net.Uri;
//import android.os.Build;
//import android.os.Bundle;
//import android.provider.MediaStore;
//import android.view.View;
//import android.view.animation.Animation;
//import android.view.animation.AnimationUtils;
//import android.widget.FrameLayout;
//import android.widget.Toast;
//
//import androidx.activity.result.ActivityResultLauncher;
//import androidx.activity.result.contract.ActivityResultContracts;
//import androidx.annotation.NonNull;
//import androidx.core.app.ActivityCompat;
//import androidx.core.content.ContextCompat;
//import androidx.core.content.FileProvider;
//
//import com.example.safetyapp.helper.EmergencyMessageHelper;
//import com.facebook.share.model.SharePhoto;
//import com.facebook.share.model.SharePhotoContent;
//import com.facebook.share.widget.ShareDialog;
//import com.google.android.gms.location.FusedLocationProviderClient;
//import com.google.android.gms.location.LocationServices;
//import com.google.firebase.auth.FirebaseAuth;
//import com.google.firebase.firestore.FirebaseFirestore;
//
//import java.io.File;
//import java.io.IOException;
//import java.text.SimpleDateFormat;
//import java.util.Date;
//
//public class MainActivity extends BaseActivity {
//
//    private View pulseView;
//    private Animation pulseAnimation;
//    private PowerButtonReceiver powerButtonReceiver;
//
//    private static final int REQ_NOTIFICATION_PERMISSION = 999;
//    private static final int REQ_SMS_PERMISSION = 1001;
//    private static final int REQ_MIC_PERMISSION = 1002;
//
//    private SensorManager sensorManager;
//    private Sensor accelerometer;
//    private ShakeDetector shakeDetector;
//    private SharedPreferences prefs;
//
//    private Uri imageUri;
//    private String emergencyMessage = "";
//    private String locationUrl = "";
//
//    private FusedLocationProviderClient locationClient;
//
//    private final ActivityResultLauncher<Intent> cameraLauncher =
//            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
//                if (result.getResultCode() == RESULT_OK && imageUri != null) {
//                    fetchEmergencyMessage(() -> fetchLocation(() -> {
//                        String fullMessage = emergencyMessage + "\n\nLocation: " + locationUrl;
//
//                        EmergencyMessageHelper helper = new EmergencyMessageHelper(MainActivity.this);
//                        helper.sendCustomMessage("sms", fullMessage);
//                        helper.sendCustomMessage("whatsapp", fullMessage);
//                        postToFacebookWithImage(imageUri, fullMessage);
//                    }));
//                } else {
//                    Toast.makeText(this, "Camera canceled or failed", Toast.LENGTH_SHORT).show();
//                }
//            });
//
//    @Override
//    protected void onCreate(Bundle savedInstanceState) {
//        super.onCreate(savedInstanceState);
//        setupLayout(R.layout.activity_main, "Welcome to নির্ভয়!", false, R.id.nav_home);
//
//        pulseView = findViewById(R.id.pulse_view);
//        FrameLayout startShakeButton = findViewById(R.id.btn_sos);
//        pulseAnimation = AnimationUtils.loadAnimation(this, R.anim.pulse);
//        prefs = getSharedPreferences("AppSettingsPrefs", MODE_PRIVATE);
//        locationClient = LocationServices.getFusedLocationProviderClient(this);
//
//        requestNotificationPermission();
//        requestSMSPermission();
//        requestMicrophonePermission();
//        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
//        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
//
//        shakeDetector = new ShakeDetector(() -> {
//            new EmergencyMessageHelper(MainActivity.this).sendMessage("sms");
//            Toast.makeText(MainActivity.this, "Emergency SMS Sent by Shake!", Toast.LENGTH_SHORT).show();
//        });
//
//        shakeDetector.setRequiredShakeCount(prefs.getInt("shake_count_threshold", 3));
//
//        startShakeButton.setOnClickListener(v -> {
//            pulseView.startAnimation(pulseAnimation);
//            capturePhoto();
//        });
//
//        powerButtonReceiver = new PowerButtonReceiver();
//        IntentFilter filter = new IntentFilter();
//        filter.addAction(Intent.ACTION_SCREEN_ON);
//        filter.addAction(Intent.ACTION_SCREEN_OFF);
//        registerReceiver(powerButtonReceiver, filter);
//
//        // Nav buttons
//        findViewById(R.id.btn_save_contatcs).setOnClickListener(v -> startActivity(new Intent(this, SaveSMSActivity.class)));
//        findViewById(R.id.btn_ai_voice).setOnClickListener(v -> startActivity(new Intent(this, AIVoiceActivity.class)));
//        findViewById(R.id.btn_share_location).setOnClickListener(v -> startActivity(new Intent(this, LiveLocation.class)));
//        findViewById(R.id.btn_emergency_mode).setOnClickListener(v -> startActivity(new Intent(this, InCaseEmergencyActivity.class)));
//        findViewById(R.id.btn_safe_zone).setOnClickListener(v -> startActivity(new Intent(this, SafeZoneActivity.class)));
//    }
//
//    private void capturePhoto() {
//        Intent cameraIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
//        try {
//            File photoFile = createImageFile();
//            imageUri = FileProvider.getUriForFile(this, getPackageName() + ".provider", photoFile);
//            cameraIntent.putExtra(MediaStore.EXTRA_OUTPUT, imageUri);
//            cameraLauncher.launch(cameraIntent);
//        } catch (IOException e) {
//            Toast.makeText(this, "Camera error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
//        }
//    }
//
//    private File createImageFile() throws IOException {
//        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
//        File storageDir = getExternalFilesDir("Pictures");
//        return File.createTempFile("SOS_" + timeStamp, ".jpg", storageDir);
//    }
//
//    private void fetchEmergencyMessage(Runnable callback) {
//        FirebaseFirestore.getInstance()
//                .collection("EmergencyMessage")
//                .document(FirebaseAuth.getInstance().getCurrentUser().getUid())
//                .get()
//                .addOnSuccessListener(document -> {
//                    emergencyMessage = document.contains("message") ? document.getString("message") : "Help me!";
//                    callback.run();
//                })
//                .addOnFailureListener(e -> {
//                    emergencyMessage = "Help me!";
//                    callback.run();
//                });
//    }
//
//    private void fetchLocation(Runnable callback) {
//        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
//            locationUrl = "Location not available";
//            callback.run();
//            return;
//        }
//
//        locationClient.getLastLocation()
//                .addOnSuccessListener(location -> {
//                    if (location != null) {
//                        locationUrl = "https://maps.google.com/?q=" + location.getLatitude() + "," + location.getLongitude();
//                    } else {
//                        locationUrl = "Location not available";
//                    }
//                    callback.run();
//                })
//                .addOnFailureListener(e -> {
//                    locationUrl = "Location not available";
//                    callback.run();
//                });
//    }
//
//    private void postToFacebookWithImage(Uri imageUri, String message) {
//        try {
//            Bitmap bitmap = MediaStore.Images.Media.getBitmap(this.getContentResolver(), imageUri);
//
//            if (ShareDialog.canShow(SharePhotoContent.class)) {
//                SharePhoto photo = new SharePhoto.Builder()
//                        .setBitmap(bitmap)
//                        .setCaption(message)
//                        .build();
//
//                SharePhotoContent content = new SharePhotoContent.Builder()
//                        .addPhoto(photo)
//                        .build();
//
//                new ShareDialog(this).show(content);
//            } else {
//                Toast.makeText(this, "Facebook share dialog not available", Toast.LENGTH_SHORT).show();
//            }
//        } catch (IOException e) {
//            e.printStackTrace();
//            Toast.makeText(this, "Failed to load image for Facebook", Toast.LENGTH_SHORT).show();
//        }
//    }
//
//
//    @Override protected void onResume() {
//        super.onResume();
//        if (sensorManager != null) {
//            sensorManager.registerListener(shakeDetector, accelerometer, SensorManager.SENSOR_DELAY_UI);
//        }
//    }
//
//    @Override protected void onPause() {
//        super.onPause();
//        if (sensorManager != null) {
//            sensorManager.unregisterListener(shakeDetector);
//        }
//    }
//
//    @Override protected void onDestroy() {
//        super.onDestroy();
//        if (powerButtonReceiver != null) {
//            unregisterReceiver(powerButtonReceiver);
//        }
//    }
//
//    private void requestNotificationPermission() {
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
//                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
//            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATION_PERMISSION);
//        }
//    }
//
//    private void requestSMSPermission() {
//        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
//            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.SEND_SMS}, REQ_SMS_PERMISSION);
//        }
//    }
//
//    @Override public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
//        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
//        if (requestCode == REQ_NOTIFICATION_PERMISSION && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
//            Toast.makeText(this, "Notification permission granted", Toast.LENGTH_SHORT).show();
//        } else if (requestCode == REQ_SMS_PERMISSION && (grantResults.length == 0 || grantResults[0] != PackageManager.PERMISSION_GRANTED)) {
//            Toast.makeText(this, "SMS permission denied. Shake SOS won't work.", Toast.LENGTH_LONG).show();
//        }else if (requestCode == REQ_MIC_PERMISSION && (grantResults.length == 0 || grantResults[0] != PackageManager.PERMISSION_GRANTED)) {
//            Toast.makeText(this, "Mic permission denied. Voice detection won't work.", Toast.LENGTH_LONG).show();
//        }
//    }
//    private void requestMicrophonePermission() {
//        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
//            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC_PERMISSION);
//        }
//    }
//}


package com.example.safetyapp;

import android.Manifest;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
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
import com.google.firebase.firestore.FirebaseFirestore;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;

public class MainActivity extends BaseActivity {

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

        // Power button triple-press receiver
        powerButtonReceiver = new PowerButtonReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(powerButtonReceiver, filter);

        // SOS Button click
        startShakeButton.setOnClickListener(v -> {
            pulseView.startAnimation(pulseAnimation);
            fetchEmergencyMessage(() -> fetchLocation(() -> {
                String fullMessage = emergencyMessage + "\n\nLocation: " + locationUrl;
                sendSelectedMessage(fullMessage);
            }));
            capturePhoto();
        });



        // Navigation Buttons
        findViewById(R.id.btn_save_contatcs).setOnClickListener(v -> startActivity(new Intent(this, SaveSMSActivity.class)));
        findViewById(R.id.btn_ai_voice).setOnClickListener(v -> startActivity(new Intent(this, AIVoiceActivity.class)));
        findViewById(R.id.btn_share_location).setOnClickListener(v -> startActivity(new Intent(this, LiveLocation.class)));
        findViewById(R.id.btn_emergency_mode).setOnClickListener(v -> startActivity(new Intent(this, InCaseEmergencyActivity.class)));
        findViewById(R.id.btn_safe_zone).setOnClickListener(v -> startActivity(new Intent(this, SafeZoneActivity.class)));
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
                        helper.sendCustomMessage("whatsapp", fullMessage);
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
        FirebaseFirestore.getInstance()
                .collection("EmergencyMessage")
                .document(FirebaseAuth.getInstance().getCurrentUser().getUid())
                .get()
                .addOnSuccessListener(document -> {
                    emergencyMessage = document.contains("message") ? document.getString("message") : "Help me!";
                    callback.run();
                })
                .addOnFailureListener(e -> {
                    emergencyMessage = "Help me!";
                    callback.run();
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

    private void sendSelectedMessage(String message) {
        String selectedMode = prefs.getString("emergency_message_mode", "sms"); // Default to SMS
        EmergencyMessageHelper helper = new EmergencyMessageHelper(this);

        if ("whatsapp".equalsIgnoreCase(selectedMode)) {
            helper.sendCustomMessage("whatsapp", message);
            Toast.makeText(this, "Message sent via WhatsApp", Toast.LENGTH_SHORT).show();
        } else {
            helper.sendCustomMessage("sms", message);
            Toast.makeText(this, "Message sent via SMS", Toast.LENGTH_SHORT).show();
        }
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (powerButtonReceiver != null) {
            unregisterReceiver(powerButtonReceiver);
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
