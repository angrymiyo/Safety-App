package com.example.safetyapp;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import java.util.HashMap;
import java.util.Map;

public class LocationTrackingService extends Service {

    private static final String CHANNEL_ID = "LocationTrackingChannel";
    private static final int NOTIFICATION_ID = 1001;

    private FusedLocationProviderClient fusedLocationProviderClient;
    private LocationCallback locationCallback;
    private DatabaseReference liveLocationRef;
    private String shareId;
    private Handler handler;

    @Override
    public void onCreate() {
        super.onCreate();
        fusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(this);
        liveLocationRef = FirebaseDatabase.getInstance().getReference("LiveLocations");
        handler = new Handler(Looper.getMainLooper());
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            shareId = intent.getStringExtra("shareId");
            String action = intent.getStringExtra("action");

            if ("STOP".equals(action)) {
                stopTracking();
                return START_NOT_STICKY;
            }

            if (shareId != null) {
                startForeground(NOTIFICATION_ID, createNotification());
                startLocationTracking();

                // Auto-stop after 1 hour
                handler.postDelayed(this::stopTracking, 3600000);
            }
        }
        return START_STICKY;
    }

    private void startLocationTracking() {
        LocationRequest locationRequest = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000)
                .setWaitForAccurateLocation(true)
                .setMinUpdateIntervalMillis(1000)
                .setMaxUpdateDelayMillis(3000)
                .setMinUpdateDistanceMeters(1.0f)
                .build();

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult locationResult) {
                if (locationResult == null) return;

                for (Location location : locationResult.getLocations()) {
                    if (location.getAccuracy() <= 50.0f) {
                        updateLocationToFirebase(location);
                    }
                }
            }
        };

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationProviderClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper());
        }
    }

    private void updateLocationToFirebase(Location location) {
        if (shareId == null) return;

        Map<String, Object> locationData = new HashMap<>();
        locationData.put("latitude", location.getLatitude());
        locationData.put("longitude", location.getLongitude());
        locationData.put("accuracy", location.getAccuracy());
        locationData.put("timestamp", System.currentTimeMillis());
        locationData.put("speed", location.hasSpeed() ? location.getSpeed() : 0);
        locationData.put("bearing", location.hasBearing() ? location.getBearing() : 0);
        locationData.put("altitude", location.hasAltitude() ? location.getAltitude() : 0);
        locationData.put("provider", location.getProvider());

        liveLocationRef.child(shareId).child("currentLocation").setValue(locationData);
        liveLocationRef.child(shareId).child("locationHistory").push().setValue(locationData);
    }

    private void stopTracking() {
        if (locationCallback != null) {
            fusedLocationProviderClient.removeLocationUpdates(locationCallback);
        }

        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }

        if (shareId != null) {
            liveLocationRef.child(shareId).child("isActive").setValue(false);
            liveLocationRef.child(shareId).child("endTime").setValue(System.currentTimeMillis());
            // Delete location history immediately to save space
            liveLocationRef.child(shareId).child("locationHistory").removeValue();
        }

        stopForeground(true);
        stopSelf();
    }

    private Notification createNotification() {
        Intent notificationIntent = new Intent(this, LiveLocation.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        Intent stopIntent = new Intent(this, LocationTrackingService.class);
        stopIntent.putExtra("action", "STOP");
        stopIntent.putExtra("shareId", shareId);
        PendingIntent stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Live Location Sharing")
                .setContentText("Sharing your location with emergency contacts")
                .setSmallIcon(R.drawable.ic_location)
                .setContentIntent(pendingIntent)
                .addAction(R.drawable.ic_stop_24, "Stop Sharing", stopPendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Location Tracking",
                    NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("Keeps location tracking active in background");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (locationCallback != null) {
            fusedLocationProviderClient.removeLocationUpdates(locationCallback);
        }
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }
    }
}
