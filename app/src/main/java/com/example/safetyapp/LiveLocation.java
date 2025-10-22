package com.example.safetyapp;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.safetyapp.helper.EmergencyMessageHelper;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.*;

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
    private LiveLocationManager locationManager;

    private Button btnShare;
    private Button btnStopSharing;
    private TextView tvStatus;
    private View statusDot;
    private View statusDotGlow;

    private LocationRequest locationRequest;
    private LocationCallback locationCallback;
    private Handler handler;
    private String shareId;
    private boolean isSharing = false;
    private String emergencyMessage = "";
    private String trackingUrl = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Setup layout with toolbar, back button, and no bottom nav
        setupLayout(R.layout.activity_live_location, "Live Location", true, R.id.nav_home, false);

        btnShare = findViewById(R.id.btnShareLocation);
        btnStopSharing = findViewById(R.id.btnStopSharing);
        tvStatus = findViewById(R.id.tvStatus);
        statusDot = findViewById(R.id.statusDot);
        statusDotGlow = findViewById(R.id.statusDotGlow);
        fusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(this);
        handler = new Handler(Looper.getMainLooper());
        locationManager = new LiveLocationManager(this);

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
        cleanupOldSessions(); // Auto-cleanup old tracking sessions

        btnShare.setOnClickListener(v -> {
            showShareMethodDialog();
        });

        btnStopSharing.setOnClickListener(v -> stopLiveLocationSharing());
    }

    private void loadContacts() {
        dbRef.addListenerForSingleValueEvent(new ValueEventListener() {
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
                // Silent error - contacts will be empty
            }
        });
    }

    private void showShareMethodDialog() {
        // Check if contacts are loaded first
        if (contacts.isEmpty()) {
            // Load contacts first, then show dialog
            dbRef.addListenerForSingleValueEvent(new ValueEventListener() {
                @Override
                public void onDataChange(DataSnapshot snapshot) {
                    contacts.clear();
                    for (DataSnapshot contactSnapshot : snapshot.getChildren()) {
                        Contact contact = contactSnapshot.getValue(Contact.class);
                        if (contact != null) {
                            contacts.add(contact);
                        }
                    }

                    if (contacts.isEmpty()) {
                        Toast.makeText(LiveLocation.this, "No emergency contacts found. Please add contacts from Settings.", Toast.LENGTH_LONG).show();
                    } else {
                        showMethodSelectionDialog();
                    }
                }

                @Override
                public void onCancelled(DatabaseError error) {
                    Toast.makeText(LiveLocation.this, "Failed to load contacts", Toast.LENGTH_SHORT).show();
                }
            });
            return;
        }

        // Contacts already loaded, show dialog
        showMethodSelectionDialog();
    }

    private void showMethodSelectionDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Share Live Location")
                .setMessage("Choose how to send your live tracking link:")
                .setPositiveButton("SMS", (dialog, which) -> {
                    startLiveTrackingWithMethod("sms");
                })
                .setNegativeButton("WhatsApp", (dialog, which) -> {
                    startLiveTrackingWithMethod("whatsapp");
                })
                .show();
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


    private void startLiveTrackingWithMethod(String method) {
        // Check location permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    LOCATION_PERMISSION_CODE);
            return;
        }

        // Check background location permission
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION},
                        BACKGROUND_LOCATION_PERMISSION_CODE);
                return;
            }
        }

        // Check SMS permission if method is SMS
        if ("sms".equals(method) && ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.SEND_SMS},
                    SMS_PERMISSION_CODE);
            return;
        }

        // Use LiveLocationManager to generate URL and start tracking
        fetchEmergencyMessage(() -> {
            try {
                // Use LiveLocationManager to start tracking and get URL
                LiveLocationManager.TrackingInfo trackingInfo = locationManager.startTracking();
                shareId = trackingInfo.getShareId();
                trackingUrl = trackingInfo.getTrackingUrl();
                isSharing = true;

                // Build message: emergency message + tracking URL
                String fullMessage = emergencyMessage + "\n\n " + trackingUrl;

                // Send using EmergencyMessageHelper
                EmergencyMessageHelper helper = new EmergencyMessageHelper(LiveLocation.this);
                helper.sendCustomMessage(method, fullMessage);

                Toast.makeText(this, "Live tracking started! Link sent to emergency contacts", Toast.LENGTH_SHORT).show();

                // Update UI
                updateUIForActiveTracking();

            } catch (IllegalStateException e) {
                Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void updateUIForActiveTracking() {
        btnShare.setEnabled(false);
        btnStopSharing.setEnabled(true);
        tvStatus.setText("Live tracking\nActive now");
        statusDot.setBackgroundResource(R.drawable.status_dot_active);
        statusDotGlow.setBackgroundResource(R.drawable.status_dot_glow_active);
    }

    private void fetchEmergencyMessage(Runnable callback) {
        DatabaseReference messageRef = FirebaseDatabase.getInstance()
                .getReference("Users")
                .child(currentUser.getUid())
                .child("emergency_message_template");

        messageRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                emergencyMessage = snapshot.exists() ? snapshot.getValue(String.class) : "Emergency! I need help!";
                if (emergencyMessage == null || emergencyMessage.isEmpty()) {
                    emergencyMessage = "Emergency! I need help!";
                }
                callback.run();
            }

            @Override
            public void onCancelled(DatabaseError error) {
                emergencyMessage = "Emergency! I need help!";
                callback.run();
            }
        });
    }

    private void updateLiveLocation(Location location) {
        if (shareId == null || !isSharing) return;

        // Only update if location is accurate enough (less than 50 meters accuracy)
        if (location.getAccuracy() > 50.0f) {
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

        // Store location in Firebase for real-time tracking
        liveLocationRef.child(shareId).child("currentLocation").setValue(locationData);
        liveLocationRef.child(shareId).child("locationHistory").push().setValue(locationData);

        // Update status
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
        tvStatus.setText("Live tracking\nUpdated at " + sdf.format(new Date()));
    }

    private void stopLiveLocationSharing() {
        if (!isSharing) return;

        isSharing = false;
        handler.removeCallbacksAndMessages(null);

        // Use LiveLocationManager to stop tracking
        locationManager.stopTracking(shareId);

        // Update UI
        btnShare.setEnabled(true);
        btnStopSharing.setEnabled(false);
        tvStatus.setText("Ready to share\nTap the button to start");
        statusDot.setBackgroundResource(R.drawable.status_dot_inactive);
        statusDotGlow.setBackgroundResource(R.drawable.status_dot_glow);
        Toast.makeText(this, "Stopped sharing live location", Toast.LENGTH_SHORT).show();
    }

    private void cleanupOldSessions() {
        // Delete tracking sessions older than 24 hours to save Firebase storage
        long oneDayAgo = System.currentTimeMillis() - (24 * 60 * 60 * 1000); // 24 hours

        liveLocationRef.orderByChild("startTime").endAt(oneDayAgo).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                for (DataSnapshot sessionSnapshot : snapshot.getChildren()) {
                    // Delete old session
                    sessionSnapshot.getRef().removeValue();
                }
            }

            @Override
            public void onCancelled(DatabaseError error) {
                // Silent cleanup failure
            }
        });
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