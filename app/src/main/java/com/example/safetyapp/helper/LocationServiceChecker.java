package com.example.safetyapp.helper;

import android.content.Context;
import android.content.Intent;
import android.location.LocationManager;
import android.provider.Settings;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

/**
 * Helper class to check and prompt users to enable location services
 */
public class LocationServiceChecker {

    /**
     * Check if location services (GPS) are enabled on the device
     *
     * @param context Application context
     * @return true if location services are enabled, false otherwise
     */
    public static boolean isLocationServiceEnabled(Context context) {
        LocationManager locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (locationManager == null) {
            return false;
        }

        // Check if either GPS or Network provider is enabled
        boolean gpsEnabled = false;
        boolean networkEnabled = false;

        try {
            gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
        } catch (Exception e) {
            e.printStackTrace();
        }

        try {
            networkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        } catch (Exception e) {
            e.printStackTrace();
        }

        return gpsEnabled || networkEnabled;
    }

    /**
     * Show dialog prompting user to enable location services
     *
     * @param activity The activity context
     * @param onDismiss Callback to run after dialog is dismissed (optional)
     */
    public static void showLocationServiceDialog(AppCompatActivity activity, Runnable onDismiss) {
        new AlertDialog.Builder(activity)
                .setTitle("Location Service Required")
                .setMessage("This app requires location services to be enabled for safety features like live location tracking and emergency alerts.\n\nPlease enable location services in the next screen.")
                .setPositiveButton("Enable", (dialog, which) -> {
                    // Open location settings
                    Intent intent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
                    activity.startActivity(intent);
                })
                .setNegativeButton("Cancel", (dialog, which) -> {
                    if (onDismiss != null) {
                        onDismiss.run();
                    }
                })
                .setCancelable(false)
                .show();
    }

    /**
     * Show dialog prompting user to enable location services (without callback)
     *
     * @param activity The activity context
     */
    public static void showLocationServiceDialog(AppCompatActivity activity) {
        showLocationServiceDialog(activity, null);
    }

    /**
     * Check location service and show dialog if disabled
     *
     * @param activity The activity context
     * @param onEnabled Callback to run if location is enabled
     * @return true if location is enabled, false if disabled (dialog will be shown)
     */
    public static boolean checkAndPromptLocationService(AppCompatActivity activity, Runnable onEnabled) {
        if (isLocationServiceEnabled(activity)) {
            if (onEnabled != null) {
                onEnabled.run();
            }
            return true;
        } else {
            showLocationServiceDialog(activity, null);
            return false;
        }
    }

    /**
     * Check location service and show dialog if disabled (without callback)
     *
     * @param activity The activity context
     * @return true if location is enabled, false if disabled (dialog will be shown)
     */
    public static boolean checkAndPromptLocationService(AppCompatActivity activity) {
        return checkAndPromptLocationService(activity, null);
    }
}
