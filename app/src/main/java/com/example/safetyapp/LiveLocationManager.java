package com.example.safetyapp;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import androidx.core.content.ContextCompat;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

import java.util.HashMap;
import java.util.Map;

/**
 * Reusable Live Location Tracking Manager
 *
 * This class handles:
 * - Generating unique tracking URLs
 * - Starting/stopping the LocationTrackingService
 * - Creating Firebase tracking sessions
 *
 * Usage:
 * LiveLocationManager manager = new LiveLocationManager(context);
 * TrackingInfo info = manager.startTracking();
 * String url = info.getTrackingUrl(); // Use this URL with any messaging system
 *
 * // Later, to stop:
 * manager.stopTracking(info.getShareId());
 */
public class LiveLocationManager {

    private final Context context;
    private final DatabaseReference liveLocationRef;
    private final FirebaseUser currentUser;

    // Tracking URL base - can be configured
    private static final String TRACKING_URL_BASE = "https://safetyapp-2042f.web.app/track?id=";

    public LiveLocationManager(Context context) {
        this.context = context;
        this.liveLocationRef = FirebaseDatabase.getInstance().getReference("LiveLocations");
        this.currentUser = FirebaseAuth.getInstance().getCurrentUser();
    }

    /**
     * Data class to hold tracking information
     */
    public static class TrackingInfo {
        private final String shareId;
        private final String trackingUrl;

        public TrackingInfo(String shareId, String trackingUrl) {
            this.shareId = shareId;
            this.trackingUrl = trackingUrl;
        }

        public String getShareId() {
            return shareId;
        }

        public String getTrackingUrl() {
            return trackingUrl;
        }

        public String getFullTrackingUrl() {
            return trackingUrl;
        }
    }

    /**
     * Generates tracking URL WITHOUT starting the service
     * Use this when you want to send the URL first, then start tracking later
     *
     * @return TrackingInfo containing shareId and trackingUrl (service NOT started yet)
     * @throws IllegalStateException if user is not logged in
     */
    public TrackingInfo generateTrackingUrl() {
        if (currentUser == null) {
            throw new IllegalStateException("User must be logged in to start tracking");
        }

        // Generate unique share ID
        String shareId = generateShareId();

        // Generate tracking URL
        String trackingUrl = generateTrackingUrl(shareId);

        return new TrackingInfo(shareId, trackingUrl);
    }

    /**
     * Starts the tracking service for an existing shareId
     * Call this AFTER sending messages to avoid SMS blocking issues
     *
     * @param shareId The shareId from generateTrackingUrl()
     */
    public void startTrackingService(String shareId) {
        // Create Firebase session
        createLiveLocationSession(shareId);

        // Start the foreground service
        startTrackingServiceInternal(shareId);
    }

    /**
     * Starts live location tracking (all-in-one method)
     * Note: Starting the service first may cause SMS sending issues
     * Consider using generateTrackingUrl() + startTrackingService() separately
     *
     * @return TrackingInfo containing shareId and trackingUrl
     * @throws IllegalStateException if user is not logged in
     */
    public TrackingInfo startTracking() {
        if (currentUser == null) {
            throw new IllegalStateException("User must be logged in to start tracking");
        }

        // Generate unique share ID
        String shareId = generateShareId();

        // Generate tracking URL
        String trackingUrl = generateTrackingUrl(shareId);

        // Create Firebase session and start service
        startTrackingService(shareId);

        return new TrackingInfo(shareId, trackingUrl);
    }

    /**
     * Stops live location tracking
     *
     * @param shareId The share ID returned from startTracking()
     */
    public void stopTracking(String shareId) {
        if (shareId == null || shareId.isEmpty()) {
            return;
        }

        // Stop the foreground service
        Intent serviceIntent = new Intent(context, LocationTrackingService.class);
        serviceIntent.putExtra("action", "STOP");
        serviceIntent.putExtra("shareId", shareId);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent);
        } else {
            context.startService(serviceIntent);
        }
    }

    /**
     * Generates a unique share ID for this tracking session
     */
    private String generateShareId() {
        return currentUser.getUid() + "_" + System.currentTimeMillis();
    }

    /**
     * Generates the tracking URL from a share ID
     */
    private String generateTrackingUrl(String shareId) {
        return TRACKING_URL_BASE + shareId;
    }

    /**
     * Creates a live location session in Firebase
     */
    private void createLiveLocationSession(String shareId) {
        Map<String, Object> sessionData = new HashMap<>();
        sessionData.put("userId", currentUser.getUid());
        sessionData.put("userEmail", currentUser.getEmail());
        sessionData.put("startTime", System.currentTimeMillis());
        sessionData.put("isActive", true);

        liveLocationRef.child(shareId).setValue(sessionData);
    }

    /**
     * Starts the LocationTrackingService (internal method)
     */
    private void startTrackingServiceInternal(String shareId) {
        Intent serviceIntent = new Intent(context, LocationTrackingService.class);
        serviceIntent.putExtra("shareId", shareId);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent);
        } else {
            context.startService(serviceIntent);
        }
    }

    /**
     * Check if all required permissions are granted
     */
    public boolean hasRequiredPermissions() {
        boolean hasFineLocation = ContextCompat.checkSelfPermission(context,
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;

        boolean hasBackgroundLocation = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            hasBackgroundLocation = ContextCompat.checkSelfPermission(context,
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED;
        }

        return hasFineLocation && hasBackgroundLocation;
    }

    /**
     * Check if user is logged in
     */
    public boolean isUserLoggedIn() {
        return currentUser != null;
    }

    /**
     * Get the tracking URL base (useful for testing or configuration)
     */
    public static String getTrackingUrlBase() {
        return TRACKING_URL_BASE;
    }
}
