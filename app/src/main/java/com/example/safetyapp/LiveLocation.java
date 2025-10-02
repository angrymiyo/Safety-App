package com.example.safetyapp;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.telephony.SmsManager;
import android.view.View;
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
    private View statusDot;
    private View statusDotGlow;

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
        statusDot = findViewById(R.id.statusDot);
        statusDotGlow = findViewById(R.id.statusDotGlow);
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
        cleanupOldSessions(); // Auto-cleanup old tracking sessions

        btnShare.setOnClickListener(v -> {
            if (contacts.isEmpty()) {
                Toast.makeText(this, "No emergency contacts found. Please add contacts from Settings.", Toast.LENGTH_LONG).show();
                return;
            }

            // Get saved message and open Google Maps to share live location
            shareLiveLocationViaGoogleMaps();
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

    private void shareLiveLocationViaGoogleMaps() {
        // Load contacts first if empty
        if (contacts.isEmpty()) {
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
                        // Contacts loaded, proceed
                        startLiveTracking();
                    }
                }

                @Override
                public void onCancelled(DatabaseError error) {
                    Toast.makeText(LiveLocation.this, "Failed to load contacts", Toast.LENGTH_SHORT).show();
                }
            });
            return;
        }

        // Contacts already loaded, proceed
        startLiveTracking();
    }

    private void startLiveTracking() {

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

        // Check SMS permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.SEND_SMS},
                    SMS_PERMISSION_CODE);
            return;
        }

        // Generate unique share ID
        shareId = currentUser.getUid() + "_" + System.currentTimeMillis();
        isSharing = true;

        // Create session in Firebase
        createLiveLocationSession();

        // Start Foreground Service for background tracking
        Intent serviceIntent = new Intent(this, LocationTrackingService.class);
        serviceIntent.putExtra("shareId", shareId);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

        // Get initial location and send message
        fusedLocationProviderClient.getLastLocation().addOnSuccessListener(location -> {
            if (location != null) {
                // Get saved emergency message (same field as SOS button)
                DatabaseReference messageRef = FirebaseDatabase.getInstance()
                        .getReference("Users")
                        .child(currentUser.getUid())
                        .child("emergency_message_template");

                messageRef.addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(DataSnapshot snapshot) {
                        String savedMessage = snapshot.getValue(String.class);

                        // Use saved custom message or default
                        if (savedMessage == null || savedMessage.isEmpty()) {
                            savedMessage = "Emergency! I need help!";
                        }

                        // Clean tracking URL
                        String trackingUrl = "https://safetyapp-2042f.web.app/track?id=" + shareId;

                        String finalMessage = savedMessage + "\n\n📍 " + trackingUrl;

                        // Send to all emergency contacts via WhatsApp AND SMS
                        for (Contact contact : contacts) {
                            sendWhatsApp(contact.getPhone(), finalMessage);
                            sendSmsDirectly(contact.getPhone(), finalMessage);
                        }

                        Toast.makeText(LiveLocation.this, "Live tracking started! Sent to " + contacts.size() + " contacts", Toast.LENGTH_SHORT).show();
                    }

                    @Override
                    public void onCancelled(DatabaseError error) {
                        // Fallback if Firebase message fetch fails
                        Toast.makeText(LiveLocation.this, "Unable to load custom message", Toast.LENGTH_SHORT).show();
                    }
                });

                // Update UI
                btnShare.setEnabled(false);
                btnStopSharing.setEnabled(true);
                tvStatus.setText("Live tracking\nActive now");
                statusDot.setBackgroundResource(R.drawable.status_dot_active);
                statusDotGlow.setBackgroundResource(R.drawable.status_dot_glow_active);
            }
        });
    }

    private void startContinuousTracking(String method) {
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

        // Generate unique share ID for this tracking session
        shareId = currentUser.getUid() + "_" + System.currentTimeMillis();
        isSharing = true;

        // Start continuous location updates
        fusedLocationProviderClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper());

        // Create live location session in Firebase
        createLiveLocationSession();

        // Get saved emergency message from Firebase
        DatabaseReference messageRef = FirebaseDatabase.getInstance()
                .getReference("Users")
                .child(currentUser.getUid())
                .child("emergencyMessage");

        messageRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                String savedMessage = snapshot.getValue(String.class);
                String userMessage;

                if (savedMessage != null && !savedMessage.isEmpty()) {
                    // Use saved emergency message
                    userMessage = savedMessage;
                } else {
                    // Use default message
                    userMessage = "I'm sharing my live location with you.";
                }

                // Create a direct Google Maps link with the Firebase data
                // This will be a web page that reads from Firebase and displays on Google Maps
                String trackingUrl = "https://www.google.com/maps/dir/?api=1&destination=" + shareId + "&travelmode=driving";

                String finalMessage = userMessage + "\n\n📍 Track my live location:\n" + trackingUrl + "\n\n🔴 Updates every 2 seconds as I move (active for 1 hour)";

                // Send message with live tracking link to all contacts
                for (Contact contact : contacts) {
                    sendWhatsApp(contact.getPhone(), finalMessage);
                }

                Toast.makeText(LiveLocation.this, "Live location sharing started!", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onCancelled(DatabaseError error) {
                // Use default message if Firebase fails
                String defaultMessage = "I'm sharing my live location with you.";
                String googleMapsUrl = "https://www.google.com/maps/search/?api=1&query=" + shareId;
                String finalMessage = defaultMessage + "\n\n📍 Track my live location on Google Maps:\n" + googleMapsUrl + "\n\n🔴 This link updates automatically as I move (active for 1 hour)";

                for (Contact contact : contacts) {
                    sendWhatsApp(contact.getPhone(), finalMessage);
                }

                Toast.makeText(LiveLocation.this, "Live location sharing started!", Toast.LENGTH_SHORT).show();
            }
        });

        // Update UI
        btnShare.setEnabled(false);
        btnStopSharing.setEnabled(true);
        tvStatus.setText("LIVE: Tracking active");
        statusDot.setBackgroundResource(R.drawable.status_dot_active);
        statusDotGlow.setBackgroundResource(R.drawable.status_dot_glow_active);

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

        // Stop the Foreground Service
        Intent serviceIntent = new Intent(this, LocationTrackingService.class);
        serviceIntent.putExtra("action", "STOP");
        serviceIntent.putExtra("shareId", shareId);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

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