package com.example.safetyapp.helper;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.telephony.SmsManager;
import android.widget.Toast;
import com.example.safetyapp.LocationTrackingService;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.example.safetyapp.Contact;
import com.facebook.share.model.ShareLinkContent;
import com.facebook.share.widget.ShareDialog;
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

public class EmergencyMessageHelper {
    private static final int LOCATION_PERMISSION_CODE = 101;
    private static final int SMS_PERMISSION_CODE = 102;

    private final Activity activity;
    private final FusedLocationProviderClient locationProvider;
    private final FirebaseUser currentUser;
    private final DatabaseReference userRef;
    private final DatabaseReference liveLocationRef;
    private String savedTemplate = "";
    private final List<Contact> contacts = new ArrayList<>();
    private String shareId;
    private boolean isTracking = false;
    private LocationCallback locationCallback;
    private Handler handler;

    public EmergencyMessageHelper(Activity activity) {
        this.activity = activity;
        this.locationProvider = LocationServices.getFusedLocationProviderClient(activity);
        this.currentUser = FirebaseAuth.getInstance().getCurrentUser();
        this.userRef = FirebaseDatabase.getInstance().getReference("Users").child(currentUser.getUid());
        this.liveLocationRef = FirebaseDatabase.getInstance().getReference("LiveLocations");
        this.handler = new Handler(Looper.getMainLooper());
        setupLocationCallback();
    }

    private void setupLocationCallback() {
        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult locationResult) {
                if (locationResult == null || !isTracking) return;

                for (Location location : locationResult.getLocations()) {
                    updateLiveLocation(location);
                }
            }
        };
    }

    private void updateLiveLocation(Location location) {
        if (shareId == null || !isTracking) return;

        if (location.getAccuracy() > 50.0f) return;

        Map<String, Object> locationData = new HashMap<>();
        locationData.put("latitude", location.getLatitude());
        locationData.put("longitude", location.getLongitude());
        locationData.put("accuracy", location.getAccuracy());
        locationData.put("timestamp", System.currentTimeMillis());

        liveLocationRef.child(shareId).child("currentLocation").setValue(locationData);
        liveLocationRef.child(shareId).child("locationHistory").push().setValue(locationData);
    }

    public void sendMessage(String method) {
        // Check location permission
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(activity, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, LOCATION_PERMISSION_CODE);
            return;
        }

        // Check background location permission (Android 10+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(activity, new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION}, 103);
                return;
            }
        }

        // Check SMS permission
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.SEND_SMS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(activity, new String[]{Manifest.permission.SEND_SMS}, SMS_PERMISSION_CODE);
            return;
        }

        // Load contacts
        userRef.child("emergencyContacts").addListenerForSingleValueEvent(new ValueEventListener() {
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
                    Toast.makeText(activity, "No emergency contacts found. Please add contacts from Settings.", Toast.LENGTH_LONG).show();
                    return;
                }

                // Generate unique share ID
                shareId = currentUser.getUid() + "_" + System.currentTimeMillis();
                isTracking = true;

                // Create session in Firebase
                Map<String, Object> sessionData = new HashMap<>();
                sessionData.put("userId", currentUser.getUid());
                sessionData.put("userEmail", currentUser.getEmail());
                sessionData.put("startTime", System.currentTimeMillis());
                sessionData.put("isActive", true);
                liveLocationRef.child(shareId).setValue(sessionData);

                // Start Foreground Service for background tracking
                Intent serviceIntent = new Intent(activity, LocationTrackingService.class);
                serviceIntent.putExtra("shareId", shareId);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    activity.startForegroundService(serviceIntent);
                } else {
                    activity.startService(serviceIntent);
                }

                // Get initial location and send message
                locationProvider.getLastLocation().addOnSuccessListener(location -> {
                    if (location != null) {
                        // Get saved emergency message
                        userRef.child("emergency_message_template").addListenerForSingleValueEvent(new ValueEventListener() {
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
                                    sendSms(contact.getPhone(), finalMessage);
                                }

                                Toast.makeText(activity, "Live tracking started! Sent to " + contacts.size() + " contacts", Toast.LENGTH_SHORT).show();
                            }

                            @Override
                            public void onCancelled(DatabaseError error) {
                                // Fallback if Firebase message fetch fails
                                Toast.makeText(activity, "Unable to load custom message", Toast.LENGTH_SHORT).show();
                            }
                        });
                    } else {
                        Toast.makeText(activity, "Unable to get location", Toast.LENGTH_SHORT).show();
                    }
                });
            }

            @Override
            public void onCancelled(DatabaseError error) {
                Toast.makeText(activity, "Failed to load contacts", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void sendSms(String phone, String message) {
        try {
            SmsManager.getDefault().sendTextMessage(phone, null, message, null, null);
            Toast.makeText(activity, "SMS sent to " + phone, Toast.LENGTH_SHORT).show();
            showSimpleNotification("Emergency SMS Sent", "Message sent to " + phone);
        } catch (Exception e) {
            Toast.makeText(activity, "Failed to send SMS to " + phone, Toast.LENGTH_SHORT).show();
            e.printStackTrace();
        }
    }

    private void sendWhatsApp(String phone, String message) {
        try {
            phone = phone.replace("+", "").replaceAll("\\s", "");
            String url = "https://wa.me/" + phone + "?text=" + URLEncoder.encode(message, "UTF-8");
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            activity.startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(activity, "Error sending WhatsApp message", Toast.LENGTH_SHORT).show();
            e.printStackTrace();
        }
    }

    public void sendSOSMessage(String method, String customMessage) {
        Toast.makeText(activity, "Starting SOS message via " + method, Toast.LENGTH_SHORT).show();

        if ("sms".equals(method) && ContextCompat.checkSelfPermission(activity, Manifest.permission.SEND_SMS)
                != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(activity, "SMS permission not granted", Toast.LENGTH_LONG).show();
            ActivityCompat.requestPermissions(activity, new String[]{Manifest.permission.SEND_SMS}, SMS_PERMISSION_CODE);
            return;
        }

        userRef.child("emergencyContacts").addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                contacts.clear();
                for (DataSnapshot contactSnapshot : snapshot.getChildren()) {
                    Contact contact = contactSnapshot.getValue(Contact.class);
                    if (contact != null) {
                        contacts.add(contact);
                    }
                }

                Toast.makeText(activity, "Found " + contacts.size() + " emergency contacts", Toast.LENGTH_SHORT).show();

                if (contacts.isEmpty()) {
                    Toast.makeText(activity, "No emergency contacts found. Please add contacts first.", Toast.LENGTH_LONG).show();
                    return;
                }

                for (Contact contact : contacts) {
                    if ("sms".equals(method)) {
                        sendSms(contact.getPhone(), customMessage);
                    } else if ("whatsapp".equals(method)) {
                        sendWhatsApp(contact.getPhone(), customMessage);
                    }
                }
            }

            @Override
            public void onCancelled(DatabaseError error) {
                Toast.makeText(activity, "Failed to load contacts: " + error.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
    }

    public void sendCustomMessage(String method, String customMessage) {
        if ("sms".equals(method) && ContextCompat.checkSelfPermission(activity, Manifest.permission.SEND_SMS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(activity, new String[]{Manifest.permission.SEND_SMS}, SMS_PERMISSION_CODE);
            return;
        }

        userRef.child("emergencyContacts").addListenerForSingleValueEvent(new ValueEventListener() {
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
                    Toast.makeText(activity, "No emergency contacts found", Toast.LENGTH_SHORT).show();
                    return;
                }

                for (Contact contact : contacts) {
                    if ("sms".equals(method)) {
                        sendSms(contact.getPhone(), customMessage);
                    } else if ("whatsapp".equals(method)) {
                        sendWhatsApp(contact.getPhone(), customMessage);
                    }
                }

                // Don't post to Facebook for SOS custom messages
            }

            @Override
            public void onCancelled(DatabaseError error) {
                Toast.makeText(activity, "Failed to load contacts", Toast.LENGTH_SHORT).show();
            }
        });
    }

    public void postToFacebookFeed(String message) {
        if (ShareDialog.canShow(ShareLinkContent.class)) {
            ShareLinkContent content = new ShareLinkContent.Builder()
                    .setQuote(message)
                    .setContentUrl(Uri.parse("https://safetyapp.page.link/alert"))
                    .build();

            ShareDialog shareDialog = new ShareDialog(activity);
            shareDialog.show(content);
        } else {
            Toast.makeText(activity, "Facebook share dialog not available", Toast.LENGTH_SHORT).show();
        }
    }

    private void showSimpleNotification(String title, String message) {
        String channelId = "sms_channel";
        NotificationManager notificationManager = (NotificationManager) activity.getSystemService(Context.NOTIFICATION_SERVICE);

        // Create channel for Android 8+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    channelId,
                    "SMS Notifications",
                    NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("Notification for emergency SMS sent");
            channel.enableVibration(true);
            channel.setVibrationPattern(new long[]{0, 1000, 500, 1000});
            channel.setLockscreenVisibility(NotificationCompat.VISIBILITY_PUBLIC);
            notificationManager.createNotificationChannel(channel);
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(activity, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setVibrate(new long[]{0, 1000, 500, 1000})
                .setAutoCancel(true);

        notificationManager.notify((int) System.currentTimeMillis(), builder.build());
    }

    public void stopTracking() {
        if (!isTracking) return;

        isTracking = false;
        handler.removeCallbacksAndMessages(null);

        // Stop the Foreground Service
        Intent serviceIntent = new Intent(activity, LocationTrackingService.class);
        serviceIntent.putExtra("action", "STOP");
        serviceIntent.putExtra("shareId", shareId);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            activity.startForegroundService(serviceIntent);
        } else {
            activity.startService(serviceIntent);
        }

        Toast.makeText(activity, "Live tracking stopped", Toast.LENGTH_SHORT).show();
    }
}
