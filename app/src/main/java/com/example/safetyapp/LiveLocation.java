package com.example.safetyapp;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.telephony.SmsManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.*;

import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class LiveLocation extends BaseActivity {

    private static final int LOCATION_PERMISSION_CODE = 101;
    private static final int BACKGROUND_LOCATION_PERMISSION_CODE = 103;
    private static final int SMS_PERMISSION_CODE = 102;

    private FusedLocationProviderClient fusedLocationProviderClient;
    private List<Contact> contacts = new ArrayList<>();
    private DatabaseReference dbRef;
    private DatabaseReference liveLocationRef;
    private FirebaseUser currentUser;

    private Button btnShare;
    private Button btnStopSharing;
    private TextView tvStatus;

    private LocationRequest locationRequest;
    private LocationCallback locationCallback;
    private Handler handler;
    private String shareId;
    private boolean isSharing = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupLayout(R.layout.activity_live_location, "Live Location", true,R.id.nav_home);
//        setContentView(R.layout.activity_live_location);

        btnShare = findViewById(R.id.btnShareLocation);
        btnStopSharing = findViewById(R.id.btnStopSharing);
        tvStatus = findViewById(R.id.tvStatus);
        fusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(this);
        handler = new Handler(Looper.getMainLooper());

        currentUser = FirebaseAuth.getInstance().getCurrentUser();
        if (currentUser == null) {
            Toast.makeText(this, "User not logged in", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        dbRef = FirebaseDatabase.getInstance().getReference("Users")
                .child(currentUser.getUid()).child("emergencyContacts");

        liveLocationRef = FirebaseDatabase.getInstance().getReference("LiveLocations");

        loadContacts();
        setupLocationRequest();
        setupLocationCallback();

        btnShare.setOnClickListener(v -> {
            if (contacts.isEmpty()) {
                Toast.makeText(this, "No emergency contacts found", Toast.LENGTH_SHORT).show();
                return;
            }

            new AlertDialog.Builder(this)
                    .setTitle("Share Live Location")
                    .setMessage("Start sharing your real-time location? Recipients will see your live movement on a map.")
                    .setPositiveButton("WhatsApp", (dialog, which) -> startLiveLocationSharing("whatsapp"))
                    .setNegativeButton("SMS", (dialog, which) -> startLiveLocationSharing("sms"))
                    .setNeutralButton("Cancel", null)
                    .show();
        });

        btnStopSharing.setOnClickListener(v -> stopLiveLocationSharing());
    }

    private void loadContacts() {
        dbRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                contacts.clear();
                for (DataSnapshot contactSnapshot : snapshot.getChildren()) {
                    Contact contact = contactSnapshot.getValue(Contact.class);
                    if (contact != null) {
                        contacts.add(contact);
                    }
                }
            }

            @Override
            public void onCancelled(DatabaseError error) {
                Toast.makeText(LiveLocation.this, "Failed to load contacts", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void setupLocationRequest() {
        locationRequest = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000) // Update every 2 seconds for real-time tracking
                .setWaitForAccurateLocation(true) // Wait for accurate location
                .setMinUpdateIntervalMillis(1000) // Minimum 1 second between updates for smooth movement
                .setMaxUpdateDelayMillis(3000) // Maximum 3 seconds delay for responsiveness
                .setMinUpdateDistanceMeters(1.0f) // Update when moved at least 1 meter
                .setDurationMillis(3600000) // Track for 1 hour max
                .build();
    }

    private void setupLocationCallback() {
        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult locationResult) {
                if (locationResult == null || !isSharing) {
                    return;
                }

                for (Location location : locationResult.getLocations()) {
                    updateLiveLocation(location);
                }
            }
        };
    }

    private void startLiveLocationSharing(String method) {
        // Check for fine location permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    LOCATION_PERMISSION_CODE);
            return;
        }

        // Check for background location permission (Android 10+) for continuous tracking
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION},
                        BACKGROUND_LOCATION_PERMISSION_CODE);
                return;
            }
        }

        if ("sms".equals(method) &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
                        != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.SEND_SMS},
                    SMS_PERMISSION_CODE);
            return;
        }

        // Generate unique share ID
        shareId = currentUser.getUid() + "_" + System.currentTimeMillis();
        isSharing = true;

        // Start location updates
        fusedLocationProviderClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper());

        // Create live location session
        createLiveLocationSession();

        // Send live location link to contacts
        String liveLocationUrl = "https://your-domain.com/live-location/" + shareId; // You'll need to host this
        String message = "🚨 LIVE LOCATION SHARING 🚨\n\nI'm sharing my real-time location with you. Click to see my live movement:\n\n" + liveLocationUrl + "\n\nThis link will work for 1 hour.";

        for (Contact contact : contacts) {
            if ("whatsapp".equals(method)) {
                sendWhatsApp(contact.getPhone(), message);
            } else {
                sendSmsDirectly(contact.getPhone(), message);
            }
        }

        // Update UI
        btnShare.setEnabled(false);
        btnStopSharing.setEnabled(true);
        tvStatus.setText("🔴 LIVE: Sharing real-time location...");
        Toast.makeText(this, "Started sharing live location!", Toast.LENGTH_SHORT).show();

        // Auto-stop after 1 hour
        handler.postDelayed(this::stopLiveLocationSharing, 3600000); // 1 hour
    }

    private void createLiveLocationSession() {
        Map<String, Object> sessionData = new HashMap<>();
        sessionData.put("userId", currentUser.getUid());
        sessionData.put("userEmail", currentUser.getEmail());
        sessionData.put("startTime", System.currentTimeMillis());
        sessionData.put("isActive", true);

        liveLocationRef.child(shareId).setValue(sessionData);
    }

    private void updateLiveLocation(Location location) {
        if (shareId == null || !isSharing) return;

        // Only update if location is accurate enough (less than 10 meters accuracy)
        if (location.getAccuracy() > 10.0f) {
            return; // Skip inaccurate locations
        }

        Map<String, Object> locationData = new HashMap<>();
        locationData.put("latitude", location.getLatitude());
        locationData.put("longitude", location.getLongitude());
        locationData.put("accuracy", location.getAccuracy());
        locationData.put("timestamp", System.currentTimeMillis());
        locationData.put("speed", location.hasSpeed() ? location.getSpeed() : 0);
        locationData.put("bearing", location.hasBearing() ? location.getBearing() : 0);
        locationData.put("altitude", location.hasAltitude() ? location.getAltitude() : 0);
        locationData.put("provider", location.getProvider());

        // Store location in path for movement tracking
        liveLocationRef.child(shareId).child("currentLocation").setValue(locationData);
        liveLocationRef.child(shareId).child("locationHistory").push().setValue(locationData);

        // Update status with accuracy info
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
        String accuracyText = String.format("±%.1fm", location.getAccuracy());
        tvStatus.setText("🔴 LIVE: Updated at " + sdf.format(new Date()) + " (" + accuracyText + ")");
    }

    private void stopLiveLocationSharing() {
        if (!isSharing) return;

        isSharing = false;
        fusedLocationProviderClient.removeLocationUpdates(locationCallback);
        handler.removeCallbacksAndMessages(null);

        // Mark session as inactive
        if (shareId != null) {
            liveLocationRef.child(shareId).child("isActive").setValue(false);
            liveLocationRef.child(shareId).child("endTime").setValue(System.currentTimeMillis());
        }

        // Update UI
        btnShare.setEnabled(true);
        btnStopSharing.setEnabled(false);
        tvStatus.setText("Live location sharing stopped");
        Toast.makeText(this, "Stopped sharing live location", Toast.LENGTH_SHORT).show();
    }

    private void sendWhatsApp(String phoneNumber, String message) {
        try {
            phoneNumber = phoneNumber.replace("+", "").replaceAll("\\s", "");
            String url = "https://wa.me/" + phoneNumber + "?text=" + URLEncoder.encode(message, "UTF-8");

            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setData(Uri.parse(url));
            startActivity(intent);  // No need to check for installed package; browser will handle it

        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "Error sending WhatsApp message", Toast.LENGTH_SHORT).show();
        }
    }

    private void sendSmsDirectly(String phoneNumber, String message) {
        try {
            SmsManager smsManager = SmsManager.getDefault();
            smsManager.sendTextMessage(phoneNumber, null, message, null, null);
            Toast.makeText(this, "Location sent to " + phoneNumber, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Failed to send SMS to " + phoneNumber, Toast.LENGTH_SHORT).show();
            e.printStackTrace();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopLiveLocationSharing();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Continue sharing in background - don't stop here
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == LOCATION_PERMISSION_CODE || requestCode == BACKGROUND_LOCATION_PERMISSION_CODE || requestCode == SMS_PERMISSION_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (requestCode == BACKGROUND_LOCATION_PERMISSION_CODE) {
                    Toast.makeText(this, "Background location permission granted. Your location will be tracked even when app is minimized.", Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, "Permission granted, tap the button again.", Toast.LENGTH_SHORT).show();
                }
            } else {
                if (requestCode == BACKGROUND_LOCATION_PERMISSION_CODE) {
                    Toast.makeText(this, "Background location permission is required for continuous tracking", Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show();
                }
            }
        }
    }
}